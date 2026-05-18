package lib.minecraft.renderer.visual;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.options.EntityOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

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
 * <p>Sibling of {@link TestEntityParity} - that one keeps comparing bedrock vs java; this one
 * holds the Java pipeline against the harness's ground truth so improvements show up against the
 * canonical reference rather than against a second derived pipeline.
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
     * Entities the maintainer has manually verified to be at acceptable parity against the
     * vanilla harness baseline. Starts empty - this baseline differs from the bedrock-vs-java
     * comparison in {@link TestEntityParity} so its allowlist is independent. Populate after
     * inspecting per-entity {@code diff.png}; entries here split the focus pool reporting.
     *
     * <p>Tier definitions live in {@code notes/JAVA_PIPELINE_RESEARCH.md}; the python helper
     * {@code scripts/parity_analysis/iterate_entity.py} validates that an entity actually meets
     * its assigned tier before it lands here. Promote candidates by running
     * {@code python scripts/parity_analysis/iterate_entity.py <entity>} and adding the entry
     * only when the script exits 0.
     */
    private static final @NotNull Set<String> ACHIEVED_PARITY = Set.of(
        "minecraft:polar_bear"
    );

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

        List<Row> rows = new ArrayList<>();
        long t0 = System.nanoTime();
        for (String entityId : entityIds) {
            String safeName = entityId.replace(':', '_');
            Path entityDir = OUTPUT_DIR.resolve(safeName);
            Files.createDirectories(entityDir);
            try {
                Path vanillaPng = VANILLA_DIR.resolve(VANILLA_PREFIX + entityId.substring("minecraft:".length()) + ".png");
                BufferedImage vanillaImg = ImageIO.read(vanillaPng.toFile());
                if (vanillaImg == null) {
                    System.err.printf("       %-40s vanilla PNG unreadable: %s%n", entityId, vanillaPng);
                    rows.add(new Row(entityId, Double.POSITIVE_INFINITY, -1, 0, 0, false, 0, 0, 0, 0));
                    continue;
                }
                int vw = vanillaImg.getWidth();
                int vh = vanillaImg.getHeight();

                EntityOptions options = EntityOptions.builder()
                    .entityId(Optional.of(entityId))
                    .antiAlias(false)
                    .build();
                boolean dumpBounds = Boolean.getBoolean("entity.bounds.dump");
                if (dumpBounds) {
                    System.out.printf("[BD] ===== %s START =====%n", entityId);
                    lib.minecraft.renderer.kit.EntityGeometryKit.setBoundsDump(true);
                }
                ImageData java;
                try {
                    java = javaRenderer.render(options);
                } finally {
                    if (dumpBounds) {
                        lib.minecraft.renderer.kit.EntityGeometryKit.setBoundsDump(false);
                        System.out.printf("[BD] ===== %s END =====%n", entityId);
                    }
                }
                BufferedImage javaImg = java.toBufferedImage();
                int jw = javaImg.getWidth();
                int jh = javaImg.getHeight();

                int cw = Math.max(vw, jw);
                int ch = Math.max(vh, jh);
                BufferedImage vanillaPadded = padToCanvas(vanillaImg, cw, ch);
                BufferedImage javaPadded = padToCanvas(javaImg, cw, ch);

                ImageIO.write(vanillaImg, "PNG", new File(entityDir.toFile(), "vanilla.png"));
                ImageIO.write(javaImg, "PNG", new File(entityDir.toFile(), "java.png"));
                BufferedImage diffImg = diffImage(vanillaPadded, javaPadded);
                ImageIO.write(diffImg, "PNG", new File(entityDir.toFile(), "diff.png"));
                BufferedImage panelImg = panelDiff(vanillaPadded, javaPadded);
                ImageIO.write(panelImg, "PNG", new File(entityDir.toFile(), "diff_panel.png"));

                Stats stats = compareImages(vanillaPadded, javaPadded);
                boolean achieved = ACHIEVED_PARITY.contains(entityId);
                String dimMismatch = (vw == jw && vh == jh) ? "" : String.format(" [%dx%d vs %dx%d]", jw, jh, vw, vh);
                rows.add(new Row(entityId, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(), achieved, jw, jh, vw, vh));
                System.out.printf("  %s %-40s mean delta %.2f  diff-px %d  java-cov %.1f%%  vanilla-cov %.1f%%%s%n",
                    achieved ? "[OK]" : "    ",
                    entityId, stats.meanDelta(), stats.differingPixels(),
                    stats.javaCoverage() * 100, stats.vanillaCoverage() * 100, dimMismatch);
            } catch (Exception ex) {
                System.err.printf("       %-40s FAILED: %s%n", entityId, ex.getMessage());
                rows.add(new Row(entityId, Double.POSITIVE_INFINITY, -1, 0, 0, false, 0, 0, 0, 0));
            }
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        rows.sort((a, b) -> Double.compare(a.meanDelta(), b.meanDelta()));

        StringBuilder report = new StringBuilder();
        report.append("entity_id\tmean_argb_delta\tdiffering_pixels\tjava_coverage\tvanilla_coverage\tparity_achieved\tjava_w\tjava_h\tvanilla_w\tvanilla_h\n");
        for (Row r : rows)
            report.append(String.format("%s\t%.4f\t%d\t%.4f\t%.4f\t%s\t%d\t%d\t%d\t%d%n",
                r.entityId(), r.meanDelta(), r.differingPixels(), r.javaCoverage(), r.vanillaCoverage(), r.achieved(),
                r.javaW(), r.javaH(), r.vanillaW(), r.vanillaH()));
        Files.writeString(REPORT_FILE, report.toString());
        System.out.printf("Wrote %s (%d rows, %d ms total)%n", REPORT_FILE, rows.size(), totalMs);

        long below025 = rows.stream().filter(r -> r.meanDelta() < 0.25).count();
        long below05 = rows.stream().filter(r -> r.meanDelta() < 0.5).count();
        long below075 = rows.stream().filter(r -> r.meanDelta() < 0.75).count();
        long below1 = rows.stream().filter(r -> r.meanDelta() < 1.0).count();
        long achieved = rows.stream().filter(Row::achieved).count();
        long achievedNotInRun = ACHIEVED_PARITY.size() - achieved;
        System.out.printf("Parity buckets: <0.25: %d / <0.5: %d / <0.75: %d / <1: %d / total: %d%n",
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
            System.out.printf("    %-40s mean delta %.2f%n", r.entityId(), r.meanDelta());
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

    /**
     * Centers {@code src} on a {@code (canvasW, canvasH)} transparent canvas so two differently-
     * sized renders can be diffed pixel-for-pixel without resampling. Used when the Java pipeline
     * and the vanilla harness disagree on canvas size for an entity (variant family-fit gap, etc).
     * No-op fast path when the source already matches the canvas.
     */
    private static @NotNull BufferedImage padToCanvas(@NotNull BufferedImage src, int canvasW, int canvasH) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw == canvasW && sh == canvasH) return src;
        BufferedImage out = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        int offX = (canvasW - sw) / 2;
        int offY = (canvasH - sh) / 2;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++)
                out.setRGB(offX + x, offY + y, src.getRGB(x, y));
        }
        return out;
    }

    /**
     * Computes per-pixel ARGB statistics between two same-size images. Mean delta uses
     * <b>over-white compositing</b>: each pixel is first composited onto a fully-opaque white
     * background ({@code C_out = C_src * A_src/255 + 255 * (1 - A_src/255)}), then the absolute
     * RGB difference is taken. This matches what a viewer perceives looking at the rendered PNG
     * over the standard image-viewer white background: a transparent pixel and a 50%-transparent
     * pixel with the same underlying RGB show only their compositing-blend difference, not the
     * raw alpha gap. Without compositing, AA edge spill ({@code vanilla [0,0,0,0]} vs
     * {@code java [246,246,246,127]}) was counted as an 865+ diff per pixel; with compositing
     * the same pixels contribute around {@code 14} (the slight tint a half-white pixel adds over
     * white). A pixel that's pure transparent in both images contributes 0; a fully different
     * opaque pixel contributes up to {@code 3 * 255 = 765}. Coverage is the fraction of pixels
     * with non-zero alpha.
     */
    private static @NotNull Stats compareImages(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        long totalDelta = 0L;
        long differing = 0L;
        long javaCoveredPx = 0L;
        long vanillaCoveredPx = 0L;
        long count = (long) w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int aa = ColorMath.alpha(pa);
                int ab = ColorMath.alpha(pb);
                int sum = compositeDiff(pa, aa, pb, ab);
                totalDelta += sum;
                if (sum > 0) differing++;
                if (aa > 0) vanillaCoveredPx++;
                if (ab > 0) javaCoveredPx++;
            }
        }
        double mean = (double) totalDelta / (double) count;
        return new Stats(mean, differing, (double) javaCoveredPx / count, (double) vanillaCoveredPx / count);
    }

    /**
     * Absolute RGB difference of two pixels after each has been composited onto a fully-opaque
     * white background. See {@link #compareImages} for why this is the perceptually-accurate
     * metric over a raw {@code |Rv - Rj| + |Gv - Gj| + |Bv - Bj|} that ignores alpha.
     */
    private static int compositeDiff(int pa, int aa, int pb, int ab) {
        int rOver = compositeOverWhite(ColorMath.red(pa), aa) - compositeOverWhite(ColorMath.red(pb), ab);
        int gOver = compositeOverWhite(ColorMath.green(pa), aa) - compositeOverWhite(ColorMath.green(pb), ab);
        int bOver = compositeOverWhite(ColorMath.blue(pa), aa) - compositeOverWhite(ColorMath.blue(pb), ab);
        return Math.abs(rOver) + Math.abs(gOver) + Math.abs(bOver);
    }

    /** {@code C_src * A/255 + 255 * (1 - A/255)} rounded to integer. */
    private static int compositeOverWhite(int channel, int alpha) {
        return (channel * alpha + 255 * (255 - alpha) + 127) / 255;
    }

    /**
     * Builds a six-panel debug visualisation packing every diff lens we care about into one
     * inspectable image. Layout (3 columns × 2 rows, with labels):
     * <pre>
     *   +-------------------+-------------------+-------------------+
     *   | vanilla reference | java pipeline     | |Δ| amplified 4×  |
     *   +-------------------+-------------------+-------------------+
     *   | luminance signed  | per-channel signed| coverage-only     |
     *   +-------------------+-------------------+-------------------+
     * </pre>
     *
     * <p>Each lens isolates a different failure mode:
     * <ul>
     *   <li><b>vanilla / java</b>: side-by-side originals so the eye can spot orientation
     *       / silhouette / clipping issues that any algorithmic diff would lose.</li>
     *   <li><b>{@code |Δ|} amplified 4×</b>: the existing absolute-delta diff. Magenta = coverage
     *       mismatch; transparent = match; RGB = colour delta with each channel × 4. Good for
     *       finding <em>any</em> difference but loses sign and clips at 255.</li>
     *   <li><b>luminance signed</b>: maps {@code (vanilla_luma - java_luma)} to a red↔blue
     *       divergent palette. Mid-grey = match. <span style="color:red">Red</span> = vanilla
     *       brighter than java (asset-renderer too dark on this face). <span style="color:blue">Blue</span>
     *       = java brighter than vanilla (too bright). Lighting bugs show as solid-coloured
     *       regions because lighting moves R, G, B uniformly.</li>
     *   <li><b>per-channel signed</b>: each pixel is {@code 128 + (vanilla.c - java.c)} per
     *       channel. Mid-grey = match. Coloured tints isolate hue shifts that the luminance
     *       view collapses: a <em>pure</em> grey region in luminance + <em>green-tinted</em>
     *       region here = green-channel-only divergence (e.g. wrong tint colour, wrong texture
     *       region sampled).</li>
     *   <li><b>coverage-only</b>: silhouette-only view. Magenta = vanilla has a pixel here but
     *       java does not (we're missing geometry / culling too aggressively). Cyan = java has
     *       a pixel but vanilla does not (we're rendering extra geometry / face that vanilla
     *       hides). Both-opaque areas get a flat dark grey so any divergent region pops.</li>
     * </ul>
     * Background outside the silhouette in every lens is a faint 8-pixel grid checker so the
     * canvas extent is visible without dominating the view.
     *
     * <p>Aggregate stats (mean Δ, mean signed luma Δ, coverage tallies) are stamped at the
     * bottom of the panel for quick scanning across many entities.
     */
    private static @NotNull BufferedImage panelDiff(@NotNull BufferedImage vanilla, @NotNull BufferedImage java) {
        int cellW = Math.max(vanilla.getWidth(), java.getWidth());
        int cellH = Math.max(vanilla.getHeight(), java.getHeight());
        int gap = 8;
        int labelH = 18;
        int statsH = 96;
        int minPanelW = 540; // floor so the stats footer text isn't clipped on tiny entities
        int panelW = Math.max(minPanelW, 3 * cellW + 4 * gap);
        int panelH = 2 * (cellH + labelH) + 3 * gap + statsH;

        BufferedImage[] cells = {
            vanilla,
            java,
            absDiffCell(vanilla, java),
            signedLumaDiffCell(vanilla, java),
            signedRgbDiffCell(vanilla, java),
            coverageDiffCell(vanilla, java)
        };
        String[] labels = {
            "vanilla",
            "java",
            "|delta| x4",
            "luma+/- (red=vanilla>, blue=java>)",
            "RGB+/- (grey=match)",
            "coverage (M=vanilla, C=java)"
        };

        BufferedImage panel = new BufferedImage(panelW, panelH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = panel.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(28, 28, 32));
            g.fillRect(0, 0, panelW, panelH);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
            for (int i = 0; i < 6; i++) {
                int col = i % 3;
                int row = i / 3;
                int x = gap + col * (cellW + gap);
                int y = gap + row * (cellH + labelH + gap);
                g.setColor(new Color(220, 220, 230));
                g.drawString(labels[i], x, y + labelH - 5);
                g.setColor(new Color(80, 80, 90));
                g.drawRect(x - 1, y + labelH - 1, cellW + 1, cellH + 1);
                g.drawImage(cells[i], x, y + labelH, null);
            }
            // Stats footer
            DiffStats ds = aggregateDiffStats(vanilla, java);
            g.setColor(new Color(220, 220, 230));
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            int sy = 2 * (cellH + labelH) + 3 * gap + 14;
            g.drawString(String.format("mean |delta|     : %8.2f / 765   mean signed luma: %+8.2f   (+ = vanilla brighter)", ds.meanAbsDelta, ds.meanSignedLuma), gap, sy);
            g.drawString(String.format("coverage         : v=%-5d j=%-5d both=%-5d vanilla-only=%-4d java-only=%-4d",
                ds.vanillaPx, ds.javaPx, ds.bothPx, ds.vanillaOnlyPx, ds.javaOnlyPx), gap, sy + 14);
            // Per-quadrant signed luma + mean abs delta - localises which side of the silhouette
            // is over/under lit.
            g.drawString("quadrant signed-luma (silhouette intersection only):", gap, sy + 32);
            g.drawString(String.format("  TL=%+6.2f  TR=%+6.2f  BL=%+6.2f  BR=%+6.2f",
                ds.qTL, ds.qTR, ds.qBL, ds.qBR), gap, sy + 46);
            g.drawString(String.format("quadrant |delta|     TL=%6.1f  TR=%6.1f  BL=%6.1f  BR=%6.1f",
                ds.qTLAbs, ds.qTRAbs, ds.qBLAbs, ds.qBRAbs), gap, sy + 60);
        } finally {
            g.dispose();
        }
        return panel;
    }

    /**
     * Aggregate metrics for the panel stats footer. {@code qXX} fields hold the mean signed
     * luma delta within each silhouette-intersection quadrant; {@code qXXAbs} hold the mean
     * absolute ARGB delta in the same quadrant. Both are computed only over pixels where
     * vanilla and java BOTH have alpha &gt; 0 so coverage mismatches don't skew per-quadrant
     * brightness numbers (they're separately surfaced via the coverage tallies).
     */
    private record DiffStats(
        double meanAbsDelta, double meanSignedLuma,
        long vanillaPx, long javaPx, long bothPx, long vanillaOnlyPx, long javaOnlyPx,
        double qTL, double qTR, double qBL, double qBR,
        double qTLAbs, double qTRAbs, double qBLAbs, double qBRAbs
    ) {}

    private static @NotNull DiffStats aggregateDiffStats(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        long total = (long) w * h;
        long absSum = 0L;
        double lumaSum = 0;
        long lumaN = 0;
        long vAny = 0, jAny = 0, both = 0, vOnly = 0, jOnly = 0;

        // Find silhouette bbox for quadrant centring; fall back to canvas centre on empty input.
        int minX = w, maxX = 0, minY = h, maxY = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                if (ColorMath.alpha(pa) > 0 || ColorMath.alpha(pb) > 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        int cx = (minX < maxX) ? (minX + maxX) / 2 : w / 2;
        int cy = (minY < maxY) ? (minY + maxY) / 2 : h / 2;
        double[] qSum = new double[4];     // signed luma sum per quadrant
        double[] qAbsSum = new double[4];  // |delta| sum per quadrant
        long[] qN = new long[4];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int aa = ColorMath.alpha(pa), ab = ColorMath.alpha(pb);
                int absDelta = compositeDiff(pa, aa, pb, ab);
                absSum += absDelta;
                if (aa > 0) vAny++;
                if (ab > 0) jAny++;
                if (aa > 0 && ab > 0) {
                    both++;
                    float vL = 0.299f * ColorMath.red(pa) + 0.587f * ColorMath.green(pa) + 0.114f * ColorMath.blue(pa);
                    float jL = 0.299f * ColorMath.red(pb) + 0.587f * ColorMath.green(pb) + 0.114f * ColorMath.blue(pb);
                    float lumaDelta = vL - jL;
                    lumaSum += lumaDelta;
                    lumaN++;
                    int qIdx = (y < cy ? 0 : 2) + (x < cx ? 0 : 1);
                    qSum[qIdx] += lumaDelta;
                    qAbsSum[qIdx] += absDelta;
                    qN[qIdx]++;
                } else if (aa > 0) vOnly++;
                else if (ab > 0) jOnly++;
            }
        }
        double meanAbs = (double) absSum / (double) total;
        double meanLuma = lumaN == 0 ? 0 : lumaSum / lumaN;
        double[] q = new double[4];
        double[] qa = new double[4];
        for (int i = 0; i < 4; i++) {
            q[i] = qN[i] == 0 ? 0 : qSum[i] / qN[i];
            qa[i] = qN[i] == 0 ? 0 : qAbsSum[i] / qN[i];
        }
        return new DiffStats(
            meanAbs, meanLuma,
            vAny, jAny, both, vOnly, jOnly,
            q[0], q[1], q[2], q[3],
            qa[0], qa[1], qa[2], qa[3]
        );
    }

    /** Background grid for "no signal here" cells - keeps the canvas extent visible without dominating. */
    private static int gridBackground(int x, int y) {
        return ((x >> 3) + (y >> 3)) % 2 == 0 ? 0xFF202024 : 0xFF181820;
    }

    /** Cell variant of the existing absolute-diff lens with a checker background where both pixels are transparent. */
    private static @NotNull BufferedImage absDiffCell(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int aa = ColorMath.alpha(pa), ab = ColorMath.alpha(pb);
                int dr = Math.abs(ColorMath.red(pa) - ColorMath.red(pb));
                int dg = Math.abs(ColorMath.green(pa) - ColorMath.green(pb));
                int db = Math.abs(ColorMath.blue(pa) - ColorMath.blue(pb));
                int da = Math.abs(aa - ab);
                if (aa == 0 && ab == 0) {
                    out.setRGB(x, y, gridBackground(x, y));
                    continue;
                }
                if (da == 0 && dr == 0 && dg == 0 && db == 0) {
                    // Match within silhouette: faint dark grey so pixel-perfect agreement is
                    // visually distinct from "outside silhouette" but doesn't compete with
                    // the divergent regions for attention.
                    out.setRGB(x, y, 0xFF101010);
                    continue;
                }
                if ((aa == 0) ^ (ab == 0)) {
                    out.setRGB(x, y, 0xFFFF00FF);
                    continue;
                }
                int amp = 4;
                int rr = Math.min(255, dr * amp);
                int gg = Math.min(255, dg * amp);
                int bb = Math.min(255, db * amp);
                out.setRGB(x, y, (255 << 24) | (rr << 16) | (gg << 8) | bb);
            }
        }
        return out;
    }

    /**
     * Maps {@code vanilla_luma - java_luma} to a red↔blue divergent palette so lighting bugs
     * (which shift luminance uniformly across all 3 channels) read as solid-coloured regions.
     * Match → mid-grey {@code (128, 128, 128)}. Vanilla brighter → red shift. Java brighter →
     * blue shift. Magnitude amplified ×2 so a 30-luma delta saturates the palette - typical
     * of a face mis-lit by 0.1-0.2 lighting factor.
     */
    private static @NotNull BufferedImage signedLumaDiffCell(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int aa = ColorMath.alpha(pa), ab = ColorMath.alpha(pb);
                if (aa == 0 && ab == 0) {
                    out.setRGB(x, y, gridBackground(x, y));
                    continue;
                }
                if ((aa == 0) ^ (ab == 0)) {
                    out.setRGB(x, y, 0xFFFF00FF);
                    continue;
                }
                float vL = 0.299f * ColorMath.red(pa) + 0.587f * ColorMath.green(pa) + 0.114f * ColorMath.blue(pa);
                float jL = 0.299f * ColorMath.red(pb) + 0.587f * ColorMath.green(pb) + 0.114f * ColorMath.blue(pb);
                float delta = vL - jL;
                int mag = (int) Math.min(127, Math.abs(delta) * 2);
                int r, g, bl;
                if (delta >= 0) {
                    // vanilla brighter -> warm shift toward red
                    r = 128 + mag;
                    g = 128 - mag / 2;
                    bl = 128 - mag / 2;
                } else {
                    // java brighter -> cool shift toward blue
                    r = 128 - mag / 2;
                    g = 128 - mag / 2;
                    bl = 128 + mag;
                }
                out.setRGB(x, y, (255 << 24) | (r << 16) | (g << 8) | bl);
            }
        }
        return out;
    }

    /**
     * Per-channel signed delta centred at mid-grey. Each channel {@code c} encodes
     * {@code 128 + (vanilla.c - java.c) × 2}, clamped to {@code [0, 255]}. A pixel where all
     * channels match comes out grey; a pixel where only the green channel differs comes out
     * green-tinted; lighting differences (which scale all 3 channels) come out grey-shifted
     * (red or cyan tint when both red and other channels move together).
     */
    private static @NotNull BufferedImage signedRgbDiffCell(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int aa = ColorMath.alpha(pa), ab = ColorMath.alpha(pb);
                if (aa == 0 && ab == 0) {
                    out.setRGB(x, y, gridBackground(x, y));
                    continue;
                }
                if ((aa == 0) ^ (ab == 0)) {
                    out.setRGB(x, y, 0xFFFF00FF);
                    continue;
                }
                int dR = (ColorMath.red(pa) - ColorMath.red(pb)) * 2;
                int dG = (ColorMath.green(pa) - ColorMath.green(pb)) * 2;
                int dB = (ColorMath.blue(pa) - ColorMath.blue(pb)) * 2;
                int r = Math.max(0, Math.min(255, 128 + dR));
                int g = Math.max(0, Math.min(255, 128 + dG));
                int bl = Math.max(0, Math.min(255, 128 + dB));
                out.setRGB(x, y, (255 << 24) | (r << 16) | (g << 8) | bl);
            }
        }
        return out;
    }

    /**
     * Coverage-only lens: ignores colour entirely, encodes only "which side(s) have a pixel
     * here". Magenta = vanilla has alpha but java doesn't (missing geometry / over-culled).
     * Cyan = java has alpha but vanilla doesn't (extra geometry / under-culled). Both-opaque
     * silhouette interior is dark grey - the area where colour-diff lenses do their work.
     * Both-transparent canvas is the same checker as the other lenses for reference.
     */
    private static @NotNull BufferedImage coverageDiffCell(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int aa = ColorMath.alpha(pa), ab = ColorMath.alpha(pb);
                if (aa == 0 && ab == 0) {
                    out.setRGB(x, y, gridBackground(x, y));
                } else if (aa > 0 && ab > 0) {
                    out.setRGB(x, y, 0xFF303034);
                } else if (aa > 0) {
                    out.setRGB(x, y, 0xFFFF00FF);
                } else {
                    out.setRGB(x, y, 0xFF00FFFF);
                }
            }
        }
        return out;
    }

    /**
     * Builds a per-pixel diff image: where vanilla and java agree the pixel is fully
     * transparent; where they disagree the pixel encodes the absolute ARGB delta amplified
     * 4x so small numeric differences are visible. Coverage-only mismatches (one image has
     * alpha, the other does not) get a magenta tint to distinguish them from colour
     * differences at covered pixels. Mirrors the helper in {@link TestEntityParity} so both
     * harnesses produce comparable diff PNGs.
     */
    private static @NotNull BufferedImage diffImage(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int aa = ColorMath.alpha(pa);
                int ab = ColorMath.alpha(pb);
                // Compositing over white gives the same per-channel diff a viewer would
                // perceive looking at the PNGs over their default white viewer background -
                // AA edge spill no longer reads as a huge raw RGB diff at transparent pixels.
                int dr = Math.abs(compositeOverWhite(ColorMath.red(pa), aa) - compositeOverWhite(ColorMath.red(pb), ab));
                int dg = Math.abs(compositeOverWhite(ColorMath.green(pa), aa) - compositeOverWhite(ColorMath.green(pb), ab));
                int db = Math.abs(compositeOverWhite(ColorMath.blue(pa), aa) - compositeOverWhite(ColorMath.blue(pb), ab));
                if (dr == 0 && dg == 0 && db == 0) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                if ((aa == 0) ^ (ab == 0)) {
                    // Surface coverage-only mismatches as magenta so they still pop visually
                    // even when their composited RGB diff is tiny.
                    out.setRGB(x, y, 0xFFFF00FF);
                    continue;
                }
                int amp = 4;
                int rr = Math.min(255, dr * amp);
                int gg = Math.min(255, dg * amp);
                int bb = Math.min(255, db * amp);
                out.setRGB(x, y, (255 << 24) | (rr << 16) | (gg << 8) | bb);
            }
        }
        return out;
    }

    /** Per-entity row in the TSV report. */
    private record Row(@NotNull String entityId, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage, boolean achieved, int javaW, int javaH, int vanillaW, int vanillaH) {}

    /** Aggregate stats from {@link #compareImages}. */
    private record Stats(double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage) {}

}
