/**
 * Turning rasterized buffers into one still or timed image - the schedule a render is played out on,
 * the compositor that merges finished sub-renders, and the terminals every renderer bottoms out in to
 * hand back {@link dev.simplified.image.ImageData ImageData}.
 *
 * <p><b>Terminal pipeline.</b> {@link lib.minecraft.renderer.engine.frame.Timeline Timeline} is the
 * render schedule that owns the final bake and wrap terminals: it draws each frame through a
 * {@link lib.minecraft.renderer.engine.frame.RasterPass RasterPass} (the supersample / FXAA /
 * downscale tail plus the one finish seam), then wraps the result into {@code ImageData}. Its
 * {@link lib.minecraft.renderer.engine.frame.Timeline#still still} and
 * {@link lib.minecraft.renderer.engine.frame.Timeline#empty empty} are the one-frame terminals for a
 * renderer that draws without a schedule.
 *
 * <p><b>Frame compositing.</b>
 * {@link lib.minecraft.renderer.engine.frame.FramePlacement FramePlacement} positions a possibly-
 * animated sub-render, and {@link lib.minecraft.renderer.engine.frame.FrameCompositor FrameCompositor}
 * merges a list of them - a static fast path when every placement is static, else an LCM-merged
 * animated loop.
 *
 * <p><b>Layer kinds.</b> The two {@link lib.minecraft.renderer.engine.layer.Layer Layer} kinds whose
 * accumulator is a frame value live here:
 * {@link lib.minecraft.renderer.engine.frame.ImageLayer ImageLayer} draws in stack order onto one
 * shared {@link dev.simplified.image.pixel.PixelBuffer PixelBuffer}, and
 * {@link lib.minecraft.renderer.engine.frame.FrameLayer FrameLayer} appends whole placements to the
 * list a compositor merge reads. The layer model itself is
 * {@link lib.minecraft.renderer.engine.layer engine.layer}.
 *
 * <p><b>Per-render context.</b> Renderers that thread bundled per-render inputs into their layers keep
 * that context private to themselves (entity's {@code FeatureContext}, item's {@code Gui2D.LayerContext}),
 * because each is consumed by exactly one renderer - this package holds no shared context type.
 *
 * <p>A type no render reaches does not belong here, and neither does one whose code, imports and
 * javadoc aside, names a Minecraft subject - a block, an item, an entity, a {@code minecraft:} id or
 * a vanilla class - or one that imports from {@code vanilla}, {@code asset}, {@code request},
 * {@code content}, {@code bake} or the root package.
 *
 * <p><b>Parity.</b> Everything here is part of a render, and the pipeline dump serialises loaded data
 * without calling a renderer, so the package declares the {@code engine-renders} claim as a demotion
 * of the dump's verdict on a change made here.
 *
 * @see lib.minecraft.renderer.engine.layer
 * @see lib.minecraft.renderer.engine.frame.Timeline
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE, scope = Scope.SUBTREE)
package lib.minecraft.renderer.engine.frame;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
