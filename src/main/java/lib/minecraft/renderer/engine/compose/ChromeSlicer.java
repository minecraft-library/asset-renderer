package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.compose.ChromeDecomposition.Anchor;
import lib.minecraft.renderer.engine.compose.ChromeDecomposition.Band;
import lib.minecraft.renderer.engine.compose.ChromeDecomposition.Border;
import lib.minecraft.renderer.engine.compose.ChromeDecomposition.Edge;
import lib.minecraft.renderer.engine.compose.ChromeDecomposition.Feature;
import lib.minecraft.renderer.engine.compose.ChromeDecomposition.Interior;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Takes an authored chrome image apart into repeatable bands and the unique features that break
 * them, and puts it back together at any size.
 * <p>
 * The border of a drawn window is rarely uniform. Corners are unique, but so is a centred ornament,
 * a stud that repeats at a period dividing nothing, or a motif straddling a corner. So a band is
 * modelled as a period plus a list of features, each anchored to whichever end it sits nearer - or
 * to the middle, when it is centred and mirrors itself. Growing the band restates the period and
 * puts the features back where their anchors say.
 * <p>
 * Nothing here is vanilla-specific. It works on the container backgrounds, which declare no border,
 * and on pack tooltip art, which does.
 *
 * @see ChromeDecomposition
 */
public final class ChromeSlicer {

    /**
     * Per-feature cost in band slices, charged when weighing a period against the features it fails
     * to explain. It buys a plateau rather than a point - anything from two to eight decomposes the
     * measured corpus identically.
     */
    private static final int FEATURE_HEADER_SLICES = 4;

    /**
     * How many conforming slices may sit between two deviating runs before they stop being one
     * feature. Without it a motif with a hole in it is reported as two.
     */
    private static final int CONFORM_GAP = 8;

    /**
     * How near the band's middle a self-mirrored run has to sit, in slices, to be called centred
     * rather than pinned to an end.
     */
    private static final double CENTRE_TOL = 2.0;

    /**
     * How many times a run must restate its period before the repeat counts as evidence of one
     * rather than as a restatement of the band.
     */
    private static final int MIN_REPEATS = 3;

    private ChromeSlicer() {}

    /**
     * Decomposes an image.
     * <p>
     * A declared border, where the art has one, <b>is</b> the four band depths: the client's own
     * nine-slice blit reads the declaration and never inspects a pixel, so deriving a different
     * depth would render the sprite at cell boundaries the client does not use. Period, features
     * and the interior class are always derived, inside whatever depths are in force.
     *
     * @param source the image to take apart
     * @param declared the border its sidecar declares, empty when it has none or when the client's
     *                 own validation would refuse the one it has
     * @param stretchInner whether an unmodelled run resamples when it grows rather than repeating
     * @return the decomposition
     */
    public static @NotNull ChromeDecomposition decompose(
        @NotNull PixelBuffer source,
        @NotNull Optional<Border> declared,
        boolean stretchInner
    ) {
        int w = source.width();
        int h = source.height();

        int left, top, right, bottom, periodX, periodY;
        Optional<Border> hint = declared.filter(border -> border.loadableAt(w, h));

        if (hint.isPresent()) {
            Border border = hint.get();
            left = border.left();
            top = border.top();
            right = border.right();
            bottom = border.bottom();
            periodX = minPeriod(source, left, top, w - right, h - bottom, true);
            periodY = minPeriod(source, left, top, w - right, h - bottom, false);
        } else {
            int[] derived = bandDepths(source);
            left = derived[0];
            top = derived[1];
            right = derived[2];
            bottom = derived[3];
            periodX = derived[4];
            periodY = derived[5];
        }

        Map<Edge, Band> bands = new EnumMap<>(Edge.class);
        for (Edge edge : Edge.values())
            bands.put(edge, bandOf(source, edge, left, top, right, bottom));

        Interior interior = classifyInterior(source, left, top, w - right, h - bottom, periodX, periodY);
        if (interior == Interior.SOLID || interior == Interior.STRETCH) {
            periodX = Math.max(1, periodX);
            periodY = Math.max(1, periodY);
        }

        return new ChromeDecomposition(w, h, left, top, right, bottom,
            Map.copyOf(bands), interior, periodX, periodY, stretchInner);
    }

    /**
     * Rebuilds the art at a new size from its decomposition.
     * <p>
     * A target narrower than the two opposing bands is not undefined behaviour but a disagreement:
     * the client clamps each band to the destination and lets the corner blits overlap. That is what
     * happens here, and a clamped band keeps its <b>outermost</b> pixels, which are the half that
     * touches the frame.
     *
     * @param decomposition the decomposition of {@code source}
     * @param source the image it was taken from
     * @param width the target width
     * @param height the target height
     * @return the rebuilt image
     */
    public static @NotNull PixelBuffer assemble(
        @NotNull ChromeDecomposition decomposition,
        @NotNull PixelBuffer source,
        int width, int height
    ) {
        PixelBuffer out = PixelBuffer.create(width, height);

        int l = decomposition.left(), t = decomposition.top();
        int r = decomposition.right(), b = decomposition.bottom();
        int w = source.width(), h = source.height();

        int l2 = Math.min(l, width), r2 = Math.min(r, width);
        int t2 = Math.min(t, height), b2 = Math.min(b, height);

        int iw = width - l2 - r2;
        int ih = height - t2 - b2;
        int srcIw = w - l - r;
        int srcIh = h - t - b;

        if (iw > 0 && ih > 0 && srcIw > 0 && srcIh > 0)
            paintInterior(out, source, decomposition, l, t, l2, t2, iw, ih, srcIw, srcIh);

        for (Edge edge : Edge.values())
            paintBand(out, source, decomposition, edge, l, t, r, b, l2, t2, r2, b2, iw, ih);

        for (int y = 0; y < t2; y++) {
            for (int x = 0; x < l2; x++) out.setPixel(x, y, source.getPixel(x, y));
            for (int x = 0; x < r2; x++) out.setPixel(width - r2 + x, y, source.getPixel(w - r2 + x, y));
        }
        for (int y = 0; y < b2; y++) {
            for (int x = 0; x < l2; x++) out.setPixel(x, height - b2 + y, source.getPixel(x, h - b2 + y));
            for (int x = 0; x < r2; x++) out.setPixel(width - r2 + x, height - b2 + y, source.getPixel(w - r2 + x, h - b2 + y));
        }

        return out;
    }

    // ------------------------------------------------------------------ bands

    private static @NotNull Band bandOf(@NotNull PixelBuffer a, @NotNull Edge edge, int l, int t, int r, int b) {
        int depth = switch (edge) {
            case TOP -> t;
            case BOTTOM -> b;
            case LEFT -> l;
            case RIGHT -> r;
        };
        int[][] strip = bandStrip(a, edge, l, t, r, b);
        if (depth <= 0 || strip.length == 0 || strip[0].length == 0)
            return new Band(edge, 0, depth, 0, new int[0], Concurrent.newList());

        int n = strip.length;
        Model model = bestPeriod(strip);
        int[] tile = model.period == 0 ? new int[0] : tileIndices(strip, model);

        ConcurrentList<Feature> features = Concurrent.newList();
        for (int[] run : model.runs) {
            int[] anchored = anchorFor(run[0], run[1], n, model.runs);
            features.add(new Feature(edge, run[0], run[1] - run[0], Anchor.values()[anchored[0]], anchored[1]));
        }

        return new Band(edge, n, depth, model.period, tile, features);
    }

    private static int[][] bandStrip(@NotNull PixelBuffer a, @NotNull Edge edge, int l, int t, int r, int b) {
        int w = a.width(), h = a.height();
        return switch (edge) {
            case TOP -> strip(a, l, 0, w - r, t, true);
            case BOTTOM -> strip(a, l, h - b, w - r, h, true);
            case LEFT -> strip(a, 0, t, l, h - b, false);
            case RIGHT -> strip(a, w - r, t, w, h - b, false);
        };
    }

    /**
     * Cuts a rectangle into slices - columns when {@code across}, rows otherwise.
     */
    private static int[][] strip(@NotNull PixelBuffer a, int x0, int y0, int x1, int y1, boolean across) {
        int cols = Math.max(0, x1 - x0);
        int rows = Math.max(0, y1 - y0);
        if (cols == 0 || rows == 0) return new int[0][];

        int count = across ? cols : rows;
        int depth = across ? rows : cols;
        int[][] out = new int[count][depth];

        for (int i = 0; i < count; i++)
            for (int j = 0; j < depth; j++)
                out[i][j] = across ? a.getPixel(x0 + i, y0 + j) : a.getPixel(x0 + j, y0 + i);

        return out;
    }

    // ------------------------------------------------------------------ period search

    /** A band's period, the per-residue slice it repeats, and the runs that break it. */
    private record Model(int period, int[][] tile, @NotNull List<int[]> runs) {}

    private static @NotNull Model bestPeriod(int[][] strip) {
        int n = strip.length;
        int depth = strip[0].length;
        if (n == 0) return new Model(0, new int[0][], List.of());

        // An exact tiling always beats a feature explanation, provided it repeats often enough to be
        // evidence of a period rather than a restatement of the band.
        for (int p = 1; p <= Math.max(1, n / 3); p++) {
            int[][] tile = modalTile(strip, p);
            if (conformsEverywhere(strip, tile, p))
                return new Model(p, tile, List.of());
        }

        // No exact tiling, so weigh each candidate by what it costs to describe. Three candidates per
        // period: the per-residue modal slice, and the runs anchored at each end. The modal slice is
        // not the background when a feature covers more than half the band, and an end-anchored tile
        // is what recovers it there.
        long best = (long) n * depth * 4;
        int bestPeriod = 0;
        int[][] bestTile = new int[0][];
        List<int[]> bestRuns = List.of();

        for (int p = 1; p <= Math.max(1, n / 2); p++) {
            List<int[][]> candidates = List.of(modalTile(strip, p), headTile(strip, p), tailTile(strip, p));
            for (int[][] tile : candidates) {
                boolean[] mask = new boolean[n];
                for (int i = 0; i < n; i++) mask[i] = Arrays.equals(strip[i], tile[i % p]);
                List<int[]> runs = group(mask);
                long cost = (long) p * depth * 4;
                for (int[] run : runs) cost += (long) (run[1] - run[0] + FEATURE_HEADER_SLICES) * depth * 4;
                if (cost < best) {
                    best = cost;
                    bestPeriod = p;
                    bestTile = tile;
                    bestRuns = runs;
                }
            }
        }

        return new Model(bestPeriod, bestTile, bestRuns);
    }

    private static int[][] modalTile(int[][] strip, int p) {
        int n = strip.length;
        int[][] tile = new int[p][];
        for (int residue = 0; residue < p; residue++) {
            Map<SliceKey, Integer> counts = new HashMap<>();
            for (int i = residue; i < n; i += p)
                counts.merge(new SliceKey(strip[i]), 1, Integer::sum);
            SliceKey top = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(new SliceKey(strip[residue]));
            tile[residue] = top.pixels();
        }
        return tile;
    }

    private static int[][] headTile(int[][] strip, int p) {
        int[][] tile = new int[p][];
        for (int i = 0; i < p; i++) tile[i] = strip[i % strip.length];
        return tile;
    }

    private static int[][] tailTile(int[][] strip, int p) {
        int n = strip.length;
        int[][] tile = new int[p][];
        for (int r = 0; r < p; r++) tile[r] = strip[Math.floorMod(n - p + r, n)];
        return tile;
    }

    private static boolean conformsEverywhere(int[][] strip, int[][] tile, int p) {
        for (int i = 0; i < strip.length; i++)
            if (!Arrays.equals(strip[i], tile[i % p])) return false;

        return true;
    }

    /** Maximal runs of non-conforming slices, merging runs separated by fewer than the gap. */
    private static @NotNull List<int[]> group(boolean[] mask) {
        int n = mask.length;
        List<int[]> runs = new ArrayList<>();
        int i = 0;

        while (i < n) {
            if (mask[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j < n) {
                if (!mask[j]) {
                    j++;
                    continue;
                }
                int k = j;
                while (k < n && mask[k]) k++;
                if (k - j < CONFORM_GAP && k < n) j = k;
                else break;
            }
            runs.add(new int[] { i, j });
            i = j;
        }

        return runs;
    }

    /** One source slice index per residue that both conforms and sits outside every feature. */
    private static int[] tileIndices(int[][] strip, @NotNull Model model) {
        int n = strip.length;
        boolean[] inside = new boolean[n];
        for (int[] run : model.runs)
            for (int i = run[0]; i < run[1]; i++) inside[i] = true;

        int[] out = new int[model.period];
        for (int residue = 0; residue < model.period; residue++) {
            out[residue] = residue;
            for (int i = residue; i < n; i += model.period) {
                if (Arrays.equals(strip[i], model.tile[residue]) && !inside[i]) {
                    out[residue] = i;
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ anchors

    private static int[] anchorFor(int start, int end, int n, @NotNull List<int[]> runs) {
        int width = end - start;
        int[] twin = mirrorTwin(runs, n, start, end);
        if (twin != null)
            return start < twin[0] ? new int[] { Anchor.START.ordinal(), start }
                                   : new int[] { Anchor.END.ordinal(), n - end };

        double centre = (start + end) / 2.0;
        boolean selfMirrored = Math.abs(start - (n - end)) <= CENTRE_TOL;
        if (selfMirrored && Math.abs(centre - n / 2.0) <= CENTRE_TOL)
            return new int[] { Anchor.CENTER.ordinal(), start - (n - width) / 2 };

        int fromStart = start;
        int fromEnd = n - end;
        double fromCentre = Math.abs(centre - n / 2.0);
        if (selfMirrored && fromCentre <= Math.min(fromStart, fromEnd))
            return new int[] { Anchor.CENTER.ordinal(), start - (n - width) / 2 };

        return fromStart <= fromEnd ? new int[] { Anchor.START.ordinal(), fromStart }
                                    : new int[] { Anchor.END.ordinal(), fromEnd };
    }

    private static int[] mirrorTwin(@NotNull List<int[]> runs, int n, int start, int end) {
        for (int[] run : runs) {
            if (run[0] == start && run[1] == end) continue;
            if (Math.abs(run[0] - (n - end)) <= 1 && Math.abs(run[1] - (n - start)) <= 1) return run;
        }
        return null;
    }

    // ------------------------------------------------------------------ band depths

    private static int[] bandDepths(@NotNull PixelBuffer a) {
        int w = a.width(), h = a.height();
        List<int[]> candidates = new ArrayList<>();

        for (int[] runX : periodicRuns(a, true, 0, h)) {
            int l = runX[0] > 0 ? runX[2] : 0;
            int r = runX[0] > 0 ? w - runX[3] : 0;
            for (int[] runY : periodicRuns(a, false, l, w - r)) {
                int t = runY[0] > 0 ? runY[2] : 0;
                int b = runY[0] > 0 ? h - runY[3] : 0;
                for (int[] runX2 : periodicRuns(a, true, t, h - b)) {
                    int l2 = runX2[0] > 0 ? runX2[2] : 0;
                    int r2 = runX2[0] > 0 ? w - runX2[3] : 0;
                    candidates.add(new int[] { l2, t, r2, b, Math.max(runX2[1], 1), Math.max(runY[1], 1) });
                }
            }
        }
        for (int[] runY : periodicRuns(a, false, 0, w)) {
            int t = runY[0] > 0 ? runY[2] : 0;
            int b = runY[0] > 0 ? h - runY[3] : 0;
            for (int[] runX : periodicRuns(a, true, t, h - b)) {
                int l = runX[0] > 0 ? runX[2] : 0;
                int r = runX[0] > 0 ? w - runX[3] : 0;
                for (int[] runY2 : periodicRuns(a, false, l, w - r)) {
                    int t2 = runY2[0] > 0 ? runY2[2] : 0;
                    int b2 = runY2[0] > 0 ? h - runY2[3] : 0;
                    candidates.add(new int[] { l, t2, r, b2, Math.max(runX[1], 1), Math.max(runY2[1], 1) });
                }
            }
        }

        if (candidates.isEmpty()) return new int[] { 0, 0, 0, 0, 1, 1 };

        int[] best = candidates.getFirst();
        long bestScore = Long.MIN_VALUE;
        for (int[] c : candidates) {
            long score = depthScore(c, w, h);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private static long depthScore(int[] c, int w, int h) {
        long iw = w - c[0] - c[2];
        long ih = h - c[1] - c[3];
        if (iw <= 0 || ih <= 0) return -1;
        boolean sane = Math.max(c[0], c[2]) <= w / 2 && Math.max(c[1], c[3]) <= h / 2;
        return sane ? iw * ih : 0;
    }

    /**
     * The near-longest maximal runs of slices that repeat exactly at some lag, over the given window.
     * The interior of an authored chrome is exactly such a run, so the bands are what sits outside
     * it and one measurement fixes both depths on the axis together with the interior's period along
     * it. Ties are real - the dispenser carries two row lattices of one height - so up to four
     * near-equal runs come back and the caller crosses them all.
     *
     * @return rows of {@code (length, period, start, end)}, longest first
     */
    private static @NotNull List<int[]> periodicRuns(@NotNull PixelBuffer a, boolean across, int otherLo, int otherHi) {
        int w = a.width(), h = a.height();
        int[][] strip = across ? strip(a, 0, otherLo, w, otherHi, true)
                               : strip(a, otherLo, 0, otherHi, h, false);
        int n = strip.length;
        if (n == 0 || strip[0].length == 0) return List.of(new int[] { 0, 0, 0, n });

        List<int[]> found = new ArrayList<>();
        for (int p = 1; p <= Math.max(1, n / MIN_REPEATS); p++) {
            int i = p;
            while (i < n) {
                if (!Arrays.equals(strip[i], strip[i - p])) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < n && Arrays.equals(strip[j], strip[j - p])) j++;
                int length = j - (i - p);
                if (length >= p * MIN_REPEATS) found.add(new int[] { length, p, i - p, j });
                i = j + 1;
            }
        }
        if (found.isEmpty()) return List.of(new int[] { 0, 0, 0, n });

        found.sort((x, y) -> x[0] != y[0] ? Integer.compare(y[0], x[0]) : Integer.compare(x[1], y[1]));

        List<int[]> top = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        int longest = found.getFirst()[0];
        for (int[] run : found) {
            if (run[0] < longest * 0.75) break;
            String span = run[2] + ":" + run[3];
            if (seen.contains(span)) continue;
            seen.add(span);
            top.add(run);
            if (top.size() >= 4) break;
        }
        return top;
    }

    // ------------------------------------------------------------------ interior

    private static @NotNull Interior classifyInterior(
        @NotNull PixelBuffer a, int x0, int y0, int x1, int y1, int periodX, int periodY
    ) {
        int iw = x1 - x0, ih = y1 - y0;
        if (iw <= 0 || ih <= 0) return Interior.SOLID;

        int first = a.getPixel(x0, y0);
        boolean uniform = true;
        for (int y = y0; y < y1 && uniform; y++)
            for (int x = x0; x < x1; x++)
                if (a.getPixel(x, y) != first) {
                    uniform = false;
                    break;
                }
        if (uniform) return Interior.SOLID;

        int px = Math.clamp(periodX, 1, iw);
        int py = Math.clamp(periodY, 1, ih);
        if ((px < iw || py < ih) && tilesAt(a, x0, y0, x1, y1, px, py)) return Interior.TILE;

        boolean rowsFlat = true;
        for (int y = y0; y < y1 && rowsFlat; y++) {
            int row = a.getPixel(x0, y);
            for (int x = x0; x < x1; x++)
                if (a.getPixel(x, y) != row) {
                    rowsFlat = false;
                    break;
                }
        }
        if (rowsFlat) return Interior.GRADIENT_V;

        boolean colsFlat = true;
        for (int x = x0; x < x1 && colsFlat; x++) {
            int col = a.getPixel(x, y0);
            for (int y = y0; y < y1; y++)
                if (a.getPixel(x, y) != col) {
                    colsFlat = false;
                    break;
                }
        }
        if (colsFlat) return Interior.GRADIENT_H;

        return Interior.STRETCH;
    }

    private static boolean tilesAt(@NotNull PixelBuffer a, int x0, int y0, int x1, int y1, int px, int py) {
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++)
                if (a.getPixel(x, y) != a.getPixel(x0 + (x - x0) % px, y0 + (y - y0) % py)) return false;

        return true;
    }

    private static int minPeriod(@NotNull PixelBuffer a, int x0, int y0, int x1, int y1, boolean across) {
        int iw = x1 - x0, ih = y1 - y0;
        if (iw <= 0 || ih <= 0) return 1;
        int n = across ? iw : ih;

        for (int p = 1; p <= n; p++) {
            boolean ok = true;
            for (int i = 0; i < n && ok; i++) {
                int src = i % p;
                for (int j = 0; j < (across ? ih : iw); j++) {
                    int mine = across ? a.getPixel(x0 + i, y0 + j) : a.getPixel(x0 + j, y0 + i);
                    int theirs = across ? a.getPixel(x0 + src, y0 + j) : a.getPixel(x0 + j, y0 + src);
                    if (mine != theirs) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) return p;
        }
        return n;
    }

    // ------------------------------------------------------------------ assembly

    private static void paintInterior(
        @NotNull PixelBuffer out, @NotNull PixelBuffer src, @NotNull ChromeDecomposition d,
        int l, int t, int l2, int t2, int iw, int ih, int srcIw, int srcIh
    ) {
        boolean stretchX = d.stretchInner() && (d.interior() == Interior.GRADIENT_H || d.interior() == Interior.STRETCH);
        boolean stretchY = d.stretchInner() && (d.interior() == Interior.GRADIENT_V || d.interior() == Interior.STRETCH);

        for (int y = 0; y < ih; y++) {
            int sy = switch (d.interior()) {
                case SOLID -> 0;
                case TILE -> y % Math.max(1, d.interiorPeriodY());
                default -> stretchY ? nearest(y, ih, srcIh) : y % srcIh;
            };
            for (int x = 0; x < iw; x++) {
                int sx = switch (d.interior()) {
                    case SOLID -> 0;
                    case TILE -> x % Math.max(1, d.interiorPeriodX());
                    default -> stretchX ? nearest(x, iw, srcIw) : x % srcIw;
                };
                out.setPixel(l2 + x, t2 + y, src.getPixel(l + sx, t + sy));
            }
        }
    }

    private static void paintBand(
        @NotNull PixelBuffer out, @NotNull PixelBuffer src, @NotNull ChromeDecomposition d,
        @NotNull Edge edge, int l, int t, int r, int b, int l2, int t2, int r2, int b2, int iw, int ih
    ) {
        Band band = d.bands().get(edge);
        if (band == null || !band.present()) return;

        int n2 = edge.across() ? iw : ih;
        if (n2 <= 0) return;

        int[][] strip = bandStrip(src, edge, l, t, r, b);
        int[] index = new int[n2];
        for (int i = 0; i < n2; i++) {
            if (band.period() > 0) index[i] = band.tile()[i % band.period()];
            else index[i] = d.stretchInner() ? nearest(i, n2, band.length()) : i % band.length();
        }
        for (Feature feature : band.features()) {
            int at = place(feature, n2);
            for (int k = 0; k < feature.width(); k++)
                if (at + k >= 0 && at + k < n2) index[at + k] = feature.offset() + k;
        }

        for (int i = 0; i < n2; i++) {
            int[] slice = strip[Math.clamp(index[i], 0, band.length() - 1)];
            switch (edge) {
                case TOP -> {
                    for (int j = 0; j < t2; j++) out.setPixel(l2 + i, j, slice[j]);
                }
                case BOTTOM -> {
                    for (int j = 0; j < b2; j++) out.setPixel(l2 + i, out.height() - b2 + j, slice[b - b2 + j]);
                }
                case LEFT -> {
                    for (int j = 0; j < l2; j++) out.setPixel(j, t2 + i, slice[j]);
                }
                case RIGHT -> {
                    for (int j = 0; j < r2; j++) out.setPixel(out.width() - r2 + j, t2 + i, slice[r - r2 + j]);
                }
            }
        }
    }

    private static int place(@NotNull Feature feature, int n2) {
        return switch (feature.anchor()) {
            case START -> feature.place();
            case END -> n2 - feature.place() - feature.width();
            case CENTER -> (n2 - feature.width()) / 2 + feature.place();
        };
    }

    private static int nearest(int local, int dest, int src) {
        return Math.clamp((2L * local + 1) * src / (2L * dest), 0, src - 1);
    }

    /** A slice, keyed by its pixels so equal slices collapse in a count. */
    private record SliceKey(int @NotNull [] pixels) {

        @Override
        public boolean equals(Object other) {
            return other instanceof SliceKey(int[] pixels1) && Arrays.equals(this.pixels, pixels1);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.pixels);
        }

    }

}
