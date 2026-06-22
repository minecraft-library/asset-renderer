package lib.minecraft.renderer.geometry;

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
 * @param glinted when {@code true} this triangle is worn-armor (or armor-trim) geometry that should
 *     receive the enchantment foil. The rasterizer records a per-pixel glint mask wherever a glinted
 *     fragment wins the depth test, and the glint compositor applies the foil only on those pixels,
 *     so the foil lands on the armor rather than the whole rendered silhouette. Always {@code false}
 *     for bare skin, blocks, items, and entity bodies
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
    boolean glinted,
    @Nullable String debugTag
) {

    /**
     * Convenience constructor for non-translucent triangles (the historic 12-arg signature in use
     * before {@link #translucent} was added). Defaults {@code translucent} and {@link #glinted} to
     * {@code false} - opaque solid cubes, alpha-cutout cubes (alpha 0 or 255 only), and emissive
     * overlays, none of which are worn-armor geometry.
     */
    public VisibleTriangle(
        @NotNull Vector3f position0, @NotNull Vector3f position1, @NotNull Vector3f position2,
        @NotNull Vector2f uv0, @NotNull Vector2f uv1, @NotNull Vector2f uv2,
        @NotNull PixelBuffer texture, int tintArgb,
        @NotNull Vector3f normal, float shading,
        boolean cullBackFaces, boolean emissive
    ) {
        this(position0, position1, position2, uv0, uv1, uv2, texture, tintArgb, normal, shading,
             cullBackFaces, emissive, false, false, null);
    }

    /**
     * Returns a copy of this triangle with {@link #glinted} set to {@code value}. Lets a builder flag
     * already-assembled geometry (e.g. armor cubes from a generic box builder) as foil-receiving
     * without rethreading the flag through the build path.
     *
     * @param value the glinted flag for the copy
     * @return a copy of this triangle with the given glinted flag
     */
    public @NotNull VisibleTriangle withGlinted(boolean value) {
        return new VisibleTriangle(this.position0, this.position1, this.position2,
            this.uv0, this.uv1, this.uv2, this.texture, this.tintArgb, this.normal, this.shading,
            this.cullBackFaces, this.emissive, this.translucent, value, this.debugTag);
    }
}
