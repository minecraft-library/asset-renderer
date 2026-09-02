package lib.minecraft.renderer.tooling.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bit-parity pins for {@link VanillaMth} against an independent re-derivation of vanilla
 * {@code net.minecraft.util.Mth}.
 *
 * <p>The trigonometry pair is deliberately the same assertion the renderer build makes about its
 * own copy of the table: both re-declare the index-per-radian constant rather than importing one,
 * and both rebuild the sampled value from {@link Math#sin(double)} at the resolved index. Two
 * builds that cannot see each other therefore hold one contract, and a drift in either fails on
 * its own side rather than surfacing as a moved pixel.
 *
 * <p>Every assertion compares {@link Float#floatToIntBits}, never {@code ==} with a tolerance. A
 * tolerance would pass exactly the near-misses these pins exist to catch.
 */
@DisplayName("VanillaMth bit-parity against an independent vanilla Mth re-derivation")
class VanillaMthTest {

    /** Vanilla's hardcoded {@code 65536 / (2 * PI)} - re-declared here, never shared. */
    private static final double RATIO = 10430.378350470453;

    @Test
    @DisplayName("mthSin samples the table bit-for-bit across a sweep")
    void mthSinBitParity() {
        for (double angle : sweep()) {
            int index = (int) (long) (angle * RATIO) & 65535;
            float expected = (float) Math.sin((double) index / RATIO);
            assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(VanillaMth.mthSin(angle)),
                "mthSin(" + angle + ")");
        }
    }

    @Test
    @DisplayName("mthCos samples the table a quarter turn ahead, bit-for-bit")
    void mthCosBitParity() {
        for (double angle : sweep()) {
            int index = (int) (long) (angle * RATIO + 16384.0) & 65535;
            float expected = (float) Math.sin((double) index / RATIO);
            assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(VanillaMth.mthCos(angle)),
                "mthCos(" + angle + ")");
        }
    }

    @Test
    @DisplayName("the table is sampled, not computed - the libm value is a different number")
    void theTableIsNotLibm() {
        // A sampled sine differs from libm almost everywhere; if these ever agree the lookup has
        // been quietly replaced by a call and the pin above would still pass.
        int agreements = 0;
        for (double angle : sweep())
            if (Float.floatToIntBits(VanillaMth.mthSin(angle)) == Float.floatToIntBits((float) Math.sin(angle)))
                agreements++;
        assertEquals(true, agreements < sweep().length / 2,
            "expected the sampled table to disagree with libm on most of the sweep, agreed on " + agreements);
    }

    @Test
    @DisplayName("the wither ribcage angle resolves to the values its baked geometry was built from")
    void witherRibcageAnchor() {
        // WitherBossModel.createBodyLayer hands PartPose.offsetAndRotation the literal 0.20420352f
        // and derives its tail pivot from cos/sin of that angle; entity_geometry.json carries the
        // results. Anchoring here ties the table to numbers already shipped.
        double ribcage = 0.20420352f;
        assertEquals(Float.floatToIntBits(16.692408F),
            Float.floatToIntBits(6.9F + VanillaMth.mthCos(ribcage) * 10.0F), "tail pivot y");
        assertEquals(Float.floatToIntBits(1.5270092F),
            Float.floatToIntBits(-0.5F + VanillaMth.mthSin(ribcage) * 10.0F), "tail pivot z");
    }

    @Test
    @DisplayName("cos of zero is exactly one and sin of zero exactly zero")
    void originIsExact() {
        assertEquals(Float.floatToIntBits(1.0F), Float.floatToIntBits(VanillaMth.mthCos(0.0)), "mthCos(0)");
        assertEquals(Float.floatToIntBits(0.0F), Float.floatToIntBits(VanillaMth.mthSin(0.0)), "mthSin(0)");
    }

    @Test
    @DisplayName("lerp reaches its endpoints the way the client's operand order does")
    void lerpEndpoints() {
        // start + delta * (end - start) does not have to land exactly on end at delta one, and
        // whether it does is part of the contract rather than a rounding detail to smooth over.
        float start = 0.1F;
        float end = 0.3F;
        assertEquals(Float.floatToIntBits(start), Float.floatToIntBits(VanillaMth.lerp(0.0F, start, end)));
        assertEquals(Float.floatToIntBits(start + 1.0F * (end - start)),
            Float.floatToIntBits(VanillaMth.lerp(1.0F, start, end)));
        assertEquals(Float.floatToIntBits(0.5F), Float.floatToIntBits(VanillaMth.lerp(0.5F, 0.0F, 1.0F)));
        assertEquals(Double.doubleToLongBits(0.25), Double.doubleToLongBits(VanillaMth.lerp(0.25, 0.0, 1.0)));
    }

    @Test
    @DisplayName("inverseLerp inverts lerp's bounds")
    void inverseLerpBounds() {
        assertEquals(Float.floatToIntBits(0.0F), Float.floatToIntBits(VanillaMth.inverseLerp(2.0F, 2.0F, 6.0F)));
        assertEquals(Float.floatToIntBits(1.0F), Float.floatToIntBits(VanillaMth.inverseLerp(6.0F, 2.0F, 6.0F)));
        assertEquals(Float.floatToIntBits(0.5F), Float.floatToIntBits(VanillaMth.inverseLerp(4.0F, 2.0F, 6.0F)));
    }

    @Test
    @DisplayName("clamp's low test is a comparison and its high test is Math.min")
    void clampAsymmetry() {
        assertEquals(Float.floatToIntBits(2.0F), Float.floatToIntBits(VanillaMth.clamp(1.0F, 2.0F, 6.0F)));
        assertEquals(Float.floatToIntBits(6.0F), Float.floatToIntBits(VanillaMth.clamp(9.0F, 2.0F, 6.0F)));
        assertEquals(Float.floatToIntBits(4.0F), Float.floatToIntBits(VanillaMth.clamp(4.0F, 2.0F, 6.0F)));
        // NaN fails `value < min`, then reaches Math.min, which propagates it. A symmetric pair of
        // comparisons would answer min instead.
        assertEquals(Float.floatToIntBits(Float.NaN), Float.floatToIntBits(VanillaMth.clamp(Float.NaN, 2.0F, 6.0F)));
    }

    @Test
    @DisplayName("wrapDegrees folds into the half-open range the client uses")
    void wrapDegreesRange() {
        float[][] cases = {{0.0F, 0.0F}, {180.0F, -180.0F}, {-180.0F, -180.0F}, {190.0F, -170.0F},
            {-190.0F, 170.0F}, {360.0F, 0.0F}, {540.0F, -180.0F}, {-540.0F, -180.0F}};
        for (float[] pair : cases)
            assertEquals(Float.floatToIntBits(pair[1]), Float.floatToIntBits(VanillaMth.wrapDegrees(pair[0])),
                "wrapDegrees(" + pair[0] + ")");
    }

    @Test
    @DisplayName("rotLerp takes the short arc across the seam")
    void rotLerpShortArc() {
        assertEquals(Float.floatToIntBits(180.0F), Float.floatToIntBits(VanillaMth.rotLerp(0.5F, 170.0F, -170.0F)));
        assertEquals(Float.floatToIntBits(175.0F), Float.floatToIntBits(VanillaMth.rotLerp(0.25F, 170.0F, -170.0F)));
    }

    @Test
    @DisplayName("rotLerpRad folds by repeated turns, not by a remainder")
    void rotLerpRadFold() {
        float tau = 6.2831855F;
        // An end many turns away must land on the same answer as its folded equivalent.
        assertEquals(Float.floatToIntBits(VanillaMth.rotLerpRad(0.5F, 0.0F, 1.0F)),
            Float.floatToIntBits(VanillaMth.rotLerpRad(0.5F, 0.0F, 1.0F + tau)));
        assertEquals(Float.floatToIntBits(0.0F), Float.floatToIntBits(VanillaMth.rotLerpRad(0.0F, 0.0F, 3.0F)));
    }

    @Test
    @DisplayName("triangleWave peaks and troughs where the client's does")
    void triangleWaveShape() {
        // The wave opens at its peak: |0 - 2| - 1 over 1 is +1, and the trough is half a period on.
        assertEquals(Float.floatToIntBits(1.0F), Float.floatToIntBits(VanillaMth.triangleWave(0.0F, 4.0F)));
        assertEquals(Float.floatToIntBits(-1.0F), Float.floatToIntBits(VanillaMth.triangleWave(2.0F, 4.0F)));
        assertEquals(Float.floatToIntBits(0.0F), Float.floatToIntBits(VanillaMth.triangleWave(1.0F, 4.0F)));
        assertEquals(Float.floatToIntBits(1.0F), Float.floatToIntBits(VanillaMth.triangleWave(4.0F, 4.0F)));
    }

    @Test
    @DisplayName("catmullrom passes through its inner control points")
    void catmullromControlPoints() {
        assertEquals(Float.floatToIntBits(2.0F), Float.floatToIntBits(VanillaMth.catmullrom(0.0F, 1.0F, 2.0F, 3.0F, 4.0F)));
        assertEquals(Float.floatToIntBits(3.0F), Float.floatToIntBits(VanillaMth.catmullrom(1.0F, 1.0F, 2.0F, 3.0F, 4.0F)));
        // Re-transcribed from the bytecode a second time, keeping every operand in its own order.
        float d = 0.37F;
        float p0 = -1.25F, p1 = 0.5F, p2 = 4.75F, p3 = 2.0F;
        float a = 2.0F * p1 + (p2 - p0) * d;
        float b = (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * d * d;
        float c = (3.0F * p1 - p0 - 3.0F * p2 + p3) * d * d * d;
        assertEquals(Float.floatToIntBits(0.5F * (a + b + c)),
            Float.floatToIntBits(VanillaMth.catmullrom(d, p0, p1, p2, p3)));
    }

    @Test
    @DisplayName("square, cube, sqrt and abs keep the client's rounding")
    void scalarHelpers() {
        assertEquals(Float.floatToIntBits(0.1F * 0.1F), Float.floatToIntBits(VanillaMth.square(0.1F)));
        assertEquals(Float.floatToIntBits(0.1F * 0.1F * 0.1F), Float.floatToIntBits(VanillaMth.cube(0.1F)));
        assertEquals(Float.floatToIntBits((float) Math.sqrt(2.0F)), Float.floatToIntBits(VanillaMth.sqrt(2.0F)));
        assertEquals(3, VanillaMth.abs(-3));
    }

    @Test
    @DisplayName("binarySearch finds the same boundary a linear scan does")
    void binarySearchAgainstLinearScan() {
        for (int size = 0; size <= 40; size++) {
            for (int boundary = 0; boundary <= size; boundary++) {
                int pivot = boundary;
                IntPredicate predicate = index -> index >= pivot;
                int linear = size;
                for (int index = 0; index < size; index++)
                    if (predicate.test(index)) {
                        linear = index;
                        break;
                    }
                assertEquals(linear, VanillaMth.binarySearch(0, size, predicate),
                    "binarySearch(0, " + size + ") with boundary " + boundary);
            }
        }
    }

    private static double[] sweep() {
        double[] angles = new double[1004];
        for (int i = 0; i < 1000; i++)
            angles[i] = -8.0 + i * 0.016;                       // dense sweep across +-8 rad
        angles[1000] = 0.20420352f;                             // the WitherBoss ribcage angle
        angles[1001] = -0.20420352f;
        angles[1002] = 2.0 * Math.PI;
        angles[1003] = 0.4f;                                    // the ghast tentacle constant term
        return angles;
    }

}
