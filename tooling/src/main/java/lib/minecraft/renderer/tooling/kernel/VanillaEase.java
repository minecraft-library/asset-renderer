package lib.minecraft.renderer.tooling.kernel;

import dev.simplified.annotations.UtilityClass;

/**
 * Bit-exact reproductions of the eight vanilla {@code net.minecraft.util.Ease} curves a skeletal
 * pose evaluation reaches - this build's half of a pair.
 *
 * <p>Vanilla declares thirty easings; these eight are the ones a model's pose arithmetic can reach,
 * the rest being first-person and pose-stack paths no model body enters. An easing outside this set
 * is a finding rather than an omission - the extractor names the function it met, so a curve that
 * becomes reachable arrives as a failed walk and not as a silently wrong shape.
 *
 * <p>Each body is transcribed operand-for-operand from the shipped client bytecode. The order
 * matters twice over: float arithmetic is neither associative nor distributive, and half of these
 * curves cross into {@code double} for a {@link Math#pow} or a {@link Math#sqrt} and narrow once on
 * the way out, so where the narrowing sits is part of the value.
 *
 * <p><b>Why it exists twice.</b> The renderer declares the same type and this build cannot see it,
 * the dependency running the other way. So each side carries its own copy and each pins it against
 * the same independently declared constants, which is what makes a drift fail on the side that
 * moved rather than surfacing later as a moved pixel. This copy is what folds an easing whose
 * argument resolves to a literal at extraction; the renderer's copy carries the residue.
 *
 * <p><b>The sine here is the sampled one.</b> {@link #inOutSine} reaches vanilla's table-sampled
 * cosine through {@link VanillaMth#mthCos}, not {@link Math#cos}, while {@link #inOutElastic}
 * reaches libm {@link Math#sin} directly. Both are vanilla's own choices at those two sites and the
 * two contracts are never unified.
 *
 * @see VanillaMth
 */
@UtilityClass
public final class VanillaEase {

    /** Vanilla's single-precision pi, the literal its sine easing multiplies by. */
    private static final float PI = 3.1415927f;

    /** The angular frequency vanilla's elastic easing samples its carrier sine at. */
    private static final double ELASTIC_FREQUENCY = 1.3962634801864624;

    /** The phase offset that puts the elastic carrier's zero crossing at the midpoint. */
    private static final double ELASTIC_PHASE = 11.125;

    /**
     * Eases in along a quarter circle.
     *
     * @param progress the normalized position along the curve
     * @return the eased position
     */
    public static float inCirc(float progress) {
        return (float) -Math.sqrt(1f - progress * progress) + 1f;
    }

    /**
     * Eases in quadratically.
     *
     * @param progress the normalized position along the curve
     * @return the eased position
     */
    public static float inQuad(float progress) {
        return progress * progress;
    }

    /**
     * Eases out along a quarter circle.
     *
     * @param progress the normalized position along the curve
     * @return the eased position
     */
    public static float outCirc(float progress) {
        return (float) Math.sqrt(1f - VanillaMth.square(progress - 1f));
    }

    /**
     * Eases out cubically.
     *
     * @param progress the normalized position along the curve
     * @return the eased position
     */
    public static float outCubic(float progress) {
        return 1f - VanillaMth.cube(1f - progress);
    }

    /**
     * Eases out quartically, reaching the fourth power as a square of a square.
     *
     * @param progress the normalized position along the curve
     * @return the eased position
     */
    public static float outQuart(float progress) {
        return 1f - VanillaMth.square(VanillaMth.square(1f - progress));
    }

    /**
     * Eases in and out along a half cosine, sampled from the vanilla sine table.
     *
     * @param progress the normalized position along the curve
     * @return the eased position
     */
    public static float inOutSine(float progress) {
        return -(VanillaMth.mthCos(PI * progress) - 1f) / 2f;
    }

    /**
     * Eases in and out exponentially, holding both endpoints exactly.
     *
     * <p>The endpoint arms answer before the exponential is evaluated, so {@code 0f} and {@code 1f}
     * come back as themselves rather than as the limit the power approaches.
     *
     * @param progress the normalized position along the curve
     * @return the eased position
     */
    public static float inOutExpo(float progress) {
        if (progress < 0.5f)
            return progress == 0f ? 0f : (float) (Math.pow(2.0, 20.0 * progress - 10.0) / 2.0);
        return progress == 1f ? 1f : (float) ((2.0 - Math.pow(2.0, -20.0 * progress + 10.0)) / 2.0);
    }

    /**
     * Eases in and out elastically - a decaying exponential carrying a libm sine.
     *
     * @param progress the normalized position along the curve
     * @return the eased position
     */
    public static float inOutElastic(float progress) {
        if (progress == 0f) return 0f;
        if (progress == 1f) return 1f;
        double carrier = Math.sin((20.0 * progress - ELASTIC_PHASE) * ELASTIC_FREQUENCY);
        if (progress < 0.5f)
            return (float) (-(Math.pow(2.0, 20.0 * progress - 10.0) * carrier) / 2.0);
        return (float) (Math.pow(2.0, -20.0 * progress + 10.0) * carrier / 2.0 + 1.0);
    }

}
