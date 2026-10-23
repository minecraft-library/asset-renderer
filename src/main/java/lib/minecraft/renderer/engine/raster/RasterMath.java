package lib.minecraft.renderer.engine.raster;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.engine.math.Vector2f;
import org.jetbrains.annotations.NotNull;

/**
 * Static helpers for the 2D triangle rasterization math shared by the drawing helpers and the
 * engine layer - sub-pixel sample quantization, barycentric coordinates, the Pineda edge
 * functions and fill rule the rasterizer walks per pixel - the GPU's top-left rule, taken in GL's
 * Y-up frame ({@link #isTopOrLeftEdge}) - and the texel an interpolated coordinate reads.
 * (Camera-to-screen projection lives on the {@code engine.camera.Lens} record.)
 */
@UtilityClass
public class RasterMath {

    /**
     * Sub-pixel precision for the fixed-point coverage test. {@code 256} matches GPU 8-bit
     * sub-pixel hardware. Precision sweep on the full entity fleet (2026-05-20) showed the
     * <code>&lt;0.25</code> bucket saturates at 86 entities for any {@code P >= 256} and degrades
     * below ({@code P=64} gives 82, {@code P=16} gives 60). Held as a constant: the GPU's
     * rasterization precision is a hardware property, not a tunable parameter.
     */
    private static final int FIXED_POINT_PRECISION = 256;

    /**
     * Bound the span walk holds an edge value, a column step times a column count and a row step times
     * a row count under - {@code 2^60}, so an edge value anywhere in a walked box, and every product
     * the span math forms, stays under {@code 2^62}.
     */
    private static final long SPAN_LIMIT = 1L << 60;

    /**
     * Quantizes a screen-space sample coordinate to the {@link #FIXED_POINT_PRECISION 1/256}
     * sub-pixel grid used by the fixed-point coverage test. Public so the rasterizer's
     * incremental edge-function loop can derive its bbox top-left sample value once per
     * triangle and walk by step values thereafter.
     *
     * @param coord the screen-space coordinate (typically pixel-center {@code px + 0.5f})
     * @return the quantized sub-pixel value as a 64-bit signed integer
     */
    public static long quantizeSample(float coord) {
        return Math.round((double) coord * FIXED_POINT_PRECISION);
    }

    /**
     * Computes the barycentric denominator (twice the signed triangle area) for a triangle. Used
     * by {@link #barycentricInto} to reject degenerate triangles ({@code denom == 0}) and to
     * divide out the barycentric numerators.
     *
     * @param a the first vertex in 2D
     * @param b the second vertex in 2D
     * @param c the third vertex in 2D
     * @return the denominator value; sign encodes winding, magnitude is twice the triangle area
     */
    public static float barycentricDenominator(@NotNull Vector2f a, @NotNull Vector2f b, @NotNull Vector2f c) {
        return (b.y() - c.y()) * (a.x() - c.x()) + (c.x() - b.x()) * (a.y() - c.y());
    }

    /**
     * Computes the barycentric {@code (u, v, w)} coordinates of a 2D point relative to a triangle,
     * where {@code u/v/w} weight vertices {@code a/b/c} respectively and sum to {@code 1}, writing
     * them into {@code out[0..2]}. Used to interpolate per-vertex attributes (UVs, depth) across the
     * triangle's face.
     * <p>
     * Allocation-free: the query point arrives as two floats and the result lands in a
     * caller-supplied scratch array, so a tight pixel loop allocates neither a {@link Vector2f} nor
     * a return array. A degenerate triangle (zero denominator) writes zeros to all three slots.
     *
     * @param a the first vertex
     * @param b the second vertex
     * @param c the third vertex
     * @param px the query point's x coordinate
     * @param py the query point's y coordinate
     * @param out a caller-supplied scratch array of length at least 3
     */
    public static void barycentricInto(
        @NotNull Vector2f a,
        @NotNull Vector2f b,
        @NotNull Vector2f c,
        float px,
        float py,
        float @NotNull [] out
    ) {
        float denom = barycentricDenominator(a, b, c);
        if (denom == 0f) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 0f;
            return;
        }

        float u = ((b.y() - c.y()) * (px - c.x()) + (c.x() - b.x()) * (py - c.y())) / denom;
        float v = ((c.y() - a.y()) * (px - c.x()) + (a.x() - c.x()) * (py - c.y())) / denom;
        out[0] = u;
        out[1] = v;
        out[2] = 1f - u - v;
    }

    /**
     * Per-triangle edge function coefficients for fast per-pixel coverage testing (Pineda
     * 1988). Each edge function {@code e_ij(sx, sy) = (xj-xi)*(sy-yi) - (yj-yi)*(sx-xi)} is a
     * linear function in {@code (sx, sy)}; factoring out the vertex-dependent constants and
     * holding them once per triangle drops the per-pixel coverage test from ~40 long-ops
     * (re-quantize 4 points + 3 cross products + denom + sign flip) to ~16 ops (3 mul-adds +
     * sign check + at-most-3 fill-rule checks).
     * <p>
     * Coefficients are pre-sign-normalized: if the triangle's signed area is negative the
     * factory negates all A/B/C and denom so the {@code >= 0} inside test works regardless
     * of winding. The {@code topLeftXX} flags pre-evaluate
     * {@link RasterMath#isTopOrLeftEdge isTopOrLeftEdge} on the quantized integer
     * endpoints; the per-pixel test reads the boolean directly. <b>They are negated along with
     * the coefficients</b> - negating an edge's coefficients is exactly reversing its direction,
     * and the classification is antisymmetric under that reversal, so the two have to move
     * together or a triangle submitted the other way round gets the opposite fill rule from the
     * neighbour it shares an edge with.
     * <p>
     * The {@code stepXij} / {@code stepYij} fields are {@code A_ij * P} and {@code B_ij * P}
     * respectively, where {@code P =} {@link RasterMath#FIXED_POINT_PRECISION}. The
     * rasterizer uses them to walk edge values incrementally across the inner loop -
     * Pineda's classic optimization: hoist edge evaluation to the bbox top-left then advance
     * by {@code stepX} per pixel in X and {@code stepY} per pixel in Y. Inner-loop coverage
     * drops further to 3 adds + sign checks.
     * <p>
     * Computed once per triangle in
     * {@link Rasterizer#projectTriangle projectTriangle} (the
     * parallel Pass-1 map); read by every sample the coverage walk visits during Pass-2
     * rasterization. {@code ~128 bytes} per triangle - amortizes against tens to thousands
     * of pixel tests.
     *
     * @param a12 sx coefficient for edge v1->v2 (paired with bary[0])
     * @param b12 sy coefficient for edge v1->v2
     * @param c12 constant for edge v1->v2
     * @param a20 sx coefficient for edge v2->v0 (paired with bary[1])
     * @param b20 sy coefficient for edge v2->v0
     * @param c20 constant for edge v2->v0
     * @param a01 sx coefficient for edge v0->v1 (paired with bary[2])
     * @param b01 sy coefficient for edge v0->v1
     * @param c01 constant for edge v0->v1
     * @param denom sign-normalized triangle determinant; {@code 0} for degenerate triangles
     * @param topLeft12 whether edge v1->v2 owns shared-edge pixels under the OpenGL fill rule
     * @param topLeft20 whether edge v2->v0 owns shared-edge pixels under the OpenGL fill rule
     * @param topLeft01 whether edge v0->v1 owns shared-edge pixels under the OpenGL fill rule
     * @param stepX12 {@code a12 * FIXED_POINT_PRECISION}; per-pixel-step in X for edge v1->v2
     * @param stepY12 {@code b12 * FIXED_POINT_PRECISION}; per-pixel-step in Y for edge v1->v2
     * @param stepX20 per-pixel-step in X for edge v2->v0
     * @param stepY20 per-pixel-step in Y for edge v2->v0
     * @param stepX01 per-pixel-step in X for edge v0->v1
     * @param stepY01 per-pixel-step in Y for edge v0->v1
     */
    public record EdgeCoefficients(
        long a12, long b12, long c12,
        long a20, long b20, long c20,
        long a01, long b01, long c01,
        long denom,
        boolean topLeft12, boolean topLeft20, boolean topLeft01,
        long stepX12, long stepY12,
        long stepX20, long stepY20,
        long stepX01, long stepY01
    ) {

        /**
         * Builds the coefficient set from three screen-space vertices. Quantizes each vertex
         * to {@link RasterMath#FIXED_POINT_PRECISION 1/256} once, computes the 3 edge
         * coefficients, the determinant, the per-pixel step values, and the top-left
         * classification per edge. If the determinant is negative the entire coefficient set
         * is sign-flipped so downstream tests stay {@code >= 0}.
         *
         * @param v0 first screen-space vertex
         * @param v1 second screen-space vertex
         * @param v2 third screen-space vertex
         * @return the precomputed coefficient set
         */
        public static @NotNull EdgeCoefficients of(@NotNull Vector2f v0, @NotNull Vector2f v1, @NotNull Vector2f v2) {
            final int P = FIXED_POINT_PRECISION;
            long x0 = Math.round((double) v0.x() * P);
            long y0 = Math.round((double) v0.y() * P);
            long x1 = Math.round((double) v1.x() * P);
            long y1 = Math.round((double) v1.y() * P);
            long x2 = Math.round((double) v2.x() * P);
            long y2 = Math.round((double) v2.y() * P);

            // Factor each edge function e_ij(sx, sy) = (xj-xi)*(sy-yi) - (yj-yi)*(sx-xi)
            // into the linear form A_ij*sx + B_ij*sy + C_ij:
            //   A_ij = yi - yj, B_ij = xj - xi, C_ij = (yj-yi)*xi - (xj-xi)*yi
            long a12 = y1 - y2;
            long b12 = x2 - x1;
            long c12 = (y2 - y1) * x1 - (x2 - x1) * y1;

            long a20 = y2 - y0;
            long b20 = x0 - x2;
            long c20 = (y0 - y2) * x2 - (x0 - x2) * y2;

            long a01 = y0 - y1;
            long b01 = x1 - x0;
            long c01 = (y1 - y0) * x0 - (x1 - x0) * y0;

            long denom = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);

            boolean topLeft12 = isTopOrLeftEdge(x1, y1, x2, y2);
            boolean topLeft20 = isTopOrLeftEdge(x2, y2, x0, y0);
            boolean topLeft01 = isTopOrLeftEdge(x0, y0, x1, y1);

            if (denom < 0L) {
                a12 = -a12; b12 = -b12; c12 = -c12;
                a20 = -a20; b20 = -b20; c20 = -c20;
                a01 = -a01; b01 = -b01; c01 = -c01;
                denom = -denom;
                // Negating an edge's coefficients is reversing its direction - e_ji == -e_ij
                // identically - and the classification reads that direction and is antisymmetric
                // under reversing it, so the flags move with the coefficients.
                topLeft12 = !topLeft12;
                topLeft20 = !topLeft20;
                topLeft01 = !topLeft01;
            }
            return new EdgeCoefficients(
                a12, b12, c12, a20, b20, c20, a01, b01, c01, denom,
                topLeft12, topLeft20, topLeft01,
                a12 * P, b12 * P,
                a20 * P, b20 * P,
                a01 * P, b01 * P);
        }
    }

    /**
     * Tests whether the sample {@code (px, py)} lies inside the triangle described by the
     * precomputed {@link EdgeCoefficients}. Per-pixel hot path: 3 long mul-adds + sign checks.
     * <p>
     * {@link #isInsideTriangle(Vector2f, Vector2f, Vector2f, float, float)} agrees with this by
     * construction rather than by a second algebra - it builds the coefficients and delegates here -
     * so the two overloads cannot drift and neither one is evidence about the rasterizer.
     * <p>
     * <b>The real second implementation of this rule is not an overload.</b> {@code Rasterizer}'s
     * coverage loop does not call this method: it steps the three edge functions incrementally across
     * a scanline off the same {@link EdgeCoefficients}, because re-evaluating three mul-adds per pixel
     * is the cost this class exists to avoid, and on a wide enough box it walks only each scanline's
     * span - the columns {@link #spanStart} and {@link #spanEnd} solve as the ones where all three
     * edge values are non-negative, every other column failing this test outright. The stepping
     * recurrence, the span and this fresh evaluation are three spellings of one rule, and nothing in
     * the type system holds them together - the fence is {@code RasterMathTest}, which drives the span
     * helpers and the stepping form the loop uses and asserts the span holds exactly the columns a
     * fresh evaluation finds non-negative, and that the stepped values reach what a fresh evaluation
     * computes. Change any side and run it.
     *
     * @param ec the triangle's precomputed edge coefficients
     * @param px the sample point's x coordinate (typically pixel-center {@code px + 0.5f})
     * @param py the sample point's y coordinate (typically pixel-center {@code py + 0.5f})
     * @return {@code true} if the sample is owned by this triangle under the fill rule
     *     {@link #isTopOrLeftEdge} states
     */
    public static boolean isInsideTriangle(@NotNull EdgeCoefficients ec, float px, float py) {
        if (ec.denom == 0L) return false;
        final int P = FIXED_POINT_PRECISION;
        long sx = Math.round((double) px * P);
        long sy = Math.round((double) py * P);

        long e12 = ec.a12 * sx + ec.b12 * sy + ec.c12;
        long e20 = ec.a20 * sx + ec.b20 * sy + ec.c20;
        long e01 = ec.a01 * sx + ec.b01 * sy + ec.c01;

        if (e12 < 0L || e20 < 0L || e01 < 0L) return false;

        if (e12 == 0L && !ec.topLeft12) return false;
        if (e20 == 0L && !ec.topLeft20) return false;
        if (e01 == 0L && !ec.topLeft01) return false;
        return true;
    }

    /**
     * Sub-pixel-fixed-point inside-triangle test - free-function variant for callers that
     * don't pre-build an {@link EdgeCoefficients}. Internally builds the coefficient set
     * once and delegates to the precomputed overload. Hot callers (the rasterizer inner
     * loop) should pre-build via {@link EdgeCoefficients#of} and reuse across all pixels of
     * a triangle.
     * <p>
     * See {@link EdgeCoefficients} for the algebra. {@link #FIXED_POINT_PRECISION 1/256}
     * matches GPU 8-bit sub-pixel hardware. Vanilla's GPU rasterizes via the same
     * convention; integer math makes the edge classification deterministic regardless of
     * upstream FP drift.
     * <p>
     * Empirical (2026-05-20): {@code 86/97/99/99} fleet parity buckets vs the prior
     * float-bary path's {@code 84/96/99/99}. Witch dropped from {@code 1.31} to
     * {@code 0.12} in snap-off, squid from {@code 0.60} to {@code 0.13}, glow_squid from
     * {@code 0.48} to {@code 0.12}.
     *
     * @param v0 the triangle's first screen-space vertex
     * @param v1 the triangle's second screen-space vertex
     * @param v2 the triangle's third screen-space vertex
     * @param px the sample point's x coordinate (typically pixel-center {@code px + 0.5f})
     * @param py the sample point's y coordinate (typically pixel-center {@code py + 0.5f})
     * @return {@code true} if the sample is owned by this triangle under the fill rule
     *     {@link #isTopOrLeftEdge} states
     */
    public static boolean isInsideTriangle(
        @NotNull Vector2f v0,
        @NotNull Vector2f v1,
        @NotNull Vector2f v2,
        float px,
        float py
    ) {
        return isInsideTriangle(EdgeCoefficients.of(v0, v1, v2), px, py);
    }

    /**
     * Whether the span walk reads a triangle's box exactly - whether every edge value it reaches,
     * stepped from {@code row12}, {@code row20} and {@code row01} across {@code columns} columns and
     * down {@code rows} rows, stays inside a {@code long}.
     * <p>
     * The coverage walk compares the stepped values as the {@code long}s they are, and the span is
     * solved over the integers, so the two agree wherever no stepped value wraps. Each edge keeps that
     * when its value at the box's first sample, its column step times {@code columns} and its row step
     * times {@code rows} all stay under {@code 2^60}. Every canvas this renderer draws sits far inside
     * the bound; a corner a perspective lens throws towards infinity can leave it, and its triangle
     * then walks every column of its box.
     *
     * @param ec the triangle's precomputed edge coefficients
     * @param row12 the edge value opposite the first corner at the box's first sample
     * @param row20 the edge value opposite the second corner at the box's first sample
     * @param row01 the edge value opposite the third corner at the box's first sample
     * @param columns the number of columns in the box, at least {@code 1}
     * @param rows the number of rows the walk steps the edge values down, at least {@code 1}
     * @return whether the span walk reads the box exactly
     */
    static boolean spanFits(@NotNull EdgeCoefficients ec, long row12, long row20, long row01, int columns, int rows) {
        long columnBound = SPAN_LIMIT / columns;
        long rowBound = SPAN_LIMIT / rows;
        return within(row12, SPAN_LIMIT) && within(ec.stepX12, columnBound) && within(ec.stepY12, rowBound)
            && within(row20, SPAN_LIMIT) && within(ec.stepX20, columnBound) && within(ec.stepY20, rowBound)
            && within(row01, SPAN_LIMIT) && within(ec.stepX01, columnBound) && within(ec.stepY01, rowBound);
    }

    /**
     * Whether a value lies strictly between {@code -bound} and {@code bound}.
     *
     * @param value the value
     * @param bound the positive bound
     * @return whether {@code |value| < bound}
     */
    private static boolean within(long value, long bound) {
        return value > -bound && value < bound;
    }

    /**
     * Returns an edge's crossing on a scanline, {@code floorDiv(edge, |stepX|)} with {@code 1} standing
     * in for a zero step, from which {@link #spanStart} and {@link #spanEnd} read the columns the edge
     * leaves non-negative.
     * <p>
     * At column offset {@code k} along the scanline the edge value is {@code edge + k * stepX}. Where
     * {@code stepX > 0} it is non-negative from {@code -crossing} on, which is
     * {@code ceilDiv(-edge, stepX)}; where {@code stepX < 0} it is non-negative up to {@code crossing},
     * which is {@code floorDiv(edge, -stepX)}. A zero step leaves it flat along the scanline, and the
     * crossing then carries the one thing that matters, its sign: negative exactly when the edge value
     * is.
     * <p>
     * The same quotient of an edge's row step, {@code crossingOf(stepY, stepX)}, is the whole part
     * {@link #nextCrossing} adds per scanline.
     *
     * @param edge the edge value at the scanline's first column
     * @param stepX the edge's step per column
     * @return the edge's crossing
     */
    static long crossingOf(long edge, long stepX) {
        return Math.floorDiv(edge, Math.max(1L, Math.abs(stepX)));
    }

    /**
     * Returns an edge's crossing on a scanline from its crossing on the scanline above, without
     * dividing - always equal to {@link #crossingOf crossingOf(edge, stepX)}.
     * <p>
     * With {@code d = |stepX|}, the crossing is the edge value's quotient by {@code d} rounded down,
     * and a scanline down adds the row step to the edge value. Splitting both into a quotient and a
     * remainder in {@code [0, d)}, the two remainders sum to under {@code 2d}, so the new quotient is
     * {@code previous + rowStep} or one more, and what that sum leaves of the edge value tells which.
     *
     * @param previous the edge's crossing on the scanline above
     * @param rowStep the crossing of the edge's row step, {@code crossingOf(stepY, stepX)}
     * @param edge the edge value at this scanline's first column
     * @param stepX the edge's step per column
     * @return the edge's crossing on this scanline
     */
    static long nextCrossing(long previous, long rowStep, long edge, long stepX) {
        long divisor = Math.max(1L, Math.abs(stepX));
        long next = previous + rowStep;
        return edge - next * divisor >= divisor ? next + 1L : next;
    }

    /**
     * Returns the first column offset of a scanline's span - the columns from here to
     * {@link #spanEnd} inclusive, empty when this passes that, whose samples leave all three edge
     * values non-negative - from the three edges' {@link #crossingOf crossings} on the scanline.
     * <p>
     * Only an edge rising along the scanline ({@code stepX > 0}) bounds the span from the left, at
     * {@code ceilDiv(-edge, stepX)}, which is minus its crossing. The bound is taken in {@code long}
     * and clamped to {@code [0, last + 1]} before it narrows to an {@code int}.
     *
     * @param ec the triangle's precomputed edge coefficients
     * @param crossing12 the crossing of the edge opposite the first corner
     * @param crossing20 the crossing of the edge opposite the second corner
     * @param crossing01 the crossing of the edge opposite the third corner
     * @param last the box's last column offset, {@code 0} or above
     * @return the span's first column offset, {@code last + 1} where the left bounds pass the box
     */
    static int spanStart(@NotNull EdgeCoefficients ec, long crossing12, long crossing20, long crossing01, int last) {
        long first = 0L;
        if (ec.stepX12 > 0L) first = Math.max(first, -crossing12);
        if (ec.stepX20 > 0L) first = Math.max(first, -crossing20);
        if (ec.stepX01 > 0L) first = Math.max(first, -crossing01);
        return (int) Math.min(first, last + 1L);
    }

    /**
     * Returns the last column offset of a scanline's span - the columns from {@link #spanStart} to
     * here inclusive, empty when that passes this, whose samples leave all three edge values
     * non-negative - from the three edges' {@link #crossingOf crossings} on the scanline.
     * <p>
     * An edge falling along the scanline ({@code stepX < 0}) bounds the span from the right, at
     * {@code floorDiv(edge, -stepX)}, which is its crossing. An edge flat along it
     * ({@code stepX == 0}) bounds nothing where its value is non-negative and empties the scanline
     * where it is negative. The bound is taken in {@code long} and clamped to {@code [-1, last]}
     * before it narrows to an {@code int}.
     *
     * @param ec the triangle's precomputed edge coefficients
     * @param crossing12 the crossing of the edge opposite the first corner
     * @param crossing20 the crossing of the edge opposite the second corner
     * @param crossing01 the crossing of the edge opposite the third corner
     * @param last the box's last column offset, {@code 0} or above
     * @return the span's last column offset, {@code -1} where the right bounds or a flat edge empty it
     */
    static int spanEnd(@NotNull EdgeCoefficients ec, long crossing12, long crossing20, long crossing01, int last) {
        long end = last;
        if (ec.stepX12 < 0L) end = Math.min(end, crossing12);
        else if (ec.stepX12 == 0L && crossing12 < 0L) return -1;
        if (ec.stepX20 < 0L) end = Math.min(end, crossing20);
        else if (ec.stepX20 == 0L && crossing20 < 0L) return -1;
        if (ec.stepX01 < 0L) end = Math.min(end, crossing01);
        else if (ec.stepX01 == 0L && crossing01 < 0L) return -1;
        return (int) Math.max(end, -1L);
    }

    /**
     * Returns {@code true} if the directed edge from {@code (sx, sy)} to {@code (ex, ey)} owns the
     * samples lying exactly on it - a top or left edge under the GPU's top-left fill rule, which
     * decides which of two adjacent triangles takes a sample on the edge they share. Operates on the
     * sub-pixel fixed-point endpoints so the classification is deterministic regardless of upstream
     * float drift.
     * <p>
     * <b>The GPU applies the rule in GL's window frame, where Y runs up.</b> Image space runs Y down,
     * so the same rule reads bottom-left here: a sample exactly on a horizontal edge two faces share
     * belongs to the face above it in image space, and a sample exactly on any other shared edge to
     * the face on its right, flipping Y leaving left and right where they are.
     * <ul>
     * <li>Top edge in GL's frame: horizontal ({@code sy == ey}) going left ({@code ex < sx}), the
     * interior above it in image space</li>
     * <li>Left edge: non-horizontal going up ({@code ey < sy}), the interior to its right</li>
     * </ul>
     *
     * <p><b>The two arms follow from the winding rather than being free to choose.</b> Callers hand
     * the edge in the direction the {@link EdgeCoefficients#of sign-normalized} coefficients
     * evaluate it, which is the winding where {@code e >= 0} marks the interior - clockwise on
     * screen with Y running down. Under that winding {@code e_ij >= 0} puts the interior to the left
     * of a downward edge, so a downward edge is the triangle's <em>right</em> one and the
     * <em>upward</em> edge is its left. The same derivation puts the interior below an edge going
     * right and above an edge going left, so the edge going left is the triangle's bottom edge in
     * image space, which is its top edge in GL's frame. Reading either arm the other way round is
     * equally self-consistent - it still hands every shared edge to exactly one side - but it hands
     * that edge to the opposite side from the GPU; the horizontal arm read that way is the top-left
     * rule taken in image space.
     */
    private static boolean isTopOrLeftEdge(long sx, long sy, long ex, long ey) {
        if (sy == ey) return ex < sx;
        return ey < sy;
    }

    /**
     * Returns the texel a coordinate reads on one axis - the texel containing it, or the one below
     * it when the coordinate lies exactly on a texel boundary.
     * <p>
     * <b>A boundary reads the texel below it because that is where the reference GPU almost always
     * reads it.</b> Each rounding in its interpolation cuts toward zero, so a coordinate that is
     * exactly a boundary value lands a few float steps under it, and its point sampler takes the
     * floor of what it lands on. So {@code ceil(x) - 1} rather than {@code floor(x)}:
     * {@code 10.3 -> 10}, {@code 10.0 -> 9}, {@code 0.0 -> -1}. The two differ only on a boundary,
     * and where a face's edge or a texel edge runs exactly through pixel centres the snapped corners
     * put the coordinate on one.
     * <p>
     * The answer is not bounded: a coordinate at a sheet's first texel answers {@code -1}, and
     * wrapping or bounding it is the caller's.
     *
     * @param scaled the coordinate times the texture's size on this axis
     * @return the texel index, {@code -1} or below for a coordinate at or under the sheet's start
     */
    public static int texelOf(float scaled) {
        return (int) Math.ceil(scaled) - 1;
    }

    /**
     * Returns the texel the exact interpolated coordinate reads on one axis, under the same rule as
     * {@link #texelOf(float)}, computed from the coverage walk's integer edge values rather than from
     * floating-point barycentrics.
     * <p>
     * The barycentric weights at a sample are exactly {@code e12 / denom}, {@code e20 / denom} and
     * {@code e01 / denom}, all four from one sign-normalized {@link EdgeCoefficients} set, so when each
     * corner's coordinate is a whole number of texels the sample's coordinate in texels is exactly
     * {@code (e12 * t0 + e20 * t1 + e01 * t2) / denom}, and {@code floorDiv(numerator - 1, denom)} is
     * that quotient's {@code ceil - 1}. A float evaluation of the same coordinate can land a step
     * either side of a boundary it sits exactly on, which reads the wrong texel there.
     * <p>
     * The numerator stays inside a {@code long} for any canvas and sheet this renderer draws: the edge
     * values grow with the square of the canvas side in sub-pixel units and the corner coordinates
     * with the sheet's size, and a 16384-pixel canvas against a 4096-texel sheet still leaves headroom.
     *
     * @param e12 the sample's edge value opposite the first corner
     * @param e20 the sample's edge value opposite the second corner
     * @param e01 the sample's edge value opposite the third corner
     * @param denom the sign-normalized determinant, positive for a covered sample
     * @param t0 the first corner's coordinate, in whole texels
     * @param t1 the second corner's coordinate, in whole texels
     * @param t2 the third corner's coordinate, in whole texels
     * @return the texel index, {@code -1} or below for a coordinate at or under the sheet's start
     */
    public static int exactTexelOf(long e12, long e20, long e01, long denom, long t0, long t1, long t2) {
        return (int) Math.floorDiv(e12 * t0 + e20 * t1 + e01 * t2 - 1, denom);
    }

    /**
     * Writes the integer bounding box of a triangle, clamped to the canvas, into {@code out[0..3]}
     * as {@code [minX, minY, maxX, maxY]}.
     * <p>
     * Allocation-free: the result lands in a caller-supplied scratch array rather than a fresh one,
     * so a per-triangle loop allocates nothing. Bounds are clamped to {@code [0, canvasW-1]} x
     * {@code [0, canvasH-1]} inclusive.
     * <p>
     * A triangle lying entirely off the canvas comes back <b>inverted</b> rather than flagged: each end
     * is clamped independently, so one wholly past the right edge answers {@code minX > maxX} - on a
     * 16-wide canvas a triangle spanning x 20 to 25 writes {@code [20, .., 15, ..]}. An inclusive
     * {@code for (x = minX; x <= maxX; x++)} walks it zero times, which is the right answer arrived at
     * by the ordering rather than by a test. Reading the width as {@code maxX - minX + 1} gives a
     * negative, so a caller that reads the extent rejects an inverted box first, as
     * {@code Rasterizer.drawsInOneBand} does.
     *
     * @param a the first vertex
     * @param b the second vertex
     * @param c the third vertex
     * @param canvasW the canvas width
     * @param canvasH the canvas height
     * @param out a caller-supplied scratch array of length at least 4
     */
    public static void triangleBoundsInto(
        @NotNull Vector2f a,
        @NotNull Vector2f b,
        @NotNull Vector2f c,
        int canvasW,
        int canvasH,
        int @NotNull [] out
    ) {
        out[0] = Math.max(0, (int) Math.floor(Math.min(a.x(), Math.min(b.x(), c.x()))));
        out[1] = Math.max(0, (int) Math.floor(Math.min(a.y(), Math.min(b.y(), c.y()))));
        out[2] = Math.min(canvasW - 1, (int) Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x()))));
        out[3] = Math.min(canvasH - 1, (int) Math.ceil(Math.max(a.y(), Math.max(b.y(), c.y()))));
    }

}
