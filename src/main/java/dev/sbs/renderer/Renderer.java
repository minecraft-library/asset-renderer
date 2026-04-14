package dev.sbs.renderer;

import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.StaticImageData;
import org.jetbrains.annotations.NotNull;

/**
 * Baseline contract for every top-level renderer in the {@code asset-renderer} module.
 * <p>
 * A renderer takes an immutable {@code options} object and produces an {@link ImageData} output,
 * which is either a {@link StaticImageData} (single frame) or an {@link AnimatedImageData}
 * (multiple frames with per-frame delay).
 * <p>
 * Implementations are stateless between calls - all input comes from the options object and the
 * ambient pack / model repositories configured on the renderer's engine.
 *
 * @param <O> the options type accepted by this renderer
 */
public interface Renderer<O> {

    /**
     * Renders the given options into an image.
     *
     * @param options the options describing what to render
     * @return the rendered image data
     */
    @NotNull ImageData render(@NotNull O options);

}
