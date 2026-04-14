package dev.sbs.renderer.geometry;

import dev.sbs.renderer.engine.RenderEngine;
import org.jetbrains.annotations.NotNull;

/**
 * Describes how strongly to blend an orthographic projection with a perspective projection and
 * how much of the output framebuffer the projected geometry should fill.
 * <p>
 * A value of {@code amount = 0} produces a pure orthographic projection (used by the isometric
 * engine). Larger values pull vertices towards the camera, approximating a pinhole projection.
 * {@link #projectionScale()} is the multiplier applied to model-space coordinates during
 * projection - it controls how much of the output tile the projected geometry covers and
 * supplies the safety margin for rotated or multi-element geometry that would otherwise clip
 * the framebuffer edges. The blend math lives in {@link RenderEngine} and is shared by every
 * engine that wants a hint of depth without the complexity of a full 3D perspective setup.
 *
 * @param amount the blend factor in {@code [0, 1]} - 0 is pure ortho, 1 is full perspective
 * @param cameraDistance the virtual camera distance in model units
 * @param focalLength the focal length in model units
 * @param projectionScale the multiplier applied to model-space coordinates relative to the
 *     output tile's smaller dimension - {@code 0.4f} leaves ~30% margin per side for rotated
 *     geometry, {@code 0.65f} fills an isometric-projected unit cube to ~90% of the tile
 */
public record PerspectiveParams(float amount, float cameraDistance, float focalLength, float projectionScale) {

    /**
     * A pure orthographic projection with no perspective blend and the conservative
     * {@code 0.4f} scale - leaves generous margin for rotated or limb-bearing geometry.
     * Used by {@code PlayerRenderer} and any caller that renders articulated models which
     * extend beyond the unit cube after animation.
     */
    public static final @NotNull PerspectiveParams NONE = new PerspectiveParams(0f, 0f, 0f, 0.4f);

    /** A moderate perspective suitable for GUI item icons. */
    public static final @NotNull PerspectiveParams GUI_ITEM = new PerspectiveParams(0.3f, 8f, 8f, 0.4f);

    /**
     * A pure orthographic projection tuned for isometric block renders: a unit cube projected
     * at standard 30°/45° pitch/yaw spans roughly {@code sqrt(2) ≈ 1.414} in model-space
     * width after rotation, so a {@code 0.65f} scale makes the block fill ~92% of the output
     * tile while leaving a small safety margin for stairs, slabs, and multi-element geometry
     * that extend beyond the unit cube under their variant rotations.
     */
    public static final @NotNull PerspectiveParams ISOMETRIC_BLOCK = new PerspectiveParams(0f, 0f, 0f, 0.65f);

}
