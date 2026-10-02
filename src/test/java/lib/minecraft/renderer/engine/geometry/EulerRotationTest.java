package lib.minecraft.renderer.engine.geometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@link EulerRotation}'s data-only contract: the {@link EulerRotation#NONE} identity constant, that
 * {@code pitch}/{@code yaw}/{@code roll} are exposed verbatim in degrees, and that the per-axis radian
 * accessors ({@link EulerRotation#pitchRadians()}, {@link EulerRotation#yawRadians()},
 * {@link EulerRotation#rollRadians()}) apply the degrees-to-radians conversion. The record composes no
 * rotation of its own, so those accessors are the whole of its arithmetic.
 */
@DisplayName("EulerRotation degrees and radian conversion")
class EulerRotationTest {

    @Test
    @DisplayName("NONE is the zero rotation")
    void none() {
        assertThat(EulerRotation.NONE, equalTo(new EulerRotation(0f, 0f, 0f)));
    }

    @Test
    @DisplayName("per-axis radian accessors convert from degrees")
    void radianAccessors() {
        EulerRotation r = new EulerRotation(90f, 180f, 270f);
        assertThat(r.pitchRadians(), equalTo((float) Math.toRadians(90)));
        assertThat(r.yawRadians(), equalTo((float) Math.toRadians(180)));
        assertThat(r.rollRadians(), equalTo((float) Math.toRadians(270)));
    }

    @Test
    @DisplayName("components are exposed verbatim in degrees")
    void components() {
        EulerRotation r = new EulerRotation(12.5f, -45f, 7f);
        assertThat(r.pitch(), equalTo(12.5f));
        assertThat(r.yaw(), equalTo(-45f));
        assertThat(r.roll(), equalTo(7f));
    }

}
