package lib.minecraft.renderer.engine.raster;

import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.tensor.Vector2f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Static helpers for the 2D triangle rasterization math shared by the drawing helpers and the
 * engine layer - sub-pixel sample quantization, barycentric coordinates, and the Pineda edge
 * functions / top-left fill rule the rasterizer walks per pixel. (Camera-to-screen projection lives
 * on the {@code engine.camera.Lens} record.)
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
     * where {@code u/v/w} weight vertices {@code a/b/c} respectively and sum to {@code 1}. Used to
     * interpolate per-vertex attributes (UVs, depth) across the triangle's face.
     * <p>
     * Allocates a fresh array; hot pixel loops should call
     * {@link #barycentricInto(Vector2f, Vector2f, Vector2f, float, float, float[])} with a reused
     * scratch buffer instead.
     *
     * @param a the first vertex
     * @param b the second vertex
     * @param c the third vertex
     * @param point the query point
     * @return a three-element float array {@code [u, v, w]}; all zeros for a degenerate triangle
     */
    public static float @NotNull [] barycentric(
        @NotNull Vector2f a,
        @NotNull Vector2f b,
        @NotNull Vector2f c,
        @NotNull Vector2f point
    ) {
        float[] out = new float[3];
        barycentricInto(a, b, c, point.x(), point.y(), out);
        return out;
    }

    /**
     * Allocation-free variant of {@link #barycentric(Vector2f, Vector2f, Vector2f, Vector2f)}.
     * Takes the query point as two floats (so callers inside tight pixel loops do not have to
     * allocate a {@link Vector2f}) and writes the three barycentric coordinates into
     * {@code out[0..2]}.
     * <p>
     * Math is bit-identical to the allocating variant: a degenerate triangle (zero denominator)
     * writes zeros to all three output slots, matching {@link #barycentric}'s behaviour.
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
     * sign check + at-most-3 top-left checks).
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
     * {@link ModelEngine#projectTriangle projectTriangle} (the
     * parallel Pass-1 map); read by every pixel of the triangle's bbox during Pass-2
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
     * precomputed {@link EdgeCoefficients}. Per-pixel hot path: 3 long mul-adds + sign
     * checks. Bit-identical inside-test outcome to
     * {@link #isInsideTriangle(Vector2f, Vector2f, Vector2f, float, float)} - the algebra is
     * an exact factoring; integer arithmetic guarantees no precision drift.
     *
     * @param ec the triangle's precomputed edge coefficients
     * @param px the sample point's x coordinate (typically pixel-center {@code px + 0.5f})
     * @param py the sample point's y coordinate (typically pixel-center {@code py + 0.5f})
     * @return {@code true} if the sample is owned by this triangle under the top-left rule
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
     * @return {@code true} if the sample is owned by this triangle under the top-left rule
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
     * Returns {@code true} if the directed edge from {@code (sx, sy)} to {@code (ex, ey)}
     * is a top or left edge in CW Y-down screen space - the OpenGL fill-rule classification
     * for which adjacent triangle "owns" a shared-edge pixel. Operates on the sub-pixel
     * fixed-point endpoints so the classification is deterministic regardless of upstream
     * float drift.
     * <ul>
     * <li>Top edge: horizontal ({@code sy == ey}) going right ({@code ex > sx})</li>
     * <li>Left edge: non-horizontal going up ({@code ey < sy})</li>
     * </ul>
     *
     * <p><b>The two arms follow from the winding rather than being free to choose.</b> Callers hand
     * the edge in the direction the {@link EdgeCoefficients#of sign-normalized} coefficients
     * evaluate it, which is the winding where {@code e >= 0} marks the interior - clockwise on
     * screen with Y running down. Under that winding {@code e_ij >= 0} puts the interior to the left
     * of a downward edge, so a downward edge is the triangle's <em>right</em> one and the
     * <em>upward</em> edge is its left; the horizontal arm follows the same derivation and lands on
     * left-to-right for top. The mirrored reading is equally self-consistent - it still hands every
     * shared edge to exactly one side - but it is the bottom-right rule, and it hands that edge to
     * the opposite side from the GPU.
     */
    private static boolean isTopOrLeftEdge(long sx, long sy, long ex, long ey) {
        if (sy == ey) return ex > sx;
        return ey < sy;
    }

    /**
     * Returns the integer bounding box of a triangle clamped to the canvas.
     *
     * @param a the first vertex
     * @param b the second vertex
     * @param c the third vertex
     * @param canvasW the canvas width
     * @param canvasH the canvas height
     * @return {@code [minX, minY, maxX, maxY]} (inclusive)
     */
    public static int @NotNull [] triangleBounds(
        @NotNull Vector2f a,
        @NotNull Vector2f b,
        @NotNull Vector2f c,
        int canvasW,
        int canvasH
    ) {
        int[] out = new int[4];
        triangleBoundsInto(a, b, c, canvasW, canvasH, out);
        return out;
    }

    /**
     * Allocation-free variant of {@link #triangleBounds(Vector2f, Vector2f, Vector2f, int, int)}.
     * Writes {@code [minX, minY, maxX, maxY]} into {@code out[0..3]} using a caller-supplied
     * scratch array.
     * <p>
     * Math is bit-identical to the allocating variant. Bounds are clamped to
     * {@code [0, canvasW-1]} x {@code [0, canvasH-1]} inclusive.
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
