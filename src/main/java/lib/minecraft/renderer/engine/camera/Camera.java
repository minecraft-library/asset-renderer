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
 * <p>{@code Camera} is the value type plus the primitives the {@link Projection} catalog assembles -
 * the named vanilla poses ({@code VANILLA_BLOCK} / {@code VANILLA_PLAYER} / {@code VANILLA_ENTITY}) live
 * on {@link Projection}, not here. Callers reach for a factory rather than the constructor:
 * <ul>
 *   <li><b>{@link #fromPose(EulerRotation)}</b> - the goto builder: a {@code display.*} GUI pose from
 *       the supplied Euler angles via vanilla's {@code rotationXYZ}. Backs every {@link Projection}
 *       GUI-pose member and any caller that needs an ad-hoc display pose (item shield, bed parity).</li>
 *   <li><b>{@link #entityIsoChain(EulerRotation)}</b> - the vanilla entity-preview iso chain with its
 *       det=-1 LER chirality, shared by {@link Projection#VANILLA_ENTITY} and the entity renderer's
 *       bounds / anchor projection. Parity-locked - see the method javadoc.</li>
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
     * Builds the vanilla entity-preview iso chain - the full entity-preview transform expressed as the
     * column-vector matrix our column-form rasterizer consumes, AFTER accounting for the kit's
     * pre-applied {@code FLIP_Y} on positions. Shared by {@link Projection#VANILLA_ENTITY} and the
     * entity renderer's bounds / anchor projection so both stay a single source of truth.
     * <p>
     * The harness applies (col form, applied to a Y-down model vertex right-to-left):
     * <pre>
     * scale(1,1,-1) outer chirality
     *   &times; R_X(210&deg;)   iso pitch
     *   &times; R_Y(45&deg;)    iso yaw
     *   &times; R_X(180&deg;)   LER chirality (scale(-1,-1,1)) + setupRotations (rotateY(180&deg;))
     * </pre>
     * Our pipeline applies kit {@code FLIP_Y = diag(1,-1,1)} to positions before the engine sees
     * them. The trailing {@code scale(1,-1,1)} absorbs the kit's flip into the camera matrix (and
     * simplifies {@code scale(1,-1,1) &times; R_X(180&deg;) = diag(1,1,-1)} so two outer
     * {@code scale(1,1,-1)} factors remain).
     * <p>
     * The two outer {@code scale(1,1,-1)} factors give a det=-1 transform total - matching the
     * harness's odd-reflection-count chirality. The simpler {@code Quaternionf.rotationXYZ(210&deg;,
     * 45&deg;, 0&deg;)} alone (det=+1) is INSUFFICIENT - it produces the iso rotation but omits the
     * LER chirality and reflection components, which Round 2 confirmed regresses every entity ~6x.
     * <p>
     * Column-vector application order is rightmost-first:
     * {@code scale(1,1,-1) * isoQuat * scale(1,1,-1) * scale(1,-1,1)}. Each fluent op matches JOML's
     * in-place translate/scale/rotate bit-for-bit with default {@code joml.useMathFma=false}.
     *
     * @param isoPose the entity iso pose driving the central rotation - vanilla's {@code [210, 45, 0]},
     *     supplied by {@link Projection#VANILLA_ENTITY} (its canonical home)
     * @return the entity iso chain matrix
     */
    public static @NotNull Matrix4f entityIsoChain(@NotNull EulerRotation isoPose) {
        return Matrix4f.IDENTITY
            .scale(1f, -1f, 1f)
            .scale(1f, 1f, -1f)
            .rotate(Quaternionf.rotationXYZ(isoPose.pitchRadians(), isoPose.yawRadians(), isoPose.rollRadians()))
            .scale(1f, 1f, -1f);
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
