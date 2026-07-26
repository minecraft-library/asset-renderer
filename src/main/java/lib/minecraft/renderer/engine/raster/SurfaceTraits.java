package lib.minecraft.renderer.engine.raster;

import dev.simplified.image.pixel.BlendMode;
import org.jetbrains.annotations.NotNull;

/**
 * The surface concerns the rasterizer reads off each {@link VisibleTriangle}, grouped into one value
 * carried by the triangle: six boolean flags plus the overlay's colour-composition mode and opacity
 * multiplier.
 * <p>
 * A triangle's surface character is declared once at construction rather than threaded as loose
 * flags. The {@code with*} helpers compose a new traits value for a derived triangle; they never
 * mutate a built {@code VisibleTriangle}. The four-argument constructor is the common case - opaque
 * or texture-alpha geometry that composites with the standard {@link BlendMode#NORMAL source-over}
 * blend at full opacity, writes depth and draws in emission order; only overlays that declare an
 * explicit {@code pipeline} node in {@code entity_models.json} carry a non-default {@link #blend} /
 * {@link #alpha} / {@link #writesDepth} / {@link #sorted}.
 * <p>
 * <b>{@link #emissive}, {@link #writesDepth} and {@link #sorted} are three separate declarations
 * vanilla makes, not one.</b> Emissive is the {@code EMISSIVE} shader define; the depth write is the
 * pipeline's {@code DepthStencilState.writeDepth}; the sort is the render type's
 * {@code sortOnUpload}. Vanilla splits them every way: the eyes pass is emissive and does not write
 * depth, the energy swirl is emissive and does, the slime shell is neither emissive nor unsorted.
 * Inferring any of them from another loses the passes that disagree.
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
 *     {@link BlendMode#NORMAL} (source-over, the default for every body and texture-alpha
 *     translucent surface), {@link BlendMode#ADD} (the creeper / wither energy swirl) or
 *     {@link BlendMode#REPLACE} (a cutout pass, which writes the fragment over the destination
 *     rather than into it). Declared per-overlay by the {@code blend} JSON node; distinct from
 *     {@link #emissive} (shading) and {@link #translucent} (painter's-order sorting)
 * @param alpha the per-fragment opacity multiplier in {@code [0, 1]} applied to the sampled texel's
 *     alpha before the {@link #blend} composite - {@code 1.0} (no-op) for every surface except an
 *     overlay declaring an explicit {@code alpha} JSON node (the warden pulsating-spots glow at
 *     {@code 0.25}). Rides its own path because a fractional layer opacity cannot be carried in the
 *     overlay tint's alpha byte (the {@code MULTIPLY} tint blend preserves the texel's alpha)
 * @param writesDepth whether a surviving fragment writes its depth, from the pass's
 *     {@code DepthStencilState.writeDepth}. True for every body and for the energy swirl, false for
 *     the eyes-style passes vanilla registers with an explicit write-disabled state. A pass that
 *     writes depth occludes its own later fragments, which is what stops a two-sided inflated shell
 *     accumulating its far faces on top of its near ones
 * @param sorted whether the pass's quads are drawn back-to-front by camera-space centroid, from the
 *     render type's {@code sortOnUpload}. Only meaningful alongside {@link #writesDepth}: it is the
 *     order that decides which of two overlapping fragments writes first, and vanilla's own order is
 *     a per-quad approximation, so a quad drawn later can still lose the test over part of its area
 */
public record SurfaceTraits(boolean cullBackFaces, boolean emissive,
                            boolean translucent, boolean glinted,
                            @NotNull BlendMode blend, float alpha,
                            boolean writesDepth, boolean sorted) {

    /**
     * Opaque, back-face-culled, non-emissive body geometry compositing with the standard source-over
     * blend at full opacity - the default for skin, blocks, and entity bodies.
     */
    public static final SurfaceTraits OPAQUE_BODY = new SurfaceTraits(true, false, false, false);

    /**
     * Constructs traits for the common case - {@link BlendMode#NORMAL source-over} composition at
     * full opacity ({@code alpha 1.0}), writing depth and drawn in emission order. Only overlays
     * carrying an explicit {@code pipeline} JSON node use the canonical eight-argument constructor;
     * every other call site (blocks, bodies, texture-alpha overlays) uses this.
     *
     * @param cullBackFaces the back-face-culling flag
     * @param emissive the full-bright (skip-shading) flag
     * @param translucent the partial-alpha painter's-order-sort flag
     * @param glinted the enchantment-foil flag
     */
    public SurfaceTraits(boolean cullBackFaces, boolean emissive, boolean translucent, boolean glinted) {
        this(cullBackFaces, emissive, translucent, glinted, BlendMode.NORMAL, 1f, true, false);
    }

    /**
     * Returns a copy of these traits with {@link #cullBackFaces()} set to the given value.
     *
     * @param value the cull-back-faces flag for the copy
     * @return a copy with the given cull-back-faces flag
     */
    public @NotNull SurfaceTraits withCullBackFaces(boolean value) {
        return new SurfaceTraits(value, this.emissive, this.translucent, this.glinted, this.blend, this.alpha,
                                 this.writesDepth, this.sorted);
    }

    /**
     * Returns a copy of these traits with {@link #glinted()} set to the given value.
     *
     * @param value the glinted flag for the copy
     * @return a copy with the given glinted flag
     */
    public @NotNull SurfaceTraits withGlinted(boolean value) {
        return new SurfaceTraits(this.cullBackFaces, this.emissive, this.translucent, value, this.blend, this.alpha,
                                 this.writesDepth, this.sorted);
    }

    /**
     * Returns a copy of these traits with {@link #emissive()} set to the given value.
     *
     * @param value the emissive flag for the copy
     * @return a copy with the given emissive flag
     */
    public @NotNull SurfaceTraits withEmissive(boolean value) {
        return new SurfaceTraits(this.cullBackFaces, value, this.translucent, this.glinted, this.blend, this.alpha,
                                 this.writesDepth, this.sorted);
    }
}
