package lib.minecraft.renderer.geometry;

import lib.minecraft.renderer.engine.RenderEngine;
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
 *     geometry, {@link #ISOMETRIC_BLOCK} derives its scale from the rotated unit-cube
 *     bounding box plus a small padding margin
 */
public record PerspectiveParams(float amount, float cameraDistance, float focalLength, float projectionScale) {

    /**
     * Vertical extent of a unit cube after the standard {@code [30, 225, 0]} iso rotation,
     * relative to the model-space unit. Derived geometrically: yaw 225° rotates the cube so
     * its top-face diagonal lies along the screen X axis (width {@code √2}); pitch 30° then
     * tilts the top edge down by {@code cos(30°)} and pulls the front-bottom corner
     * vertically by {@code √2 · sin(30°)}, giving the projected height
     * {@code cos(30°) + √2 · sin(30°) ≈ 1.5731}. This is the cube silhouette's longer axis -
     * the width is only {@code √2 ≈ 1.4142} - so it determines the tightest scale that keeps
     * a vanilla cobblestone-style full cube inside its tile.
     */
    private static final float ISO_CUBE_PROJECTED_HEIGHT = (float) (
        Math.cos(Math.toRadians(30)) + Math.sqrt(2) * Math.sin(Math.toRadians(30))
    );

    /**
     * Fractional margin left empty on each side of the tile around the iso-projected cube.
     * Small enough that the block still dominates the tile, large enough to absorb the few
     * extra pixels stairs, slabs, and other variant-rotated geometry can poke past the unit
     * cube envelope.
     */
    private static final float ISO_CUBE_PADDING = 0.04f;

    /**
     * Conservative scale margin used by the presets that cannot assume a tight unit-cube
     * silhouette. Leaves ~30% of the tile empty per side so rotated, articulated, or
     * limb-bearing geometry (players, entities, held items) never clips the framebuffer.
     */
    private static final float CONSERVATIVE_PROJECTION_SCALE = 0.4f;

    /**
     * Perspective blend factor baked into {@link #GUI_ITEM} - a moderate ortho/perspective mix
     * that gives held item icons a faint 3D feel without the extreme foreshortening of a full
     * pinhole projection.
     */
    private static final float GUI_ITEM_PERSPECTIVE_AMOUNT = 0.3f;

    /**
     * Virtual camera distance (in model units) for {@link #GUI_ITEM}. Matched to the focal
     * length so the blend stays centred around the model origin.
     */
    private static final float GUI_ITEM_CAMERA_DISTANCE = 8f;

    /**
     * Focal length (in model units) for {@link #GUI_ITEM}. See {@link #GUI_ITEM_CAMERA_DISTANCE}.
     */
    private static final float GUI_ITEM_FOCAL_LENGTH = 8f;

    /**
     * A pure orthographic projection with no perspective blend and the conservative scale -
     * leaves generous margin for rotated or limb-bearing geometry. Used by {@code PlayerRenderer}
     * and any caller that renders articulated models which extend beyond the unit cube after
     * animation.
     */
    public static final @NotNull PerspectiveParams NONE = new PerspectiveParams(
        0f, 0f, 0f, CONSERVATIVE_PROJECTION_SCALE
    );

    /** A moderate perspective suitable for GUI item icons. */
    public static final @NotNull PerspectiveParams GUI_ITEM = new PerspectiveParams(
        GUI_ITEM_PERSPECTIVE_AMOUNT,
        GUI_ITEM_CAMERA_DISTANCE,
        GUI_ITEM_FOCAL_LENGTH,
        CONSERVATIVE_PROJECTION_SCALE
    );

    /**
     * A pure orthographic projection tuned for isometric block renders. The scale is computed
     * directly from a unit cube's screen-space silhouette at the standard {@code [30, 225, 0]}
     * pitch/yaw/roll pose used by vanilla's block inventory icon:
     * <ul>
     * <li>width  = {@code √2                       ≈ 1.4142} - top-face diagonal</li>
     * <li>height = {@code cos(30°) + √2 · sin(30°) ≈ 1.5731} - top edge plus foreshortened side</li>
     * </ul>
     * Height is the constraint, so {@code scale = (1 − 2 · padding) / height}. With
     * {@link #ISO_CUBE_PADDING 4% padding} per side this yields
     * {@code 0.92 / 1.5731 ≈ 0.5848}, leaving the cube's corners safely inside the tile -
     * the previous hand-tuned {@code 0.65f} sized for width alone and silently let the iso
     * top corner clip the tile by ~2% in height. Stairs, slabs, and other variant-rotated
     * geometry that extend marginally past the unit cube still fit because the horizontal
     * axis is far from saturated ({@code 0.5848 · 1.4142 ≈ 0.827}, ~17% horizontal headroom).
     */
    public static final @NotNull PerspectiveParams ISOMETRIC_BLOCK = new PerspectiveParams(
        0f, 0f, 0f,
        (1f - 2f * ISO_CUBE_PADDING) / ISO_CUBE_PROJECTED_HEIGHT
    );

}
