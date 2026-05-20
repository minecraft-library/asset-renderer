package lib.minecraft.renderer.geometry;

import lib.minecraft.renderer.tensor.Vector2f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Static helpers for 2D rasterization math that are shared by the drawing helpers and the
 * engine layer. Projection and perspective math that depends on the {@link
 * PerspectiveParams} record lives on {@code RenderEngine}; this file is
 * reserved for the primitive triangle and barycentric math that has no dependency on engine types.
 */
@UtilityClass
public class ProjectionMath {

    /**
     * Sub-pixel precision for the fixed-point coverage test. {@code 256} matches GPU 8-bit
     * sub-pixel hardware. Precision sweep on the full entity fleet (2026-05-20) showed the
     * <code>&lt;0.25</code> bucket saturates at 86 entities for any {@code P >= 256} and degrades
     * below ({@code P=64} gives 82, {@code P=16} gives 60). Held as a constant: the GPU's
     * rasterization precision is a hardware property, not a tunable parameter.
     */
    private static final int FIXED_POINT_PRECISION = 256;

    /**
     * Computes the barycentric denominator for a triangle. Used by the rasterizer to reject
     * degenerate triangles (denominator near zero) and to divide out the barycentric numerators.
     *
     * @param a the first vertex in 2D
     * @param b the second vertex in 2D
     * @param c the third vertex in 2D
     * @return the denominator value
     */
    public static float barycentricDenominator(@NotNull Vector2f a, @NotNull Vector2f b, @NotNull Vector2f c) {
        return (b.y() - c.y()) * (a.x() - c.x()) + (c.x() - b.x()) * (a.y() - c.y());
    }

    /**
     * Computes the barycentric (u, v, w) coordinates of a 2D point relative to a triangle.
     *
     * @param a the first vertex
     * @param b the second vertex
     * @param c the third vertex
     * @param point the query point
     * @return a three-element float array {@code [u, v, w]}
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
     * Tests whether the sample {@code (px, py)} lies inside the triangle {@code (v0, v1, v2)}
     * using sub-pixel-fixed-point edge functions at {@link #FIXED_POINT_PRECISION 1/256}
     * precision. Replaces the prior float-bary {@code >= 0} path which fired the OpenGL
     * top-left fill rule only when float arithmetic happened to land exactly on zero - a
     * fragile condition at axis-aligned cube edges where some chains produce exact
     * integer-multiple coordinates and others produce sub-ULP offsets.
     * <p>
     * Vanilla's GPU rasterizes via sub-pixel-fixed-point edge functions (hardware sub-pixel
     * precision is typically 4-bit or 8-bit, matching our 1/256 grid); this is the same
     * convention, applied in software. Integer math makes the edge classification
     * deterministic regardless of upstream FP drift.
     * <p>
     * Algorithm:
     * <ol>
     * <li>Quantize each vertex and the sample to {@code 1/FIXED_POINT_PRECISION} units. The
     *     {@code double} cast before multiplication avoids float overflow at large screen
     *     coords ({@code Math.round} is exact in double).</li>
     * <li>Compute edge functions {@code e_12, e_20, e_01} as 64-bit integer 2D cross products.
     *     {@code e_12} pairs with {@code bary[0]} (opposite {@code v0}), and so on.</li>
     * <li>Compute the triangle's signed-area determinant {@code denom} as a 64-bit cross
     *     product. Sign-normalize the edges relative to {@code denom} so 'inside' means all
     *     three are {@code >= 0} regardless of triangle winding.</li>
     * <li>Inside test: all three edge functions {@code >= 0}.</li>
     * <li>OpenGL top-left fill rule at edges where {@code e_ij == 0}: include the sample
     *     only if the directed edge {@code v_i -> v_j} is a top or left edge (horizontal
     *     going left, or non-horizontal going down) in CW Y-down screen space. This rule
     *     ensures exactly one of two adjacent triangles owns each shared-edge pixel.</li>
     * </ol>
     * <p>
     * Empirical (2026-05-20): {@code 86/97/99/99} fleet parity buckets, +2 in {@code <0.25}
     * vs the prior float-bary path's {@code 84/96/99/99}. Witch dropped from {@code 1.31}
     * to {@code 0.12} in snap-off, squid from {@code 0.60} to {@code 0.13}, glow_squid from
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
        final int P = FIXED_POINT_PRECISION;
        long x0 = Math.round((double) v0.x() * P);
        long y0 = Math.round((double) v0.y() * P);
        long x1 = Math.round((double) v1.x() * P);
        long y1 = Math.round((double) v1.y() * P);
        long x2 = Math.round((double) v2.x() * P);
        long y2 = Math.round((double) v2.y() * P);
        long sx = Math.round((double) px * P);
        long sy = Math.round((double) py * P);

        // e_12: paired with bary[0] (opposite v0); edge v1 -> v2 evaluated at sample.
        long e12 = (x2 - x1) * (sy - y1) - (y2 - y1) * (sx - x1);
        // e_20: paired with bary[1] (opposite v1); edge v2 -> v0.
        long e20 = (x0 - x2) * (sy - y2) - (y0 - y2) * (sx - x2);
        // e_01: paired with bary[2] (opposite v2); edge v0 -> v1.
        long e01 = (x1 - x0) * (sy - y0) - (y1 - y0) * (sx - x0);
        // denom: twice the triangle's signed area.
        long denom = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);

        if (denom == 0L) return false;

        // Sign-normalize so 'inside' means all edges >= 0 regardless of winding.
        if (denom < 0L) { e12 = -e12; e20 = -e20; e01 = -e01; }

        if (e12 < 0L || e20 < 0L || e01 < 0L) return false;

        // Top-left fill rule at exact-edge cases. Quantized integer endpoints decide
        // direction; classification matches the standard OpenGL CW Y-down convention.
        if (e12 == 0L && !isTopOrLeftEdge(x1, y1, x2, y2)) return false;
        if (e20 == 0L && !isTopOrLeftEdge(x2, y2, x0, y0)) return false;
        if (e01 == 0L && !isTopOrLeftEdge(x0, y0, x1, y1)) return false;
        return true;
    }

    /**
     * Returns {@code true} if the directed edge from {@code (sx, sy)} to {@code (ex, ey)}
     * is a top or left edge in CW Y-down screen space - the OpenGL fill-rule classification
     * for which adjacent triangle "owns" a shared-edge pixel. Operates on the sub-pixel
     * fixed-point endpoints so the classification is deterministic regardless of upstream
     * float drift.
     * <ul>
     * <li>Top edge: horizontal ({@code sy == ey}) going left ({@code sx > ex})</li>
     * <li>Left edge: non-horizontal going down ({@code ey > sy})</li>
     * </ul>
     */
    private static boolean isTopOrLeftEdge(long sx, long sy, long ex, long ey) {
        if (sy == ey) return sx > ex;
        return ey > sy;
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
