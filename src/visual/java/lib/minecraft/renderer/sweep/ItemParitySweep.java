package lib.minecraft.renderer.sweep;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.store.diff.ParityMetrics;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Per-item parity report comparing the Java pipeline output (via {@link ItemRenderer} in
 * {@link ItemOptions.Type#GUI_2D GUI_2D} mode) against the vanilla-reference-harness ground
 * truth PNGs in the harness reference tree's {@code items/}. The harness drives
 * a real Minecraft client to render each item through vanilla's GUI inventory pipeline
 * ({@code ItemDisplayContext.GUI} + {@code ITEMS_FLAT} / {@code ITEMS_3D} lighting + feature
 * dispatcher to an offscreen RGBA8 texture), so its output is the canonical baseline.
 *
 * <p>Items here means everything in the vanilla {@code Items} registry that is <em>not</em> a
 * {@code BlockItem} - block icons are covered by {@link BlockParitySweep}. Both sides
 * render at a fixed {@code 512x512} canvas to match the harness's {@code refharness.size}
 * default.
 *
 * <p>Output is organised one folder per item under {@code cache/visual/item-parity-vanilla/}
 * - each contains {@code vanilla.png} and {@code java.png}, and {@code diff.png} and
 * {@code diff_panel.png} where the two differ. A top-level {@code parity-report.tsv} ranks items by
 * mean ARGB delta ascending.
 *
 * <p>Buckets follow the convention {@code <0.25 / <0.50 / <0.75 / <1.0}.
 *
 * <p>Usage: {@code ./gradlew itemParityVanilla [-PitemId=minecraft:diamond_sword]}.
 */
@UtilityClass
public final class ItemParitySweep {

    /** Output directory for the per-item sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/item-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced reference PNGs. */
    private static final Path VANILLA_DIR = ParityPaths.references("items");

    /** Square render size (matches harness {@code refharness.size} default). */
    private static final int RENDER_SIZE = 512;

    /**
     * Runs the parity sweep over a context of its own, loaded over vanilla's own client options.
     *
     * @param args what {@link #run} reads
     * @throws IOException if the output cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        run(RendererContext.load(SweepPair.acquire(ClientOptions.defaults())), args);
    }

    /**
     * Runs the parity sweep.
     *
     * @param context the context every render resolves through, loaded over vanilla's own client options
     * @param args {@code args[0]} optional comma-separated list of item ids
     *     ({@code minecraft:diamond_sword,minecraft:bow}). When absent, every harness reference
     *     PNG present in {@link #VANILLA_DIR} that the Java pipeline also knows about is rendered.
     */
    static void run(@NotNull RendererContext context, String @NotNull [] args) throws IOException {
        List<String> itemIdFilter = args.length > 0
            ? List.of(args[0].split(","))
            : List.of();

        if (!Files.isDirectory(VANILLA_DIR)) {
            System.err.printf("Vanilla reference directory missing: %s%n  Run renderVanillaReferences first.%n",
                VANILLA_DIR.toAbsolutePath());
            return;
        }
        Files.createDirectories(OUTPUT_DIR);

        ItemRenderer javaRenderer = new ItemRenderer(context);

        TreeSet<String> javaKeys = new TreeSet<>(context.knownItemIds());
        TreeSet<String> vanillaKeys = SweepPair.referenceIds(VANILLA_DIR);
        TreeSet<String> intersection = new TreeSet<>(vanillaKeys);
        intersection.retainAll(javaKeys);

        List<String> itemIds = itemIdFilter.isEmpty()
            ? List.copyOf(intersection)
            : itemIdFilter;

        System.out.printf("Item parity sweep (vs vanilla harness): %d items to %s (vanilla-only: %d, java-only: %d)%n",
            itemIds.size(), OUTPUT_DIR.toAbsolutePath(),
            vanillaKeys.size() - intersection.size(),
            javaKeys.size() - intersection.size());

        long t0 = System.nanoTime();
        // Parallel across independent per-item renders, for BlockParitySweep's reason: the context
        // indexes are concurrent, the renderer holds only read-only state, and each render allocates
        // its own engine and buffers. The collect keeps encounter order, so the table below is the
        // one a sequential loop writes.
        List<Row> rows = itemIds.parallelStream()
            .map(itemId -> renderAndCompare(itemId, javaRenderer))
            .collect(Collectors.toCollection(ArrayList::new));
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        rows.sort(SweepReport.byDelta(Row::meanDelta));

        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows)
            lines.add(String.join("\t",
                r.itemId(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
                SweepReport.pixels(r.meanDelta(), r.differingPixels()),
                SweepReport.ratio(r.javaCoverage()), SweepReport.ratio(r.vanillaCoverage()),
                Integer.toString(r.javaW()), Integer.toString(r.javaH()),
                Integer.toString(r.vanillaW()), Integer.toString(r.vanillaH())));
        SweepReport.write(REPORT_FILE, SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tdiffering_pixels\tjava_coverage\tvanilla_coverage"
            + "\tjava_w\tjava_h\tvanilla_w\tvanilla_h", lines);
        System.out.printf("Wrote %s (%d rows, %d ms total)%n", REPORT_FILE, rows.size(), totalMs);

        SweepReport.printBuckets(rows.stream().mapToDouble(Row::meanDelta).toArray());
        List<Row> worst = rows.stream()
            .sorted((a, b) -> Double.compare(b.meanDelta(), a.meanDelta()))
            .toList();
        System.out.println("Worst deltas (worst first):");
        for (Row r : worst.subList(0, Math.min(15, worst.size())))
            System.out.printf("    %-40s mean delta %.2f%n", r.itemId(), r.meanDelta());
    }

    /**
     * Renders one item through the Java pipeline, diffs it against the vanilla reference, writes the
     * per-item files and returns the comparison {@link Row}. Self-contained, so it runs concurrently
     * across items. Any failure (missing/unreadable reference, render error) is marked
     * {@code POSITIVE_INFINITY} rather than aborting the sweep - which is what sorts it last, and what
     * {@link SweepReport} emits as {@code failed}.
     *
     * @param itemId the item id
     * @param javaRenderer the shared read-only renderer
     * @return the comparison row, its delta {@code POSITIVE_INFINITY} on failure
     */
    private static @NotNull Row renderAndCompare(@NotNull String itemId, @NotNull ItemRenderer javaRenderer) {
        Path itemDir = OUTPUT_DIR.resolve(itemId.replace(':', '_'));
        try {
            Files.createDirectories(itemDir);
            Path vanillaPng = SweepPair.reference(VANILLA_DIR, itemId);
            BufferedImage vanillaImg = ImageIO.read(vanillaPng.toFile());
            if (vanillaImg == null) {
                System.err.printf("       %-40s vanilla PNG unreadable: %s%n", itemId, vanillaPng);
                return new Row(itemId, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0);
            }
            int vw = vanillaImg.getWidth();
            int vh = vanillaImg.getHeight();

            // The row is the strip's first frame, and an always-foil item's glint scrolls: a static
            // glint is that frame alone rather than the whole loop baked for it.
            ItemOptions options = ItemOptions.builder()
                .itemId(itemId)
                .type(ItemOptions.Type.GUI_2D)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(RENDER_SIZE).build())
                .animateGlint(false)
                .build();
            ImageData java = javaRenderer.render(options).image();
            PixelBuffer held = java.toPixelBuffer();
            // The PNG holds premultiplied colour as the harness's does: the render's held ints are its bytes.
            BufferedImage javaImg = PixelBuffer.ofStraight(held.data(), held.width(), held.height()).toBufferedImage();
            int jw = javaImg.getWidth();
            int jh = javaImg.getHeight();

            ParityMetrics.Stats stats = SweepPair.writePair(itemDir, vanillaPng, vanillaImg, javaImg);
            String dimMismatch = (vw == jw && vh == jh) ? "" : String.format(" [%dx%d vs %dx%d]", jw, jh, vw, vh);
            System.out.printf("  %-40s mean delta %.2f  diff-px %d  java-cov %.1f%%  vanilla-cov %.1f%%%s%n",
                itemId, stats.meanDelta(), stats.differingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100, dimMismatch);
            return new Row(itemId, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(), jw, jh, vw, vh);
        } catch (Exception ex) {
            System.err.printf("       %-40s FAILED: %s%n", itemId, ex.getMessage());
            return new Row(itemId, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0);
        }
    }

    /** Per-item row in the TSV report. */
    private record Row(@NotNull String itemId, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage, int javaW, int javaH, int vanillaW, int vanillaH) {}

}
