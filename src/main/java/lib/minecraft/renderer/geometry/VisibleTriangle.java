package lib.minecraft.renderer.geometry;

import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

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
 * @param normal the surface normal, used for inventory lighting
 * @param shading the precomputed shading factor in {@code [0, 1]}
 * @param cullBackFaces when {@code true} the engine's back-face culling pass may discard this
 *     triangle; set to {@code false} for two-sided geometry such as leaves, glass panes, banners,
 *     and other semi-transparent or thin blocks that need to render both sides
 * @param emissive when {@code true} the rasterizer skips ambient shading (full-bright) and
 *     composites with {@code BlendMode.ADD} instead of {@code BlendMode.NORMAL}, matching
 *     vanilla Java's {@code RenderType.eyes} additive emissive pass. Used by overlay layers
 *     such as spider eyes and ender dragon eyes that brighten the underlying body texture
 *     instead of replacing or translucently masking it
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
    boolean cullBackFaces,
    boolean emissive
) {}
