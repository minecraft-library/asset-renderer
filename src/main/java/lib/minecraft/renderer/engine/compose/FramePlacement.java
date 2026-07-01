package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import org.jetbrains.annotations.NotNull;

/**
 * A single positioned image layer in a {@link FrameCompositor} composition - a whole static or
 * animated sub-render placed at a destination origin.
 * <p>
 * Implements {@link FrameLayer} by contributing itself, so a {@code List<FramePlacement>} is directly
 * a list of frame layers and callers that build placement lists need no wrapping.
 *
 * @param x the destination x origin on the merged canvas
 * @param y the destination y origin on the merged canvas
 * @param source the layer's image data, either static or animated
 */
public record FramePlacement(int x, int y, @NotNull ImageData source) implements FrameLayer {

    /** {@inheritDoc} */
    @Override
    public void contribute(@NotNull ConcurrentList<FramePlacement> sink) {
        sink.add(this);
    }
}
