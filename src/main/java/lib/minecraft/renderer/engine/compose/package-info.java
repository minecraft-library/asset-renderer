/**
 * Frame compositing, the terminal render stages, and per-render context: how per-asset contributions
 * become the final {@link dev.simplified.image.ImageData ImageData}. The layer model itself lives in
 * the {@link lib.minecraft.renderer.engine.compose.layer compose.layer} sub-package.
 *
 * <p><b>Frame compositing.</b>
 * {@link lib.minecraft.renderer.engine.compose.FramePlacement FramePlacement} positions a possibly-
 * animated sub-render; {@link lib.minecraft.renderer.engine.compose.FrameCompositor FrameCompositor}
 * merges a list of them - a static fast-path when every placement is static, else an LCM-merged
 * animated loop sampled per output frame.
 *
 * <p><b>Terminal stages.</b> The terminal pipeline is an explicit hardcoded composition of three shared
 * stages. {@link lib.minecraft.renderer.engine.compose.FinalizeStage FinalizeStage} rasterizes and
 * post-processes one buffer (supersample, FXAA, downscale), then hands it to a finalizer callback that
 * typically runs {@link lib.minecraft.renderer.engine.compose.GlintStage GlintStage} (enchantment foil).
 * {@link lib.minecraft.renderer.engine.compose.AnimationStage AnimationStage} sits outermost, invoking
 * that finalise-then-glint tail once per animation frame and baking the strip.
 * {@link lib.minecraft.renderer.engine.compose.Frames Frames} wraps the resulting buffer(s) into the
 * final {@code ImageData}.
 *
 * <p><b>Context.</b> {@link lib.minecraft.renderer.engine.compose.SceneContext SceneContext} (3D scene
 * state) and {@link lib.minecraft.renderer.engine.compose.ImageLayerContext ImageLayerContext} (item
 * layer state) carry per-render inputs to the layers that capture them at construction.
 *
 * @see lib.minecraft.renderer.engine.compose.layer
 * @see lib.minecraft.renderer.engine.compose.Frames
 */
package lib.minecraft.renderer.engine.compose;
