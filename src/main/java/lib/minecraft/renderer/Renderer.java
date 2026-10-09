package lib.minecraft.renderer;

import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.StaticImageData;
import lib.minecraft.renderer.call.request.RenderOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import org.jetbrains.annotations.NotNull;

/**
 * Baseline contract for every top-level renderer in the {@code asset-renderer} module.
 * <p>
 * A renderer takes an immutable {@code options} object and answers a {@link RenderResult}: the drawn
 * image, a {@link StaticImageData} for one frame or an {@link AnimatedImageData} for several, and every
 * stand-in drawn in it. A renderer that places other renders answers a narrower result by a covariant
 * override, naming where each was drawn.
 * <p>
 * Implementations are stateless between calls - all per-render input comes from the {@code options}
 * object, and all ambient pack / model / texture lookups come from the {@code RendererContext} the
 * implementation is constructed with.
 *
 * @param <O> the options type accepted by this renderer; implements {@link RenderOptions}
 */
public interface Renderer<O extends RenderOptions> {

    /**
     * Renders the options into an image and every stand-in drawn in it, the image either a
     * {@link StaticImageData} single frame or an {@link AnimatedImageData} multi-frame result depending
     * on the subject and options.
     *
     * @param options the options describing what to render
     * @return the rendered image and its stand-ins
     */
    @NotNull RenderResult render(@NotNull O options);

}
