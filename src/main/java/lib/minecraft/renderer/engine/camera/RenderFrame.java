package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

/**
 * The frame a geometry build is fitted through - the model-space point that maps to the canvas centre,
 * the model-units-to-NDC scale applied after centring on it, and the per-render vertex pre-scale folded
 * in ahead of both.
 *
 * <p>The three are one value. Every build boundary that takes one takes all three, in this order, and
 * no caller varies one without the others - the anchor alone was spelled three different ways at the
 * boundaries that passed it loose, and they were one anchor.
 *
 * <p><b>A frame performs no arithmetic, and that is what makes carrying it free.</b> Its consumers
 * compose the two scales differently - a per-axis {@code ndcScale * (modelScale * v - anchor)} against a
 * pre-multiplied {@code ndcScale * modelScale} baked into one matrix - so a member handing back the two
 * combined would give one of them a float it does not compute today. This hands back exactly what it was
 * given.
 *
 * @param anchor the model-space point that maps to the canvas centre
 * @param ndcScale the model-units-to-NDC scale applied after centring on {@code anchor}
 * @param modelScale the per-render vertex pre-scale folded in before the NDC scale (vanilla's combined
 *     renderer-scale + state-scale chain)
 */
public record RenderFrame(@NotNull Vector3f anchor, float ndcScale, float modelScale) {

    /**
     * The frame that fits nothing - no centring, unit NDC scale, unit pre-scale - so a build through it
     * emits geometry in the model's own units. What a build that seats its result in a frame of its own
     * afterwards asks for.
     */
    public static final @NotNull RenderFrame IDENTITY = new RenderFrame(Vector3f.ZERO, 1f, 1f);

}
