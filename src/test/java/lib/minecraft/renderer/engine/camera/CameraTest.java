package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit coverage for {@link Camera#fromTransform} - the full display-transform camera and the exact
 * {@link Camera#fromPose} expression it answers for a uniform, un-translated transform.
 */
@DisplayName("Camera display-transform factory")
class CameraTest {

    @Test
    @DisplayName("a uniform, un-translated transform is the fromPose camera bit-for-bit")
    void uniformUntranslatedTransformIsFromPose() {
        EulerRotation rotation = new EulerRotation(30f, 225f, 0f);
        float scale = 0.625f;

        Camera transform = Camera.fromTransform(rotation, Vector3f.ZERO, new Vector3f(scale, scale, scale));

        assertEquals(Camera.fromPose(rotation, Lens.orthographic(scale)), transform);
    }

    @Test
    @DisplayName("a translated transform keeps the isotropic scale on the lens and moves the pose")
    void translatedTransformMovesThePose() {
        EulerRotation rotation = new EulerRotation(30f, 135f, 0f);
        float scale = 0.625f;

        Camera transform = Camera.fromTransform(rotation, new Vector3f(0f, 1f, 0f), new Vector3f(scale, scale, scale));
        Camera pose = Camera.fromPose(rotation, Lens.orthographic(scale));

        assertEquals(pose.lens(), transform.lens());
        assertNotEquals(pose.pose(), transform.pose());
    }

}
