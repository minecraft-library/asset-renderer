package dev.sbs.renderer.engine;

import dev.sbs.renderer.math.Matrix4f;
import dev.sbs.renderer.math.Vector3f;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link ModelEngine} configured for the Minecraft wiki's standardized isometric view.
 * <p>
 * Fixes the camera transform to a 30 degree pitch and 45 degree yaw, producing the familiar
 * three-quarter view used by block icons and player skulls across the wiki and most third-party
 * renderers. Callers should pass {@link PerspectiveParams#NONE} when calling {@link #rasterize}
 * to get a pure orthographic projection that matches vanilla icons pixel-for-pixel.
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
     * The default orthographic perspective for isometric rendering. Exposed so callers do not
     * have to repeat {@link PerspectiveParams#NONE} at every call site.
     *
     * @return the orthographic perspective params
     */
    public @NotNull PerspectiveParams defaultPerspective() {
        return PerspectiveParams.NONE;
    }

    private static @NotNull Matrix4f buildCameraTransform() {
        float pitch = (float) Math.toRadians(30d);
        float yaw = (float) Math.toRadians(45d);

        // Y first (yaw), then X (pitch) so the camera looks down and to the right at the cube.
        Matrix4f yawMatrix = Matrix4f.createRotationY(yaw);
        Matrix4f pitchMatrix = Matrix4f.createRotationX(pitch);

        // Flip Y axis so the world +Y (up) maps to screen -Y, matching Java's top-left origin.
        Matrix4f flipY = Matrix4f.createScale(new Vector3f(1f, -1f, 1f));
        return flipY.multiply(pitchMatrix.multiply(yawMatrix));
    }

}
