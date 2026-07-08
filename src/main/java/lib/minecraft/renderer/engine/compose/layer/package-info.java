/**
 * The layer model: ordered, slot-keyed contributions folded into a renderer's shared accumulator.
 *
 * <p>A {@link lib.minecraft.renderer.engine.compose.layer.Layer Layer} contributes to a shared
 * accumulator of type {@code A}; the three named kinds differ only in that accumulator, and each is a
 * thin subinterface so callers get a discoverable, documented extension point:
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.compose.layer.GeometryLayer GeometryLayer} - appends
 *       {@link lib.minecraft.renderer.engine.raster.VisibleTriangle triangles} to a shared sink
 *       rasterized in one depth pass. Emission order is load-bearing (coplanar depth tie-break,
 *       translucent sort, emissive depth-skip).</li>
 *   <li>{@link lib.minecraft.renderer.engine.compose.layer.ImageLayer ImageLayer} - mutates a shared
 *       {@link dev.simplified.image.pixel.PixelBuffer PixelBuffer} in stack order.</li>
 *   <li>{@link lib.minecraft.renderer.engine.compose.layer.FrameLayer FrameLayer} - appends
 *       {@link lib.minecraft.renderer.engine.compose.FramePlacement FramePlacement}s (positioned
 *       sub-renders) to a shared list, merged by
 *       {@link lib.minecraft.renderer.engine.compose.FrameCompositor FrameCompositor}.</li>
 * </ul>
 *
 * <p>Renderers build a {@link lib.minecraft.renderer.engine.compose.layer.LayerStack LayerStack} keyed
 * by {@link lib.minecraft.renderer.engine.compose.layer.LayerSlot LayerSlot} (per-renderer enums in
 * {@code options}) so callers can splice their own passes relative to named slots via a decorator, and
 * {@link lib.minecraft.renderer.engine.compose.layer.Layers Layers}{@code .foldInto} collapses the
 * decorated stack into the accumulator - the one consume path every renderer shares.
 *
 * @see lib.minecraft.renderer.engine.compose.layer.Layer
 * @see lib.minecraft.renderer.engine.compose.layer.LayerStack
 */
package lib.minecraft.renderer.engine.compose.layer;
