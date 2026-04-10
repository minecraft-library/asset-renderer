package dev.sbs.renderer.engine;

import org.jetbrains.annotations.NotNull;

/**
 * Describes how strongly to blend an orthographic projection with a perspective projection.
 * <p>
 * A value of {@code amount = 0} produces a pure orthographic projection (used by the isometric
 * engine). Larger values pull vertices towards the camera, approximating a pinhole projection.
 * The blend math lives in {@link RenderEngine} and is shared by every engine that wants a hint
 * of depth without the complexity of a full 3D perspective setup.
 *
 * @param amount the blend factor in {@code [0, 1]} - 0 is pure ortho, 1 is full perspective
 * @param cameraDistance the virtual camera distance in model units
 * @param focalLength the focal length in model units
 */
public record PerspectiveParams(float amount, float cameraDistance, float focalLength) {

    /** A pure orthographic projection with no perspective blend. */
    public static final @NotNull PerspectiveParams NONE = new PerspectiveParams(0f, 0f, 0f);

    /** A moderate perspective suitable for GUI item icons. */
    public static final @NotNull PerspectiveParams GUI_ITEM = new PerspectiveParams(0.3f, 8f, 8f);

}
