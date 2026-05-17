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
