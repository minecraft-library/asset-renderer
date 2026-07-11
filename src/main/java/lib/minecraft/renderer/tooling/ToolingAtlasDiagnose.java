package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.tooling.atlas.AtlasScan;
import lib.minecraft.renderer.tooling.atlas.AtlasSidecar;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.JsonNode;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of the {@code diagnoseAtlas2} Gradle task - the post-hoc atlas analyzer
 * (decision 35: no redesign). Reads {@code atlas.json} through the typed {@link AtlasSidecar}
 * and {@code atlas.png}, slices every tile into {@code slice/<id>.png}, and writes
 * {@code missing.json} listing tiles flagged by two signals:
 * <ul>
 *   <li>{@code fullyTransparent} - every pixel {@code alpha == 0} (the render produced nothing);</li>
 *   <li>{@code sparseContent} - fewer than {@link AtlasPolicies} {@code SPARSE_CONTENT_THRESHOLD}
 *       of the tile's pixels are opaque (usually a template submodel).</li>
 * </ul>
 *
 * <p>{@code --source-filter=<source>} (wired as {@code diagnoseAtlas2Task10} with
 * {@code blockstate_only}) instead writes a mini-atlas of just that registration source's tiles.
 * Animated packs emit only {@code atlas.webp}; slice diagnostics need the raster {@code atlas.png},
 * so a webp-only run is a clean Diagnostics ERROR rather than a stack trace (09 SS9 Q3).
 */
public final class ToolingAtlasDiagnose {

    private static final @NotNull String SOURCE_FILTER_FLAG = "--source-filter=";
    private static final int PROGRESS_INTERVAL = 256;

    private ToolingAtlasDiagnose() {
    }

    /**
     * Runs the diagnoser.
     *
     * @param args optional {@code --source-filter=<name>} plus an optional output directory
     * @throws IOException if the atlas image, sidecar, or diagnostic outputs cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        Diagnostics diagnostics = Diagnostics.root("diagnoseAtlas", Diagnostics.Output.CONSOLE, null);
        Path root = Path.of("build/atlas");
        String sourceFilter = null;
        for (String arg : args) {
            if (arg.startsWith(SOURCE_FILTER_FLAG)) sourceFilter = arg.substring(SOURCE_FILTER_FLAG.length()).trim();
            else if (!arg.startsWith("--")) root = Path.of(arg);
        }

        Path atlasPng = root.resolve("atlas.png");
        Path atlasJson = root.resolve("atlas.json");
        if (!Files.isRegularFile(atlasPng)) {
            if (Files.isRegularFile(root.resolve("atlas.webp"))) {
                diagnostics.error("animated atlas (atlas.webp) - slice diagnostics need the raster atlas.png; webp decode is rewrite-era");
                return;
            }
            throw new ToolingException("Missing atlas image '%s'", atlasPng.toAbsolutePath());
        }
        if (!Files.isRegularFile(atlasJson))
            throw new ToolingException("Missing atlas sidecar '%s'", atlasJson.toAbsolutePath());

        AtlasSidecar sidecar = AtlasSidecar.parse(JsonNode.parse(Files.readAllBytes(atlasJson)));
        BufferedImage atlas = ImageIO.read(atlasPng.toFile());
        if (atlas == null)
            throw new ToolingException("Could not decode atlas PNG '%s'", atlasPng.toAbsolutePath());

        if (sourceFilter != null)
            runSourceFilter(diagnostics, root, atlas, sidecar, sourceFilter);
        else
            runFlagAndSlice(diagnostics, root, atlas, sidecar);
    }

    /** The default pass: slice every tile, flag the transparent / sparse ones into missing.json. */
    private static void runFlagAndSlice(@NotNull Diagnostics diagnostics, @NotNull Path root, @NotNull BufferedImage atlas, @NotNull AtlasSidecar sidecar) throws IOException {
        Path sliceDir = root.resolve("slice");
        Files.createDirectories(sliceDir);
        int total = sidecar.tiles().size();
        diagnostics.info("slicing %d tiles into %s", total, sliceDir);

        JsonNode flagged = JsonNode.array();
        int fully = 0;
        int sparse = 0;
        for (int i = 0; i < total; i++) {
            AtlasSidecar.Tile tile = sidecar.tiles().get(i);
            BufferedImage slice = copy(atlas.getSubimage(tile.x(), tile.y(), tile.width(), tile.height()), tile.width(), tile.height());
            ImageIO.write(slice, "PNG", sliceDir.resolve(sanitize(tile.id()) + ".png").toFile());

            AtlasScan.Result scan = AtlasScan.scan(slice, tile.width(), tile.height());
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

        JsonNode report = JsonNode.object()
            .putInt("atlasTileCount", total)
            .putInt("missingCount", fully + sparse)
            .putInt("fullyTransparent", fully)
            .putInt("sparseContent", sparse)
            .put("sparseContentThreshold", (float) AtlasScan.sparseContentThreshold())
            .put("tiles", flagged);
        report.writeResource(root.resolve("missing.json"), diagnostics);
        diagnostics.info("flagged %d/%d tiles (%d fully transparent, %d sparse)", fully + sparse, total, fully, sparse);
    }

    /** The {@code --source-filter} pass: a mini-atlas of just the matching registration source's tiles. */
    private static void runSourceFilter(@NotNull Diagnostics diagnostics, @NotNull Path root, @NotNull BufferedImage atlas, @NotNull AtlasSidecar sidecar, @NotNull String sourceFilter) throws IOException {
        Path outDir = resolveContained(root, sourceFilter);
        Files.createDirectories(outDir);
        int tileSize = sidecar.tileSize();

        List<AtlasSidecar.Tile> matching = new ArrayList<>();
        for (AtlasSidecar.Tile tile : sidecar.tiles())
            if (sourceFilter.equals(tile.source())) matching.add(tile);
        matching.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        if (matching.isEmpty()) {
            diagnostics.info("no tiles matched --source-filter=%s", sourceFilter);
            return;
        }

        int columns = (int) Math.max(1, Math.ceil(Math.sqrt(matching.size())));
        int rows = (matching.size() + columns - 1) / columns;
        BufferedImage mini = new BufferedImage(columns * tileSize, rows * tileSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = mini.createGraphics();
        JsonNode miniTiles = JsonNode.array();
        List<String> ids = new ArrayList<>(matching.size());
        for (int i = 0; i < matching.size(); i++) {
            AtlasSidecar.Tile tile = matching.get(i);
            int col = i % columns;
            int row = i / columns;
            graphics.drawImage(atlas.getSubimage(tile.x(), tile.y(), tile.width(), tile.height()), col * tileSize, row * tileSize, null);
            miniTiles.add(tileJson(new AtlasSidecar.Tile(tile.id(), tile.kind(), tile.source(),
                col, row, col * tileSize, row * tileSize, tile.width(), tile.height())));
            ids.add(tile.id());
        }
        graphics.dispose();

        ImageIO.write(mini, "PNG", outDir.resolve("atlas.png").toFile());
        JsonNode miniRoot = JsonNode.object()
            .putInt("tileSize", tileSize)
            .putInt("columns", columns)
            .putInt("count", matching.size())
            .put("sourceFilter", sourceFilter)
            .put("tiles", miniTiles);
        miniRoot.writeResource(outDir.resolve("atlas.json"), diagnostics);

        ids.sort(String.CASE_INSENSITIVE_ORDER);
        Files.writeString(outDir.resolve("ids.txt"), String.join(System.lineSeparator(), ids) + System.lineSeparator());
        diagnostics.info("wrote mini-atlas: %d tiles, %dx%d grid -> %s", matching.size(), columns, rows, outDir.resolve("atlas.png").toAbsolutePath());
    }

    /** One tile's grid + pixel fields as a JSON object (the flagged / mini-atlas row prefix). */
    private static @NotNull JsonNode tileJson(@NotNull AtlasSidecar.Tile tile) {
        return JsonNode.object()
            .put("id", tile.id())
            .put("kind", tile.kind())
            .put("source", tile.source())
            .putInt("col", tile.col())
            .putInt("row", tile.row())
            .putInt("x", tile.x())
            .putInt("y", tile.y())
            .putInt("width", tile.width())
            .putInt("height", tile.height());
    }

    /** Copies a subimage into a standalone {@code TYPE_INT_ARGB} raster for writing. */
    private static @NotNull BufferedImage copy(@NotNull BufferedImage slice, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        out.createGraphics().drawImage(slice, 0, 0, null);
        return out;
    }

    /** Rounds a ratio to 4 decimal places so the JSON stays readable. */
    private static float round4(double value) {
        return (float) (Math.round(value * 10_000.0) / 10_000.0);
    }

    /** Replaces path-reserved characters so sliced ids become filesystem-safe filenames. */
    private static @NotNull String sanitize(@NotNull String id) {
        return id.replace(':', '_').replace('/', '_');
    }

    /** Resolves an untrusted CLI name under {@code base}, rejecting escapes (path-containment). */
    private static @NotNull Path resolveContained(@NotNull Path base, @Nullable String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..") || Path.of(name).isAbsolute())
            throw new ToolingException("--source-filter '%s' must be a simple directory name (no separators, parent refs, or absolute paths)", name);
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path resolved = normalizedBase.resolve(name).normalize();
        if (!resolved.startsWith(normalizedBase) || resolved.equals(normalizedBase))
            throw new ToolingException("--source-filter '%s' resolves outside the base directory", name);
        return resolved;
    }

}
