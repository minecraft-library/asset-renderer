package lib.minecraft.renderer.parity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.Background;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Shared parity-comparison metric and visualisation, used by every renderer-vs-vanilla experiment so
 * the delta numbers stay comparable across tools. It is separate from {@code TestEntityParityVanilla}
 * so a one-off raster-replay or GPU-path experiment can diff against the exact same metric the
 * headline parity sweep reports, rather than against a second implementation of it.
 *
 * <p>{@link #compareImages} is the canonical metric and carries its definition and rationale.
 * {@link #panelDiff} packs the six diagnostic lenses into one inspectable image, and
 * {@link #padToCanvas} reconciles two differently-sized renders onto a common transparent canvas
 * without resampling.
 */
@UtilityClass
public final class ParityMetrics {

    /**
     * Computes per-pixel ARGB statistics over the two images' overlapping
     * {@code (min(w), min(h))} region.
     *
     * <p>Callers pad both sides onto a common canvas first (see {@link #padToCanvas}), so in
     * practice this is the full union canvas.
     *
     * <p>Mean delta uses <b>over-white compositing</b>: each pixel is first composited onto a
     * fully-opaque white background ({@code C_out = C_src * A_src/255 + 255 * (1 - A_src/255)}),
     * then the absolute RGB difference is taken. This matches what a viewer perceives looking at
     * the rendered PNG over the standard image-viewer white background: a transparent pixel and a
     * 50%-transparent pixel with the same underlying RGB show only their compositing-blend
     * difference, not the raw alpha gap. Without compositing, AA edge spill
     * ({@code vanilla [0,0,0,0]} vs {@code java [246,246,246,127]}) counts as an 865+ diff per
     * pixel; with compositing the same pixels contribute around {@code 14}, the slight tint a
     * half-white pixel adds over white.
     *
     * <p>A pixel that is pure transparent in both images contributes 0; a fully different opaque
     * pixel contributes up to {@code 3 * 255 = 765}. Coverage is the fraction of pixels with
     * non-zero alpha.
     *
     * @param a the reference (vanilla) image
     * @param b the candidate (java) image
     * @return mean delta, differing-pixel count, and per-side coverage
     */
    public static @NotNull Stats compareImages(@NotNull BufferedImage a, @NotNull BufferedImage b) {
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
     * Returns the absolute RGB difference of two pixels after each has been composited onto a
     * fully-opaque white background.
     *
     * <p>See {@link #compareImages} for why this is the perceptually-accurate metric over a raw
     * {@code |Rv - Rj| + |Gv - Gj| + |Bv - Bj|} that ignores alpha.
     *
     * @param pa the reference pixel, packed ARGB
     * @param aa the reference pixel's alpha
     * @param pb the candidate pixel, packed ARGB
     * @param ab the candidate pixel's alpha
     * @return the summed per-channel absolute difference, in {@code [0, 765]}
     */
    private static int compositeDiff(int pa, int aa, int pb, int ab) {
        int rOver = compositeOverWhite(ColorMath.red(pa), aa) - compositeOverWhite(ColorMath.red(pb), ab);
        int gOver = compositeOverWhite(ColorMath.green(pa), aa) - compositeOverWhite(ColorMath.green(pb), ab);
        int bOver = compositeOverWhite(ColorMath.blue(pa), aa) - compositeOverWhite(ColorMath.blue(pb), ab);
        return Math.abs(rOver) + Math.abs(gOver) + Math.abs(bOver);
    }

    /**
     * Composites one channel over opaque white, as {@code C_src * A/255 + 255 * (1 - A/255)}
     * rounded to integer.
     *
     * @param channel the source channel value
     * @param alpha the source pixel's alpha
     * @return the composited channel value, in {@code [0, 255]}
     */
    private static int compositeOverWhite(int channel, int alpha) {
        return (channel * alpha + 255 * (255 - alpha) + 127) / 255;
    }

    /**
     * Centers {@code src} on a {@code (canvasW, canvasH)} transparent canvas so two differently-
     * sized renders can be diffed pixel-for-pixel without resampling. Used when the Java pipeline
     * and the vanilla harness disagree on canvas size for an entity (variant family-fit gap, etc).
     * No-op fast path when the source already matches the canvas.
     *
     * @param src the source render
     * @param canvasW target canvas width
     * @param canvasH target canvas height
     * @return the centered render, or {@code src} unchanged when it already fits
     */
    public static @NotNull BufferedImage padToCanvas(@NotNull BufferedImage src, int canvasW, int canvasH) {
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
     * Alpha-tight-crops {@code src} to its opaque silhouette, scales it (nearest-neighbour,
     * aspect-preserving) to {@code fill} of a {@code box x box} canvas, and centres it - so two
     * differently-framed / differently-proportioned renders line up by silhouette for a
     * lighting-focused diff.
     * <p>
     * The resampling sibling of {@link #padToCanvas}, and the difference is what each is for:
     * padding reconciles two canvas sizes without touching a pixel, where this one deliberately
     * rescales both sides so the comparison is about shading rather than about fit. A sweep that
     * uses it is reporting a lighting delta and cannot report a byte gate.
     *
     * @param src the source render
     * @param box the square canvas edge
     * @param fill the fraction of the canvas the silhouette fills
     * @return the aligned render
     */
    public static @NotNull BufferedImage alignToBox(@NotNull BufferedImage src, int box, float fill) {
        int minX = src.getWidth(), minY = src.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < src.getHeight(); y++)
            for (int x = 0; x < src.getWidth(); x++)
                if (ColorMath.alpha(src.getRGB(x, y)) > 8) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
        if (maxX < minX) return new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB);
        BufferedImage cropped = src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);

        float target = box * fill;
        float scale = Math.min(target / cropped.getWidth(), target / cropped.getHeight());
        int sw = Math.max(1, Math.round(cropped.getWidth() * scale));
        int sh = Math.max(1, Math.round(cropped.getHeight() * scale));

        BufferedImage out = new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(cropped, (box - sw) / 2, (box - sh) / 2, sw, sh, null);
        } finally {
            g.dispose();
        }
        return out;
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
     * Builds a six-panel debug visualisation packing every diff lens a mismatch is diagnosed
     * through into one inspectable image. Layout (3 columns × 2 rows, with labels):
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
     *       java does not (missing geometry, or culling too aggressively). Cyan = java has a
     *       pixel but vanilla does not (extra geometry, or a face vanilla hides). Both-opaque
     *       areas get a flat dark grey so any divergent region pops.</li>
     * </ul>
     * Background outside the silhouette in every lens is a faint 8-pixel grid checker so the
     * canvas extent is visible without dominating the view.
     *
     * <p>Aggregate stats (mean Δ, mean signed luma Δ, coverage tallies) are stamped at the
     * bottom of the panel for quick scanning across many entities.
     *
     * @param vanilla the reference render, padded to the common canvas
     * @param java the candidate render, padded to the common canvas
     * @param vanillaPB pixel-buffer view of {@code vanilla} for the diff lenses
     * @param javaPB pixel-buffer view of {@code java} for the diff lenses
     * @return the assembled six-panel image
     */
    public static @NotNull BufferedImage panelDiff(
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
     *
     * @param ds the aggregate metrics to stamp
     * @return one footer line per entry, in print order
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
     * Aggregate metrics for the panel stats footer.
     *
     * <p>Every quadrant figure is computed only over pixels where vanilla and java both have
     * alpha &gt; 0, so a coverage mismatch does not skew a quadrant's brightness number; the
     * mismatch is surfaced separately by the coverage tallies.
     *
     * @param meanAbsDelta mean over-white-composited absolute RGB delta over the whole canvas
     * @param meanSignedLuma mean signed luma delta over the silhouette intersection, positive
     *     where vanilla is brighter
     * @param vanillaPx count of pixels with non-zero alpha in the reference
     * @param javaPx count of pixels with non-zero alpha in the candidate
     * @param bothPx count of pixels with non-zero alpha in both
     * @param vanillaOnlyPx count of pixels the reference covers and the candidate does not
     * @param javaOnlyPx count of pixels the candidate covers and the reference does not
     * @param qTL mean signed luma delta in the top-left quadrant of the silhouette intersection
     * @param qTR mean signed luma delta in the top-right quadrant
     * @param qBL mean signed luma delta in the bottom-left quadrant
     * @param qBR mean signed luma delta in the bottom-right quadrant
     * @param qTLAbs mean absolute ARGB delta in the top-left quadrant
     * @param qTRAbs mean absolute ARGB delta in the top-right quadrant
     * @param qBLAbs mean absolute ARGB delta in the bottom-left quadrant
     * @param qBRAbs mean absolute ARGB delta in the bottom-right quadrant
     */
    private record DiffStats(
        double meanAbsDelta, double meanSignedLuma,
        long vanillaPx, long javaPx, long bothPx, long vanillaOnlyPx, long javaOnlyPx,
        double qTL, double qTR, double qBL, double qBR,
        double qTLAbs, double qTRAbs, double qBLAbs, double qBRAbs
    ) {}

    /**
     * Aggregates the panel footer's metrics in one pass over the overlapping region.
     *
     * <p>Quadrants are centred on the union silhouette's bounding box rather than on the canvas,
     * falling back to the canvas centre when neither side covers a pixel.
     *
     * @param a the reference (vanilla) image
     * @param b the candidate (java) image
     * @return the footer metrics
     */
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
     *
     * <p>The {@link PixelBuffer#diff} API writes {@link ColorMath#TRANSPARENT} for out-of-canvas
     * (and, in some modes, in-silhouette match) pixels; compositing each diff over this checker
     * keeps the canvas extent visible without dominating the view.
     *
     * @param w the cell width in pixels
     * @param h the cell height in pixels
     * @return the checker raster, sized to one cell
     */
    private static @NotNull BufferedImage buildGridBackdrop(int w, int h) {
        PixelBuffer backdrop = PixelBuffer.create(w, h);
        Background.checkerboard().fill(backdrop);
        return backdrop.toBufferedImage();
    }

    /**
     * Aggregate per-pixel comparison statistics.
     *
     * @param meanDelta mean over-white-composited absolute RGB delta over the union canvas
     * @param differingPixels count of pixels with non-zero delta
     * @param javaCoverage fraction of pixels with non-zero alpha in the candidate image
     * @param vanillaCoverage fraction of pixels with non-zero alpha in the reference image
     */
    public record Stats(double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage) {}

}
