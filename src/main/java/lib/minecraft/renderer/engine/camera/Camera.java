package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.request.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import org.jetbrains.annotations.NotNull;

/**
 * A baked camera pose, applied after the caller's model transform during rasterization. The value a
 * {@link ModelEngine} composes with every render: column-vector form, applied right-to-left to a
 * model vertex.
 *
 * <p>{@code Camera} is a dependency-free value type plus two GUI-pose primitives; the named vanilla
 * poses and the entity chirality chain live on {@link Projection}, which assembles them. Callers reach
 * for a factory rather than the constructor:
 * <ul>
 *   <li><b>{@link #fromPose(EulerRotation)}</b> - the goto builder: a {@code display.*} GUI pose from
 *       the supplied Euler angles via vanilla's {@code rotationXYZ}. Backs every {@link Projection}
 *       GUI-pose member and any caller that needs an ad-hoc display pose (item shield, bed parity).</li>
 *   <li><b>{@link #identity()}</b> - no pre-rotation; geometry viewed straight down {@code -Z}.</li>
 * </ul>
 *
 * @param matrix the pose composed into every rasterization, in column-vector form
 */
public record Camera(@NotNull Matrix4f matrix) {

    /**
     * Returns a camera whose pose is a vanilla {@code display.*} GUI pose built from the supplied
     * Euler-angle rotation - the goto builder for every {@link Projection} GUI-pose member and for
     * callers that supply an ad-hoc pose (the item shield's {@code [15, -25, -5]}, a block model's
     * {@code display.gui} override such as stairs' {@code [30, 135, 0]}).
     *
     * @param rotation the Euler-angle pose (in degrees) baked into the camera transform
     * @return a camera with the requested pose
     */
    public static @NotNull Camera fromPose(@NotNull EulerRotation rotation) {
        return new Camera(buildGuiDisplayTransform(rotation));
    }

    /**
     * Returns the identity camera - geometry is viewed directly down the negative Z axis with no
     * pre-rotation.
     *
     * @return the identity camera
     */
    public static @NotNull Camera identity() {
        return new Camera(Matrix4f.IDENTITY);
    }

    /**
     * Builds the matrix equivalent of vanilla's {@code Quaternionf.rotationXYZ(x, y, z)} for a
     * {@code display.*} transform's Euler angles in degrees. Bit-identical to vanilla's
     * {@code new Matrix4f().rotation(new Quaternionf().rotationXYZ(...))} by routing through the same
     * {@link Quaternionf} quaternion-to-matrix conversion.
     */
    private static @NotNull Matrix4f buildGuiDisplayTransform(@NotNull EulerRotation rotation) {
        return Quaternionf
            .rotationXYZ(rotation.pitchRadians(), rotation.yawRadians(), rotation.rollRadians())
            .toMatrix4f();
    }

}
