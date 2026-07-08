package lib.minecraft.renderer.engine.raster;

import dev.simplified.image.pixel.BlendMode;
import org.jetbrains.annotations.NotNull;

/**
 * The surface concerns the rasterizer reads off each {@link VisibleTriangle}, grouped into one value
 * carried by the triangle: four boolean flags plus the overlay's colour-composition mode and opacity
 * multiplier.
 * <p>
 * A triangle's surface character is declared once at construction rather than threaded as loose
 * flags. The {@code with*} helpers compose a new traits value for a derived triangle; they never
 * mutate a built {@code VisibleTriangle}. The four-argument constructor is the common case - opaque
 * or texture-alpha geometry that composites with the standard {@link BlendMode#NORMAL source-over}
 * blend at full opacity; only overlays that declare an explicit {@code blend} / {@code alpha} node in
 * {@code entity_models.json} carry a non-default {@link #blend} / {@link #alpha}.
 *
 * @param cullBackFaces when {@code true} the engine's back-face culling pass may discard this
 *     triangle; set to {@code false} for two-sided geometry such as leaves, glass panes, banners, and
 *     other semi-transparent or thin blocks that need both sides rendered
 * @param emissive when {@code true} the rasterizer skips ambient shading (full-bright) - matching
 *     vanilla Java's {@code RenderType.eyes} / {@code NO_CARDINAL_LIGHTING} pass used by overlay
 *     layers such as spider eyes and ender dragon eyes. Orthogonal to {@link #blend}: emissive
 *     controls shading (lit vs full-bright), {@code blend} controls colour composition (source-over
 *     vs additive)
 * @param translucent when {@code true} the source cube has texels with {@code 0 < alpha < 255} on
 *     visible faces (slime outer shell, glass/ice-like shells), as distinct from alpha-cutout no-cull
 *     cubes whose texels are strictly {@code alpha == 0} or {@code alpha == 255}; the rasterizer sorts
 *     {@code translucent=true} triangles back-to-front so they blend in vanilla's painter's order
 * @param glinted when {@code true} this triangle is worn-armor (or armor-trim) geometry that should
 *     receive the enchantment foil; the rasterizer records a per-pixel glint mask wherever a glinted
 *     fragment wins the depth test, and the glint compositor applies the foil only on those pixels, so
 *     it lands on the armor rather than the whole silhouette. Always {@code false} for bare skin,
 *     blocks, items, and entity bodies
 * @param blend the colour-composition mode the rasterizer composites this fragment with -
 *     {@link BlendMode#NORMAL} (source-over, the default for every body / cutout / texture-alpha
 *     translucent surface) or {@link BlendMode#ADD} (additive glow, the creeper / wither energy
 *     swirl). Declared per-overlay by the {@code blend} JSON node; distinct from {@link #emissive}
 *     (shading) and {@link #translucent} (painter's-order sorting)
 * @param alpha the per-fragment opacity multiplier in {@code [0, 1]} applied to the sampled texel's
 *     alpha before the {@link #blend} composite - {@code 1.0} (no-op) for every surface except an
 *     overlay declaring an explicit {@code alpha} JSON node (the warden pulsating-spots glow at
 *     {@code 0.25}). Rides its own path because a fractional layer opacity cannot be carried in the
 *     overlay tint's alpha byte (the {@code MULTIPLY} tint blend preserves the texel's alpha)
 */
public record SurfaceTraits(boolean cullBackFaces, boolean emissive,
                            boolean translucent, boolean glinted,
                            @NotNull BlendMode blend, float alpha) {

    /**
     * Opaque, back-face-culled, non-emissive body geometry compositing with the standard source-over
     * blend at full opacity - the default for skin, blocks, and entity bodies.
     */
    public static final SurfaceTraits OPAQUE_BODY = new SurfaceTraits(true, false, false, false);

    /**
     * Constructs traits for the common case - {@link BlendMode#NORMAL source-over} composition at full
     * opacity ({@code alpha 1.0}). Only overlays carrying an explicit {@code blend} / {@code alpha}
     * JSON node use the canonical six-argument constructor; every other call site (blocks, bodies,
     * cutout and texture-alpha overlays) is byte-identical to the pre-blend-node pipeline.
     *
     * @param cullBackFaces the back-face-culling flag
     * @param emissive the full-bright (skip-shading) flag
     * @param translucent the partial-alpha painter's-order-sort flag
     * @param glinted the enchantment-foil flag
     */
    public SurfaceTraits(boolean cullBackFaces, boolean emissive, boolean translucent, boolean glinted) {
        this(cullBackFaces, emissive, translucent, glinted, BlendMode.NORMAL, 1f);
    }

    /**
     * Returns a copy of these traits with {@link #cullBackFaces()} set to the given value.
     *
     * @param value the cull-back-faces flag for the copy
     * @return a copy with the given cull-back-faces flag
     */
    public @NotNull SurfaceTraits withCullBackFaces(boolean value) {
        return new SurfaceTraits(value, this.emissive, this.translucent, this.glinted, this.blend, this.alpha);
    }

    /**
     * Returns a copy of these traits with {@link #glinted()} set to the given value.
     *
     * @param value the glinted flag for the copy
     * @return a copy with the given glinted flag
     */
    public @NotNull SurfaceTraits withGlinted(boolean value) {
        return new SurfaceTraits(this.cullBackFaces, this.emissive, this.translucent, value, this.blend, this.alpha);
    }

    /**
     * Returns a copy of these traits with {@link #emissive()} set to the given value.
     *
     * @param value the emissive flag for the copy
     * @return a copy with the given emissive flag
     */
    public @NotNull SurfaceTraits withEmissive(boolean value) {
        return new SurfaceTraits(this.cullBackFaces, value, this.translucent, this.glinted, this.blend, this.alpha);
    }
}
