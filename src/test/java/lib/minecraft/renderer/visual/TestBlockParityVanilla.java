package lib.minecraft.renderer.visual;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.engine.texture.Biome;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Per-block parity report comparing the Java pipeline output (via {@link BlockRenderer} in
 * {@link BlockOptions.Type#ISOMETRIC_3D ISOMETRIC_3D} mode) against the vanilla-reference-harness
 * ground truth PNGs in {@code cache/asset-renderer/vanilla/26.1/references/blocks/}. The harness
 * drives a real Minecraft client to render each block-as-item through vanilla's actual GUI
 * inventory pipeline ({@code ItemDisplayContext.GUI} display transform + {@code ITEMS_3D}
 * lighting + feature dispatcher to an offscreen RGBA8 texture), so its output is the canonical
 * baseline; the Java pipeline aims to match it.
 *
 * <p>Both sides render at a fixed {@code 512x512} canvas (the harness's
 * {@code HarnessConfig.IMAGE_SIZE} / {@code refharness.size} default). When dimensions diverge
 * (shouldn't happen at fixed canvas, but defensive), both PNGs are pasted onto a common
 * {@code (max(vw,jw), max(vh,jh))} canvas with transparent margins before the per-pixel diff so
 * the comparison still makes sense visually; the dimension mismatch surfaces in the TSV report.
 *
 * <p>Output is organised one folder per block under {@code cache/visual/block-parity-vanilla/}
 * - each contains {@code vanilla.png}, {@code java.png}, {@code diff.png}, {@code diff_panel.png}.
 * A top-level {@code parity-report.tsv} ranks blocks by mean ARGB delta ascending.
 *
 * <p>Buckets follow the convention {@code <0.25 / <0.50 / <0.75 / <1.0}.
 *
 * <p>Usage: {@code ./gradlew :asset-renderer:blockParityVanilla [-PblockId=minecraft:tnt]}.
 */
@UtilityClass
public final class TestBlockParityVanilla {

    /** Output directory for the per-block sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/block-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced reference PNGs. */
    private static final Path VANILLA_DIR = Path.of("cache/asset-renderer/vanilla/26.1/references/blocks");

    /** Filename prefix the harness writes (block id with {@code :} replaced by {@code __}). */
    private static final @NotNull String VANILLA_PREFIX = "minecraft__";

    /** Square render size (matches harness {@code refharness.size} default). */
    private static final int RENDER_SIZE = 512;

    /**
     * Biome modelling vanilla's no-world-context "in hand" tint - the colour
     * {@code BlockTintSource.color(state)} returns for a block-item GUI icon, which the harness now
     * bakes into every reference (see {@code BlockFrameRenderer.resolveInventoryTints}). Vanilla
     * resolves each tint target differently in hand, so this is not a single biome point:
     * <ul>
     * <li><b>grass</b> - {@code GrassColor.getDefaultColor() = get(0.5, 1.0)}, the grass colormap
     *     centre {@code (127, 127)}; reproduced by sampling at {@code temperature 0.5 / downfall
     *     1.0} with no grass override.</li>
     * <li><b>foliage</b> - the fixed {@code FoliageColor.FOLIAGE_DEFAULT = 0xFF48B518} constant, NOT
     *     a colormap sample; injected as a foliage override.</li>
     * <li><b>dry foliage</b> - the fixed {@code DryFoliageColor} default {@code 0xFF5C3C32};
     *     injected as a dry-foliage override.</li>
     * </ul>
     * Constant-tinted blocks (birch / spruce leaves, lily_pad) ignore the biome and use the fixed
     * colour baked into {@code block_tints.json}.
     */
    private static final @NotNull Biome INVENTORY_DEFAULT_BIOME = Biome.builder("inventory_default")
        .temperature(0.5f)
        .downfall(1.0f)
        .foliageColorOverride(0xFF48B518)
        .dryFoliageColorOverride(0xFF5C3C32)
        .build();

    /**
     * Blocks the maintainer has manually verified to be at acceptable parity against the
     * vanilla harness baseline. Starts empty - populate after inspecting per-block
     * {@code diff.png}; entries here split the focus pool reporting.
     */
    private static final @NotNull Set<String> ACHIEVED_PARITY = Set.of();

    /**
     * Runs the parity sweep.
     *
     * @param args {@code args[0]} optional comma-separated list of block ids
     *     ({@code minecraft:tnt,minecraft:stone}). When absent, every harness reference PNG
     *     present in {@link #VANILLA_DIR} that the Java pipeline also knows about is rendered.
     */
    public static void main(String @NotNull [] args) throws IOException {
        List<String> blockIdFilter = args.length > 0
            ? List.of(args[0].split(","))
            : List.of();

        if (!Files.isDirectory(VANILLA_DIR)) {
            System.err.printf("Vanilla reference directory missing: %s%n  Run :asset-renderer:renderVanillaReferences first.%n",
                VANILLA_DIR.toAbsolutePath());
            return;
        }
        Files.createDirectories(OUTPUT_DIR);

        Pipeline.Result result;
        try {
            result = Pipeline.run(PipelineOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        BlockRenderer javaRenderer = new BlockRenderer(context);

        TreeSet<String> javaKeys = new TreeSet<>(context.knownBlockIds());
        TreeSet<String> vanillaKeys = collectVanillaBlockIds();
        TreeSet<String> intersection = new TreeSet<>(vanillaKeys);
        intersection.retainAll(javaKeys);

        List<String> blockIds = blockIdFilter.isEmpty()
            ? List.copyOf(intersection)
            : blockIdFilter;

        System.out.printf("Block parity sweep (vs vanilla harness): %d blocks to %s (vanilla-only: %d, java-only: %d)%n",
            blockIds.size(), OUTPUT_DIR.toAbsolutePath(),
            vanillaKeys.size() - intersection.size(),
            javaKeys.size() - intersection.size());

        long t0 = System.nanoTime();
        // Parallel dispatch across independent per-block renders, mirroring
        // AtlasRenderer.render's parallelStream pattern: the RendererContext indexes are
        // ConcurrentMaps and the texture cache is thread-safe, BlockRenderer holds only the
        // read-only context, and each render allocates its own engine + buffers - so a single
        // shared javaRenderer is safe to call concurrently. Each block writes to its own
        // sub-directory and returns its Row; no shared mutable state beyond the collector.
        List<Row> rows = blockIds.parallelStream()
            .map(blockId -> renderAndCompare(blockId, javaRenderer))
            .collect(Collectors.toCollection(ArrayList::new));
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        rows.sort((a, b) -> Double.compare(a.meanDelta(), b.meanDelta()));

        StringBuilder report = new StringBuilder();
        report.append("block_id\tmean_argb_delta\tdiffering_pixels\tjava_coverage\tvanilla_coverage\tparity_achieved\tjava_w\tjava_h\tvanilla_w\tvanilla_h\n");
        for (Row r : rows)
            report.append(String.format("%s\t%.4f\t%d\t%.4f\t%.4f\t%s\t%d\t%d\t%d\t%d%n",
                r.blockId(), r.meanDelta(), r.differingPixels(), r.javaCoverage(), r.vanillaCoverage(), r.achieved(),
                r.javaW(), r.javaH(), r.vanillaW(), r.vanillaH()));
        Files.writeString(REPORT_FILE, report.toString());
        System.out.printf("Wrote %s (%d rows, %d ms total)%n", REPORT_FILE, rows.size(), totalMs);

        long below025 = rows.stream().filter(r -> r.meanDelta() < 0.25).count();
        long below05 = rows.stream().filter(r -> r.meanDelta() < 0.50).count();
        long below075 = rows.stream().filter(r -> r.meanDelta() < 0.75).count();
        long below1 = rows.stream().filter(r -> r.meanDelta() < 1.00).count();
        long achieved = rows.stream().filter(Row::achieved).count();
        long achievedNotInRun = ACHIEVED_PARITY.size() - achieved;
        System.out.printf("Parity buckets: <0.25: %d / <0.50: %d / <0.75: %d / <1.0: %d / total: %d%n",
            below025, below05, below075, below1, rows.size());
        System.out.printf("Achieved-parity allowlist: %d in this run / %d total%s%n",
            achieved, ACHIEVED_PARITY.size(),
            achievedNotInRun > 0 ? " (" + achievedNotInRun + " not rendered in this run - subset filter)" : "");
        List<Row> focus = rows.stream()
            .filter(r -> !r.achieved())
            .sorted((a, b) -> Double.compare(b.meanDelta(), a.meanDelta()))
            .toList();
        System.out.println("Focus pool (not in achieved-parity list, worst first):");
        for (Row r : focus.subList(0, Math.min(15, focus.size())))
            System.out.printf("    %-40s mean delta %.2f%n", r.blockId(), r.meanDelta());
    }

    /**
     * Renders one block through the Java pipeline, diffs it against the vanilla reference, writes
     * the per-block {@code vanilla/java/diff/diff_panel} PNGs, and returns the comparison
     * {@link Row}. Self-contained (no shared mutable state) so it can run concurrently across
     * blocks via {@code parallelStream}; the shared {@code javaRenderer} reads only the immutable
     * {@link PipelineRendererContext}. Any failure (missing/unreadable reference, render error) is
     * captured as a {@code POSITIVE_INFINITY} sentinel row rather than aborting the sweep.
     */
    private static @NotNull Row renderAndCompare(@NotNull String blockId, @NotNull BlockRenderer javaRenderer) {
        Path blockDir = OUTPUT_DIR.resolve(blockId.replace(':', '_'));
        try {
            Files.createDirectories(blockDir);
            Path vanillaPng = VANILLA_DIR.resolve(VANILLA_PREFIX + blockId.substring("minecraft:".length()) + ".png");
            BufferedImage vanillaImg = ImageIO.read(vanillaPng.toFile());
            if (vanillaImg == null) {
                System.err.printf("       %-40s vanilla PNG unreadable: %s%n", blockId, vanillaPng);
                return new Row(blockId, Double.POSITIVE_INFINITY, -1, 0, 0, false, 0, 0, 0, 0);
            }
            int vw = vanillaImg.getWidth();
            int vh = vanillaImg.getHeight();

            // No explicit variant: BlockRenderer falls back to the block's tooling-derived
            // default blockstate key (block_defaults.json, baked onto Block.defaultStateKey),
            // which is vanilla's `block.defaultBlockState()`. This replaces the harness
            // `.variant` sidecar this test used to consume - blocks like doors /
            // glazed_terracotta still resolve the correct variant + per-variant rotation.
            BlockOptions options = BlockOptions.builder()
                .blockId(blockId)
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .output(OutputOptions.builder()
                    .canvasSize(RENDER_SIZE)
                    .build())
                .biome(INVENTORY_DEFAULT_BIOME)
                .build();
            ImageData java = javaRenderer.render(options);
            BufferedImage javaImg = java.toBufferedImage();
            int jw = javaImg.getWidth();
            int jh = javaImg.getHeight();

            int cw = Math.max(vw, jw);
            int ch = Math.max(vh, jh);
            BufferedImage vanillaPadded = ParityMetrics.padToCanvas(vanillaImg, cw, ch);
            BufferedImage javaPadded = ParityMetrics.padToCanvas(javaImg, cw, ch);

            ImageIO.write(vanillaImg, "PNG", new File(blockDir.toFile(), "vanilla.png"));
            ImageIO.write(javaImg, "PNG", new File(blockDir.toFile(), "java.png"));
            PixelBuffer vanillaPB = PixelBuffer.wrap(vanillaPadded);
            PixelBuffer javaPB = PixelBuffer.wrap(javaPadded);
            BufferedImage diffImg = vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage();
            ImageIO.write(diffImg, "PNG", new File(blockDir.toFile(), "diff.png"));
            BufferedImage panelImg = ParityMetrics.panelDiff(vanillaPadded, javaPadded, vanillaPB, javaPB);
            ImageIO.write(panelImg, "PNG", new File(blockDir.toFile(), "diff_panel.png"));

            ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaPadded, javaPadded);
            boolean achieved = ACHIEVED_PARITY.contains(blockId);
            String dimMismatch = (vw == jw && vh == jh) ? "" : String.format(" [%dx%d vs %dx%d]", jw, jh, vw, vh);
            System.out.printf("  %s %-40s mean delta %.2f  diff-px %d  java-cov %.1f%%  vanilla-cov %.1f%%%s%n",
                achieved ? "[OK]" : "    ",
                blockId, stats.meanDelta(), stats.differingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100, dimMismatch);
            return new Row(blockId, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(), achieved, jw, jh, vw, vh);
        } catch (Exception ex) {
            System.err.printf("       %-40s FAILED: %s%n", blockId, ex.getMessage());
            return new Row(blockId, Double.POSITIVE_INFINITY, -1, 0, 0, false, 0, 0, 0, 0);
        }
    }

    /**
     * Walks the harness output directory and converts each {@code minecraft__<name>.png} filename
     * into the block id {@code minecraft:<name>} so the cross-reference with the Java pipeline's
     * keyset is exact.
     */
    private static @NotNull TreeSet<String> collectVanillaBlockIds() throws IOException {
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

    /** Per-block row in the TSV report. */
    private record Row(@NotNull String blockId, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage, boolean achieved, int javaW, int javaH, int vanillaW, int vanillaH) {}

}
