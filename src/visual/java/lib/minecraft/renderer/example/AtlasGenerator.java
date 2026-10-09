package lib.minecraft.renderer.example;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import dev.simplified.gson.exception.JsonException;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.call.request.AtlasOptions;
import lib.minecraft.renderer.call.result.AtlasResult;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A worked example of driving {@link AtlasRenderer} end to end, run by the {@code generateAtlas}
 * Gradle task - a render job over the texture pack, not a client-jar extraction. The render pass is
 * a thin I/O shell around {@link ClientAcquisition#acquire(ClientOptions)} plus
 * {@link AtlasRenderer#render(AtlasOptions)}: it writes the atlas image, {@code atlas.png}, plus the
 * {@code atlas.json} sidecar to the output directory, scratch {@code build/atlas/}, never a bundled
 * resource.
 *
 * <p>{@link AtlasRenderer} hands back the typed {@link AtlasResult.Sidecar}; this class serialises it to
 * {@code atlas.json} and reads that file back before reporting the run, so a sidecar a downstream
 * reader cannot decode fails at the run that wrote it.
 *
 * <p>The Gradle task selects the diagnostic passes by property and forwards each as the switch this
 * main reads. {@code -Pdiagnose} ({@code --diagnose} in argv) adds a post-hoc analysis of the atlas
 * on disk: it reads {@code atlas.json} through {@link AtlasResult.Sidecar} and {@code atlas.png}, slices
 * every tile into {@code slice/<id>.png}, and writes {@code missing.json} listing tiles flagged by
 * three signals:
 * <ul>
 *   <li>{@code fullyTransparent} - every pixel {@code alpha == 0} (the render produced nothing);</li>
 *   <li>{@code sparseContent} - fewer than {@value #SPARSE_CONTENT_THRESHOLD} of the tile's pixels
 *       are opaque (usually a template submodel);</li>
 *   <li>{@code substituted} - the tile's render drew a stand-in, which its row's
 *       {@code substitutions} names.</li>
 * </ul>
 *
 * <p>{@code -PsourceFilter=<source>} ({@code --source-filter=<source>}) adds a second analysis, a
 * mini-atlas of just that registration source's tiles; the two compose in one run.
 * {@code -PskipRender} ({@code --skip-render}) reads the atlas already on disk rather than producing
 * a fresh one, so a diagnostic pass can be repeated without paying for the render.
 */
@UtilityClass
public final class AtlasGenerator {

    private static final @NotNull String DIAGNOSE_FLAG = "--diagnose";
    private static final @NotNull String SKIP_RENDER_FLAG = "--skip-render";
    private static final @NotNull String SOURCE_FILTER_FLAG = "--source-filter=";
    private static final @NotNull String DEFAULT_OUTPUT_DIR = "build/atlas";
    private static final int PROGRESS_INTERVAL = 256;

    /**
     * The opaque-pixel fraction below which a tile is flagged {@code sparseContent} - the diagnose
     * flow's one threshold, and the metric {@code missing.json} reports the flags against.
     *
     * <p>The value is empirically tuned by inspecting the flag distribution: at {@code 5%} normal
     * thin blocks - torches, candles, buttons - false-flag, and at {@code 2%} what stays in the
     * bucket is template submodels that should be filtered at registration instead - tripwire
     * sub-models, glass_pane sub-posts, stem_stage growth.
     *
     * <p>It is a constant on its consumer and not a {@code NavigationPolicy}: a render or diagnose
     * job consults a threshold, it does not navigate bytecode.
     */
    private static final double SPARSE_CONTENT_THRESHOLD = 0.02;

    /**
     * The decoded atlas raster paired with its typed sidecar, as the diagnostic passes read them
     * back off disk.
     *
     * @param image the decoded {@code atlas.png} raster
     * @param sidecar the tile table parsed from {@code atlas.json}
     */
    private record LoadedAtlas(@NotNull BufferedImage image, @NotNull AtlasResult.Sidecar sidecar) {}

    /**
     * A validated source filter: the registration source whose tiles the mini-atlas keeps, paired
     * with the contained directory that atlas is written into.
     *
     * @param source the registration source token each tile's own source is matched against
     * @param directory the contained output directory, named after the source
     */
    private record SourceFilter(@NotNull String source, @NotNull Path directory) {}

    /**
     * The two emptiness flags plus the opaque-pixel ratio for one tile slice, all three read off a
     * single pass counting that slice's opaque pixels.
     *
     * @param fullyTransparent whether every pixel has {@code alpha == 0} (the render produced nothing)
     * @param sparseContent whether the slice rendered something but fewer than the sparse threshold of its pixels are opaque
     * @param opaqueRatio the fraction of opaque pixels, in {@code [0, 1]}
     */
    private record Result(boolean fullyTransparent, boolean sparseContent, double opaqueRatio) {}

    /**
     * Runs the render pass, then whichever diagnostic passes the flags asked for.
     *
     * @param args the output directory as the sole positional argument, defaulting to
     *     {@code build/atlas}, plus any of {@code --diagnose}, {@code --source-filter=<source>} and
     *     {@code --skip-render}
     * @throws IOException if the atlas image, sidecar or diagnostic outputs cannot be read or written
     * @throws AtlasException if the sidecar is unparseable, does not read back as it was written,
     *     is missing entirely, or the source filter is not a simple directory name
     */
    public static void main(String @NotNull [] args) throws IOException {
        Path parsedDir = Path.of(DEFAULT_OUTPUT_DIR);
        Optional<String> source = Optional.empty();
        boolean diagnose = false;
        boolean skipRender = false;
        for (String arg : args) {
            if (arg.startsWith(SOURCE_FILTER_FLAG)) source = Optional.of(arg.substring(SOURCE_FILTER_FLAG.length()).trim());
            else if (DIAGNOSE_FLAG.equals(arg)) diagnose = true;
            else if (SKIP_RENDER_FLAG.equals(arg)) skipRender = true;
            else if (!arg.startsWith("--")) parsedDir = Path.of(arg);
        }
        Path outputDir = parsedDir;

        // The filter names a directory and arrives untrusted, so it is contained before the run
        // writes anything: a name that escapes costs neither a render nor a slice pass. Source and
        // directory travel as one value from here on, so the two can never disagree.
        Optional<SourceFilter> filter = source.map(name -> new SourceFilter(name, resolveContained(outputDir, name)));

        if (!skipRender) renderAtlas(outputDir);
        if (!diagnose && filter.isEmpty()) {
            if (skipRender)
                log("--skip-render with no diagnostic pass requested: nothing to do, add --diagnose or --source-filter=<source>");
            return;
        }

        LoadedAtlas atlas = loadAtlas(outputDir);
        if (diagnose) sliceAndFlag(outputDir, atlas);
        if (filter.isPresent()) writeSourceAtlas(atlas, filter.get());
    }

    /**
     * Writes one progress line to stdout.
     *
     * @param message the format string
     * @param args the format arguments
     */
    private static void log(@NotNull @PrintFormat String message, @Nullable Object... args) {
        System.out.printf(message + "%n", args);
    }

    /**
     * Renders the whole atlas and writes the image and its sidecar into the output directory.
     *
     * <p>This is the no-flag default: acquire the client assets, load a
     * {@link RendererContext} over them and hand it to {@link AtlasRenderer}. The atlas is a static
     * sheet, written as {@code atlas.png}, and {@code atlas.json} is the {@link AtlasResult.Sidecar}
     * the renderer returned, serialised here and then read back off disk: the bytes that landed are
     * what a downstream reader meets, so a file it cannot decode, or one holding a count its own tile
     * array contradicts, fails here instead.
     *
     * @param outputDir the directory the atlas image and its sidecar are written to
     * @throws IOException if the atlas image cannot be written or the sidecar cannot be read back
     * @throws JsonException if the sidecar cannot be written
     * @throws AtlasException if the sidecar does not read back as the value it was written from
     */
    private static void renderAtlas(@NotNull Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        ClientAssets assets = ClientAcquisition.acquire(ClientOptions.defaults());
        RendererContext context = RendererContext.load(assets);
        log("pipeline ready: %d blocks, %d items at %s",
            context.knownBlockIds().size(), context.knownItemIds().size(), assets.options().vanillaRoot());
        AtlasResult atlas = new AtlasRenderer(context).render(AtlasOptions.defaults());

        File outputFile = outputDir.resolve("atlas.png").toFile();
        new ImageFactory().toFile(atlas.image(), ImageFormat.PNG, outputFile);

        AtlasResult.Sidecar sidecar = atlas.sidecar();
        Path jsonFile = outputDir.resolve("atlas.json");
        // JsonTree.write terminates the file with one literal LF, which is what this needs: a writer
        // that asks the JVM what a newline is emits CRLF on one host and LF on another for the same
        // input, which makes the emitted file's digest a fact about who ran it. It also writes with
        // HTML escaping off - inert while a tile id and the kind and source tokens carry no
        // character escaping would rewrite, and the reason a future id source that does would land
        // raw rather than escaped.
        sidecar.toJson().write(jsonFile);

        // Read back rather than re-checked in memory: the record in hand cannot disagree with
        // itself, where the file carries its count and its tile array as two independent members
        // and can hold a token no reader resolves to a constant.
        AtlasResult.Sidecar written = readSidecar(jsonFile);
        if (written.count() != written.tiles().size())
            throw new AtlasException("Atlas sidecar '%s' declares %d tiles and carries %d", jsonFile.toAbsolutePath(), written.count(), written.tiles().size());
        if (!written.equals(sidecar))
            throw new AtlasException("Atlas sidecar '%s' does not read back as the %d tiles it was written from", jsonFile.toAbsolutePath(), sidecar.tiles().size());
        log("wrote atlas: %d tiles -> %s (%dx%d px)",
            sidecar.tiles().size(), outputFile.getAbsolutePath(), atlas.image().getWidth(), atlas.image().getHeight());
        log("wrote sidecar: %s", jsonFile.toAbsolutePath());
    }

    /**
     * Reads the atlas raster and its typed sidecar back off disk for the diagnostic passes.
     *
     * <p>Both diagnostic passes read the same two files, so the read and its guards happen here
     * once. A tree missing either file, or holding a PNG that does not decode, is a hard failure.
     *
     * @param root the directory holding {@code atlas.png} and {@code atlas.json}
     * @return the decoded atlas and its sidecar
     * @throws IOException if the atlas PNG or its sidecar cannot be read
     * @throws AtlasException if either file is missing, the sidecar is unparseable or names a kind
     *     or source no constant answers to, or the PNG cannot be decoded
     */
    private static @NotNull LoadedAtlas loadAtlas(@NotNull Path root) throws IOException {
        Path atlasPng = root.resolve("atlas.png");
        Path atlasJson = root.resolve("atlas.json");
        if (!Files.isRegularFile(atlasPng))
            throw new AtlasException("Missing atlas image '%s'", atlasPng.toAbsolutePath());
        if (!Files.isRegularFile(atlasJson))
            throw new AtlasException("Missing atlas sidecar '%s'", atlasJson.toAbsolutePath());

        AtlasResult.Sidecar sidecar = readSidecar(atlasJson);
        BufferedImage atlas = ImageIO.read(atlasPng.toFile());
        if (atlas == null)
            throw new AtlasException("Could not decode atlas PNG '%s'", atlasPng.toAbsolutePath());
        return new LoadedAtlas(atlas, sidecar);
    }

    /**
     * Reads a sidecar off disk, surfacing an undecodable one as a failure naming the file.
     *
     * <p>Both failure modes are the file's, not the caller's: bytes that are not JSON, and a row
     * whose kind, source, stand-in or skipped-row token {@link AtlasResult.Sidecar} resolves against no
     * constant. Each arrives as the same {@link AtlasException} naming the path, so a hand-edited or
     * foreign sidecar reports which file the run choked on.
     *
     * @param file the sidecar to read
     * @return the typed sidecar
     * @throws IOException if the file cannot be read
     * @throws AtlasException if the bytes are not JSON, or a row names a token no constant answers to
     */
    private static @NotNull AtlasResult.Sidecar readSidecar(@NotNull Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return AtlasResult.Sidecar.parse(JsonTree.parse(bytes));
        } catch (JsonException | IllegalArgumentException ex) {
            throw new AtlasException(ex, "Failed to parse atlas sidecar '%s'", file.toAbsolutePath());
        }
    }

    /**
     * Slices every tile into {@code slice/<id>.png} and flags the transparent, sparse and substituted
     * ones into {@code missing.json}.
     *
     * <p>Each tile is copied out of the atlas into a standalone raster, written, then scanned by
     * {@link #scan(BufferedImage, int, int)}. A tile flagged by any signal contributes its sidecar
     * row - grid cell, pixel rectangle and any stand-ins its render drew - plus {@code fullyTransparent},
     * {@code sparseContent}, {@code substituted} and its opaque ratio; the report header carries the
     * tile total, the flagged count and the count each signal flagged, and the threshold the sparse
     * signal compared against. The two pixel signals never flag one tile together, while a substituted
     * tile may also be transparent or sparse, so the three counts can sum past the flagged count.
     * Progress is reported every {@value #PROGRESS_INTERVAL} tiles.
     *
     * @param root the directory holding the atlas, and the parent of the slice output
     * @param loaded the decoded atlas and its sidecar
     * @throws IOException if a slice or the report cannot be written
     */
    private static void sliceAndFlag(@NotNull Path root, @NotNull LoadedAtlas loaded) throws IOException {
        BufferedImage atlas = loaded.image();
        AtlasResult.Sidecar sidecar = loaded.sidecar();
        Path sliceDir = root.resolve("slice");
        Files.createDirectories(sliceDir);
        int total = sidecar.tiles().size();
        log("slicing %d tiles into %s", total, sliceDir);

        JsonTree flagged = JsonTree.array();
        int count = 0;
        int fully = 0;
        int sparse = 0;
        int substituted = 0;
        for (int i = 0; i < total; i++) {
            AtlasResult.Tile tile = sidecar.tiles().get(i);
            BufferedImage slice = copy(atlas.getSubimage(tile.x(), tile.y(), tile.width(), tile.height()), tile.width(), tile.height());
            ImageIO.write(slice, "PNG", sliceDir.resolve(sanitize(tile.id()) + ".png").toFile());

            Result scan = scan(slice, tile.width(), tile.height());
            boolean standIn = !tile.substitutions().isEmpty();
            if (scan.fullyTransparent() || scan.sparseContent() || standIn) {
                flagged.add(tile.toJson()
                    .put("fullyTransparent", scan.fullyTransparent())
                    .put("sparseContent", scan.sparseContent())
                    .put("substituted", standIn)
                    .put("opaqueRatio", round4(scan.opaqueRatio())));
                count++;
                if (scan.fullyTransparent()) fully++;
                else if (scan.sparseContent()) sparse++;
                if (standIn) substituted++;
            }
            if ((i + 1) % PROGRESS_INTERVAL == 0) log("sliced %d/%d", i + 1, total);
        }

        JsonTree report = JsonTree.object()
            .putInt("atlasTileCount", total)
            .putInt("missingCount", count)
            .putInt("fullyTransparent", fully)
            .putInt("sparseContent", sparse)
            .putInt("substituted", substituted)
            .put("sparseContentThreshold", (float) SPARSE_CONTENT_THRESHOLD)
            .put("tiles", flagged);
        Path missing = root.resolve("missing.json");
        report.write(missing);
        log("wrote %s", missing.toAbsolutePath());
        log("flagged %d/%d tiles (%d fully transparent, %d sparse, %d substituted)", count, total, fully, sparse, substituted);
    }

    /**
     * Writes a mini-atlas of just the tiles one registration source contributed.
     *
     * <p>Matching tiles are sorted case-insensitively by id and packed into a square-ish grid of
     * {@code ceil(sqrt(n))} columns and as many rows as that needs, drawn into a fresh
     * {@code TYPE_INT_ARGB} raster. Beside the {@code atlas.png} it writes an {@code atlas.json}
     * repeating each tile row re-coordinated onto the new grid, and an {@code ids.txt} listing the
     * ids one per line. The three land in a directory named after the filter, under the atlas
     * directory, and a filter no tile matches reports that and writes nothing.
     *
     * @param loaded the decoded atlas and its sidecar
     * @param filter the registration source to keep, paired with the contained directory the three
     *     files land in
     * @throws IOException if the mini-atlas, its sidecar or the id list cannot be written
     */
    private static void writeSourceAtlas(@NotNull LoadedAtlas loaded, @NotNull SourceFilter filter) throws IOException {
        BufferedImage atlas = loaded.image();
        AtlasResult.Sidecar sidecar = loaded.sidecar();
        Path outDir = filter.directory();
        Files.createDirectories(outDir);
        int tileSize = sidecar.tileSize();

        List<AtlasResult.Tile> matching = new ArrayList<>();
        for (AtlasResult.Tile tile : sidecar.tiles())
            if (filter.source().equals(tile.source().jsonName())) matching.add(tile);
        matching.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        if (matching.isEmpty()) {
            log("no tiles matched --source-filter=%s", filter.source());
            return;
        }

        int columns = (int) Math.max(1, Math.ceil(Math.sqrt(matching.size())));
        int rows = (matching.size() + columns - 1) / columns;
        BufferedImage mini = new BufferedImage(columns * tileSize, rows * tileSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = mini.createGraphics();
        JsonTree miniTiles = JsonTree.array();
        List<String> ids = new ArrayList<>(matching.size());
        for (int i = 0; i < matching.size(); i++) {
            AtlasResult.Tile tile = matching.get(i);
            int col = i % columns;
            int row = i / columns;
            graphics.drawImage(atlas.getSubimage(tile.x(), tile.y(), tile.width(), tile.height()), col * tileSize, row * tileSize, null);
            miniTiles.add(tile.at(col, row, tileSize).toJson());
            ids.add(tile.id());
        }
        graphics.dispose();

        ImageIO.write(mini, "PNG", outDir.resolve("atlas.png").toFile());
        JsonTree miniRoot = JsonTree.object()
            .putInt("tileSize", tileSize)
            .putInt("columns", columns)
            .putInt("count", matching.size())
            .put("sourceFilter", filter.source())
            .put("tiles", miniTiles);
        Path atlasJson = outDir.resolve("atlas.json");
        miniRoot.write(atlasJson);
        log("wrote %s", atlasJson.toAbsolutePath());

        ids.sort(String.CASE_INSENSITIVE_ORDER);
        // A literal LF here and after the last id, for the reason the sidecar carries one: the
        // platform separator makes an emitted file's bytes a fact about the host that produced them.
        Files.writeString(outDir.resolve("ids.txt"), String.join("\n", ids) + "\n");
        log("wrote mini-atlas: %d tiles, %dx%d grid -> %s", matching.size(), columns, rows, outDir.resolve("atlas.png").toAbsolutePath());
    }

    /**
     * Copies a subimage into a standalone {@code TYPE_INT_ARGB} raster for writing.
     *
     * @param slice the atlas subimage, which shares the atlas raster's backing buffer
     * @param width the slice width in pixels
     * @param height the slice height in pixels
     * @return a freestanding raster holding the slice's pixels
     */
    private static @NotNull BufferedImage copy(@NotNull BufferedImage slice, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        out.createGraphics().drawImage(slice, 0, 0, null);
        return out;
    }

    /**
     * Scans a tile slice for the transparent and sparse signals.
     *
     * <p>One pass over the slice's pixels counts the opaque ones; both signals and the opaque ratio
     * derive from that single count, the sparse one comparing the ratio against
     * {@value #SPARSE_CONTENT_THRESHOLD}.
     *
     * @param image the sliced tile
     * @param width the slice width in pixels
     * @param height the slice height in pixels
     * @return the flag signals plus the opaque ratio
     */
    private static @NotNull Result scan(@NotNull BufferedImage image, int width, int height) {
        int opaque = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                if ((image.getRGB(x, y) >>> 24) != 0) opaque++;
        int totalPixels = width * height;
        double ratio = totalPixels == 0 ? 0.0 : (double) opaque / totalPixels;
        boolean fullyTransparent = opaque == 0;
        boolean sparseContent = !fullyTransparent && ratio < SPARSE_CONTENT_THRESHOLD;
        return new Result(fullyTransparent, sparseContent, ratio);
    }

    /**
     * Rounds a ratio to 4 decimal places so the JSON stays readable.
     *
     * @param value the ratio to round
     * @return the ratio at 4 decimal places
     */
    private static float round4(double value) {
        return (float) (Math.round(value * 10_000.0) / 10_000.0);
    }

    /**
     * Replaces path-reserved characters so sliced ids become filesystem-safe filenames.
     *
     * @param id the tile id
     * @return the id with the namespace colon and every path separator replaced by an underscore
     */
    private static @NotNull String sanitize(@NotNull String id) {
        return id.replace(':', '_').replace('/', '_');
    }

    /**
     * Resolves an untrusted CLI name under {@code base}, rejecting escapes (path-containment).
     *
     * @param base the directory the name must resolve inside
     * @param name the untrusted directory name
     * @return the contained directory
     * @throws AtlasException if the name is empty, carries a separator, a parent reference or an
     *     absolute path, or resolves to the base itself or outside it
     */
    private static @NotNull Path resolveContained(@NotNull Path base, @NotNull String name) {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..") || Path.of(name).isAbsolute())
            throw new AtlasException("--source-filter '%s' must be a simple directory name (no separators, parent refs, or absolute paths)", name);
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path resolved = normalizedBase.resolve(name).normalize();
        if (!resolved.startsWith(normalizedBase) || resolved.equals(normalizedBase))
            throw new AtlasException("--source-filter '%s' resolves outside the base directory", name);
        return resolved;
    }

}
