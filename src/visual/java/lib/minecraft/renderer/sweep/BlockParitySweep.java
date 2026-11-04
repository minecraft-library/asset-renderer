package lib.minecraft.renderer.sweep;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.call.request.BlockOptions;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.store.diff.ParityMetrics;
import lib.minecraft.renderer.vanilla.Biome;
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
 * Per-block parity report comparing the Java pipeline output (via {@link BlockRenderer} in
 * {@link BlockOptions.Type#ISOMETRIC_3D ISOMETRIC_3D} mode) against the vanilla-reference-harness
 * ground truth PNGs in the harness reference tree's {@code blocks/}. The harness
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
 * <p>A reference named {@code <block>~<property>=<value>} is one case of a {@code block_state} select
 * on the block's item definition, which the harness draws with the stack carrying that property in its
 * {@code minecraft:block_state} component and nothing else changed. It is a row of its own, keyed by
 * that name, and this side draws it the way the stack reaches a slot: the block's faithful inventory
 * icon through {@link ItemRenderer}, the same stack in its context.
 *
 * <p>Output is organised one folder per block under {@code cache/visual/block-parity-vanilla/}
 * - each contains {@code vanilla.png} and {@code java.png}, and {@code diff.png} and
 * {@code diff_panel.png} where the two differ. A top-level {@code parity-report.tsv} ranks blocks by
 * mean ARGB delta ascending.
 *
 * <p>Buckets follow the convention {@code <0.25 / <0.50 / <0.75 / <1.0}.
 *
 * <p>Usage: {@code ./gradlew blockParityVanilla [-PblockId=minecraft:tnt]}.
 */
@UtilityClass
public final class BlockParitySweep {

    /** Output directory for the per-block sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/block-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced reference PNGs. */
    private static final Path VANILLA_DIR = ParityPaths.references("blocks");

    /** Square render size (matches harness {@code refharness.size} default). */
    private static final int RENDER_SIZE = 512;

    /** What separates a block id from each {@code <property>=<value>} a state-case reference names. */
    private static final char TOKEN = '~';

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
     * @param args {@code args[0]} optional comma-separated list of subjects - block ids
     *     ({@code minecraft:tnt,minecraft:stone}), or a state case as its reference names it
     *     ({@code minecraft:beehive~honey_level=5}). When absent, every harness reference PNG
     *     present in {@link #VANILLA_DIR} whose block the Java pipeline also knows about is rendered.
     */
    static void run(@NotNull RendererContext context, String @NotNull [] args) throws IOException {
        List<String> blockIdFilter = args.length > 0
            ? List.of(args[0].split(","))
            : List.of();

        if (!Files.isDirectory(VANILLA_DIR)) {
            System.err.printf("Vanilla reference directory missing: %s%n  Run renderVanillaReferences first.%n",
                VANILLA_DIR.toAbsolutePath());
            return;
        }
        Files.createDirectories(OUTPUT_DIR);

        BlockRenderer javaRenderer = new BlockRenderer(context);
        ItemRenderer iconRenderer = new ItemRenderer(context);

        TreeSet<String> javaKeys = new TreeSet<>(context.knownBlockIds());
        TreeSet<String> vanillaKeys = SweepPair.referenceIds(VANILLA_DIR);
        TreeSet<String> intersection = new TreeSet<>(vanillaKeys);
        intersection.removeIf(subject -> !javaKeys.contains(blockOf(subject)));

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
            .map(blockId -> renderAndCompare(blockId, javaRenderer, iconRenderer))
            .collect(Collectors.toCollection(ArrayList::new));
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        rows.sort(SweepReport.byDelta(Row::meanDelta));

        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows)
            lines.add(String.join("\t",
                r.blockId(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
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
            System.out.printf("    %-40s mean delta %.2f%n", r.blockId(), r.meanDelta());
    }

    /**
     * Renders one block through the Java pipeline, diffs it against the vanilla reference, writes
     * the per-block files, and returns the comparison {@link Row}. Self-contained (no shared mutable state) so it can run concurrently across
     * blocks via {@code parallelStream}; the shared {@code javaRenderer} reads only the immutable
     * {@link RendererContext}. Any failure (missing/unreadable reference, render error) is
     * marked {@code POSITIVE_INFINITY} rather than aborting the sweep - which is what sorts it
     * last, and what {@link SweepReport} emits as {@code failed}.
     */
    private static @NotNull Row renderAndCompare(
        @NotNull String blockId,
        @NotNull BlockRenderer javaRenderer,
        @NotNull ItemRenderer iconRenderer
    ) {
        Path blockDir = OUTPUT_DIR.resolve(blockId.replace(':', '_').replace('=', '_'));
        try {
            Files.createDirectories(blockDir);
            Path vanillaPng = SweepPair.reference(VANILLA_DIR, blockId);
            BufferedImage vanillaImg = ImageIO.read(vanillaPng.toFile());
            if (vanillaImg == null) {
                System.err.printf("       %-40s vanilla PNG unreadable: %s%n", blockId, vanillaPng);
                return new Row(blockId, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0);
            }
            int vw = vanillaImg.getWidth();
            int vh = vanillaImg.getHeight();

            // No explicit variant: BlockRenderer falls back to the block's tooling-derived
            // default blockstate key (block_defaults.json, baked onto Block.defaultStateKey),
            // which is vanilla's `block.defaultBlockState()`, so blocks like doors /
            // glazed_terracotta resolve the correct variant + per-variant rotation.
            ImageData java = blockId.indexOf(TOKEN) < 0
                ? javaRenderer.render(BlockOptions.builder()
                    .blockId(blockId)
                    .type(BlockOptions.Type.ISOMETRIC_3D)
                    .output(OutputOptions.builder()
                        .canvasSize(RENDER_SIZE)
                        .build())
                    .biome(Biome.INVENTORY_DEFAULT)
                    .build()).image()
                : iconRenderer.render(ItemOptions.builder()
                    .itemId(blockOf(blockId))
                    .type(ItemOptions.Type.GUI_ICON)
                    .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(RENDER_SIZE).build())
                    .context(ItemContext.ofStack(stateCaseStack(blockId)))
                    .build()).image();
            PixelBuffer held = java.toPixelBuffer();
            // The PNG holds premultiplied colour as the harness's does: the render's held ints are its bytes.
            BufferedImage javaImg = PixelBuffer.ofStraight(held.data(), held.width(), held.height()).toBufferedImage();
            int jw = javaImg.getWidth();
            int jh = javaImg.getHeight();

            ParityMetrics.Stats stats = SweepPair.writePair(blockDir, vanillaPng, vanillaImg, javaImg);
            String dimMismatch = (vw == jw && vh == jh) ? "" : String.format(" [%dx%d vs %dx%d]", jw, jh, vw, vh);
            System.out.printf("  %-40s mean delta %.2f  diff-px %d  java-cov %.1f%%  vanilla-cov %.1f%%%s%n",
                blockId, stats.meanDelta(), stats.differingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100, dimMismatch);
            return new Row(blockId, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(), jw, jh, vw, vh);
        } catch (Exception ex) {
            System.err.printf("       %-40s FAILED: %s%n", blockId, ex.getMessage());
            return new Row(blockId, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0);
        }
    }

    /** The block a subject draws - the whole subject, or what precedes a state case's first token. */
    private static @NotNull String blockOf(@NotNull String subject) {
        int token = subject.indexOf(TOKEN);
        return token < 0 ? subject : subject.substring(0, token);
    }

    /**
     * Builds the stack a state-case reference was drawn with: one of the block's item, its
     * {@code minecraft:block_state} component holding each {@code <property>=<value>} the subject
     * names as a string, which is how the component stores every value.
     */
    private static @NotNull CompoundTag stateCaseStack(@NotNull String subject) {
        CompoundTag properties = new CompoundTag();
        for (String token : subject.substring(subject.indexOf(TOKEN) + 1).split(String.valueOf(TOKEN))) {
            int equals = token.indexOf('=');
            properties.put(token.substring(0, equals), new StringTag(token.substring(equals + 1)));
        }
        CompoundTag components = new CompoundTag();
        components.put("minecraft:block_state", properties);
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(blockOf(subject)));
        stack.put("count", new IntTag(1));
        stack.put("components", components);
        return stack;
    }

    /** Per-block row in the TSV report. */
    private record Row(@NotNull String blockId, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage, int javaW, int javaH, int vanillaW, int vanillaH) {}

}
