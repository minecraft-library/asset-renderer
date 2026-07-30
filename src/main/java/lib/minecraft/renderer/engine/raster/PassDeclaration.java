package lib.minecraft.renderer.engine.raster;

import dev.simplified.image.pixel.BlendMode;
import org.jetbrains.annotations.NotNull;

/**
 * What vanilla declares about a render pass rather than about any one surface drawn through it - how a
 * surviving fragment is shaded, composited, scaled in opacity, written to the depth buffer, and ordered
 * against its siblings.
 *
 * <p>The five travel together from the shipped {@code pipeline} node an overlay declares, through the
 * build, onto every triangle the build emits, and no consumer varies one of them alone. Declaring them
 * once is what stops the tuple being transcribed at each hop - it was written out at three separate
 * records before this existed.
 *
 * <p><b>{@link #emissive}, {@link #writesDepth} and {@link #sorted} are three separate declarations
 * vanilla makes, not one.</b> Emissive is the {@code EMISSIVE} shader define; the depth write is the
 * pipeline's {@code DepthStencilState.writeDepth}; the sort is the render type's
 * {@code sortOnUpload}. Vanilla splits them every way, and the breeze splits them inside one entity -
 * its eyes pass does not write depth and its wind pass does. Inferring any of them from another loses
 * the passes that disagree.
 *
 * @param emissive when {@code true} the rasterizer skips ambient shading (full-bright) - matching
 *     vanilla Java's {@code RenderType.eyes} / {@code NO_CARDINAL_LIGHTING} pass used by overlay
 *     layers such as spider eyes and ender dragon eyes. Orthogonal to {@link #blend}: emissive
 *     controls shading (lit vs full-bright), {@code blend} controls colour composition (source-over
 *     vs additive)
 * @param blend the colour-composition mode the rasterizer composites the fragment with -
 *     {@link BlendMode#NORMAL} (source-over, the default for every body and texture-alpha
 *     translucent surface), {@link BlendMode#ADD} (the creeper / wither energy swirl) or
 *     {@link BlendMode#REPLACE} (a cutout pass, which writes the fragment over the destination
 *     rather than into it). Declared per-overlay by the {@code blend} JSON node; distinct from
 *     {@link #emissive} (shading) and from the sorting a partial-alpha surface asks for
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
public record PassDeclaration(boolean emissive, @NotNull BlendMode blend, float alpha,
                              boolean writesDepth, boolean sorted) {

    /**
     * The pass every body, block, item and texture-alpha overlay draws through - shaded, compositing
     * {@link BlendMode#NORMAL source-over} at full opacity, writing depth and drawn in emission order.
     * Only an overlay carrying an explicit {@code pipeline} JSON node declares anything else.
     */
    public static final @NotNull PassDeclaration DEFAULT =
        new PassDeclaration(false, BlendMode.NORMAL, 1f, true, false);

    /**
     * Returns this declaration with {@link #emissive()} set to the given value, or itself when the value
     * already matches - so re-declaring a pass that is already shaded costs nothing. Asked by the
     * geometry copies that re-frame a triangle and keep only its full-bright flag.
     *
     * @param value the full-bright flag for the result
     * @return this declaration when {@code value} already matches, else a copy carrying it
     */
    public @NotNull PassDeclaration withEmissive(boolean value) {
        return value == this.emissive
            ? this
            : new PassDeclaration(value, this.blend, this.alpha, this.writesDepth, this.sorted);
    }

}
