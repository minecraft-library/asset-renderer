package dev.sbs.renderer.geometry;

import dev.sbs.renderer.tensor.Vector2f;
import org.jetbrains.annotations.NotNull;

/**
 * A triangle whose vertices have been projected into screen coordinates, carrying a per-vertex
 * depth value so the rasterizer can apply the depth test and barycentric interpolation.
 *
 * @param v0 the first vertex in screen space
 * @param v1 the second vertex in screen space
 * @param v2 the third vertex in screen space
 * @param depth0 the camera-space depth of {@code v0}
 * @param depth1 the camera-space depth of {@code v1}
 * @param depth2 the camera-space depth of {@code v2}
 */
public record Projection(
    @NotNull Vector2f v0,
    @NotNull Vector2f v1,
    @NotNull Vector2f v2,
    float depth0,
    float depth1,
    float depth2
) {}
