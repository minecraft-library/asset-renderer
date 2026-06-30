/**
 * Layer composition and the terminal render stages: how the per-asset geometry and pixel
 * contributions are assembled into the final {@link dev.simplified.image.ImageData ImageData}.
 *
 * <p><b>Layers.</b> A renderer builds a {@link lib.minecraft.renderer.engine.compose.LayerStack LayerStack}
 * keyed by {@link lib.minecraft.renderer.engine.compose.LayerSlot LayerSlot} (implemented as per-renderer
 * enums in {@code options}) so callers can splice their own passes in relative to named insertion
 * points. Two layer kinds exist:
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.compose.GeometryLayer GeometryLayer} - contributes
 *       {@link lib.minecraft.renderer.engine.raster.VisibleTriangle triangles} to one shared sink that
 *       rasterizes in a single depth pass. Emission order is load-bearing (depth tie-break,
 *       translucent sort, emissive depth-skip). Per-render state travels via
 *       {@link lib.minecraft.renderer.engine.compose.SceneContext SceneContext}.</li>
 *   <li>{@link lib.minecraft.renderer.engine.compose.ImageLayer ImageLayer} - mutates a shared 2D
 *       {@link dev.simplified.image.pixel.PixelBuffer PixelBuffer} in stack order, with state via
 *       {@link lib.minecraft.renderer.engine.compose.ImageLayerContext ImageLayerContext}.</li>
 * </ul>
 * {@link lib.minecraft.renderer.engine.compose.FrameLayer FrameLayer} and
 * {@link lib.minecraft.renderer.engine.compose.FramePlacement FramePlacement} position possibly-animated
 * sub-renders, merged by {@link lib.minecraft.renderer.engine.compose.FrameCompositor FrameCompositor}.
 *
 * <p><b>Stages.</b> The terminal pipeline is an explicit hardcoded sequence:
 * {@link lib.minecraft.renderer.engine.compose.FinalizeStage FinalizeStage} (supersample, FXAA, downscale)
 * &rarr; {@link lib.minecraft.renderer.engine.compose.GlintStage GlintStage} (enchantment foil) &rarr;
 * {@link lib.minecraft.renderer.engine.compose.AnimationStage AnimationStage} (frame orchestration).
 * {@link lib.minecraft.renderer.engine.compose.Frames Frames} wraps the resulting buffers into the final
 * {@code ImageData}.
 *
 * @see lib.minecraft.renderer.engine.compose.LayerStack
 * @see lib.minecraft.renderer.engine.compose.Frames
 */
package lib.minecraft.renderer.engine.compose;
