package lib.minecraft.renderer.engine.compose.layer;

import org.jetbrains.annotations.NotNull;

/**
 * A single ordered contribution folded into a renderer's shared accumulator {@code A}.
 * <p>
 * The one shape behind every layer kind: a {@link GeometryLayer} appends triangles to a shared sink, an
 * {@link ImageLayer} mutates a shared pixel buffer, and a {@link FrameLayer} appends placements to a
 * shared list - each is a {@code Layer<A>} that folds itself into the accumulator its renderer then
 * finalizes. Layers run in the order a {@link LayerStack} flattens them (slot order, then insertion
 * order); ordering is load-bearing, so a layer must contribute deterministically and must not sort or
 * filter the shared accumulator.
 *
 * @param <A> the shared accumulator this layer contributes to (a triangle sink, a pixel buffer, or a
 *        placement sink)
 */
@FunctionalInterface
public interface Layer<A> {

    /**
     * Folds this layer into the shared {@code target} in contribution order.
     *
     * @param target the shared accumulator every layer in the stack contributes to
     */
    void contribute(@NotNull A target);
}
