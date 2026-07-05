package lib.minecraft.renderer.engine.compose.layer;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.engine.compose.FrameCompositor;
import lib.minecraft.renderer.engine.compose.FramePlacement;

/**
 * Contributor of positioned, possibly-animated sub-renders to a {@link FrameCompositor} merge - a
 * {@link Layer} over the shared placement list, the "frame tier" alongside the in-place 2D
 * {@link ImageLayer} and the triangle-contributing {@link GeometryLayer}.
 * <p>
 * Where an {@code ImageLayer} mutates one shared buffer and a {@code GeometryLayer} feeds a shared
 * depth pass, a {@code FrameLayer} appends whole {@link FramePlacement}s (each a complete static or
 * animated image placed at a destination origin) to a shared sink in back-to-front order; the
 * compositor then blits or time-samples them per output frame. Built-in layers append a single
 * placement ({@code sink -> sink.add(placement)}); custom layers may contribute several or computed
 * placements.
 */
@FunctionalInterface
public interface FrameLayer extends Layer<ConcurrentList<FramePlacement>> {

}
