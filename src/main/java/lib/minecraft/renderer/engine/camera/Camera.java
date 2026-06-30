package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import org.jetbrains.annotations.NotNull;

/**
 * A baked camera pose, applied after the caller's model transform during rasterization. The value a
 * {@link ModelEngine} composes with every render: column-vector form, applied right-to-left to a
 * model vertex.
 *
 * <p>Factories pick which named vanilla {@code display.*} pose ends up in the matrix; callers reach
 * for a factory rather than the constructor:
 * <ul>
 *   <li><b>{@link #forBlockIcon()}</b> - the stock {@code [30, 225, 0]} pitch / yaw / roll from the
 *       root {@code block/block.json} model's {@code display.gui}. The default three-quarter
 *       block-icon view (block atlases, skulls, busts, full-body skin renders).</li>
 *   <li><b>{@link #forPlayerIcon()}</b> - the block pose with the yaw flipped 180&deg; so a
 *       humanoid's front faces the camera ({@code [30, 45, 0]}). Carries no LER chirality flips -
 *       those belong to the entity-kit pipeline, not the hard-coded player cubes.</li>
 *   <li><b>{@link #forEntityIcon()}</b> - vanilla's {@code EntityFrameRenderer.ISO_ROTATION}
 *       ({@code [210, 45, 0]}) plus the LER chirality and reflection scales the vanilla-reference
 *       harness applies. Required for entity parity against the harness PNGs.</li>
 *   <li><b>{@link #withGuiPose(EulerRotation)}</b> - a caller-supplied pose, for models that
 *       override the default {@code display.gui} (stairs author {@code [30, 135, 0]}).</li>
 * </ul>
 *
 * @param matrix the pose composed into every rasterization, in column-vector form
 */
public record Camera(@NotNull Matrix4f matrix) {

    /**
     * Vanilla's standard block {@code display.gui} rotation {@code [30, 225, 0]} composed into a
     * single matrix. Matches {@code Quaternionf.rotationXYZ(toRadians(30), toRadians(225), 0)}.
     */
    private static final @NotNull Matrix4f CAMERA = buildGuiDisplayTransform(EulerRotation.STANDARD_ISO_BLOCK);

    /**
     * Vanilla's block {@code display.gui} rotation with the yaw flipped 180&deg; so a humanoid's
     * front faces the camera ({@code [30, 45, 0]}, {@link EulerRotation#STANDARD_ISO_PLAYER}).
     */
    private static final @NotNull Matrix4f CAMERA_PLAYER = buildGuiDisplayTransform(EulerRotation.STANDARD_ISO_PLAYER);

    /**
     * Vanilla's full entity-preview transform chain expressed as the column-vector matrix our
     * column-form rasterizer consumes, AFTER accounting for the kit's pre-applied {@code FLIP_Y} on
     * positions. Identical to {@link #entityIsoChain()}.
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
     */
    private static final @NotNull Matrix4f CAMERA_ENTITY = entityIsoChain();

    /**
     * Returns the standard block inventory-icon pose ({@code [30, 225, 0]} pitch/yaw/roll), the
     * block-icon camera baked into the root {@code block/block.json} model's {@code display.gui}.
     *
     * @return the standard block-icon camera
     */
    public static @NotNull Camera forBlockIcon() {
        return new Camera(CAMERA);
    }

    /**
     * Returns the front-facing humanoid GUI pose ({@link EulerRotation#STANDARD_ISO_PLAYER},
     * {@code [30, 45, 0]}). Shares the block-icon three-quarter framing but turns the model's front
     * toward the camera, where {@link #forBlockIcon()} would show a humanoid's back. Unlike
     * {@link #forEntityIcon()} it carries no LER chirality flips.
     *
     * @return the front-facing player camera
     */
    public static @NotNull Camera forPlayerIcon() {
        return new Camera(CAMERA_PLAYER);
    }

    /**
     * Returns the standard entity inventory-preview pose ({@code [210, 45, 0]} pitch/yaw/roll),
     * matching {@code EntityFrameRenderer.ISO_ROTATION} in the vanilla-reference-harness, so entity
     * output aligns with the harness ground-truth PNGs.
     *
     * @return the standard entity-preview camera
     */
    public static @NotNull Camera forEntityIcon() {
        return new Camera(CAMERA_ENTITY);
    }

    /**
     * Returns a camera whose pose is a vanilla {@code display.*} GUI pose built from the supplied
     * Euler-angle rotation. Use when a block or item model overrides the default {@code [30, 225, 0]}
     * (e.g. stairs author {@code display.gui} as {@code [30, 135, 0]}).
     *
     * @param rotation the Euler-angle pose (in degrees) baked into the camera transform
     * @return a camera with the requested pose
     */
    public static @NotNull Camera withGuiPose(@NotNull EulerRotation rotation) {
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
     * Builds the vanilla entity-preview iso chain shared by {@link #forEntityIcon()} and the entity
     * renderer's bounds/anchor projection. Column-vector application order is rightmost-first:
     * {@code scale(1,1,-1) * isoQuat * scale(1,1,-1) * scale(1,-1,1)}. Each fluent op matches JOML's
     * in-place translate/scale/rotate bit-for-bit with default {@code joml.useMathFma=false}.
     *
     * @return the entity iso chain matrix
     */
    public static @NotNull Matrix4f entityIsoChain() {
        EulerRotation iso = EulerRotation.STANDARD_ISO_ENTITY;
        return Matrix4f.IDENTITY
            .scale(1f, -1f, 1f)
            .scale(1f, 1f, -1f)
            .rotate(Quaternionf.rotationXYZ(iso.pitchRadians(), iso.yawRadians(), 0f))
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
