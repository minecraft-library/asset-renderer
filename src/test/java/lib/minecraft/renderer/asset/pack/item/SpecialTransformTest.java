package lib.minecraft.renderer.asset.pack.item;

import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

/**
 * The {@link SpecialTransform} decomposition pinned to vanilla's {@code T . Rleft . S . Rright} order
 * under this codebase's {@code v_row x M} convention (the transpose the fluent {@code Matrix4f} chain
 * bakes in, matching the item {@code display} transform). The probes are convention-robust - translation,
 * scale, and 180-degree flips - so no assertion hinges on a rotation-handedness convention.
 */
@DisplayName("SpecialTransform decomposition")
class SpecialTransformTest {

    private static final double EPS = 1e-5;

    @Test
    @DisplayName("identity transform maps every point to itself")
    void identity() {
        assertThat(SpecialTransform.IDENTITY.isIdentity(), is(true));
        assertPoint(SpecialTransform.IDENTITY.toMatrix(), new Vector3f(3f, -2f, 5f), 3f, -2f, 5f);
    }

    @Test
    @DisplayName("translation-only moves the origin to the translation")
    void translationOnly() {
        SpecialTransform t = new SpecialTransform(
            new float[]{0, 0, 0, 1}, new float[]{0, 0, 0, 1}, new float[]{1, 1, 1}, new float[]{0.5f, 0f, 0.5f});
        assertPoint(t.toMatrix(), Vector3f.ZERO, 0.5f, 0f, 0.5f);
    }

    @Test
    @DisplayName("scale-only scales each axis")
    void scaleOnly() {
        SpecialTransform t = new SpecialTransform(
            new float[]{0, 0, 0, 1}, new float[]{0, 0, 0, 1}, new float[]{2, 3, 4}, new float[]{0, 0, 0});
        assertPoint(t.toMatrix(), new Vector3f(1f, 1f, 1f), 2f, 3f, 4f);
    }

    @Test
    @DisplayName("a 180-degree left rotation about X flips Y and Z")
    void leftRotation180X() {
        SpecialTransform t = new SpecialTransform(
            new float[]{1, 0, 0, 0}, new float[]{0, 0, 0, 1}, new float[]{1, 1, 1}, new float[]{0, 0, 0});
        assertPoint(t.toMatrix(), new Vector3f(0f, 1f, 0f), 0f, -1f, 0f);
        assertPoint(t.toMatrix(), new Vector3f(0f, 0f, 1f), 0f, 0f, -1f);
    }

    @Test
    @DisplayName("translation applies AFTER the left rotation (T . Rleft order)")
    void translationAfterRotation() {
        // (0,1,0) -> Rleft(180 X) -> (0,-1,0) -> +translation(0,5,0) -> (0,4,0). Translate-first would
        // give (0,-6,0), so this pins the T . Rleft composition order.
        SpecialTransform t = new SpecialTransform(
            new float[]{1, 0, 0, 0}, new float[]{0, 0, 0, 1}, new float[]{1, 1, 1}, new float[]{0f, 5f, 0f});
        assertPoint(t.toMatrix(), new Vector3f(0f, 1f, 0f), 0f, 4f, 0f);
    }

    private static void assertPoint(Matrix4f m, Vector3f in, float ex, float ey, float ez) {
        Vector3f out = in.transform(m);
        assertThat((double) out.x(), closeTo(ex, EPS));
        assertThat((double) out.y(), closeTo(ey, EPS));
        assertThat((double) out.z(), closeTo(ez, EPS));
    }

}
