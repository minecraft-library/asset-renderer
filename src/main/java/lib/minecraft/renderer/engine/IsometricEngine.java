package lib.minecraft.renderer.engine;

import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link ModelEngine} whose camera transform is a named vanilla-Minecraft {@code display.*}
 * pose. Callers use one of two factories rather than {@code new} to pick which pose ends up in
 * the camera:
 * <ul>
 * <li>{@link #standard(RendererContext)} - the stock {@code [30, 225, 0]} pitch/yaw/roll from
 * the root {@code block/block.json} model's {@code display.gui}. Use for renders that want the
 * default three-quarter block-icon view (skulls, busts, full-body skin renders).</li>
 * <li>{@link #withGuiPose(RendererContext, EulerRotation)} - a caller-supplied pose.
 * Use when a block or item model overrides the default (stairs author {@code display.gui} as
 * {@code [30, 135, 0]}) so the engine's camera reflects the specific model's authored
 * orientation.</li>
 * </ul>
 * The camera is applied after the caller's model transform during rasterization, equivalent to
 * vanilla's {@code PoseStack.mulPose(Quaternionf.rotationXYZ(...))} call around the model
 * rendering block. Renderers that are not isometric (free-rotation entity views, handheld item
 * placements) should use plain {@link ModelEngine} instead.
 */
public class IsometricEngine extends ModelEngine {

    /**
     * Vanilla's standard block {@code display.gui} rotation {@code [30, 225, 0]} composed into
     * a single matrix. Matches {@code Quaternionf.rotationXYZ(toRadians(30), toRadians(225), 0)}.
     */
    private static final @NotNull Matrix4f CAMERA = buildGuiDisplayTransform(EulerRotation.STANDARD_ISO_BLOCK);

    /**
     * Vanilla's full entity-preview transform chain expressed as the column-vector matrix our
     * column-form rasterizer consumes, AFTER accounting for the kit's pre-applied
     * {@code FLIP_Y} on positions.
     * <p>
     * The harness applies (col form, applied to a Y-down model vertex right-to-left):
     * <pre>
     * scale(1,1,-1) outer chirality
     *   × R_X(210°)   iso pitch
     *   × R_Y(45°)    iso yaw
     *   × R_X(180°)   LER chirality (scale(-1,-1,1)) + setupRotations (rotateY(180°))
     * </pre>
     * Our pipeline applies kit {@code FLIP_Y = diag(1,-1,1)} to positions before the engine sees
     * them. The trailing {@code scale(1,-1,1)} absorbs the kit's flip into the camera matrix
     * (and simplifies {@code scale(1,-1,1) × R_X(180°) = diag(1,1,-1)} so two outer
     * {@code scale(1,1,-1)} factors remain). Equivalent to
     * {@code scale(1,1,-1) × R_Y(yaw) × R_X(pitch) × scale(1,1,-1) × scale(1,-1,1)} in
     * column-vector form, applied right-to-left to a model vertex.
     * <p>
     * The two outer {@code scale(1,1,-1)} factors give a det=-1 transform total - matching the
     * harness's odd-reflection-count chirality. The simpler {@code Quaternionf.rotationXYZ(210°,
     * 45°, 0°)} alone (det=+1) is INSUFFICIENT - it produces the iso rotation but omits the LER
     * chirality and reflection components, which Round 2 confirmed regresses every entity ~6x.
     */
    private static final @NotNull Matrix4f CAMERA_ENTITY = buildEntityCameraTransform();

    private static @NotNull Matrix4f buildEntityCameraTransform() {
        EulerRotation iso = EulerRotation.STANDARD_ISO_ENTITY;
        // Vanilla PoseStack-equivalent chain via fluent ops (each matches JOML's in-place
        // translate/scale/rotate bit-for-bit with default `joml.useMathFma=false`).
        // Application order rightmost-first under col-vec: scale(1,1,-1) * isoQuat *
        // scale(1,1,-1) * scale(1,-1,1).
        return Matrix4f.IDENTITY
            .scale(1f, -1f, 1f)
            .scale(1f, 1f, -1f)
            .rotate(Quaternionf.rotationXYZ(iso.pitchRadians(), iso.yawRadians(), 0f))
            .scale(1f, 1f, -1f);
    }

    private IsometricEngine(@NotNull RendererContext context, @NotNull Matrix4f camera) {
        super(context, camera);
    }

    /**
     * Returns an engine wired to vanilla Minecraft's standard block inventory icon pose
     * ({@code [30, 225, 0]} pitch/yaw/roll). Equivalent to the block-icon camera baked into
     * the root {@code block/block.json} model's {@code display.gui} transform.
     *
     * @param context the renderer context
     * @return an isometric engine with the standard block-icon camera
     */
    public static @NotNull IsometricEngine forBlockIcon(@NotNull RendererContext context) {
        return new IsometricEngine(context, CAMERA);
    }

    /**
     * Returns an engine wired to vanilla Minecraft's standard entity inventory-preview pose
     * ({@code [210, 45, 0]} pitch/yaw/roll), matching {@code EntityFrameRenderer.ISO_ROTATION}
     * in the vanilla-reference-harness. Use for entity rendering through
     * {@link lib.minecraft.renderer.EntityRenderer} so the output pose aligns with the
     * harness ground-truth PNGs. Block / item rendering should continue using
     * {@link #forBlockIcon(RendererContext)}.
     *
     * @param context the renderer context
     * @return an isometric engine with the standard entity-preview camera
     */
    public static @NotNull IsometricEngine forEntityIcon(@NotNull RendererContext context) {
        return new IsometricEngine(context, CAMERA_ENTITY);
    }

    /**
     * Returns an engine whose camera is a vanilla {@code display.*} GUI pose built from the
     * supplied Euler-angle rotation. Use this when a block or item model overrides the default
     * {@code [30, 225, 0]} (e.g. stairs author {@code display.gui} as {@code [30, 135, 0]}) so
     * the render respects the model's authored pose without the caller composing it into a
     * {@code modelTransform}.
     *
     * @param context the renderer context
     * @param rotation the Euler-angle pose (in degrees) baked into the camera transform
     * @return an isometric engine with the requested pose baked into the camera
     */
    public static @NotNull IsometricEngine withGuiPose(
        @NotNull RendererContext context,
        @NotNull EulerRotation rotation
    ) {
        return new IsometricEngine(context, buildGuiDisplayTransform(rotation));
    }

    /**
     * Builds the matrix equivalent of vanilla's {@code Quaternionf.rotationXYZ(x, y, z)} for
     * a {@code display.*} transform's Euler angles in degrees. Bit-identical to vanilla's
     * {@code new Matrix4f().rotation(new Quaternionf().rotationXYZ(...))} by routing through
     * the same {@link Quaternionf} quaternion-to-matrix conversion.
     */
    private static @NotNull Matrix4f buildGuiDisplayTransform(@NotNull EulerRotation rotation) {
        return Quaternionf
            .rotationXYZ(rotation.pitchRadians(), rotation.yawRadians(), rotation.rollRadians())
            .toMatrix4f();
    }

}
