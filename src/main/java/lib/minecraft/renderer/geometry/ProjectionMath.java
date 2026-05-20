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
     * Tests whether a barycentric coordinate triple falls inside the triangle. All three values
     * must be non-negative.
     *
     * @param uvw the barycentric triple
     * @return {@code true} if the point lies within the triangle
     */
    public static boolean isInsideTriangle(float @NotNull [] uvw) {
        return uvw[0] >= 0f && uvw[1] >= 0f && uvw[2] >= 0f;
    }

    /**
     * Tests whether a barycentric coordinate triple falls inside the triangle, applying the
     * OpenGL top-left fill rule for pixels lying exactly on a triangle edge. Vanilla's GPU
     * rasterizer assigns each edge pixel to exactly one of the two adjacent triangles - the
     * one whose owned edge is a TOP or LEFT edge (in CW Y-down screen space, that means
     * horizontal edges going LEFT or non-horizontal edges going DOWN). Without this rule,
     * our {@link #isInsideTriangle plain inclusion} test double-counts shared-edge pixels
     * across both adjacent triangles, which double-rasterises at axis-aligned cube face
     * edges and produces alpha-blend stacking that doesn't match vanilla.
     * <p>
     * Front-facing triangles in our pipeline are CW in Y-down screen space (det=-1 chirality
     * means model CCW projects to screen CW). For each edge i the test is:
     * <ul>
     * <li>{@code bary[i] > 0}: strictly inside relative to edge i - always include</li>
     * <li>{@code bary[i] == 0}: on edge i - include only if edge i is a TOP or LEFT edge</li>
     * <li>{@code bary[i] < 0}: outside relative to edge i - exclude</li>
     * </ul>
     * Edge i is opposite vertex i. The barycentric layout pairs {@code u=bary[0]} with edge
     * {@code v1→v2}, {@code v=bary[1]} with edge {@code v2→v0}, {@code w=bary[2]} with edge
     * {@code v0→v1}.
     *
     * @param uvw the barycentric triple
     * @param v0 the triangle's first screen-space vertex
     * @param v1 the triangle's second screen-space vertex
     * @param v2 the triangle's third screen-space vertex
     * @return {@code true} if the point is owned by this triangle under the top-left rule
     */
    public static boolean isInsideTriangleTopLeft(
        float @NotNull [] uvw,
        @NotNull Vector2f v0,
        @NotNull Vector2f v1,
        @NotNull Vector2f v2
    ) {
        if (uvw[0] < 0f || uvw[1] < 0f || uvw[2] < 0f) return false;
        if (uvw[0] == 0f && !isTopOrLeftEdge(v1, v2)) return false;
        if (uvw[1] == 0f && !isTopOrLeftEdge(v2, v0)) return false;
        if (uvw[2] == 0f && !isTopOrLeftEdge(v0, v1)) return false;
        return true;
    }

    /**
     * Returns {@code true} if the directed edge from {@code start} to {@code end} is a top or
     * left edge in CW Y-down screen space - the OpenGL fill-rule classification for which
     * adjacent triangle "owns" a shared-edge pixel.
     * <ul>
     * <li>Top edge: horizontal ({@code start.y == end.y}) going left ({@code start.x > end.x})</li>
     * <li>Left edge: non-horizontal going down ({@code end.y > start.y})</li>
     * </ul>
     */
    private static boolean isTopOrLeftEdge(@NotNull Vector2f start, @NotNull Vector2f end) {
        if (start.y() == end.y()) return start.x() > end.x();
        return end.y() > start.y();
    }

    /**
     * Sub-pixel precision for {@link #isInsideTriangleFixedPoint fixed-point coverage}. Default
     * {@code 256} matches typical GPU 8-bit sub-pixel precision. Tunable via
     * {@code -Dentity.fixedPointPrecision=N} for sweep experiments.
     */
    private static final int FIXED_POINT_PRECISION =
        Integer.parseInt(System.getProperty("entity.fixedPointPrecision", "256"));

    /**
     * Enables sub-pixel-fixed-point edge function rasterization. Default {@code true} - the
     * fixed-point path is the primary rasterization rule. {@link lib.minecraft.renderer.engine.ModelEngine
     * ModelEngine} routes inside-triangle tests through {@link #isInsideTriangleFixedPoint};
     * when false, the legacy float {@link #isInsideTriangleTopLeft} path is used.
     * <p>
     * Empirical result (2026-05-20): fixed-point at {@link #FIXED_POINT_PRECISION 1/256} sub-pixel
     * precision lifts snap-off parity from 78/92/95/95 to 86/97/99/99 buckets (full-fleet sweep),
     * exceeding the snap-on band-aid baseline (84/96/99/99). Disables exact-edge ambiguity at the
     * cube's shared-face vertical edges where asset's chain produced float-exact integer-aligned
     * vertices and vanilla's chain produced sub-ULP-offset vertices: integer math is deterministic
     * regardless of upstream FP drift. Witch dropped from 1.31 to 0.12, squid from 0.60 to 0.13,
     * glow_squid from 0.48 to 0.12 in the snap-off configuration.
     * <p>
     * Disable via {@code -Dentity.fixedPoint=false} when bisecting regressions.
     */
    public static final boolean FIXED_POINT_ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("entity.fixedPoint", "true"));

    /**
     * Sub-pixel-fixed-point inside-triangle test with top-left rule. Quantizes both vertex
     * coordinates and the sample point to a {@link #FIXED_POINT_PRECISION}-resolution grid, then
     * computes edge functions as 64-bit integer cross products. At exact-edge cases (edge function
     * {@code == 0}), applies the standard top-left rule via the edges' quantized directions.
     * <p>
     * This is the precision-hunt entry point for the witch x=21 residual: vanilla's GPU
     * rasterizes via sub-pixel-fixed-point edge functions, while our float
     * {@link #isInsideTriangleTopLeft} path fires {@code bary[i] == 0f} only when float
     * arithmetic happens to land exactly on zero - a fragile condition at axis-aligned cube edges
     * where some chains produce exact integer-multiple coordinates and others produce sub-ULP
     * offsets. Integer math makes the edge classification deterministic regardless of upstream
     * float drift.
     * <p>
     * Algorithm:
     * <ol>
     * <li>Round each vertex and the sample to integer multiples of {@code 1/FIXED_POINT_PRECISION}.</li>
     * <li>Compute edge functions {@code e_12, e_20, e_01} as long-integer 2D cross products.
     *     {@code e_12} pairs with {@code bary[0]} (opposite {@code v0}), etc.</li>
     * <li>Compute the triangle's signed-area determinant {@code denom} as a long-integer cross.</li>
     * <li>Inside test: all three edge functions have the same sign as {@code denom} (or are zero).</li>
     * <li>Top-left rule at edges where {@code e_ij == 0}: include only if the directed edge
     *     {@code v_i -> v_j} is a top or left edge per the existing CW Y-down classification
     *     (horizontal going left, or non-horizontal going down).</li>
     * </ol>
     *
     * @param v0 the triangle's first screen-space vertex
     * @param v1 the triangle's second screen-space vertex
     * @param v2 the triangle's third screen-space vertex
     * @param px the sample point's x coordinate (typically pixel-center {@code px + 0.5f})
     * @param py the sample point's y coordinate (typically pixel-center {@code py + 0.5f})
     * @return {@code true} if the sample is owned by this triangle under the fixed-point top-left rule
     */
    public static boolean isInsideTriangleFixedPoint(
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

        // Normalize signs so 'inside' means all edges >= 0 regardless of triangle winding.
        if (denom < 0L) { e12 = -e12; e20 = -e20; e01 = -e01; }

        if (e12 < 0L || e20 < 0L || e01 < 0L) return false;

        if (e12 == 0L && !isTopOrLeftEdge(v1, v2)) return false;
        if (e20 == 0L && !isTopOrLeftEdge(v2, v0)) return false;
        if (e01 == 0L && !isTopOrLeftEdge(v0, v1)) return false;
        return true;
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
