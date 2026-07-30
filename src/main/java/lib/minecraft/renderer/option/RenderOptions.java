package lib.minecraft.renderer.option;

import dev.simplified.image.Background;
import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.option.spec.OutputOptions;
import org.jetbrains.annotations.NotNull;

/**
 * Marker implemented by every top-level renderer options type - the upper bound of
 * {@link Renderer}'s type parameter.
 * <p>
 * It exists so {@code Renderer<O extends RenderOptions>} accepts only genuine options bags and rejects
 * arbitrary types. Sub-option value objects composed into an options bag ({@link OutputOptions},
 * {@code ArmorOptions}, and siblings) are not options themselves and do not implement it.
 * <p>
 * The two accessors are declared here rather than left as per-class Lombok getters so a reader holding
 * an {@code O extends RenderOptions} can ask for them at all - the background a finished frame
 * composites over, and the frame a subject is projected through. Both are {@code default} because
 * neither is total over the implementors: <b>six</b> of the eleven declare an {@code output} field and
 * <b>nine</b> a {@code background}, so declaring either abstract would force five public options classes
 * to grow a member they have no use for. Every type that does carry the field overrides the default with
 * its own Lombok accessor, so a default answers only where the field is genuinely absent.
 * <p>
 * <b>A type with no such field answers the neutral value, and that is a real cost rather than a free
 * convenience.</b> Atlas, grid, layout, menu and text options project nothing - they compose images that
 * are already rendered - so they have no output frame and answer a neutral one; menu and text also have
 * no background and answer a transparent one. Nothing reads those answers today. The hazard is that a
 * future generic reader receives a plausible default where it wants a compile error, which is the same
 * silent-miss shape a form-parameterised accessor has elsewhere in this renderer. So a reader that
 * genuinely needs one of these fields should take the concrete options type, or the population should be
 * narrowed to an interface over the six that carry an output frame.
 */
public interface RenderOptions {

    /**
     * The background a finished frame is composited over - {@link Background#TRANSPARENT} for an options
     * type that declares none.
     *
     * @return the render background
     */
    default @NotNull Background getBackground() {
        return Background.TRANSPARENT;
    }

    /**
     * The output frame a subject is projected and rasterized through - the neutral frame for an options
     * type that declares none.
     *
     * @return the output frame
     */
    default @NotNull OutputOptions getOutput() {
        return OutputOptions.defaults();
    }

}
