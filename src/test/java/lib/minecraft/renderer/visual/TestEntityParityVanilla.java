package lib.minecraft.renderer.visual;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
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
import java.awt.FontMetrics;
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
     * vanilla harness baseline. Starts empty - populate after inspecting per-entity
     * {@code diff.png}; entries here split the focus pool reporting.
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
                ImageData java;
                lib.minecraft.renderer.pipeline.util.RendererDebug.beginPerEntityBoundsDump(entityId);
                try {
                    java = javaRenderer.render(options);
                } finally {
                    lib.minecraft.renderer.pipeline.util.RendererDebug.endPerEntityBoundsDump(entityId);
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
                PixelBuffer vanillaPB = PixelBuffer.wrap(vanillaPadded);
                PixelBuffer javaPB = PixelBuffer.wrap(javaPadded);
                BufferedImage diffImg = vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage();
                ImageIO.write(diffImg, "PNG", new File(entityDir.toFile(), "diff.png"));
                BufferedImage panelImg = panelDiff(vanillaPadded, javaPadded, vanillaPB, javaPB);
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
     * Supersample factor applied to every panel dimension (cell size, gaps, fonts). Rendering
     * the whole panel at this multiple of natural pixel size produces text that's crisp at any
     * zoom level - the cell images get scaled up with nearest-neighbor (preserves the pixel-art
     * grid) while AWT renders fonts directly at the higher point size so glyph edges hit the
     * native pixel grid instead of being downsampled.
     */
    private static final int PANEL_SUPERSAMPLE = 2;

    /**
     * Natural-resolution font sizes (multiplied by {@link #PANEL_SUPERSAMPLE} at render time).
     */
    private static final int PANEL_LABEL_FONT_PT = 14;
    private static final int PANEL_STATS_FONT_PT = 13;

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
    private static @NotNull BufferedImage panelDiff(
        @NotNull BufferedImage vanilla, @NotNull BufferedImage java,
        @NotNull PixelBuffer vanillaPB, @NotNull PixelBuffer javaPB
    ) {
        int ss = PANEL_SUPERSAMPLE;
        int gap = 8 * ss;
        int labelPad = 8 * ss;        // horizontal pad between label text and cell edges
        int statsBlockPad = 12 * ss;  // vertical pad between paired stats lines
        Font labelFont = new Font(Font.MONOSPACED, Font.BOLD, PANEL_LABEL_FONT_PT * ss);
        Font statsFont = new Font(Font.MONOSPACED, Font.PLAIN, PANEL_STATS_FONT_PT * ss);

        BufferedImage[] cells = {
            vanilla,
            java,
            vanillaPB.diff(javaPB, DiffType.ABSOLUTE).toBufferedImage(),
            vanillaPB.diff(javaPB, DiffType.SIGNED_LUMA).toBufferedImage(),
            vanillaPB.diff(javaPB, DiffType.SIGNED_RGB).toBufferedImage(),
            vanillaPB.diff(javaPB, DiffType.COVERAGE).toBufferedImage()
        };
        String[] labels = {
            "vanilla",
            "java",
            "|delta| x4",
            "luma +/- (red=vanilla>, blue=java>)",
            "RGB +/- (grey=match)",
            "coverage (M=vanilla, C=java)"
        };
        String[] statsLines = formatStatsLines(aggregateDiffStats(vanilla, java));

        // Probe font metrics on a throwaway Graphics2D so cellW can be widened to the longest
        // label and panelW widened to the longest stats line - prevents text overflow into
        // adjacent cells or off the right panel edge on tiny entities.
        int maxLabelWidth;
        int labelLineHeight;
        int statsLineHeight;
        int maxStatsWidth;
        try (DisposingGraphics probe = new DisposingGraphics(1, 1)) {
            Graphics2D pg = probe.g;
            pg.setFont(labelFont);
            FontMetrics lfm = pg.getFontMetrics();
            int m = 0;
            for (String l : labels) m = Math.max(m, lfm.stringWidth(l));
            maxLabelWidth = m;
            labelLineHeight = lfm.getHeight();
            pg.setFont(statsFont);
            FontMetrics sfm = pg.getFontMetrics();
            statsLineHeight = sfm.getHeight();
            int sw = 0;
            for (String s : statsLines) sw = Math.max(sw, sfm.stringWidth(s));
            maxStatsWidth = sw;
        }

        int naturalCellW = Math.max(vanilla.getWidth(), java.getWidth()) * ss;
        int naturalCellH = Math.max(vanilla.getHeight(), java.getHeight()) * ss;
        // Widen each cell column so the longest label fits with padding on both sides; this
        // is the single fix that stops labels from spilling into the next column on entities
        // whose native render is narrower than the label text.
        int cellW = Math.max(naturalCellW, maxLabelWidth + 2 * labelPad);
        int cellH = naturalCellH;
        int labelH = labelLineHeight + 2 * (3 * ss);    // glyph height + breathing room
        // Stats footer: one line per statsLines entry plus a half-line gap before the quadrant
        // block (statsLines[2] onward). +statsBlockPad on top to separate from the cell rows.
        int statsH = statsBlockPad + statsLineHeight * statsLines.length + statsLineHeight / 2;

        int cellRowW = 3 * cellW + 4 * gap;
        int statsRowW = maxStatsWidth + 2 * gap;
        int panelW = Math.max(cellRowW, statsRowW);
        int panelH = 2 * (cellH + labelH) + 3 * gap + statsH;

        BufferedImage gridBackdrop = buildGridBackdrop(cellW, cellH);

        BufferedImage panel = new BufferedImage(panelW, panelH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = panel.createGraphics();
        try {
            // GASP hinting + fractional metrics give the crispest small-glyph rendering on
            // Windows + Linux; ON-without-GASP can read fuzzy at small point sizes.
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(new Color(28, 28, 32));
            g.fillRect(0, 0, panelW, panelH);
            g.setFont(labelFont);
            FontMetrics lfm = g.getFontMetrics();
            int labelBaselineOffset = (labelH + lfm.getAscent() - lfm.getDescent()) / 2;
            for (int i = 0; i < 6; i++) {
                int col = i % 3;
                int row = i / 3;
                int x = gap + col * (cellW + gap);
                int y = gap + row * (cellH + labelH + gap);
                // Label band: own row above each cell, padded so glyph cells never reach the
                // cell image footprint. Horizontal pad keeps the leftmost glyph inset from
                // the cell's left edge.
                g.setColor(new Color(220, 220, 230));
                g.drawString(labels[i], x + labelPad, y + labelBaselineOffset);
                int cellY = y + labelH;
                g.setColor(new Color(80, 80, 90));
                g.drawRect(x - 1, cellY - 1, cellW + 1, cellH + 1);
                if (i >= 2) g.drawImage(gridBackdrop, x, cellY, null);
                // Cell image gets nearest-neighbor-upscaled to ss times its natural size so the
                // entity's pixel-art grid stays pixel-sharp at the supersampled resolution.
                // When cellW exceeds the scaled image width (e.g. a tiny entity whose label is
                // wider than its render), the image is centered within the cell footprint.
                int scaledW = cells[i].getWidth() * ss;
                int scaledH = cells[i].getHeight() * ss;
                int imgX = x + (cellW - scaledW) / 2;
                int imgY = cellY + (cellH - scaledH) / 2;
                Object prevInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(cells[i], imgX, imgY, scaledW, scaledH, null);
                if (prevInterp != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, prevInterp);
                else g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            }
            // Stats footer
            g.setColor(new Color(220, 220, 230));
            g.setFont(statsFont);
            int sy = 2 * (cellH + labelH) + 3 * gap + statsBlockPad + statsLineHeight;
            for (int i = 0; i < statsLines.length; i++) {
                // Inject a half-line gap before the quadrant block (line index 2) so it reads
                // as a distinct sub-section under the two-line "totals" block.
                int extraGap = i >= 2 ? statsLineHeight / 2 : 0;
                g.drawString(statsLines[i], gap, sy + i * statsLineHeight + extraGap);
            }
        } finally {
            g.dispose();
        }
        return panel;
    }

    /**
     * Formats the panel's stats footer into individual lines so layout code can probe the
     * maximum line width via {@link FontMetrics#stringWidth} and widen the panel accordingly.
     */
    private static @NotNull String[] formatStatsLines(@NotNull DiffStats ds) {
        return new String[]{
            String.format("mean |delta|     : %8.2f / 765   mean signed luma: %+8.2f   (+ = vanilla brighter)", ds.meanAbsDelta, ds.meanSignedLuma),
            String.format("coverage         : v=%-5d j=%-5d both=%-5d vanilla-only=%-4d java-only=%-4d",
                ds.vanillaPx, ds.javaPx, ds.bothPx, ds.vanillaOnlyPx, ds.javaOnlyPx),
            "quadrant signed-luma (silhouette intersection only):",
            String.format("  TL=%+6.2f  TR=%+6.2f  BL=%+6.2f  BR=%+6.2f",
                ds.qTL, ds.qTR, ds.qBL, ds.qBR),
            String.format("quadrant |delta|     TL=%6.1f  TR=%6.1f  BL=%6.1f  BR=%6.1f",
                ds.qTLAbs, ds.qTRAbs, ds.qBLAbs, ds.qBRAbs)
        };
    }

    /**
     * AutoCloseable wrapper around a throwaway {@link Graphics2D} used purely for probing
     * {@link FontMetrics}. The probe buffer is 1x1 so the allocation cost is negligible.
     */
    private static final class DisposingGraphics implements AutoCloseable {
        final BufferedImage probe;
        final Graphics2D g;
        DisposingGraphics(int w, int h) {
            this.probe = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            this.g = probe.createGraphics();
        }
        @Override public void close() { g.dispose(); }
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

    /**
     * Builds the 8×8 checker backdrop that sits behind each diff cell in {@link #panelDiff}.
     * The {@link PixelBuffer#diff} API writes {@link ColorMath#TRANSPARENT} for out-of-canvas
     * (and, in some modes, in-silhouette match) pixels; compositing each diff over this
     * checker keeps the canvas extent visible without dominating the view.
     */
    private static @NotNull BufferedImage buildGridBackdrop(int w, int h) {
        BufferedImage backdrop = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                backdrop.setRGB(x, y, ((x >> 3) + (y >> 3)) % 2 == 0 ? 0xFF202024 : 0xFF181820);
            }
        }
        return backdrop;
    }

    /** Per-entity row in the TSV report. */
    private record Row(@NotNull String entityId, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage, boolean achieved, int javaW, int javaH, int vanillaW, int vanillaH) {}

    /** Aggregate stats from {@link #compareImages}. */
    private record Stats(double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage) {}

}
