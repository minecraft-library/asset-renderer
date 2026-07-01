package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

/**
 * The camera's projection - the 3D-to-2D flatten that turns a camera-space point into screen
 * coordinates, plus how much of the output framebuffer the projected geometry fills. The intrinsics
 * half of the camera, paired with {@link Camera}'s pose and selected through
 * {@link Projection}.
 * <p>
 * Three flatten families are supported, discriminated by {@link #kind()}:
 * <ul>
 * <li><b>{@link Kind#ORTHOGRAPHIC}</b> - parallel projection, depth discarded. Backs the axonometric
 *     views (isometric / dimetric / trimetric) and the vanilla iso block preset.</li>
 * <li><b>{@link Kind#PERSPECTIVE}</b> - a pinhole projection blended with orthographic by
 *     {@link #amount()}; vertices foreshorten toward {@link #cameraDistance()} / {@link #focalLength()}.
 *     Backs the central-perspective views (one / two / three point) and GUI item icons.</li>
 * <li><b>{@link Kind#OBLIQUE}</b> - a parallel projection that shears the depth axis by
 *     {@link #obliqueDepthFactor()} at {@link #obliqueAngleRadians()}. Backs cavalier / cabinet /
 *     military.</li>
 * </ul>
 * {@link #projectionScale()} is the multiplier applied to model-space coordinates relative to the
 * output tile's smaller dimension; it controls how much of the tile the geometry covers and supplies
 * the safety margin for rotated or multi-element geometry that would otherwise clip the framebuffer.
 * Reach for the {@link #orthographic}, {@link #perspective}, {@link #oblique} factories rather than the
 * canonical constructor. {@link #project} carries the per-family flatten math.
 *
 * @param kind the flatten family selecting which arm of {@link #project} runs
 * @param amount the orthographic-to-perspective blend in {@code [0, 1]} for {@link Kind#PERSPECTIVE} -
 *     0 is pure ortho, 1 full perspective; unused by the other families
 * @param cameraDistance the virtual camera distance in model units for {@link Kind#PERSPECTIVE}
 * @param focalLength the focal length in model units for {@link Kind#PERSPECTIVE}
 * @param obliqueDepthFactor the depth foreshortening for {@link Kind#OBLIQUE} - cavalier 1.0, cabinet 0.5
 * @param obliqueAngleRadians the signed receding-axis angle for {@link Kind#OBLIQUE}, in radians
 * @param projectionScale the multiplier applied to model-space coordinates relative to the output
 *     tile's smaller dimension - {@code 0.4} leaves ~30% margin per side for rotated geometry,
 *     {@link #ISOMETRIC_BLOCK} uses vanilla's {@code 0.625} {@code display.gui.scale}
 */
public record Lens(
    @NotNull Kind kind,
    float amount,
    float cameraDistance,
    float focalLength,
    float obliqueDepthFactor,
    float obliqueAngleRadians,
    float projectionScale
) {

    /**
     * The 3D-to-2D flatten family of a {@link Lens}, selecting which arm of {@link #project}
     * runs.
     */
    public enum Kind {

        /**
         * Parallel projection - depth discarded, x/y scaled uniformly. Backs the axonometric views.
         */
        ORTHOGRAPHIC,

        /**
         * Pinhole projection blended with orthographic by {@link #amount()}. Backs central perspective.
         */
        PERSPECTIVE,

        /**
         * Parallel projection with the depth axis sheared by {@link #obliqueDepthFactor()} at
         * {@link #obliqueAngleRadians()}. Backs cavalier / cabinet / military.
         */
        OBLIQUE

    }

    /**
     * Vanilla's {@code display.gui.scale} for the root {@code block/block.json} model. Every block
     * inherits this unless its own model overrides the gui display transform. With the standard
     * {@code [30, 225, 0]} iso rotation it produces a cube silhouette of {@code 0.625 · √2 ≈ 0.884}
     * wide × {@code 0.625 · (cos30° + √2·sin30°) ≈ 0.983} tall relative to the inventory slot,
     * pixel-identical to the vanilla-reference-harness PNGs at the chosen render size.
     */
    private static final float BLOCK_GUI_DISPLAY_SCALE = 0.625f;

    /**
     * Conservative scale margin used by the presets that cannot assume a tight unit-cube silhouette.
     * Leaves ~30% of the tile empty per side so rotated, articulated, or limb-bearing geometry
     * (players, entities, held items) never clips the framebuffer.
     */
    private static final float CONSERVATIVE_PROJECTION_SCALE = 0.4f;

    /**
     * Perspective blend factor baked into {@link #GUI_ITEM} - a moderate ortho/perspective mix that
     * gives held item icons a faint 3D feel without the extreme foreshortening of a full pinhole.
     */
    private static final float GUI_ITEM_PERSPECTIVE_AMOUNT = 0.3f;

    /**
     * Virtual camera distance (in model units) for {@link #GUI_ITEM}. Matched to the focal length so
     * the blend stays centred around the model origin.
     */
    private static final float GUI_ITEM_CAMERA_DISTANCE = 8f;

    /**
     * Focal length (in model units) for {@link #GUI_ITEM}. See {@link #GUI_ITEM_CAMERA_DISTANCE}.
     */
    private static final float GUI_ITEM_FOCAL_LENGTH = 8f;

    /**
     * Creates an orthographic (parallel) projection with the given screen-fill scale. Depth is
     * discarded; x/y scale uniformly.
     *
     * @param projectionScale the model-to-tile screen-fill multiplier
     * @return the orthographic projection
     */
    public static @NotNull Lens orthographic(float projectionScale) {
        return new Lens(Kind.ORTHOGRAPHIC, 0f, 0f, 0f, 0f, 0f, projectionScale);
    }

    /**
     * Creates a perspective projection blending orthographic with a pinhole foreshortening.
     *
     * @param amount the ortho-to-perspective blend in {@code [0, 1]}
     * @param cameraDistance the virtual camera distance in model units
     * @param focalLength the focal length in model units
     * @param projectionScale the model-to-tile screen-fill multiplier
     * @return the perspective projection
     */
    public static @NotNull Lens perspective(float amount, float cameraDistance, float focalLength, float projectionScale) {
        return new Lens(Kind.PERSPECTIVE, amount, cameraDistance, focalLength, 0f, 0f, projectionScale);
    }

    /**
     * Creates an oblique projection - a parallel projection that shears the depth axis.
     *
     * @param depthFactor the depth foreshortening (cavalier 1.0, cabinet 0.5)
     * @param angleRadians the signed receding-axis angle in radians
     * @param projectionScale the model-to-tile screen-fill multiplier
     * @return the oblique projection
     */
    public static @NotNull Lens oblique(float depthFactor, float angleRadians, float projectionScale) {
        return new Lens(Kind.OBLIQUE, 0f, 0f, 0f, depthFactor, angleRadians, projectionScale);
    }

    /**
     * A pure orthographic projection with no perspective blend and the conservative scale - leaves
     * generous margin for rotated or limb-bearing geometry that extends beyond the unit cube after
     * animation. The neutral flatten for any caller that wants parallel projection without an
     * axonometric preset's tighter fill.
     */
    public static final @NotNull Lens NONE = orthographic(CONSERVATIVE_PROJECTION_SCALE);

    /**
     * A moderate perspective suitable for GUI item icons.
     */
    public static final @NotNull Lens GUI_ITEM = perspective(
        GUI_ITEM_PERSPECTIVE_AMOUNT, GUI_ITEM_CAMERA_DISTANCE, GUI_ITEM_FOCAL_LENGTH, CONSERVATIVE_PROJECTION_SCALE
    );

    /**
     * A pure orthographic projection tuned for isometric block renders. Scale is vanilla's own
     * {@link #BLOCK_GUI_DISPLAY_SCALE 0.625} {@code display.gui.scale} literal so the projected
     * silhouette of a unit cube at the iso pose matches the vanilla-reference harness PNGs
     * byte-for-byte ({@code 0.625 · √2 ≈ 0.884} wide × {@code 0.625 · 1.5731 ≈ 0.983} tall in
     * unit-slot coordinates). Stairs / slabs / fence gates carry their own {@code display.gui}
     * overrides which the block-icon camera honours, so they fit at vanilla's footprint too.
     */
    public static final @NotNull Lens ISOMETRIC_BLOCK = orthographic(BLOCK_GUI_DISPLAY_SCALE);

    /**
     * Projects a model-space point onto 2D screen coordinates under this projection's
     * {@link #kind() flatten family}. {@link Kind#ORTHOGRAPHIC} discards depth; {@link Kind#PERSPECTIVE}
     * blends in pinhole foreshortening toward {@link #cameraDistance()} / {@link #focalLength()};
     * {@link Kind#OBLIQUE} shears the depth axis by {@link #obliqueDepthFactor()} at
     * {@link #obliqueAngleRadians()}.
     *
     * @param point the 3D point to project
     * @param scale the uniform screen-space scale factor
     * @param offsetX the horizontal screen offset to apply after scaling
     * @param offsetY the vertical screen offset to apply after scaling
     * @return the projected 2D point
     */
    public @NotNull Vector2f project(@NotNull Vector3f point, float scale, float offsetX, float offsetY) {
        return switch (this.kind) {
            case ORTHOGRAPHIC -> new Vector2f(point.x() * scale + offsetX, -point.y() * scale + offsetY);
            case PERSPECTIVE -> {
                float denom = this.cameraDistance - point.z();
                float perspectiveFactor = denom == 0f ? 1f : (this.focalLength / denom);
                float blended = 1f + (perspectiveFactor - 1f) * this.amount;
                yield new Vector2f(point.x() * scale * blended + offsetX, -point.y() * scale * blended + offsetY);
            }
            case OBLIQUE -> {
                float shearX = (float) Math.cos(this.obliqueAngleRadians) * this.obliqueDepthFactor;
                float shearY = (float) Math.sin(this.obliqueAngleRadians) * this.obliqueDepthFactor;
                float z = point.z();
                yield new Vector2f(
                    (point.x() + z * shearX) * scale + offsetX,
                    -(point.y() + z * shearY) * scale + offsetY
                );
            }
        };
    }

}
