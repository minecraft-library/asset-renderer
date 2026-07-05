/**
 * Frame compositing, the terminal render pipeline, and per-render context: how per-asset contributions
 * become the final {@link dev.simplified.image.ImageData ImageData}. The layer model itself lives in
 * the {@link lib.minecraft.renderer.engine.compose.layer compose.layer} sub-package.
 *
 * <p><b>Frame compositing.</b>
 * {@link lib.minecraft.renderer.engine.compose.FramePlacement FramePlacement} positions a possibly-
 * animated sub-render; {@link lib.minecraft.renderer.engine.compose.FrameCompositor FrameCompositor}
 * both merges a list of them (a static fast-path when every placement is static, else an LCM-merged
 * animated loop) and owns the shared frame-to-{@code ImageData} wrapping ({@code wrapFrames} /
 * {@code staticFrame} / {@code emptyFrame}) that every renderer and the terminal pipeline bottom out in.
 *
 * <p><b>Terminal pipeline.</b> {@link lib.minecraft.renderer.engine.compose.Finalize Finalize} is the
 * single spec-driven terminal for the rasterizing renderers: it draws each frame, runs the shared
 * supersample / FXAA / downscale tail, then either scrolls an enchantment glint or bakes an animation
 * strip (the two are mutually exclusive), and wraps the result via {@code FrameCompositor}.
 *
 * <p><b>Context.</b> {@link lib.minecraft.renderer.engine.compose.SceneContext SceneContext} (3D scene
 * state) and {@link lib.minecraft.renderer.engine.compose.ImageLayerContext ImageLayerContext} (item
 * layer state) carry per-render inputs to the layers that capture them at construction.
 *
 * @see lib.minecraft.renderer.engine.compose.layer
 * @see lib.minecraft.renderer.engine.compose.Finalize
 */
package lib.minecraft.renderer.engine.compose;
