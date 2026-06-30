package lib.minecraft.renderer.engine.camera;

/**
 * The horizontal facing of a {@link GraphicalProjection} - which side of the subject turns toward the
 * viewer. {@link #RIGHT} is the default and reproduces the vanilla three-quarter orientation;
 * {@link #LEFT} mirrors it about the front-facing vertical plane (yaw reflected about the
 * camera-facing direction).
 */
public enum HorizontalFacing {

    /**
     * The subject's right-front corner faces the camera - the default, vanilla orientation.
     */
    RIGHT,

    /**
     * The subject's left-front corner faces the camera - the horizontal mirror of {@link #RIGHT}.
     */
    LEFT

}
