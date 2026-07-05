package lib.minecraft.renderer.engine.compose.layer;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.engine.compose.FrameCompositor;
import lib.minecraft.renderer.engine.compose.FramePlacement;
import org.jetbrains.annotations.NotNull;

/**
 * Contributor of positioned, possibly-animated sub-renders to a {@link FrameCompositor} merge - the
 * "frame tier" of the layer model, alongside the in-place 2D {@link ImageLayer} and the
 * triangle-contributing {@link GeometryLayer}.
 * <p>
 * Where an {@code ImageLayer} mutates one shared buffer and a {@code GeometryLayer} feeds a shared
 * depth pass, a {@code FrameLayer} appends whole {@link FramePlacement}s (each a complete static or
 * animated image placed at a destination origin) to a shared sink in back-to-front order; the
 * compositor then blits or time-samples them per output frame. The canonical implementation is
 * {@link FramePlacement} itself - a single placement that contributes itself - so a list of
 * placements is directly a list of frame layers; custom layers may contribute several or computed
 * placements.
 */
@FunctionalInterface
public interface FrameLayer {

    /**
     * Appends this layer's placement(s) to {@code sink} in back-to-front order.
     *
     * @param sink shared placement list every frame layer appends to
     */
    void contribute(@NotNull ConcurrentList<FramePlacement> sink);
}
