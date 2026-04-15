package dev.sbs.renderer.engine;

import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.tensor.Matrix4f;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link ModelEngine} wired to vanilla Minecraft's standard block inventory pose.
 * <p>
 * Vanilla renders block icons by applying the {@code display.gui} rotation from the root
 * {@code block/block.json} model ({@code [30, 225, 0]} = pitch, yaw, roll) to the model
 * geometry via {@code PoseStack#mulPose(Quaternionf.rotationXYZ(...))}. This engine bakes
 * that exact rotation into its camera transform so callers that just want the standard
 * three-quarter block icon view can rasterize without composing any extra transform
 * themselves.
 * <p>
 * Callers that need a non-standard pose - e.g. blocks whose own model overrides
 * {@code display.gui} (stairs use {@code [30, 135, 0]}) - should build their own matrix via
 * {@link #buildGuiDisplayTransform(float, float, float)} and feed it through the explicit
 * {@link #rasterize(ConcurrentList, PixelBuffer, PerspectiveParams, Matrix4f)}
 * overload on {@link ModelEngine} rather than relying on this engine's default camera.
 */
public class IsometricEngine extends ModelEngine {

    /**
     * Vanilla's standard block {@code display.gui} rotation {@code [30, 225, 0]} composed into
     * a single matrix. Matches {@code Quaternionf.rotationXYZ(toRadians(30), toRadians(225), 0)}.
     */
    private static final @NotNull Matrix4f CAMERA = buildGuiDisplayTransform(30f, 225f, 0f);

    public IsometricEngine(@NotNull RendererContext context) {
        super(context, CAMERA);
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
     * Returns an engine whose camera is a vanilla {@code display.*} GUI pose built from the
     * supplied pitch/yaw/roll degrees. Use this when a block or item model overrides the
     * default {@code [30, 225, 0]} (e.g. stairs author {@code display.gui} as
     * {@code [30, 135, 0]}) so the render respects the model's authored pose without the
     * caller composing it into a {@code modelTransform}.
     *
     * @param context the renderer context
     * @param pitchDegrees the rotation about the X axis in degrees (vanilla {@code rotation[0]})
     * @param yawDegrees the rotation about the Y axis in degrees (vanilla {@code rotation[1]})
     * @param rollDegrees the rotation about the Z axis in degrees (vanilla {@code rotation[2]})
     * @return an isometric engine with the requested pose baked into the camera
     */
    public static @NotNull IsometricEngine withGuiPose(
        @NotNull RendererContext context,
        float pitchDegrees,
        float yawDegrees,
        float rollDegrees
    ) {
        return new IsometricEngine(context, buildGuiDisplayTransform(pitchDegrees, yawDegrees, rollDegrees));
    }

    /**
     * Builds the matrix equivalent of vanilla's {@code Quaternionf.rotationXYZ(x, y, z)} for
     * a {@code display.*} transform's Euler angles in degrees.
     * <p>
     * JOML's {@code rotationXYZ} produces the quaternion {@code q_x * q_y * q_z}; when that
     * quaternion rotates a vector {@code q * v * q^-1}, the rotations apply to the vector in
     * the order Z, then Y, then X (innermost first). The equivalent column-vector matrix is
     * {@code R_x * R_y * R_z}. Under this codebase's row-vector convention ({@code v * M}) the
     * correct composition is therefore the transpose, {@code R_z^T * R_y^T * R_x^T}, which is
     * exactly {@link Matrix4f#createRotationZ createRotationZ} {@link Matrix4f#multiply
     * multiply} {@link Matrix4f#createRotationY createRotationY} {@link Matrix4f#multiply
     * multiply} {@link Matrix4f#createRotationX createRotationX}.
     * <p>
     * Getting the order right matters: swapping it to {@code Rx * Ry * Rz} produces the same
     * math for single-axis rotations but silently flips the tilt direction for compound poses
     * like the standard {@code [30, 225, 0]} block-icon pose, which shows up as the block's
     * bottom face being visible instead of the top.
     *
     * @param pitchDegrees the rotation about the X axis in degrees (vanilla {@code rotation[0]})
     * @param yawDegrees the rotation about the Y axis in degrees (vanilla {@code rotation[1]})
     * @param rollDegrees the rotation about the Z axis in degrees (vanilla {@code rotation[2]})
     * @return the composed rotation matrix
     */
    public static @NotNull Matrix4f buildGuiDisplayTransform(float pitchDegrees, float yawDegrees, float rollDegrees) {
        return Matrix4f.createRotationZ((float) Math.toRadians(rollDegrees))
            .multiply(Matrix4f.createRotationY((float) Math.toRadians(yawDegrees)))
            .multiply(Matrix4f.createRotationX((float) Math.toRadians(pitchDegrees)));
    }

}
