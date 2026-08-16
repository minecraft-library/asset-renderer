package lib.minecraft.renderer.visual;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.parity.AppearanceCodec;
import lib.minecraft.renderer.parity.AppearanceKey;
import lib.minecraft.renderer.parity.ParityMetrics;
import lib.minecraft.renderer.parity.ParityPaths;
import lib.minecraft.renderer.parity.SweepReport;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
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

/**
 * Per-entity parity report comparing the Java pipeline output (via {@link EntityRenderer} +
 * {@code entity_models.json} / {@code entity_geometry.json}) against the
 * vanilla-reference-harness ground truth PNGs in
 * the harness reference tree's {@code entities/}. The harness drives a real
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
 * <p>Usage: {@code ./gradlew entityParityVanilla [-PentityId=minecraft:zombie]}.
 */
@UtilityClass
public final class TestEntityParityVanilla {

    /** Output directory for the per-entity sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/entity-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced reference PNGs. */
    private static final Path VANILLA_DIR = ParityPaths.references("entities");

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
            System.err.printf("Vanilla reference directory missing: %s%n  Run renderVanillaReferences first.%n",
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
        ConcurrentMap<String, Entity> javaEntities = EntityModelLoader.load();
        if (javaEntities.isEmpty()) {
            System.err.println("entity_models.json missing - run entityModels first");
            return;
        }
        EntityRenderer javaRenderer = new EntityRenderer(context, javaEntities);

        TreeSet<String> javaKeys = new TreeSet<>(javaEntities.keySet());
        AppearanceCodec codec = AppearanceCodec.of(javaEntities);

        // Every reference name is read through the one grammar both repos implement. A name this
        // parser cannot read stops the sweep: a reference emitted in a spelling nothing reads has no
        // row to be missing from, so it would otherwise look exactly like a reference never emitted.
        List<Subject> subjects = new ArrayList<>();
        List<AppearanceKey.Result.Malformed> malformed = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String fileName : collectReferenceNames()) {
            String stem = fileName.substring(0, fileName.length() - ".png".length());
            switch (codec.parse(fileName)) {
                case AppearanceKey.Result.Malformed bad -> malformed.add(bad);
                case AppearanceKey.Result.Parsed parsed -> {
                    if (javaKeys.contains(parsed.key().entityId())) subjects.add(new Subject(stem, parsed.key()));
                    else unresolved.add(stem);
                }
            }
        }
        if (!malformed.isEmpty()) {
            System.err.printf("%d reference name(s) the parser could not read:%n", malformed.size());
            for (AppearanceKey.Result.Malformed bad : malformed)
                System.err.printf("    %-56s %s%n", bad.name(), bad.reason());
            System.exit(1);
        }
        if (!entityIdFilter.isEmpty())
            subjects = subjects.stream()
                .filter(s -> entityIdFilter.contains(s.key().entityId()) || entityIdFilter.contains(s.refStem()))
                .collect(Collectors.toCollection(ArrayList::new));

        // Entities the pipeline models that no reference names - the other side of the coverage
        // question from `unresolved`, which is references naming an entity the pipeline does not model.
        TreeSet<String> covered = subjects.stream().map(s -> s.key().entityId())
            .collect(Collectors.toCollection(TreeSet::new));
        long uncovered = javaKeys.stream().filter(id -> !covered.contains(id)).count();
        System.out.printf("Parity sweep (vs vanilla harness): %d subjects to %s (malformed: 0, unresolved harness refs: %d, java entities with no reference: %d)%n",
            subjects.size(), OUTPUT_DIR.toAbsolutePath(), unresolved.size(), uncovered);

        long t0 = System.nanoTime();
        // Parallel dispatch across independent per-entity renders, mirroring
        // TestBlockParityVanilla / AtlasRenderer.render's parallelStream pattern: the
        // PipelineRendererContext indexes are ConcurrentMaps and the texture cache is thread-safe,
        // EntityRenderer holds only the read-only context + immutable entity-definition map, and each
        // render allocates its own engine + buffers - so a single shared javaRenderer is safe to call
        // concurrently. Each entity writes to its own sub-directory and returns its Row; no shared
        // mutable state beyond the collector. (RendererDebug's bounds-dump toggle is a ThreadLocal,
        // so the per-entity begin/end framing stays correct on each worker thread.)
        List<Row> rows = subjects.parallelStream()
            .map(subject -> renderAndCompare(subject, javaRenderer))
            .collect(Collectors.toCollection(ArrayList::new));
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        rows.sort(SweepReport.byDelta(Row::meanDelta));

        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows)
            lines.add(String.join("\t",
                r.entityId(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
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
            System.out.printf("    %-40s mean delta %.2f%n", r.entityId(), r.meanDelta());
    }

    /**
     * Renders one entity through the Java pipeline, diffs it against the vanilla reference, writes
     * the per-entity {@code vanilla/java/diff/diff_panel} PNGs, and returns the comparison
     * {@link Row}. Self-contained (no shared mutable state) so it can run concurrently across
     * entities via {@code parallelStream}; the shared {@code javaRenderer} reads only the immutable
     * {@link PipelineRendererContext} + entity-definition map. Any failure (missing/unreadable
     * reference, render error) marks the row {@code POSITIVE_INFINITY} rather than aborting the
     * sweep - which is what sorts it last, and what {@link SweepReport} emits as {@code failed}.
     *
     * @param subject the parity subject (its ref id names the vanilla PNG + output folder; its entity id
     *     and variant drive the Java render)
     * @param javaRenderer the shared read-only Java entity renderer
     * @return the comparison row, its delta {@code POSITIVE_INFINITY} on failure
     */
    private static @NotNull Row renderAndCompare(@NotNull Subject subject, @NotNull EntityRenderer javaRenderer) {
        String refId = subject.refStem();
        Path entityDir = OUTPUT_DIR.resolve(refId);
        try {
            Files.createDirectories(entityDir);
            Path vanillaPng = VANILLA_DIR.resolve(refId + ".png");
            BufferedImage vanillaImg = ImageIO.read(vanillaPng.toFile());
            if (vanillaImg == null) {
                System.err.printf("       %-40s vanilla PNG unreadable: %s%n", refId, vanillaPng);
                return new Row(refId, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0);
            }
            int vw = vanillaImg.getWidth();
            int vh = vanillaImg.getHeight();

            EntityOptions.Builder optionsBuilder = EntityOptions.builder()
                .entityId(Optional.of(subject.key().entityId()))
                .appearance(subject.key().appearance())
                .fitMode(EntityOptions.FitMode.GROUP_BOUNDS);
            subject.key().armor().ifPresent(optionsBuilder::armor);
            EntityOptions options = optionsBuilder.build();
            ImageData java;
            lib.minecraft.renderer.engine.RendererDebug.beginPerEntityBoundsDump(refId);
            try {
                java = javaRenderer.render(options);
            } finally {
                lib.minecraft.renderer.engine.RendererDebug.endPerEntityBoundsDump(refId);
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
                refId, stats.meanDelta(), stats.differingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100, dimMismatch);
            return new Row(refId, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(), jw, jh, vw, vh);
        } catch (Exception ex) {
            System.err.printf("       %-40s FAILED: %s%n", refId, ex.getMessage());
            return new Row(refId, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0);
        }
    }

    /** Lists every reference file name in the harness output directory, in sorted order. */
    private static @NotNull List<String> collectReferenceNames() throws IOException {
        try (Stream<Path> stream = Files.list(VANILLA_DIR)) {
            return stream.map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".png"))
                .sorted()
                .toList();
        }
    }

    /**
     * One parity subject: the reference to compare against, and what it says to render.
     *
     * @param refStem the reference file name without its suffix, which also names the output folder
     * @param key the render the name resolved to
     */
    private record Subject(@NotNull String refStem, @NotNull AppearanceKey key) {}

    /** Per-entity row in the TSV report. */
    private record Row(@NotNull String entityId, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage, int javaW, int javaH, int vanillaW, int vanillaH) {}

}
