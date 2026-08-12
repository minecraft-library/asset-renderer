package lib.minecraft.renderer.example;

import dev.simplified.gson.JsonTree;
import dev.simplified.gson.exception.JsonException;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.webp.WebPWriteOptions;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.option.AtlasOptions;
import lib.minecraft.renderer.option.AtlasSidecar;
import lib.minecraft.renderer.option.AtlasTile;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;

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
 * {@link AtlasRenderer#renderAtlas(AtlasOptions)}: it writes the atlas image ({@code atlas.png}, or
 * {@code atlas.webp} for animated packs) plus the {@code atlas.json} sidecar to the output
 * directory, scratch {@code build/atlas/}, never a bundled resource.
 *
 * <p>{@link AtlasRenderer} hands back the typed {@link AtlasSidecar}; this class serialises it to
 * {@code atlas.json} and reads that file back before reporting the run, so a sidecar a downstream
 * reader cannot decode fails at the run that wrote it.
 *
 * <p>The Gradle task selects the diagnostic passes by property and forwards each as the switch this
 * main reads. {@code -Pdiagnose} ({@code --diagnose} in argv) adds a post-hoc analysis of the atlas
 * on disk: it reads {@code atlas.json} through {@link AtlasSidecar} and {@code atlas.png}, slices
 * every tile into {@code slice/<id>.png}, and writes {@code missing.json} listing tiles flagged by
 * two signals:
 * <ul>
 *   <li>{@code fullyTransparent} - every pixel {@code alpha == 0} (the render produced nothing);</li>
 *   <li>{@code sparseContent} - fewer than {@value #SPARSE_CONTENT_THRESHOLD} of the tile's pixels
 *       are opaque (usually a template submodel).</li>
 * </ul>
 *
 * <p>{@code -PsourceFilter=<source>} ({@code --source-filter=<source>}) adds a second analysis, a
 * mini-atlas of just that registration source's tiles; the two compose in one run.
 * {@code -PskipRender} ({@code --skip-render}) reads the atlas already on disk rather than producing
 * a fresh one, so a diagnostic pass can be repeated without paying for the render.
 *
 * <p>Animated packs emit only {@code atlas.webp}; slice diagnostics need the raster
 * {@code atlas.png}, so a webp-only run is a clean Diagnostics ERROR rather than a stack trace.
 */
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

    private AtlasGenerator() {
    }

    /**
     * The decoded atlas raster paired with its typed sidecar, as the diagnostic passes read them
     * back off disk.
     *
     * @param image the decoded {@code atlas.png} raster
     * @param sidecar the tile table parsed from {@code atlas.json}
     */
    private record LoadedAtlas(@NotNull BufferedImage image, @NotNull AtlasSidecar sidecar) {}

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
     * @throws ToolingException if the sidecar is unparseable, does not read back as it was written,
     *     is missing entirely, or the source filter is not a simple directory name
     */
    public static void main(String @NotNull [] args) throws IOException {
        Diagnostics diagnostics = Diagnostics.root("generateAtlas", Diagnostics.Output.CONSOLE, null);
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

        if (!skipRender) renderAtlas(diagnostics, outputDir);
        if (!diagnose && filter.isEmpty()) {
            if (skipRender)
                diagnostics.info("--skip-render with no diagnostic pass requested: nothing to do, add --diagnose or --source-filter=<source>");
            return;
        }

        Optional<LoadedAtlas> loaded = loadAtlas(diagnostics, outputDir);
        if (loaded.isEmpty()) return;
        LoadedAtlas atlas = loaded.get();
        if (diagnose) sliceAndFlag(diagnostics, outputDir, atlas);
        if (filter.isPresent()) writeSourceAtlas(diagnostics, atlas, filter.get());
    }

    /**
     * Renders the whole atlas and writes the image and its sidecar into the output directory.
     *
     * <p>This is the no-flag default: acquire the client assets, stand a
     * {@link PipelineRendererContext} on them and hand it to {@link AtlasRenderer}. An animated
     * pack writes {@code atlas.webp}, lossless and multithreaded; a static one writes
     * {@code atlas.png}. Either way {@code atlas.json} is the {@link AtlasSidecar} the renderer
     * returned, serialised here and then read back off disk: the bytes that landed are what a
     * downstream reader meets, so a file it cannot decode, or one holding a count its own tile array
     * contradicts, fails here instead.
     *
     * @param diagnostics the sink the run reports through
     * @param outputDir the directory the atlas image and its sidecar are written to
     * @throws IOException if the atlas image cannot be written or the sidecar cannot be read back
     * @throws JsonException if the sidecar cannot be written
     * @throws ToolingException if the sidecar does not read back as the value it was written from
     */
    private static void renderAtlas(@NotNull Diagnostics diagnostics, @NotNull Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        ClientAssets assets = ClientAcquisition.acquire(ClientOptions.defaults());
        PipelineRendererContext context = PipelineRendererContext.of(assets);
        diagnostics.info("pipeline ready: %d blocks, %d items at %s",
            context.knownBlockIds().size(), context.knownItemIds().size(), assets.vanillaRoot());
        AtlasRenderer.AtlasResult atlas = new AtlasRenderer(context).renderAtlas(AtlasOptions.defaults());

        boolean animated = atlas.image().isAnimated();
        File outputFile = outputDir.resolve("atlas." + (animated ? "webp" : "png")).toFile();
        ImageFactory imageFactory = new ImageFactory();
        if (animated)
            imageFactory.toFile(atlas.image(), ImageFormat.WEBP, outputFile,
                WebPWriteOptions.builder().isLossless().isMultithreaded().build());
        else
            imageFactory.toFile(atlas.image(), ImageFormat.PNG, outputFile);

        AtlasSidecar sidecar = atlas.sidecar();
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
        AtlasSidecar written = readSidecar(jsonFile);
        if (written.count() != written.tiles().size())
            throw new ToolingException("Atlas sidecar '%s' declares %d tiles and carries %d", jsonFile.toAbsolutePath(), written.count(), written.tiles().size());
        if (!written.equals(sidecar))
            throw new ToolingException("Atlas sidecar '%s' does not read back as the %d tiles it was written from", jsonFile.toAbsolutePath(), sidecar.tiles().size());
        diagnostics.info("wrote atlas: %d tiles -> %s (%dx%d px)",
            sidecar.tiles().size(), outputFile.getAbsolutePath(), atlas.image().getWidth(), atlas.image().getHeight());
        diagnostics.info("wrote sidecar: %s", jsonFile.toAbsolutePath());
    }

    /**
     * Reads the atlas raster and its typed sidecar back off disk for the diagnostic passes.
     *
     * <p>Both diagnostic passes read the same two files, so the read and its guards happen here
     * once. An animated pack leaves only {@code atlas.webp} behind and the slice diagnostics need
     * the raster {@code atlas.png}, so that tree records a Diagnostics ERROR and yields nothing
     * rather than throwing - the render that produced it was legitimate, and only the analysis
     * stops. A tree holding neither image, or an unreadable one, is a hard failure.
     *
     * @param diagnostics the sink the run reports through
     * @param root the directory holding {@code atlas.png} and {@code atlas.json}
     * @return the decoded atlas and its sidecar, or empty when the tree holds an animated atlas only
     * @throws IOException if the atlas PNG or its sidecar cannot be read
     * @throws ToolingException if either file is missing, the sidecar is unparseable or names a kind
     *     or source no constant answers to, or the PNG cannot be decoded
     */
    private static @NotNull Optional<LoadedAtlas> loadAtlas(@NotNull Diagnostics diagnostics, @NotNull Path root) throws IOException {
        Path atlasPng = root.resolve("atlas.png");
        Path atlasJson = root.resolve("atlas.json");
        if (!Files.isRegularFile(atlasPng)) {
            if (Files.isRegularFile(root.resolve("atlas.webp"))) {
                diagnostics.error("animated atlas (atlas.webp) - slice diagnostics need the raster atlas.png, and no webp decoder is wired");
                return Optional.empty();
            }
            throw new ToolingException("Missing atlas image '%s'", atlasPng.toAbsolutePath());
        }
        if (!Files.isRegularFile(atlasJson))
            throw new ToolingException("Missing atlas sidecar '%s'", atlasJson.toAbsolutePath());

        AtlasSidecar sidecar = readSidecar(atlasJson);
        BufferedImage atlas = ImageIO.read(atlasPng.toFile());
        if (atlas == null)
            throw new ToolingException("Could not decode atlas PNG '%s'", atlasPng.toAbsolutePath());
        return Optional.of(new LoadedAtlas(atlas, sidecar));
    }

    /**
     * Reads a sidecar off disk, surfacing an undecodable one as a failure naming the file.
     *
     * <p>Both failure modes are the file's, not the caller's: bytes that are not JSON, and a row
     * whose kind or source token {@link AtlasSidecar} resolves against no constant. Each arrives as
     * the same {@link ToolingException} naming the path, so a hand-edited or foreign sidecar reports
     * which file the run choked on.
     *
     * @param file the sidecar to read
     * @return the typed sidecar
     * @throws IOException if the file cannot be read
     * @throws ToolingException if the bytes are not JSON, or a row names a kind or source no
     *     constant answers to
     */
    private static @NotNull AtlasSidecar readSidecar(@NotNull Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return AtlasSidecar.parse(JsonTree.parse(bytes));
        } catch (JsonException | IllegalArgumentException ex) {
            throw new ToolingException(ex, "Failed to parse atlas sidecar '%s'", file.toAbsolutePath());
        }
    }

    /**
     * Slices every tile into {@code slice/<id>.png} and flags the transparent and sparse ones into
     * {@code missing.json}.
     *
     * <p>Each tile is copied out of the atlas into a standalone raster, written, then scanned by
     * {@link #scan(BufferedImage, int, int)}. A tile flagged by either signal contributes a row
     * carrying its grid cell and pixel rectangle plus {@code fullyTransparent},
     * {@code sparseContent} and its opaque ratio; the report header carries the tile total, the
     * flagged count split by signal, and the threshold the sparse signal compared against. Progress
     * is reported every {@value #PROGRESS_INTERVAL} tiles.
     *
     * @param diagnostics the sink the run reports through
     * @param root the directory holding the atlas, and the parent of the slice output
     * @param loaded the decoded atlas and its sidecar
     * @throws IOException if a slice or the report cannot be written
     */
    private static void sliceAndFlag(@NotNull Diagnostics diagnostics, @NotNull Path root, @NotNull LoadedAtlas loaded) throws IOException {
        BufferedImage atlas = loaded.image();
        AtlasSidecar sidecar = loaded.sidecar();
        Path sliceDir = root.resolve("slice");
        Files.createDirectories(sliceDir);
        int total = sidecar.tiles().size();
        diagnostics.info("slicing %d tiles into %s", total, sliceDir);

        JsonTree flagged = JsonTree.array();
        int fully = 0;
        int sparse = 0;
        for (int i = 0; i < total; i++) {
            AtlasTile tile = sidecar.tiles().get(i);
            BufferedImage slice = copy(atlas.getSubimage(tile.x(), tile.y(), tile.width(), tile.height()), tile.width(), tile.height());
            ImageIO.write(slice, "PNG", sliceDir.resolve(sanitize(tile.id()) + ".png").toFile());

            Result scan = scan(slice, tile.width(), tile.height());
            if (scan.fullyTransparent() || scan.sparseContent()) {
                flagged.add(tileJson(tile)
                    .put("fullyTransparent", scan.fullyTransparent())
                    .put("sparseContent", scan.sparseContent())
                    .put("opaqueRatio", round4(scan.opaqueRatio())));
                if (scan.fullyTransparent()) fully++;
                else sparse++;
            }
            if ((i + 1) % PROGRESS_INTERVAL == 0) diagnostics.info("sliced %d/%d", i + 1, total);
        }

        JsonTree report = JsonTree.object()
            .putInt("atlasTileCount", total)
            .putInt("missingCount", fully + sparse)
            .putInt("fullyTransparent", fully)
            .putInt("sparseContent", sparse)
            .put("sparseContentThreshold", (float) SPARSE_CONTENT_THRESHOLD)
            .put("tiles", flagged);
        Path missing = root.resolve("missing.json");
        report.write(missing);
        diagnostics.info("wrote %s", missing.toAbsolutePath());
        diagnostics.info("flagged %d/%d tiles (%d fully transparent, %d sparse)", fully + sparse, total, fully, sparse);
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
     * @param diagnostics the sink the run reports through
     * @param loaded the decoded atlas and its sidecar
     * @param filter the registration source to keep, paired with the contained directory the three
     *     files land in
     * @throws IOException if the mini-atlas, its sidecar or the id list cannot be written
     */
    private static void writeSourceAtlas(@NotNull Diagnostics diagnostics, @NotNull LoadedAtlas loaded, @NotNull SourceFilter filter) throws IOException {
        BufferedImage atlas = loaded.image();
        AtlasSidecar sidecar = loaded.sidecar();
        Path outDir = filter.directory();
        Files.createDirectories(outDir);
        int tileSize = sidecar.tileSize();

        List<AtlasTile> matching = new ArrayList<>();
        for (AtlasTile tile : sidecar.tiles())
            if (filter.source().equals(tile.source().jsonName())) matching.add(tile);
        matching.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        if (matching.isEmpty()) {
            diagnostics.info("no tiles matched --source-filter=%s", filter.source());
            return;
        }

        int columns = (int) Math.max(1, Math.ceil(Math.sqrt(matching.size())));
        int rows = (matching.size() + columns - 1) / columns;
        BufferedImage mini = new BufferedImage(columns * tileSize, rows * tileSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = mini.createGraphics();
        JsonTree miniTiles = JsonTree.array();
        List<String> ids = new ArrayList<>(matching.size());
        for (int i = 0; i < matching.size(); i++) {
            AtlasTile tile = matching.get(i);
            int col = i % columns;
            int row = i / columns;
            graphics.drawImage(atlas.getSubimage(tile.x(), tile.y(), tile.width(), tile.height()), col * tileSize, row * tileSize, null);
            miniTiles.add(tileJson(new AtlasTile(tile.id(), tile.kind(), tile.source(),
                col, row, col * tileSize, row * tileSize, tile.width(), tile.height())));
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
        diagnostics.info("wrote %s", atlasJson.toAbsolutePath());

        ids.sort(String.CASE_INSENSITIVE_ORDER);
        // A literal LF here and after the last id, for the reason the sidecar carries one: the
        // platform separator makes an emitted file's bytes a fact about the host that produced them.
        Files.writeString(outDir.resolve("ids.txt"), String.join("\n", ids) + "\n");
        diagnostics.info("wrote mini-atlas: %d tiles, %dx%d grid -> %s", matching.size(), columns, rows, outDir.resolve("atlas.png").toAbsolutePath());
    }

    /**
     * Builds one tile's grid and pixel fields as a JSON object, the row prefix both the flagged
     * list and the mini-atlas sidecar share.
     *
     * @param tile the tile to transcribe
     * @return the tile's id, kind, source, grid cell and pixel rectangle
     */
    private static @NotNull JsonTree tileJson(@NotNull AtlasTile tile) {
        return JsonTree.object()
            .put("id", tile.id())
            .put("kind", tile.kind().jsonName())
            .put("source", tile.source().jsonName())
            .putInt("col", tile.col())
            .putInt("row", tile.row())
            .putInt("x", tile.x())
            .putInt("y", tile.y())
            .putInt("width", tile.width())
            .putInt("height", tile.height());
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
     * @throws ToolingException if the name is empty, carries a separator, a parent reference or an
     *     absolute path, or resolves to the base itself or outside it
     */
    private static @NotNull Path resolveContained(@NotNull Path base, @NotNull String name) {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..") || Path.of(name).isAbsolute())
            throw new ToolingException("--source-filter '%s' must be a simple directory name (no separators, parent refs, or absolute paths)", name);
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path resolved = normalizedBase.resolve(name).normalize();
        if (!resolved.startsWith(normalizedBase) || resolved.equals(normalizedBase))
            throw new ToolingException("--source-filter '%s' resolves outside the base directory", name);
        return resolved;
    }

}
