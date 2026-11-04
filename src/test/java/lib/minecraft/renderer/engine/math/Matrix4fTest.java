package lib.minecraft.renderer.engine.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * The {@link Matrix4f} composition contract nothing else states - bit-parity with {@code org.joml} for
 * the fluent {@link Matrix4f#translate(float, float, float)}, {@link Matrix4f#scale(float, float, float)}
 * and {@link Matrix4f#rotate(Quaternionf)} ops.
 *
 * <p>Bit-parity is load-bearing because vanilla's own pose is an {@code org.joml.Matrix4f} and every
 * vertex it transforms is JOML arithmetic, so any per-op divergence accumulates through the chain and
 * lands as vertex drift.
 */
@DisplayName("Matrix4f JOML parity")
class Matrix4fTest {

    @Test
    @DisplayName("fluent translate/scale/rotate matches a JOML pose chain bit-for-bit")
    void fluentOpsMatchJomlBitIdentical() {
        float tx = 113.0f, ty = 153.0f;
        float scale = 17.5f;
        float pitch = (float) Math.toRadians(210);
        float yaw = (float) Math.toRadians(45);

        org.joml.Matrix4f jPose = new org.joml.Matrix4f();
        jPose.translate(tx, ty, 0f);
        jPose.scale(scale, scale, scale);
        jPose.scale(1f, 1f, -1f);
        jPose.rotate(new org.joml.Quaternionf().rotationXYZ(pitch, yaw, 0f));
        jPose.scale(1f, 1f, 1f);
        jPose.rotate(new org.joml.Quaternionf().rotationY((float) Math.PI));
        jPose.scale(-1f, -1f, 1f);
        jPose.translate(0f, -1.501f, 0f);

        Matrix4f ours = Matrix4f.IDENTITY
            .translate(tx, ty, 0f)
            .scale(scale, scale, scale)
            .scale(1f, 1f, -1f)
            .rotate(Quaternionf.rotationXYZ(pitch, yaw, 0f))
            .scale(1f, 1f, 1f)
            .rotate(Quaternionf.rotationXYZ(0f, (float) Math.PI, 0f))
            .scale(-1f, -1f, 1f)
            .translate(0f, -1.501f, 0f);

        // JOML's zero-indexed m(col, row) maps to our one-indexed get(col + 1, row + 1); both are
        // column-major, so no transpose is involved.
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                assertThat("matrix entry differs at col=" + col + " row=" + row,
                    ulpsBetween(jPose.get(col, row), ours.get(col + 1, row + 1)), equalTo(0));
            }
        }

        float vx = -4f, vy = -10f, vz = -4f;
        org.joml.Vector3f vanillaOut = new org.joml.Vector3f();
        jPose.transformPosition(vx, vy, vz, vanillaOut);
        Vector3f oursOut = Vector3f.transform(new Vector3f(vx, vy, vz), ours);

        assertThat("transformed vertex x", ulpsBetween(vanillaOut.x, oursOut.x()), equalTo(0));
        assertThat("transformed vertex y", ulpsBetween(vanillaOut.y, oursOut.y()), equalTo(0));
        assertThat("transformed vertex z", ulpsBetween(vanillaOut.z, oursOut.z()), equalTo(0));
    }

    /**
     * Distance between two floats in units-in-the-last-place, exploiting IEEE-754's monotonic bit
     * ordering for like-signed values. Opposite-sign inputs return {@link Integer#MAX_VALUE}.
     *
     * @param a first value
     * @param b second value
     * @return ULP distance, or {@link Integer#MAX_VALUE} when the signs differ
     */
    private static int ulpsBetween(float a, float b) {
        if (a == b) return 0;
        int ia = Float.floatToRawIntBits(a);
        int ib = Float.floatToRawIntBits(b);
        if ((ia ^ ib) < 0) return Integer.MAX_VALUE;
        return Math.abs(ia - ib);
    }
}
