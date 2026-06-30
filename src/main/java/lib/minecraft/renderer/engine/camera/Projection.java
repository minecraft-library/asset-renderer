package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

/**
 * The camera's projection - how strongly an orthographic projection blends with a perspective one,
 * and how much of the output framebuffer the projected geometry fills. The intrinsics half of the
 * camera, paired with {@link Camera}'s pose.
 * <p>
 * A value of {@code amount = 0} produces a pure orthographic projection (the isometric presets).
 * Larger values pull vertices towards the camera, approximating a pinhole projection.
 * {@link #projectionScale()} is the multiplier applied to model-space coordinates during
 * projection - it controls how much of the output tile the projected geometry covers and supplies
 * the safety margin for rotated or multi-element geometry that would otherwise clip the framebuffer
 * edges. {@link #project} carries the blend math.
 *
 * @param amount the blend factor in {@code [0, 1]} - 0 is pure ortho, 1 is full perspective
 * @param cameraDistance the virtual camera distance in model units
 * @param focalLength the focal length in model units
 * @param projectionScale the multiplier applied to model-space coordinates relative to the
 *     output tile's smaller dimension - {@code 0.4f} leaves ~30% margin per side for rotated
 *     geometry, {@link #ISOMETRIC_BLOCK} derives its scale from the rotated unit-cube
 *     bounding box plus a small padding margin
 */
public record Projection(float amount, float cameraDistance, float focalLength, float projectionScale) {

    /**
     * Vanilla's {@code display.gui.scale} for the root {@code block/block.json} model. Every
     * block inherits this unless its own model overrides the gui display transform. With the
     * standard {@code [30, 225, 0]} iso rotation it produces a cube silhouette of
     * {@code 0.625 · √2 ≈ 0.884} wide × {@code 0.625 · (cos30° + √2·sin30°) ≈ 0.983} tall
     * relative to the inventory slot, leaving a thin band at the top and bottom and ~12% on
     * each side. Matched against the vanilla-reference-harness PNGs the silhouette is
     * pixel-identical at the chosen render size.
     */
    private static final float BLOCK_GUI_DISPLAY_SCALE = 0.625f;

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
    public static final @NotNull Projection NONE = new Projection(
        0f, 0f, 0f, CONSERVATIVE_PROJECTION_SCALE
    );

    /**
     * A moderate perspective suitable for GUI item icons.
     */
    public static final @NotNull Projection GUI_ITEM = new Projection(
        GUI_ITEM_PERSPECTIVE_AMOUNT,
        GUI_ITEM_CAMERA_DISTANCE,
        GUI_ITEM_FOCAL_LENGTH,
        CONSERVATIVE_PROJECTION_SCALE
    );

    /**
     * A pure orthographic projection tuned for isometric block renders. Scale is vanilla's
     * own {@link #BLOCK_GUI_DISPLAY_SCALE 0.625} {@code display.gui.scale} literal so the
     * projected silhouette of a unit cube at the iso pose matches the vanilla-reference
     * harness PNGs byte-for-byte ({@code 0.625 · √2 ≈ 0.884} wide × {@code 0.625 · 1.5731
     * ≈ 0.983} tall in unit-slot coordinates). Stairs / slabs / fence gates carry their
     * own {@code display.gui} overrides which {@code engineForBlockIcon} honours, so they
     * fit at vanilla's footprint too rather than relying on a generic padding margin.
     */
    public static final @NotNull Projection ISOMETRIC_BLOCK = new Projection(
        0f, 0f, 0f, BLOCK_GUI_DISPLAY_SCALE
    );

    /**
     * Projects a model-space point onto 2D screen coordinates under this projection. With
     * {@code amount == 0} this is a pure orthographic projection; larger values blend in
     * perspective foreshortening toward {@link #cameraDistance()} / {@link #focalLength()}.
     *
     * @param point the 3D point to project
     * @param scale the uniform screen-space scale factor
     * @param offsetX the horizontal screen offset to apply after scaling
     * @param offsetY the vertical screen offset to apply after scaling
     * @return the projected 2D point
     */
    public @NotNull Vector2f project(@NotNull Vector3f point, float scale, float offsetX, float offsetY) {
        if (this.amount <= 0f)
            return new Vector2f(point.x() * scale + offsetX, -point.y() * scale + offsetY);

        float denom = this.cameraDistance - point.z();
        float perspectiveFactor = denom == 0f ? 1f : (this.focalLength / denom);
        float blended = 1f + (perspectiveFactor - 1f) * this.amount;
        return new Vector2f(point.x() * scale * blended + offsetX, -point.y() * scale * blended + offsetY);
    }

}
