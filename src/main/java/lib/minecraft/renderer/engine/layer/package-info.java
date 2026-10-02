/**
 * The layer model: ordered, slot-keyed contributions folded into a renderer's shared accumulator.
 *
 * <p>A {@link lib.minecraft.renderer.engine.layer.Layer Layer} contributes to a shared
 * accumulator of type {@code A}; the three named kinds differ only in that accumulator, and each is a
 * thin subinterface so callers get a discoverable, documented extension point:
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.draw.GeometryLayer GeometryLayer} - appends
 *       {@link lib.minecraft.renderer.engine.draw.VisibleTriangle triangles} to a shared sink
 *       rasterized in one depth pass. Emission order is load-bearing (coplanar depth tie-break,
 *       translucent sort, emissive depth-skip).</li>
 *   <li>{@link lib.minecraft.renderer.engine.frame.ImageLayer ImageLayer} - mutates a shared
 *       {@link dev.simplified.image.pixel.PixelBuffer PixelBuffer} in stack order.</li>
 *   <li>{@link lib.minecraft.renderer.engine.frame.FrameLayer FrameLayer} - appends
 *       {@link lib.minecraft.renderer.engine.frame.FramePlacement FramePlacement}s (positioned
 *       sub-renders) to a shared list, merged by
 *       {@link lib.minecraft.renderer.engine.frame.FrameCompositor FrameCompositor}.</li>
 * </ul>
 *
 * <p>Renderers build a {@link lib.minecraft.renderer.engine.layer.LayerStack LayerStack} keyed
 * by {@link lib.minecraft.renderer.engine.layer.LayerSlot LayerSlot} (per-renderer enums in
 * {@code options}) so callers can splice their own passes relative to named slots via a decorator, and
 * {@link lib.minecraft.renderer.engine.layer.Layers Layers}{@code .foldInto} collapses the
 * decorated stack into the accumulator - the one consume path every renderer shares.
 *
 * <p><b>Parity.</b> Everything here is part of a render, and the pipeline dump serialises loaded data
 * without calling a renderer, so the package declares the {@code engine-renders} claim as a demotion
 * of the dump's verdict on a change made here.
 *
 * @see lib.minecraft.renderer.engine.layer.Layer
 * @see lib.minecraft.renderer.engine.layer.LayerStack
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE, scope = Scope.SUBTREE)
package lib.minecraft.renderer.engine.layer;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
