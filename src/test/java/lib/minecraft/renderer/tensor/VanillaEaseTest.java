package lib.minecraft.renderer.tensor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bit-parity pins for {@link VanillaEase} against an independent re-derivation of vanilla
 * {@code net.minecraft.util.Ease}.
 *
 * <p>Each sweep assertion writes the curve's arithmetic out a second time in this file rather than
 * calling the production method, so the two transcriptions have to agree operand-for-operand. The
 * endpoint assertions are the independent half: they come from what each curve is defined to do at
 * its ends rather than from either transcription.
 *
 * <p>Every assertion compares {@link Float#floatToIntBits}, never {@code ==} with a tolerance. A
 * tolerance would pass exactly the near-misses these pins exist to catch.
 */
@DisplayName("VanillaEase bit-parity against an independent vanilla Ease re-derivation")
class VanillaEaseTest {

    /** Vanilla's single-precision pi - re-declared here, never shared. */
    private static final float PI = 3.1415927f;

    @Test
    @DisplayName("every curve reproduces its transcription across a sweep")
    void everyCurveIsBitIdentical() {
        for (float t : sweep()) {
            pin("inCirc", (float) -Math.sqrt(1f - t * t) + 1f, VanillaEase.inCirc(t), t);
            pin("inQuad", t * t, VanillaEase.inQuad(t), t);
            pin("outCirc", (float) Math.sqrt(1f - (t - 1f) * (t - 1f)), VanillaEase.outCirc(t), t);
            pin("outCubic", 1f - (1f - t) * (1f - t) * (1f - t), VanillaEase.outCubic(t), t);
            pin("outQuart", 1f - sq(sq(1f - t)), VanillaEase.outQuart(t), t);
            pin("inOutSine", -(VanillaMth.mthCos(PI * t) - 1f) / 2f, VanillaEase.inOutSine(t), t);
            pin("inOutExpo", expo(t), VanillaEase.inOutExpo(t), t);
            pin("inOutElastic", elastic(t), VanillaEase.inOutElastic(t), t);
        }
    }

    @Test
    @DisplayName("the curves that are defined to hold their endpoints hold them exactly")
    void endpointsAreExact() {
        // Seven of the eight are defined to run 0 to 1. inOutSine is deliberately absent: it reads
        // the sampled cosine table, whose value a quarter turn in is not exactly 1f, so pinning it
        // to a round 0f would assert against libm rather than against vanilla.
        assertEquals(0f, VanillaEase.inCirc(0f), "inCirc(0)");
        assertEquals(1f, VanillaEase.inCirc(1f), "inCirc(1)");
        assertEquals(0f, VanillaEase.inQuad(0f), "inQuad(0)");
        assertEquals(1f, VanillaEase.inQuad(1f), "inQuad(1)");
        assertEquals(0f, VanillaEase.outCirc(0f), "outCirc(0)");
        assertEquals(1f, VanillaEase.outCirc(1f), "outCirc(1)");
        assertEquals(0f, VanillaEase.outCubic(0f), "outCubic(0)");
        assertEquals(1f, VanillaEase.outCubic(1f), "outCubic(1)");
        assertEquals(0f, VanillaEase.outQuart(0f), "outQuart(0)");
        assertEquals(1f, VanillaEase.outQuart(1f), "outQuart(1)");
        assertEquals(0f, VanillaEase.inOutExpo(0f), "inOutExpo(0)");
        assertEquals(1f, VanillaEase.inOutExpo(1f), "inOutExpo(1)");
        assertEquals(0f, VanillaEase.inOutElastic(0f), "inOutElastic(0)");
        assertEquals(1f, VanillaEase.inOutElastic(1f), "inOutElastic(1)");
    }

    @Test
    @DisplayName("the exponential easing crosses its midpoint exactly halfway")
    void exponentialMidpointIsExact() {
        // The upper arm at 0.5 is (2 - 2^0) / 2, which is 0.5 with no rounding anywhere. It pins
        // that the two arms are split at 0.5 and that the upper one is the one 0.5 takes.
        assertEquals(0.5f, VanillaEase.inOutExpo(0.5f), "inOutExpo(0.5)");
    }

    @Test
    @DisplayName("the sine easing samples the table, so it is not the libm curve")
    void sineEasingIsNotLibm() {
        // If these ever agree everywhere, mthCos has been quietly replaced by Math.cos and the
        // transcription pin above would still pass, because the test would have moved with it.
        int agreements = 0;
        for (float t : sweep()) {
            float libm = (float) (-(Math.cos(PI * t) - 1f) / 2f);
            if (Float.floatToIntBits(libm) == Float.floatToIntBits(VanillaEase.inOutSine(t))) agreements++;
        }
        assertTrue(agreements < sweep().length / 2,
            "expected the sampled table to disagree with libm on most of the sweep, agreed on " + agreements);
    }

    @Test
    @DisplayName("the elastic easing reaches libm sine, so it is not the sampled curve")
    void elasticEasingIsNotTabulated() {
        int agreements = 0;
        for (float t : sweep()) {
            if (t == 0f || t == 1f) continue;
            double carrier = VanillaMth.mthSin((20.0 * t - 11.125) * 1.3962634801864624);
            float tabulated = t < 0.5f
                ? (float) (-(Math.pow(2.0, 20.0 * t - 10.0) * carrier) / 2.0)
                : (float) (Math.pow(2.0, -20.0 * t + 10.0) * carrier / 2.0 + 1.0);
            if (Float.floatToIntBits(tabulated) == Float.floatToIntBits(VanillaEase.inOutElastic(t))) agreements++;
        }
        assertTrue(agreements < sweep().length / 2,
            "expected libm to disagree with the sampled table on most of the sweep, agreed on " + agreements);
    }

    /** Vanilla's square, re-declared so the quartic transcription does not borrow the production one. */
    private static float sq(float value) {
        return value * value;
    }

    private static float expo(float t) {
        if (t < 0.5f) return t == 0f ? 0f : (float) (Math.pow(2.0, 20.0 * t - 10.0) / 2.0);
        return t == 1f ? 1f : (float) ((2.0 - Math.pow(2.0, -20.0 * t + 10.0)) / 2.0);
    }

    private static float elastic(float t) {
        if (t == 0f) return 0f;
        if (t == 1f) return 1f;
        double carrier = Math.sin((20.0 * t - 11.125) * 1.3962634801864624);
        if (t < 0.5f) return (float) (-(Math.pow(2.0, 20.0 * t - 10.0) * carrier) / 2.0);
        return (float) (Math.pow(2.0, -20.0 * t + 10.0) * carrier / 2.0 + 1.0);
    }

    /** Endpoints, both arms of every split, and enough interior to catch a reordered operand. */
    private static float[] sweep() {
        float[] out = new float[65];
        for (int i = 0; i < out.length; i++) out[i] = i / 64f;
        return out;
    }

    private static void pin(String name, float expected, float actual, float at) {
        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual), name + "(" + at + ")");
    }

}
