package lib.minecraft.renderer.geometry;

import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import dev.simplified.image.pixel.PixelBuffer;
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
 * @param translucent when {@code true} the source cube has texels with {@code 0 < alpha < 255}
 *     on visible faces (slime outer shell, glass/ice-like shells), as distinct from alpha-cutout
 *     no-cull cubes whose texels are strictly {@code alpha == 0} or {@code alpha == 255}. The
 *     rasterizer's preprocessing step sorts {@code translucent=true} triangles back-to-front so
 *     they blend in vanilla's painter's order; cutout no-cull triangles stay in emission order
 *     because their alpha-255 fragments depth-fail subsequent farther fragments correctly
 *     without sorting.
 * @param debugTag opaque identifier (typically {@code "bone:face"} or {@code "block:face"}) carried
 *     for diagnostic dumps only - the rasterizer attaches it to each pixel write when
 *     {@code -Dentity.pixel.dump=x0,y0,x1,y1} is set so per-pixel trace records show which kit
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
    boolean cullBackFaces,
    boolean emissive,
    boolean translucent,
    @Nullable String debugTag
) {

    /**
     * Convenience constructor that omits {@link #debugTag} for production call sites.
     */
    public VisibleTriangle(
        @NotNull Vector3f position0, @NotNull Vector3f position1, @NotNull Vector3f position2,
        @NotNull Vector2f uv0, @NotNull Vector2f uv1, @NotNull Vector2f uv2,
        @NotNull PixelBuffer texture, int tintArgb,
        @NotNull Vector3f normal, float shading,
        boolean cullBackFaces, boolean emissive, boolean translucent
    ) {
        this(position0, position1, position2, uv0, uv1, uv2, texture, tintArgb, normal, shading,
             cullBackFaces, emissive, translucent, null);
    }

    /**
     * Convenience constructor for non-translucent triangles (the historic 12-arg signature in use
     * before {@link #translucent} was added). Defaults {@code translucent} to {@code false} -
     * opaque solid cubes, alpha-cutout cubes (alpha 0 or 255 only), and emissive overlays.
     */
    public VisibleTriangle(
        @NotNull Vector3f position0, @NotNull Vector3f position1, @NotNull Vector3f position2,
        @NotNull Vector2f uv0, @NotNull Vector2f uv1, @NotNull Vector2f uv2,
        @NotNull PixelBuffer texture, int tintArgb,
        @NotNull Vector3f normal, float shading,
        boolean cullBackFaces, boolean emissive
    ) {
        this(position0, position1, position2, uv0, uv1, uv2, texture, tintArgb, normal, shading,
             cullBackFaces, emissive, false, null);
    }
}
