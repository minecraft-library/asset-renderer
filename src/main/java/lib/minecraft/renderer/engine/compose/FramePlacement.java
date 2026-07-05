package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.ImageData;
import org.jetbrains.annotations.NotNull;

/**
 * A single positioned image in a {@link FrameCompositor} composition - a whole static or animated
 * sub-render placed at a destination origin.
 * <p>
 * Pure data: a {@code FrameLayer} contributes {@code FramePlacement}s to the shared placement sink, and
 * {@link FrameCompositor} blits or time-samples them per output frame.
 *
 * @param x the destination x origin on the merged canvas
 * @param y the destination y origin on the merged canvas
 * @param source the layer's image data, either static or animated
 */
public record FramePlacement(int x, int y, @NotNull ImageData source) {

}
