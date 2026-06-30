package lib.minecraft.renderer.engine.camera;

/**
 * The vertical facing of a {@link GraphicalProjection} - whether the camera looks down at the
 * subject's top ({@link #DOWN}, the default bird's-eye view) or up at its underside ({@link #UP}).
 */
public enum VerticalFacing {

    /**
     * The camera looks down at the subject's top - the default.
     */
    DOWN,

    /**
     * The camera looks up at the subject's underside - the vertical mirror of {@link #DOWN}.
     */
    UP

}
