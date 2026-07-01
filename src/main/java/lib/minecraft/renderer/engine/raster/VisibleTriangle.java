package lib.minecraft.renderer.engine.raster;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A single triangle awaiting rasterization, carrying enough information for the texture sampler,
 * depth test, and lighting in one compact record.
 *
 * @param position0 the first vertex position in model space
 * @param position1 the second vertex position in model space
 * @param position2 the third vertex position in model space
 * @param uv0 the first vertex UV coordinate (in {@code [0, 1]} range)
 * @param uv1 the second vertex UV coordinate
 * @param uv2 the third vertex UV coordinate
 * @param texture the texture this triangle samples from
 * @param tintArgb the ARGB tint applied to each sampled pixel, or {@code 0xFFFFFFFF} for no tint
 * @param normal the model-space surface normal; transformed with the triangle to drive per-face
 *     relighting (block-icon and item-3D passes light by this normal, snapped to the nearest
 *     cardinal for block quads)
 * @param shading the precomputed shading factor in {@code [0, 1]} baked at quad-emit time, or a
 *     disabled sentinel for full-bright faces; multiplies the sampled RGB before compositing
 * @param traits the surface traits (back-face culling, emissive, translucent, glinted) the rasterizer
 *     reads to drive culling, blend mode, translucent sorting, and the glint mask
 * @param debugTag opaque identifier (typically {@code "bone:face"} or {@code "block:face"}) carried
 *     for diagnostic dumps only - the rasterizer attaches it to each pixel write when
 *     {@code -Dasset.entity.pixel.dump=x0,y0,x1,y1} is set so per-pixel trace records show which kit
 *     triangle won the depth test. Always {@code null} in non-diagnostic builds; do not branch
 *     rendering on it.
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
    @NotNull SurfaceTraits traits,
    @Nullable String debugTag
) {

    /**
     * Convenience constructor for the common case of a triangle with no diagnostic tag, defaulting
     * {@link #debugTag} to {@code null}.
     */
    public VisibleTriangle(
        @NotNull Vector3f position0, @NotNull Vector3f position1, @NotNull Vector3f position2,
        @NotNull Vector2f uv0, @NotNull Vector2f uv1, @NotNull Vector2f uv2,
        @NotNull PixelBuffer texture, int tintArgb,
        @NotNull Vector3f normal, float shading,
        @NotNull SurfaceTraits traits
    ) {
        this(position0, position1, position2, uv0, uv1, uv2, texture, tintArgb, normal, shading,
             traits, null);
    }
}
