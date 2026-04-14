package dev.sbs.renderer.engine;

import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.tensor.Matrix4f;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link ModelEngine} configured for the Minecraft wiki's standardized isometric view.
 * <p>
 * Fixes the camera transform to a 30 degree pitch and 45 degree yaw, producing the familiar
 * three-quarter view used by block icons and player skulls across the wiki and most third-party
 * renderers. Callers should pass {@link PerspectiveParams#ISOMETRIC_BLOCK} when calling
 * {@link #rasterize} to get a pure orthographic projection tuned so a unit cube fills the
 * output tile - or {@link PerspectiveParams#NONE} for a more conservative scale that matches
 * the articulated-figure preset used by {@code PlayerRenderer}.
 */
public class IsometricEngine extends ModelEngine {

    private static final @NotNull Matrix4f CAMERA = buildCameraTransform();

    public IsometricEngine(@NotNull RendererContext context) {
        super(context);
    }

    @Override
    public @NotNull Matrix4f cameraTransform() {
        return CAMERA;
    }

    /**
     * The default orthographic perspective for isometric rendering. Returns
     * {@link PerspectiveParams#ISOMETRIC_BLOCK} so a unit cube fills the output tile without
     * excess padding.
     *
     * @return the orthographic perspective params
     */
    public @NotNull PerspectiveParams defaultPerspective() {
        return PerspectiveParams.ISOMETRIC_BLOCK;
    }

    private static @NotNull Matrix4f buildCameraTransform() {
        // Vanilla's gui display transform specifies rotation [30, 225, 0] in Minecraft's
        // left-handed model space (+Z = south). Our rotation matrices use right-handed
        // convention, so the Y rotation is negated: 225 LH = -225 RH = -45 RH (mod 360 =
        // 315). The camera is Ry(-45) * Rx(30) in row-vector convention (v * M), matching
        // the standard yaw-then-pitch composition order. The world-to-screen Y inversion
        // (world +Y up, screen +Y down) is handled by negating Y in the projection step.
        float pitch = (float) Math.toRadians(30d);
        float yaw = (float) Math.toRadians(-45d);

        Matrix4f yawMatrix = Matrix4f.createRotationY(yaw);
        Matrix4f pitchMatrix = Matrix4f.createRotationX(pitch);

        // Row-vector convention (v * M): yaw applied first, then pitch.
        return yawMatrix.multiply(pitchMatrix);
    }

}
