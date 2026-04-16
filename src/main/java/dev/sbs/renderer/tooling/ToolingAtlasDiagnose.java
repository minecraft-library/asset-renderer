package dev.sbs.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Diagnostic pass over the atlas output. Reads {@code atlas.json} + {@code atlas.png} from an
 * output directory (defaults to {@code build/atlas}), slices every tile into
 * {@code slice/{sanitizedId}.png}, and writes {@code missing.json} listing tiles that look
 * broken or near-empty based on two independent signals:
 * <ul>
 * <li>{@code fullyTransparent} - every pixel has {@code alpha == 0}. Primary bug signal:
 *     the render produced nothing at all.</li>
 * <li>{@code sparseContent} - fewer than {@link #SPARSE_CONTENT_THRESHOLD} of the tile's
 *     pixels are opaque. Catches tiles that technically rendered something but only a
 *     handful of pixels, which is usually a sign of a template submodel that shouldn't be
 *     in the atlas (e.g. {@code glass_pane_post} contributing only the 2px centre column,
 *     or {@code tripwire_*} sub-models contributing a single string). Legitimately thin
 *     blocks like torches, candles, and chains stay above the threshold so they are not
 *     false-flagged.</li>
 * </ul>
 * A tile is flagged when either signal is true. Each entry carries both signal values plus
 * the {@code opaqueRatio} metric so post-processing can distinguish outright blanks from
 * sparse renders.
 * <p>
 * The previous {@code centerTransparent} / {@code centerBlockTransparent} single-pixel and
 * 5x5 block checks were removed: the geometric centre of an isometric block render is empty
 * by construction (the camera ray passes through the block's interior) so pressure plates,
 * rails, and similar thin blocks were false-flagged at rates that drowned out real bugs.
 * <p>
 * The tool does not invoke the renderer - it only analyses already-produced output, so it stays
 * fast and isolated from pipeline bootstrap cost. Rerun after regenerating the atlas to refresh
 * the diagnosis.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:diagnoseAtlas} (or pass a directory as
 * {@code args[0]}).
 */
@UtilityClass
public final class ToolingAtlasDiagnose {

    /** How often to emit a progress dot while slicing tiles. */
    private static final int PROGRESS_DOT_INTERVAL = 256;

    /**
     * Opaque-pixel fraction below which a tile is flagged as {@code sparseContent}. Tuned at
     * {@code 2%} after inspecting the flag distribution: at {@code 5%} normal thin blocks
     * (torches, candles at 2-count, buttons pressed) were false-flagged; at {@code 2%} the
     * bucket is dominated by template submodels that should be filtered at registration
     * rather than rendered (tripwire sub-models, glass_pane sub-posts, stem_stage early
     * growth models).
     */
    private static final double SPARSE_CONTENT_THRESHOLD = 0.02;

    public static void main(String @NotNull [] args) throws IOException {
        Path root = Path.of("build/atlas");
        String sourceFilter = null;
        for (String arg : args) {
            if (arg.startsWith("--source-filter=")) sourceFilter = arg.substring("--source-filter=".length()).trim();
            else if (!arg.startsWith("--")) root = Path.of(arg);
        }
        Path atlasPng = root.resolve("atlas.png");
        Path atlasJson = root.resolve("atlas.json");
        Path sliceDir = root.resolve("slice");
        Path missingJson = root.resolve("missing.json");

        if (!Files.isRegularFile(atlasPng))
            throw new IOException("Missing atlas image: " + atlasPng.toAbsolutePath());
        if (!Files.isRegularFile(atlasJson))
            throw new IOException("Missing atlas sidecar: " + atlasJson.toAbsolutePath());

        if (sourceFilter != null) {
            runSourceFilter(root, atlasPng, atlasJson, sourceFilter);
            return;
        }

        Files.createDirectories(sliceDir);

        System.out.printf("Reading %s...%n", atlasJson);
        JsonObject sidecar = new Gson().fromJson(Files.readString(atlasJson), JsonObject.class);
        JsonArray tiles = sidecar.getAsJsonArray("tiles");
        int total = tiles.size();
        System.out.printf("Reading %s (%d tiles)...%n", atlasPng, total);
        BufferedImage atlas = ImageIO.read(atlasPng.toFile());
        if (atlas == null)
            throw new IOException("Could not decode atlas PNG: " + atlasPng.toAbsolutePath());

        System.out.printf("Slicing %d tiles into %s...%n", total, sliceDir);
        JsonArray flagged = new JsonArray();
        int fullyCount = 0;
        int sparseCount = 0;

        for (int i = 0; i < total; i++) {
            JsonObject tile = tiles.get(i).getAsJsonObject();
            String id = tile.get("id").getAsString();
            int x = tile.get("x").getAsInt();
            int y = tile.get("y").getAsInt();
            int width = tile.get("width").getAsInt();
            int height = tile.get("height").getAsInt();

            BufferedImage slice = atlas.getSubimage(x, y, width, height);
            BufferedImage sliceCopy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            sliceCopy.createGraphics().drawImage(slice, 0, 0, null);
            ImageIO.write(sliceCopy, "PNG", sliceDir.resolve(sanitize(id) + ".png").toFile());

            ScanResult scan = scan(sliceCopy, width, height);
            if (scan.fullyTransparent || scan.sparseContent) {
                JsonObject entry = tile.deepCopy();
                entry.addProperty("fullyTransparent", scan.fullyTransparent);
                entry.addProperty("sparseContent", scan.sparseContent);
                entry.addProperty("opaqueRatio", round4(scan.opaqueRatio));
                flagged.add(entry);

                if (scan.fullyTransparent) fullyCount++;
                else sparseCount++;
            }

            if ((i + 1) % PROGRESS_DOT_INTERVAL == 0) {
                System.out.print('.');
                System.out.flush();
            }
        }
        System.out.println();

        JsonObject report = new JsonObject();
        report.addProperty("atlasTileCount", total);
        report.addProperty("missingCount", flagged.size());
        report.addProperty("fullyTransparent", fullyCount);
        report.addProperty("sparseContent", sparseCount);
        report.addProperty("sparseContentThreshold", SPARSE_CONTENT_THRESHOLD);
        report.add("tiles", flagged);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(missingJson, gson.toJson(report) + System.lineSeparator());

        System.out.printf(
            "Flagged %d/%d tiles (%d fully transparent, %d sparse [<%.0f%% opaque]) -> %s%n",
            flagged.size(),
            total,
            fullyCount,
            sparseCount,
            SPARSE_CONTENT_THRESHOLD * 100.0,
            missingJson.toAbsolutePath()
        );
    }

    /**
     * Scans a slice once and returns both signal values plus the opaque-pixel ratio. Runs in
     * a single pass over the pixel buffer and short-circuits early when both signals have
     * already been decided - a tile whose opaque count has crossed
     * {@link #SPARSE_CONTENT_THRESHOLD} is neither {@code fullyTransparent} nor
     * {@code sparseContent}, so further pixel reads are just for the ratio metric.
     */
    private static @NotNull ScanResult scan(@NotNull BufferedImage image, int width, int height) {
        int opaqueCount = 0;
        for (int yy = 0; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                if ((image.getRGB(xx, yy) >>> 24) != 0) opaqueCount++;
            }
        }

        int totalPixels = width * height;
        double opaqueRatio = totalPixels == 0 ? 0.0 : (double) opaqueCount / totalPixels;
        boolean fullyTransparent = opaqueCount == 0;
        boolean sparseContent = !fullyTransparent && opaqueRatio < SPARSE_CONTENT_THRESHOLD;

        return new ScanResult(fullyTransparent, sparseContent, opaqueRatio);
    }

    /** Output of {@link #scan}: both flag values plus the opaque-pixel ratio. */
    private record ScanResult(
        boolean fullyTransparent,
        boolean sparseContent,
        double opaqueRatio
    ) {}

    /** Rounds a ratio to 4 decimal places so JSON output stays readable. */
    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    /**
     * Replaces path-reserved characters so sliced ids become Windows-safe filenames.
     * {@code "minecraft:acacia_log"} becomes {@code "minecraft_acacia_log"}.
     */
    private static @NotNull String sanitize(@NotNull String id) {
        return id.replace(':', '_').replace('/', '_');
    }

    /**
     * Writes a mini-atlas containing only the tiles whose {@code source} matches the requested
     * value. Output lands at {@code <root>/<sourceFilter>/}: a fresh {@code atlas.png} grid
     * composed from the filtered slices, an {@code atlas.json} trimmed to just those tiles, and
     * an {@code ids.txt} with the matching ids one per line, alphabetically sorted. Used to
     * visually verify additions from a specific registration path (e.g. Task 10's
     * {@code blockstate_only}) without hunting through the full atlas.
     */
    private static void runSourceFilter(@NotNull Path root, @NotNull Path atlasPng, @NotNull Path atlasJson, @NotNull String sourceFilter) throws IOException {
        Path outDir = root.resolve(sourceFilter);
        Files.createDirectories(outDir);

        System.out.printf("Reading %s...%n", atlasJson);
        JsonObject sidecar = new Gson().fromJson(Files.readString(atlasJson), JsonObject.class);
        JsonArray tiles = sidecar.getAsJsonArray("tiles");
        int tileSize = sidecar.get("tileSize").getAsInt();

        System.out.printf("Reading %s...%n", atlasPng);
        BufferedImage atlas = ImageIO.read(atlasPng.toFile());
        if (atlas == null)
            throw new IOException("Could not decode atlas PNG: " + atlasPng.toAbsolutePath());

        List<JsonObject> matching = new ArrayList<>();
        for (int i = 0; i < tiles.size(); i++) {
            JsonObject tile = tiles.get(i).getAsJsonObject();
            if (!tile.has("source")) continue;
            if (sourceFilter.equals(tile.get("source").getAsString())) matching.add(tile);
        }
        matching.sort((a, b) -> a.get("id").getAsString().compareToIgnoreCase(b.get("id").getAsString()));

        if (matching.isEmpty()) {
            System.out.printf("No tiles matched --source-filter=%s%n", sourceFilter);
            return;
        }

        int columns = (int) Math.max(1, Math.ceil(Math.sqrt(matching.size())));
        int rows = (matching.size() + columns - 1) / columns;
        BufferedImage mini = new BufferedImage(columns * tileSize, rows * tileSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mini.createGraphics();
        JsonArray miniTiles = new JsonArray();
        List<String> ids = new ArrayList<>(matching.size());
        for (int i = 0; i < matching.size(); i++) {
            JsonObject tile = matching.get(i);
            int x = tile.get("x").getAsInt();
            int y = tile.get("y").getAsInt();
            int width = tile.get("width").getAsInt();
            int height = tile.get("height").getAsInt();
            int col = i % columns;
            int row = i / columns;

            BufferedImage slice = atlas.getSubimage(x, y, width, height);
            g.drawImage(slice, col * tileSize, row * tileSize, null);

            JsonObject entry = new JsonObject();
            entry.addProperty("id", tile.get("id").getAsString());
            entry.addProperty("kind", tile.get("kind").getAsString());
            entry.addProperty("source", tile.get("source").getAsString());
            entry.addProperty("col", col);
            entry.addProperty("row", row);
            entry.addProperty("x", col * tileSize);
            entry.addProperty("y", row * tileSize);
            entry.addProperty("width", width);
            entry.addProperty("height", height);
            miniTiles.add(entry);
            ids.add(tile.get("id").getAsString());
        }
        g.dispose();

        Path miniPng = outDir.resolve("atlas.png");
        Path miniJson = outDir.resolve("atlas.json");
        Path idsTxt = outDir.resolve("ids.txt");

        ImageIO.write(mini, "PNG", miniPng.toFile());

        JsonObject miniRoot = new JsonObject();
        miniRoot.addProperty("tileSize", tileSize);
        miniRoot.addProperty("columns", columns);
        miniRoot.addProperty("count", matching.size());
        miniRoot.addProperty("sourceFilter", sourceFilter);
        miniRoot.add("tiles", miniTiles);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(miniJson, gson.toJson(miniRoot) + System.lineSeparator());

        Collections.sort(ids, String.CASE_INSENSITIVE_ORDER);
        Files.writeString(idsTxt, String.join(System.lineSeparator(), ids) + System.lineSeparator());

        System.out.printf(
            "Wrote mini-atlas: %d tiles, %dx%d grid -> %s%n",
            matching.size(),
            columns,
            rows,
            miniPng.toAbsolutePath()
        );
    }

}
