package dev.sbs.renderer.engine;

import dev.sbs.renderer.math.Vector2f;
import dev.sbs.renderer.math.Vector3f;
import dev.simplified.image.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * A single triangle awaiting rasterization, carrying enough information for the texture sampler,
 * depth test, lighting, and priority sort in one compact record.
 *
 * @param position0 the first vertex position in model space
 * @param position1 the second vertex position in model space
 * @param position2 the third vertex position in model space
 * @param uv0 the first vertex UV coordinate (in {@code [0, 1]} range)
 * @param uv1 the second vertex UV coordinate
 * @param uv2 the third vertex UV coordinate
 * @param texture the texture this triangle samples from
 * @param tintArgb the ARGB tint applied to each sampled pixel, or {@code 0xFFFFFFFF} for no tint
 * @param normal the surface normal, used for inventory lighting
 * @param shading the precomputed shading factor in {@code [0, 1]}
 * @param renderPriority the sort key used to break ties during painter's-algorithm ordering
 * @param cullBackFaces when {@code true} the engine's back-face culling pass may discard this
 *     triangle; set to {@code false} for two-sided geometry such as leaves, glass panes, banners,
 *     and other semi-transparent or thin blocks that need to render both sides
 */
public record VisibleTriangle(
    @NotNull Vector3f position0,
    @NotNull Vector3f position1,
    @NotNull Vector3f position2,
    @NotNull Vector2f uv0,
    @NotNull Vector2f uv1,
    @NotNull Vector2f uv2,
    @NotNull PixelBuffer texture,
    int tintArgb,
    @NotNull Vector3f normal,
    float shading,
    int renderPriority,
    boolean cullBackFaces
) {

    /**
     * Convenience constructor for cullable triangles (the common case). Sets
     * {@link #cullBackFaces()} to {@code true}.
     *
     * @param position0 the first vertex position in model space
     * @param position1 the second vertex position in model space
     * @param position2 the third vertex position in model space
     * @param uv0 the first vertex UV coordinate
     * @param uv1 the second vertex UV coordinate
     * @param uv2 the third vertex UV coordinate
     * @param texture the texture this triangle samples from
     * @param tintArgb the ARGB tint applied to each sampled pixel
     * @param normal the surface normal
     * @param shading the precomputed shading factor
     * @param renderPriority the tie-break sort key
     */
    public VisibleTriangle(
        @NotNull Vector3f position0,
        @NotNull Vector3f position1,
        @NotNull Vector3f position2,
        @NotNull Vector2f uv0,
        @NotNull Vector2f uv1,
        @NotNull Vector2f uv2,
        @NotNull PixelBuffer texture,
        int tintArgb,
        @NotNull Vector3f normal,
        float shading,
        int renderPriority
    ) {
        this(position0, position1, position2, uv0, uv1, uv2, texture, tintArgb, normal, shading, renderPriority, true);
    }

    /**
     * Returns the mean depth of the three vertices along the given axis, used as the painter's
     * algorithm sort key.
     *
     * @return the average Z depth in model space
     */
    public float averageDepth() {
        return (this.position0.getZ() + this.position1.getZ() + this.position2.getZ()) / 3f;
    }

}
