package lib.minecraft.renderer.engine;

import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
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
     * Vanilla's full entity-preview transform chain {@code scale(1,1,-1) × R_X(210°) × R_Y(45°)
     * × R_X(180°)} expressed in the row-vector form our row-form rasterizer consumes, AFTER
     * accounting for the kit's pre-applied {@code FLIP_Y} on positions.
     * <p>
     * The harness applies (col form, applied to a Y-down model vertex right-to-left):
     * <pre>
     * scale(1,1,-1) outer chirality
     *   × R_X(210°)   iso pitch
     *   × R_Y(45°)    iso yaw
     *   × R_X(180°)   LER chirality (scale(-1,-1,1)) + setupRotations (rotateY(180°))
     * </pre>
     * Our pipeline applies kit {@code FLIP_Y = diag(1,-1,1)} to positions before the engine sees
     * them, so the engine's camera matrix must convert that Y-flipped vertex to the same screen
     * output. Solving {@code FLIP_Y_row × engine_camera_row = M_harness_col^T}:
     * <pre>
     * engine_camera_row = scale(1,-1,1) × R_X(180°) × R_Y(45°) × R_X(210°) × scale(1,1,-1)
     *                   = scale(1,1,-1) × R_Y(45°) × R_X(210°) × scale(1,1,-1)
     * </pre>
     * (simplification: {@code scale(1,-1,1) × R_X(180°)} algebraically equals {@code diag(1,-1,1)
     * × diag(1,-1,-1) = diag(1,1,-1)}).
     * <p>
     * The two outer {@code scale(1,1,-1)} factors give a det=-1 transform total - matching the
     * harness's odd-reflection-count chirality. The simpler {@code Quaternionf.rotationXYZ(210°,
     * 45°, 0°)} alone (det=+1) is INSUFFICIENT - it produces the iso rotation but omits the LER
     * chirality and reflection components, which Round 2 confirmed regresses every entity ~6x.
     */
    private static final @NotNull Matrix4f CAMERA_ENTITY = buildEntityCameraTransform();

    private static @NotNull Matrix4f buildEntityCameraTransform() {
        EulerRotation iso = EulerRotation.STANDARD_ISO_ENTITY;
        // Trailing scale(1,-1,1) compensates for the opposite Y-invert conventions between vanilla
        // and our pipelines. Vanilla's projection uses {@code invertY=true} which maps world +y to
        // the BOTTOM of the output image (vanilla's pose stack works in image-Y-down at projection
        // input). Our {@code RenderEngine.projectPerspective} does {@code -point.y} which maps
        // pre-projection +y to the TOP of the output image (we work in screen-Y-up at projection
        // input). The math-derived matrix above {@code scale(1,1,-1) × R_Y × R_X × scale(1,1,-1)}
        // produces vanilla's pre-projection coordinates, but vanilla's image-Y-down vs our
        // screen-Y-up means an extra Y-negate is required so the image positions line up.
        return Matrix4f.createScale(1f, 1f, -1f)
            .multiply(Matrix4f.createRotationY(iso.yawRadians()))
            .multiply(Matrix4f.createRotationX(iso.pitchRadians()))
            .multiply(Matrix4f.createScale(1f, 1f, -1f))
            .multiply(Matrix4f.createScale(1f, -1f, 1f));
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
    public static @NotNull IsometricEngine standard(@NotNull RendererContext context) {
        return new IsometricEngine(context, CAMERA);
    }

    /**
     * Returns an engine wired to vanilla Minecraft's standard entity inventory-preview pose
     * ({@code [210, 45, 0]} pitch/yaw/roll), matching {@code EntityFrameRenderer.ISO_ROTATION}
     * in the vanilla-reference-harness. Use for entity rendering through
     * {@link lib.minecraft.renderer.EntityRenderer} so the output pose aligns with the
     * harness ground-truth PNGs. Block / item rendering should continue using
     * {@link #standard(RendererContext)}.
     *
     * @param context the renderer context
     * @return an isometric engine with the standard entity-preview camera
     */
    public static @NotNull IsometricEngine entityStandard(@NotNull RendererContext context) {
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
     * a {@code display.*} transform's Euler angles in degrees.
     * <p>
     * JOML's {@code rotationXYZ} produces the quaternion {@code q_x * q_y * q_z}; when that
     * quaternion rotates a vector {@code q * v * q^-1}, the rotations apply to the vector in
     * the order Z, then Y, then X (innermost first). The equivalent column-vector matrix is
     * {@code R_x * R_y * R_z}. Under this codebase's row-vector convention ({@code v * M}) the
     * correct composition is therefore the transpose, {@code R_z * R_y * R_x}, which is
     * exactly {@link Matrix4f#createRotationZ createRotationZ} {@link Matrix4f#multiply
     * multiply} {@link Matrix4f#createRotationY createRotationY} {@link Matrix4f#multiply
     * multiply} {@link Matrix4f#createRotationX createRotationX}.
     * <p>
     * Getting the order right matters: swapping it to {@code Rx * Ry * Rz} produces the same
     * math for single-axis rotations but silently flips the tilt direction for compound poses
     * like the standard {@code [30, 225, 0]} block-icon pose, which shows up as the block's
     * bottom face being visible instead of the top.
     */
    private static @NotNull Matrix4f buildGuiDisplayTransform(@NotNull EulerRotation rotation) {
        return Matrix4f.createRotationZ(rotation.rollRadians())
            .multiply(Matrix4f.createRotationY(rotation.yawRadians()))
            .multiply(Matrix4f.createRotationX(rotation.pitchRadians()));
    }

}
