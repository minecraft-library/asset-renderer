package lib.minecraft.renderer.engine.compose.layer;

import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

/**
 * The single consume path every renderer shares: collapse a {@link LayerStack} into its accumulator.
 */
@UtilityClass
public class Layers {

    /**
     * Applies the caller's {@code decorator} to {@code stack}, flattens it into render order
     * ({@link LayerStack#ordered()}), and folds each layer into {@code target} in that order. The one
     * fold shared by every renderer - geometry into a triangle sink, image into a pixel buffer, frame
     * into a placement sink.
     *
     * @param stack the built-in layer stack
     * @param decorator the caller's splice hook applied before flattening (identity by default)
     * @param target the shared accumulator each layer contributes to
     * @param <A> the accumulator type
     * @param <L> the layer type held by the stack
     */
    public static <A, L extends Layer<A>> void foldInto(
        @NotNull LayerStack<L> stack,
        @NotNull UnaryOperator<LayerStack<L>> decorator,
        @NotNull A target
    ) {
        for (L layer : decorator.apply(stack).ordered())
            layer.contribute(target);
    }
}
