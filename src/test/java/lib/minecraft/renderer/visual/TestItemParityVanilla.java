package lib.minecraft.renderer.visual;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Per-item parity report comparing the Java pipeline output (via {@link ItemRenderer} in
 * {@link ItemOptions.Type#GUI_2D GUI_2D} mode) against the vanilla-reference-harness ground
 * truth PNGs in {@code cache/asset-renderer/vanilla/26.1/references/items/}. The harness drives
 * a real Minecraft client to render each item through vanilla's GUI inventory pipeline
 * ({@code ItemDisplayContext.GUI} + {@code ITEMS_FLAT} / {@code ITEMS_3D} lighting + feature
 * dispatcher to an offscreen RGBA8 texture), so its output is the canonical baseline.
 *
 * <p>Items here means everything in the vanilla {@code Items} registry that is <em>not</em> a
 * {@code BlockItem} - block icons are covered by {@link TestBlockParityVanilla}. Both sides
 * render at a fixed {@code 512x512} canvas to match the harness's {@code refharness.size}
 * default.
 *
 * <p>Output is organised one folder per item under {@code cache/visual/item-parity-vanilla/}
 * - each contains {@code vanilla.png}, {@code java.png}, {@code diff.png}, {@code diff_panel.png}.
 * A top-level {@code parity-report.tsv} ranks items by mean ARGB delta ascending.
 *
 * <p>Buckets follow the convention {@code <0.25 / <0.50 / <0.75 / <1.0}.
 *
 * <p>Usage: {@code ./gradlew :asset-renderer:itemParityVanilla [-PitemId=minecraft:diamond_sword]}.
 */
@UtilityClass
public final class TestItemParityVanilla {

    /** Output directory for the per-item sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/item-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced reference PNGs. */
    private static final Path VANILLA_DIR = Path.of("cache/asset-renderer/vanilla/26.1/references/items");

    /** Filename prefix the harness writes (item id with {@code :} replaced by {@code __}). */
    private static final @NotNull String VANILLA_PREFIX = "minecraft__";

    /** Square render size (matches harness {@code refharness.size} default). */
    private static final int RENDER_SIZE = 512;

    /**
     * Runs the parity sweep.
     *
     * @param args {@code args[0]} optional comma-separated list of item ids
     *     ({@code minecraft:diamond_sword,minecraft:bow}). When absent, every harness reference
     *     PNG present in {@link #VANILLA_DIR} that the Java pipeline also knows about is rendered.
     */
    public static void main(String @NotNull [] args) throws IOException {
        List<String> itemIdFilter = args.length > 0
            ? List.of(args[0].split(","))
            : List.of();

        if (!Files.isDirectory(VANILLA_DIR)) {
            System.err.printf("Vanilla reference directory missing: %s%n  Run :asset-renderer:renderVanillaReferences first.%n",
                VANILLA_DIR.toAbsolutePath());
            return;
        }
        Files.createDirectories(OUTPUT_DIR);

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        ItemRenderer javaRenderer = new ItemRenderer(context);

        TreeSet<String> javaKeys = new TreeSet<>(context.knownItemIds());
        TreeSet<String> vanillaKeys = collectVanillaItemIds();
        TreeSet<String> intersection = new TreeSet<>(vanillaKeys);
        intersection.retainAll(javaKeys);

        List<String> itemIds = itemIdFilter.isEmpty()
            ? List.copyOf(intersection)
            : itemIdFilter;

        System.out.printf("Item parity sweep (vs vanilla harness): %d items to %s (vanilla-only: %d, java-only: %d)%n",
            itemIds.size(), OUTPUT_DIR.toAbsolutePath(),
            vanillaKeys.size() - intersection.size(),
            javaKeys.size() - intersection.size());

        List<Row> rows = new ArrayList<>();
        long t0 = System.nanoTime();
        for (String itemId : itemIds) {
            String safeName = itemId.replace(':', '_');
            Path itemDir = OUTPUT_DIR.resolve(safeName);
            Files.createDirectories(itemDir);
            try {
                Path vanillaPng = VANILLA_DIR.resolve(VANILLA_PREFIX + itemId.substring("minecraft:".length()) + ".png");
                BufferedImage vanillaImg = ImageIO.read(vanillaPng.toFile());
                if (vanillaImg == null) {
                    System.err.printf("       %-40s vanilla PNG unreadable: %s%n", itemId, vanillaPng);
                    rows.add(new Row(itemId, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0));
                    continue;
                }
                int vw = vanillaImg.getWidth();
                int vh = vanillaImg.getHeight();

                ItemOptions options = ItemOptions.builder()
                    .itemId(itemId)
                    .type(ItemOptions.Type.GUI_2D)
                    .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(RENDER_SIZE).build())
                    .build();
                ImageData java = javaRenderer.render(options);
                BufferedImage javaImg = java.toBufferedImage();
                int jw = javaImg.getWidth();
                int jh = javaImg.getHeight();

                int cw = Math.max(vw, jw);
                int ch = Math.max(vh, jh);
                BufferedImage vanillaPadded = ParityMetrics.padToCanvas(vanillaImg, cw, ch);
                BufferedImage javaPadded = ParityMetrics.padToCanvas(javaImg, cw, ch);

                ImageIO.write(vanillaImg, "PNG", new File(itemDir.toFile(), "vanilla.png"));
                ImageIO.write(javaImg, "PNG", new File(itemDir.toFile(), "java.png"));
                PixelBuffer vanillaPB = PixelBuffer.wrap(vanillaPadded);
                PixelBuffer javaPB = PixelBuffer.wrap(javaPadded);
                BufferedImage diffImg = vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage();
                ImageIO.write(diffImg, "PNG", new File(itemDir.toFile(), "diff.png"));
                BufferedImage panelImg = ParityMetrics.panelDiff(vanillaPadded, javaPadded, vanillaPB, javaPB);
                ImageIO.write(panelImg, "PNG", new File(itemDir.toFile(), "diff_panel.png"));

                ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaPadded, javaPadded);
                String dimMismatch = (vw == jw && vh == jh) ? "" : String.format(" [%dx%d vs %dx%d]", jw, jh, vw, vh);
                rows.add(new Row(itemId, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(), jw, jh, vw, vh));
                System.out.printf("  %-40s mean delta %.2f  diff-px %d  java-cov %.1f%%  vanilla-cov %.1f%%%s%n",
                    itemId, stats.meanDelta(), stats.differingPixels(),
                    stats.javaCoverage() * 100, stats.vanillaCoverage() * 100, dimMismatch);
            } catch (Exception ex) {
                System.err.printf("       %-40s FAILED: %s%n", itemId, ex.getMessage());
                rows.add(new Row(itemId, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0));
            }
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        rows.sort((a, b) -> Double.compare(a.meanDelta(), b.meanDelta()));

        StringBuilder report = new StringBuilder();
        report.append("item_id\tmean_argb_delta\tdiffering_pixels\tjava_coverage\tvanilla_coverage\tjava_w\tjava_h\tvanilla_w\tvanilla_h\n");
        for (Row r : rows)
            report.append(String.format("%s\t%.4f\t%d\t%.4f\t%.4f\t%d\t%d\t%d\t%d%n",
                r.itemId(), r.meanDelta(), r.differingPixels(), r.javaCoverage(), r.vanillaCoverage(),
                r.javaW(), r.javaH(), r.vanillaW(), r.vanillaH()));
        Files.writeString(REPORT_FILE, report.toString());
        System.out.printf("Wrote %s (%d rows, %d ms total)%n", REPORT_FILE, rows.size(), totalMs);

        long below025 = rows.stream().filter(r -> r.meanDelta() < 0.25).count();
        long below05 = rows.stream().filter(r -> r.meanDelta() < 0.50).count();
        long below075 = rows.stream().filter(r -> r.meanDelta() < 0.75).count();
        long below1 = rows.stream().filter(r -> r.meanDelta() < 1.00).count();
        System.out.printf("Parity buckets: <0.25: %d / <0.50: %d / <0.75: %d / <1.0: %d / total: %d%n",
            below025, below05, below075, below1, rows.size());
        List<Row> worst = rows.stream()
            .sorted((a, b) -> Double.compare(b.meanDelta(), a.meanDelta()))
            .toList();
        System.out.println("Worst deltas (worst first):");
        for (Row r : worst.subList(0, Math.min(15, worst.size())))
            System.out.printf("    %-40s mean delta %.2f%n", r.itemId(), r.meanDelta());
    }

    /**
     * Walks the harness output directory and converts each {@code minecraft__<name>.png} filename
     * into the item id {@code minecraft:<name>}.
     */
    private static @NotNull TreeSet<String> collectVanillaItemIds() throws IOException {
        try (Stream<Path> stream = Files.list(VANILLA_DIR)) {
            TreeSet<String> ids = new TreeSet<>();
            stream.forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.startsWith(VANILLA_PREFIX) || !name.endsWith(".png")) return;
                String stem = name.substring(VANILLA_PREFIX.length(), name.length() - ".png".length());
                ids.add("minecraft:" + stem);
            });
            return ids;
        }
    }

    /** Per-item row in the TSV report. */
    private record Row(@NotNull String itemId, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage, int javaW, int javaH, int vanillaW, int vanillaH) {}

}
