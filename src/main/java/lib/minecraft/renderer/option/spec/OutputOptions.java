package lib.minecraft.renderer.option.spec;

import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.engine.camera.Facing;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.request.EulerRotation;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The output frame shared by the subject renderers (block, entity, item, player, fluid, portal):
 * output size, graphical projection, view-facing reflection, model rotation, and the SSAA /
 * FXAA anti-aliasing knobs. Composed into each subject's options as one {@code output} field so the
 * frame is declared once rather than re-spelled per renderer.
 * <p>
 * Defaults are neutral - {@link Renderer#DEFAULT_CANVAS_SIZE} pixels, {@link Projection#VANILLA_ISO},
 * {@link Facing#DEFAULT}, {@link EulerRotation#NONE}, no supersampling, no FXAA. A subject that needs a
 * different default (the item icon's {@link Projection#VANILLA_GUI_ITEM}) pins it in its own
 * {@code DEFAULT_OUTPUT} constant.
 *
 * @see Renderer
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class OutputOptions {

    /**
     * Output image dimensions in pixels (square), defaulting to {@link Renderer#DEFAULT_CANVAS_SIZE}.
     */
    @lombok.Builder.Default
    private final int canvasSize = Renderer.DEFAULT_CANVAS_SIZE;

    /**
     * Graphical projection posing the camera and its lens. Defaults to {@link Projection#VANILLA_ISO}.
     */
    @lombok.Builder.Default
    private final @NotNull Projection projection = Projection.VANILLA_ISO;

    /**
     * View-facing reflection applied to the {@link #getProjection() projection}. Defaults to
     * {@link Facing#DEFAULT} (no reflection).
     */
    @lombok.Builder.Default
    private final @NotNull Facing facing = Facing.DEFAULT;

    /**
     * User-override model rotation applied before the camera transform, in degrees. Defaults to
     * {@link EulerRotation#NONE}.
     */
    @lombok.Builder.Default
    private final @NotNull EulerRotation rotation = EulerRotation.NONE;

    /**
     * Supersample scale factor - the subject is rasterized at {@code canvasSize * supersample} then
     * downsampled for sharper edges (SSAA). A value of {@code 1} (default) disables supersampling.
     */
    @lombok.Builder.Default
    private final int supersample = 1;

    /**
     * Whether to apply FXAA post-processing on the rasterized buffer. Default {@code false}; when
     * {@link #supersample} is {@code > 1}, FXAA runs on the hi-res buffer before downsampling.
     */
    @lombok.Builder.Default
    private final boolean antiAlias = false;

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a
     * few fields changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull OutputOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default output frame
     */
    public static @NotNull OutputOptions defaults() {
        return builder().build();
    }
}
