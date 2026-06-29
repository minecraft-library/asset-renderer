package lib.minecraft.renderer.compose;

import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * A 2D composite layer that draws onto a shared frame in stack order.
 * <p>
 * This is the literal "stackable stencil" model: each layer reads and writes the same
 * {@link PixelBuffer}, and applying the whole ordered stack yields the final frame. Layers run in the
 * order produced by {@link LayerStack#ordered()}; a layer may composite a sprite, tint, overlay, or
 * decoration, and captures whatever per-render inputs it needs at construction. Frame-multiplying
 * effects (animated glint) are handled by the renderer's finalisation stage rather than an
 * {@code ImageLayer}, because they change the output from one buffer to many.
 */
@FunctionalInterface
public interface ImageLayer {

    /**
     * Composites this layer onto {@code frame} in place.
     *
     * @param frame the shared pixel buffer accumulated by the layer stack
     */
    void apply(@NotNull PixelBuffer frame);
}
