package lib.minecraft.renderer.engine.frame;

import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A single positioned image in a {@link FrameCompositor} composition - a whole static or animated
 * sub-render placed at a destination origin.
 * <p>
 * Pure data: a {@code FrameLayer} contributes {@code FramePlacement}s to the shared placement sink, and
 * {@link FrameCompositor} blits or time-samples them per output frame.
 * <p>
 * A placement without an extent draws the picture its source shows at each output frame at that
 * picture's own size. A placement with one rescales the picture into the extent's rectangle at the
 * origin by the nearest-neighbour sampling of {@link PixelBuffer#blitScaled}. Both composite
 * source-over, unless the placement is additive, when every compositor adds its picture's colour to
 * the canvas under it. What picture an animated source shows at a playback time is
 * {@link FrameCompositor}'s to say.
 *
 * @param x the destination x origin on the merged canvas
 * @param y the destination y origin on the merged canvas
 * @param source the layer's image data, either static or animated
 * @param extent the rectangle each picture is rescaled into from the origin, or empty for each picture's own size
 * @param additive whether the picture is added to what lies under it rather than laid over it
 */
public record FramePlacement(int x, int y, @NotNull ImageData source, @NotNull Optional<Extent> extent, boolean additive) {

    /**
     * Constructs a new {@code FramePlacement} that draws each frame at its own size, laid over what
     * lies under it.
     *
     * @param x the destination x origin on the merged canvas
     * @param y the destination y origin on the merged canvas
     * @param source the layer's image data, either static or animated
     */
    public FramePlacement(int x, int y, @NotNull ImageData source) {
        this(x, y, source, Optional.empty(), false);
    }

    /**
     * Returns this placement drawing its source into a {@code width} by {@code height} rectangle at
     * the same origin. A source already that size is drawn as it is, with no extent; a source of any
     * other size takes the rectangle as its extent and is rescaled into it.
     *
     * @param width the rectangle's width in pixels
     * @param height the rectangle's height in pixels
     * @return the fitted placement
     */
    public @NotNull FramePlacement fittedTo(int width, int height) {
        boolean asItIs = this.source.getWidth() == width && this.source.getHeight() == height;
        return new FramePlacement(this.x, this.y, this.source, asItIs ? Optional.empty() : Optional.of(new Extent(width, height)), this.additive);
    }

    /**
     * Returns this placement with its picture's colour added to what lies under it rather than laid
     * over it, as the client's additive GUI pipeline lays a text field's highlight band.
     *
     * @return the additive placement
     */
    public @NotNull FramePlacement additively() {
        return new FramePlacement(this.x, this.y, this.source, this.extent, true);
    }

    /**
     * The size of the rectangle a fitted placement rescales each frame into.
     *
     * @param width the rectangle's width in pixels
     * @param height the rectangle's height in pixels
     */
    public record Extent(int width, int height) {}

}
