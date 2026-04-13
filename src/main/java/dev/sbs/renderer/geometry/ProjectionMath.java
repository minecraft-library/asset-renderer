package dev.sbs.renderer.geometry;

import dev.sbs.renderer.tensor.Vector2f;
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
        float denom = barycentricDenominator(a, b, c);
        if (denom == 0f) return new float[]{ 0f, 0f, 0f };

        float u = ((b.y() - c.y()) * (point.x() - c.x()) + (c.x() - b.x()) * (point.y() - c.y())) / denom;
        float v = ((c.y() - a.y()) * (point.x() - c.x()) + (a.x() - c.x()) * (point.y() - c.y())) / denom;
        float w = 1f - u - v;
        return new float[]{ u, v, w };
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
        int minX = Math.max(0, (int) Math.floor(Math.min(a.x(), Math.min(b.x(), c.x()))));
        int minY = Math.max(0, (int) Math.floor(Math.min(a.y(), Math.min(b.y(), c.y()))));
        int maxX = Math.min(canvasW - 1, (int) Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x()))));
        int maxY = Math.min(canvasH - 1, (int) Math.ceil(Math.max(a.y(), Math.max(b.y(), c.y()))));
        return new int[]{ minX, minY, maxX, maxY };
    }

}
