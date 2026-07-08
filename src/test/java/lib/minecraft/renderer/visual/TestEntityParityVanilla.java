package lib.minecraft.renderer.visual;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Per-entity parity report comparing the Java pipeline output (via {@link EntityRenderer} +
 * {@code entity_models.json} / {@code entity_geometry.json}) against the
 * vanilla-reference-harness ground truth PNGs in
 * {@code cache/asset-renderer/vanilla/26.1/references/entities/}. The harness drives a real
 * Minecraft client to render each entity through vanilla's actual render path, so its output is
 * the canonical baseline; the Java pipeline aims to match it.
 *
 * <p>Both sides are now rendered at native resolution: the harness uses
 * {@code pixelsPerBlock=256} with a {@code maxCanvasSize=1024} cap, and {@code EntityRenderer}
 * mirrors that math (see its {@code computeCanvasFit}) so for entities present in both pipelines
 * the dimensions agree. When dimensions still differ (e.g. variant family-fit not yet ported to
 * the Java side), both PNGs are pasted onto a common {@code (max(vw,jw), max(vh,jh))} canvas with
 * transparent margins before the per-pixel diff so the comparison still makes sense visually; the
 * dimension mismatch surfaces in the TSV report.
 *
 * <p>Output is organised one folder per entity under {@code cache/visual/entity-parity-vanilla/}
 * - each contains {@code vanilla.png}, {@code java.png}, {@code diff.png}. A top-level
 * {@code parity-report.tsv} ranks entities by mean ARGB delta ascending.
 *
 * <p>Usage: {@code ./gradlew :asset-renderer:entityParityVanilla [-PentityId=minecraft:zombie]}.
 */
@UtilityClass
public final class TestEntityParityVanilla {

    /** Output directory for the per-entity sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/entity-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced reference PNGs. */
    private static final Path VANILLA_DIR = Path.of("cache/asset-renderer/vanilla/26.1/references/entities");

    /** Filename prefix the harness writes (entity id with {@code :} replaced by {@code __}). */
    private static final @NotNull String VANILLA_PREFIX = "minecraft__";

    /**
     * Runs the parity sweep.
     *
     * @param args {@code args[0]} optional comma-separated list of entity ids
     *     ({@code minecraft:zombie,minecraft:cave_spider}). When absent, every harness reference
     *     PNG present in {@link #VANILLA_DIR} that the Java pipeline also knows about is rendered.
     */
    public static void main(String @NotNull [] args) throws IOException {
        List<String> entityIdFilter = args.length > 0
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
        ConcurrentMap<String, EntityModelLoader.EntityDefinition> javaEntities = EntityModelLoader.load();
        if (javaEntities.isEmpty()) {
            System.err.println("entity_models.json missing - run :asset-renderer:entityModelsJava first");
            return;
        }
        EntityRenderer javaRenderer = new EntityRenderer(context, javaEntities);

        TreeSet<String> javaKeys = new TreeSet<>(javaEntities.keySet());
        TreeSet<String> vanillaKeys = collectVanillaEntityIds();
        TreeSet<String> intersection = new TreeSet<>(vanillaKeys);
        intersection.retainAll(javaKeys);

        List<String> entityIds = entityIdFilter.isEmpty()
            ? List.copyOf(intersection)
            : entityIdFilter;

        System.out.printf("Parity sweep (vs vanilla harness): %d entities to %s (vanilla-only: %d, java-only: %d)%n",
            entityIds.size(), OUTPUT_DIR.toAbsolutePath(),
            vanillaKeys.size() - intersection.size(),
            javaKeys.size() - intersection.size());

        long t0 = System.nanoTime();
        // Parallel dispatch across independent per-entity renders, mirroring
        // TestBlockParityVanilla / AtlasRenderer.render's parallelStream pattern: the
        // PipelineRendererContext indexes are ConcurrentMaps and the texture cache is thread-safe,
        // EntityRenderer holds only the read-only context + immutable entity-definition map, and each
        // render allocates its own engine + buffers - so a single shared javaRenderer is safe to call
        // concurrently. Each entity writes to its own sub-directory and returns its Row; no shared
        // mutable state beyond the collector. (RendererDebug's bounds-dump toggle is a ThreadLocal,
        // so the per-entity begin/end framing stays correct on each worker thread.)
        List<Row> rows = entityIds.parallelStream()
            .map(entityId -> renderAndCompare(entityId, javaRenderer))
            .collect(Collectors.toCollection(ArrayList::new));
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        rows.sort((a, b) -> Double.compare(a.meanDelta(), b.meanDelta()));

        StringBuilder report = new StringBuilder();
        report.append("entity_id\tmean_argb_delta\tdiffering_pixels\tjava_coverage\tvanilla_coverage\tjava_w\tjava_h\tvanilla_w\tvanilla_h\n");
        for (Row r : rows)
            report.append(String.format("%s\t%.4f\t%d\t%.4f\t%.4f\t%d\t%d\t%d\t%d%n",
                r.entityId(), r.meanDelta(), r.differingPixels(), r.javaCoverage(), r.vanillaCoverage(),
                r.javaW(), r.javaH(), r.vanillaW(), r.vanillaH()));
        Files.writeString(REPORT_FILE, report.toString());
        System.out.printf("Wrote %s (%d rows, %d ms total)%n", REPORT_FILE, rows.size(), totalMs);

        long below025 = rows.stream().filter(r -> r.meanDelta() < 0.25).count();
        long below05 = rows.stream().filter(r -> r.meanDelta() < 0.5).count();
        long below075 = rows.stream().filter(r -> r.meanDelta() < 0.75).count();
        long below1 = rows.stream().filter(r -> r.meanDelta() < 1.0).count();
        System.out.printf("Parity buckets: <0.25: %d / <0.5: %d / <0.75: %d / <1: %d / total: %d%n",
            below025, below05, below075, below1, rows.size());
        List<Row> worst = rows.stream()
            .sorted((a, b) -> Double.compare(b.meanDelta(), a.meanDelta()))
            .toList();
        System.out.println("Worst deltas (worst first):");
        for (Row r : worst.subList(0, Math.min(15, worst.size())))
            System.out.printf("    %-40s mean delta %.2f%n", r.entityId(), r.meanDelta());
    }

    /**
     * Renders one entity through the Java pipeline, diffs it against the vanilla reference, writes
     * the per-entity {@code vanilla/java/diff/diff_panel} PNGs, and returns the comparison
     * {@link Row}. Self-contained (no shared mutable state) so it can run concurrently across
     * entities via {@code parallelStream}; the shared {@code javaRenderer} reads only the immutable
     * {@link PipelineRendererContext} + entity-definition map. Any failure (missing/unreadable
     * reference, render error) is captured as a {@code POSITIVE_INFINITY} sentinel row rather than
     * aborting the sweep.
     *
     * @param entityId the entity id to render and compare
     * @param javaRenderer the shared read-only Java entity renderer
     * @return the comparison row, or a {@code POSITIVE_INFINITY} sentinel on failure
     */
    private static @NotNull Row renderAndCompare(@NotNull String entityId, @NotNull EntityRenderer javaRenderer) {
        Path entityDir = OUTPUT_DIR.resolve(entityId.replace(':', '_'));
        try {
            Files.createDirectories(entityDir);
            Path vanillaPng = VANILLA_DIR.resolve(VANILLA_PREFIX + entityId.substring("minecraft:".length()) + ".png");
            BufferedImage vanillaImg = ImageIO.read(vanillaPng.toFile());
            if (vanillaImg == null) {
                System.err.printf("       %-40s vanilla PNG unreadable: %s%n", entityId, vanillaPng);
                return new Row(entityId, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0);
            }
            int vw = vanillaImg.getWidth();
            int vh = vanillaImg.getHeight();

            EntityOptions options = EntityOptions.builder()
                .entityId(Optional.of(entityId))
                .fitMode(EntityOptions.FitMode.FAMILY_BOUNDS)
                .build();
            ImageData java;
            lib.minecraft.renderer.engine.RendererDebug.beginPerEntityBoundsDump(entityId);
            try {
                java = javaRenderer.render(options);
            } finally {
                lib.minecraft.renderer.engine.RendererDebug.endPerEntityBoundsDump(entityId);
            }
            BufferedImage javaImg = java.toBufferedImage();
            int jw = javaImg.getWidth();
            int jh = javaImg.getHeight();

            int cw = Math.max(vw, jw);
            int ch = Math.max(vh, jh);
            BufferedImage vanillaPadded = ParityMetrics.padToCanvas(vanillaImg, cw, ch);
            BufferedImage javaPadded = ParityMetrics.padToCanvas(javaImg, cw, ch);

            ImageIO.write(vanillaImg, "PNG", new File(entityDir.toFile(), "vanilla.png"));
            ImageIO.write(javaImg, "PNG", new File(entityDir.toFile(), "java.png"));
            PixelBuffer vanillaPB = PixelBuffer.wrap(vanillaPadded);
            PixelBuffer javaPB = PixelBuffer.wrap(javaPadded);
            BufferedImage diffImg = vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage();
            ImageIO.write(diffImg, "PNG", new File(entityDir.toFile(), "diff.png"));
            BufferedImage panelImg = ParityMetrics.panelDiff(vanillaPadded, javaPadded, vanillaPB, javaPB);
            ImageIO.write(panelImg, "PNG", new File(entityDir.toFile(), "diff_panel.png"));

            ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaPadded, javaPadded);
            String dimMismatch = (vw == jw && vh == jh) ? "" : String.format(" [%dx%d vs %dx%d]", jw, jh, vw, vh);
            System.out.printf("  %-40s mean delta %.2f  diff-px %d  java-cov %.1f%%  vanilla-cov %.1f%%%s%n",
                entityId, stats.meanDelta(), stats.differingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100, dimMismatch);
            return new Row(entityId, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(), jw, jh, vw, vh);
        } catch (Exception ex) {
            System.err.printf("       %-40s FAILED: %s%n", entityId, ex.getMessage());
            return new Row(entityId, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Walks the harness output directory and converts each {@code minecraft__<name>.png} filename
     * into the entity id {@code minecraft:<name>} so the cross-reference with the Java pipeline's
     * keyset is exact.
     */
    private static @NotNull TreeSet<String> collectVanillaEntityIds() throws IOException {
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

    /** Per-entity row in the TSV report. */
    private record Row(@NotNull String entityId, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage, int javaW, int javaH, int vanillaW, int vanillaH) {}

}
