package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.StaticImageData;
import lib.minecraft.renderer.option.Option;
import org.jetbrains.annotations.NotNull;

/**
 * Baseline contract for every top-level renderer in the {@code asset-renderer} module.
 * <p>
 * A renderer takes an immutable {@code options} object and produces an {@link ImageData} output,
 * which is either a {@link StaticImageData} (single frame) or an {@link AnimatedImageData}
 * (multiple frames with per-frame delay).
 * <p>
 * Implementations are stateless between calls - all per-render input comes from the {@code options}
 * object, and all ambient pack / model / texture lookups come from the {@code RendererContext} the
 * implementation is constructed with.
 *
 * @param <O> the options type accepted by this renderer; implements {@link Option}
 */
public interface Renderer<O extends Option> {

    /**
     * Shared square-pixel default output size (in pixels) for single-subject renders. Consumed by
     * {@code BlockOptions}, {@code EntityOptions}, {@code ItemOptions}, {@code PlayerOptions},
     * {@code FluidOptions}, and {@code PortalOptions} as the default value of their
     * {@code outputSize} field so the subject-scoped renderers all agree on one tile dimension out
     * of the box.
     */
    int DEFAULT_OUTPUT_SIZE = 256;

    /**
     * Renders the given options into an image, either a {@link StaticImageData} single frame or an
     * {@link AnimatedImageData} multi-frame result depending on the subject and options.
     *
     * @param options the options describing what to render
     * @return the rendered image data
     */
    @NotNull ImageData render(@NotNull O options);

}
