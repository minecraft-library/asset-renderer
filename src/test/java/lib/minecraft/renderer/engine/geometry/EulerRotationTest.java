package lib.minecraft.renderer.engine.geometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * {@link EulerRotation}'s arithmetic: the per-axis radian accessors ({@link EulerRotation#pitchRadians()},
 * {@link EulerRotation#yawRadians()}, {@link EulerRotation#rollRadians()}) apply the degrees-to-radians
 * conversion, and that conversion parts from vanilla's float multiply on exactly 66 of the 721 integer
 * degrees in {@code [-360, 360]}. The record composes no rotation of its own, so those accessors are the
 * whole of its arithmetic.
 * <p>
 * The 66 is what lets {@link EulerRotationVanillaAnglesTest}'s scan of the client's authored angles fail
 * at all; it reads no pack, so it is held here, where no client jar is needed.
 */
@DisplayName("EulerRotation degrees and radian conversion")
class EulerRotationTest {

    /** The factor vanilla multiplies a degree by, in float, to reach its radian. */
    private static final float VANILLA_DEGREES_TO_RADIANS = 0.017453292f;

    /** The rule this measurement backs, so a failure names the decision to re-measure. */
    private static final String RULE = "docs/renderer/Geometry.md, \"Do not switch "
        + "EulerRotation's degrees-to-radians conversion to vanilla's float multiply\"";

    @Test
    @DisplayName("per-axis radian accessors convert from degrees")
    void radianAccessors() {
        EulerRotation r = new EulerRotation(90f, 180f, 270f);
        assertThat(r.pitchRadians(), equalTo((float) Math.toRadians(90)));
        assertThat(r.yawRadians(), equalTo((float) Math.toRadians(180)));
        assertThat(r.rollRadians(), equalTo((float) Math.toRadians(270)));
    }

    @Test
    @DisplayName("the two routes part on 66 of the 721 integer degrees in [-360, 360]")
    void theRoutesPartOnSixtySixIntegerDegrees() {
        long parted = IntStream.rangeClosed(-360, 360)
            .filter(degrees -> Float.compare(new EulerRotation(degrees, 0f, 0f).pitchRadians(),
                degrees * VANILLA_DEGREES_TO_RADIANS) != 0)
            .count();

        assertThat("integer degrees in [-360, 360] where EulerRotation's radian is not vanilla's - the figure "
            + RULE + " states, and what lets the authored-angle check fail at all", parted, is(66L));
    }

}
