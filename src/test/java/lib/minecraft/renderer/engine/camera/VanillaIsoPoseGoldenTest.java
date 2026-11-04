package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.store.PinSet;
import lib.minecraft.renderer.store.Pins;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;

/**
 * Characterization golden that pins the {@link Projection#VANILLA_ISO} camera pose, so accidental
 * drift in the iso display pose trips these assertions.
 *
 * <p>The value lives in {@code pin.vanilla-iso-pose}, captured by this test and read back by it.
 * {@code VANILLA_ISO} resolves to the plain {@code rotationXYZ(30, 225, 0)} iso display pose
 * (det=+1); the entity's model-to-world facing and chirality live on {@code EntityRenderer}'s
 * {@code ENTITY_FACING}, applied as its {@code Placement}, not on the camera. A deliberate change to
 * that pose re-baselines the pin - which is a promotion of the capture this test already wrote, never
 * a paste into Java source.
 *
 * <p>A camera pose with a determinant of zero or below would put chirality on the camera rather than
 * on the renderer's placement. It would also move the pose off this golden and flip the screen winding
 * every culled render reads, so the golden and those renders are what hold the pose to a det=+1
 * rotation.
 */
class VanillaIsoPoseGoldenTest {

    private static final String POSE_ARTIFACT = "pin.vanilla-iso-pose";

    /** How to read the flattened pose back, stored beside it. */
    private static final String POSE_ORDER = "get(col,row): [c1r1,c1r2,c1r3,c1r4, c2r1,...]";

    private static final PinSet POSE_PIN = PinSet.of(POSE_ARTIFACT, Map.of(
        "pose", "Projection.VANILLA_ISO.resolve().camera().pose()"));

    /** Tolerance for the golden float compares - exact-ish, guards against ULP-scale drift creeping in. */
    private static final float EPS = 1e-6f;

    /** Pins all 16 floats of the {@code VANILLA_ISO} pose to the captured baseline within {@link #EPS}. */
    @Test
    @DisplayName("golden: VANILLA_ISO pose 16 floats match the captured display-pose baseline")
    void pose_matchesGolden() {
        Matrix4f pose = Projection.VANILLA_ISO.resolve().camera().pose();
        POSE_PIN.floats("pose", flatten(pose), POSE_ORDER);
        POSE_PIN.requireBaseline();

        // The expected length is an ARGUMENT, which is what carries the old anti-vacuity guard: an
        // emptied golden array once returned rather than failing, so an emptied pin throws here.
        float[] expected = Pins.floats(POSE_ARTIFACT, "pose", 16);
        int index = 0;
        for (int col = 1; col <= 4; col++)
            for (int row = 1; row <= 4; row++, index++)
                assertThat("pose(" + col + "," + row + ")",
                    (double) pose.get(col, row), closeTo(expected[index], EPS));
    }

    /**
     * Flattens a matrix in {@code get(col,row)} order - the order {@link #POSE_ORDER} states and the
     * pin stores.
     *
     * @param matrix the matrix to flatten
     * @return its 16 floats
     */
    private static float[] flatten(Matrix4f matrix) {
        float[] out = new float[16];
        int index = 0;
        for (int col = 1; col <= 4; col++)
            for (int row = 1; row <= 4; row++, index++)
                out[index] = matrix.get(col, row);
        return out;
    }

}
