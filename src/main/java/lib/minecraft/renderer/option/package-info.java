/**
 * Renderer options - one {@code *Options} bag per renderer (the argument to
 * {@link lib.minecraft.renderer.Renderer#render Renderer.render}), each implementing the
 * {@link lib.minecraft.renderer.option.RenderOptions RenderOptions} marker that bounds
 * {@link lib.minecraft.renderer.Renderer Renderer}'s type parameter.
 *
 * <p><b>Charter.</b> Every type here is a {@code *Options} bag - either one a renderer takes whole,
 * or one nested into those that share a concern:
 * {@link lib.minecraft.renderer.option.OutputOptions OutputOptions} (the render frame),
 * {@link lib.minecraft.renderer.option.AnimationOptions AnimationOptions},
 * {@link lib.minecraft.renderer.option.ArmorOptions ArmorOptions} (the worn slots),
 * {@link lib.minecraft.renderer.option.SkinOptions SkinOptions} /
 * {@link lib.minecraft.renderer.option.TextureOptions TextureOptions} (the player's texture sources),
 * {@link lib.minecraft.renderer.option.DecorationOptions DecorationOptions} (an item icon's
 * composed marks) and
 * {@link lib.minecraft.renderer.option.AppearanceOptions AppearanceOptions} (the entity axis
 * selections). A value type only one bag names nests inside it, as
 * {@code MenuOptions.MenuSlotContent} and {@code FluidOptions.CornerHeights} do.
 *
 * <p><b>What a bag names lives with what reads it.</b> The vanilla vocabulary a selection is drawn
 * from is domain data whichever side supplies it, so it sits in
 * {@link lib.minecraft.renderer.asset asset} and this package points at it: the appearance axes in
 * {@link lib.minecraft.renderer.asset.appearance asset.appearance}, the armor vocabulary in
 * {@link lib.minecraft.renderer.asset.equipment asset.equipment}, the dye palette at
 * {@link lib.minecraft.renderer.asset.DyeColor DyeColor}, and the item-tree evaluation context at
 * {@link lib.minecraft.renderer.asset.pack.item.ItemModelContext ItemModelContext}. One sub-package
 * stays: {@link lib.minecraft.renderer.option.slot slot}, the per-renderer {@code LayerSlot}
 * implementations naming the splice points a caller's {@code layerDecorator} targets.
 *
 * <p>The one further category is a renderer's own output vocabulary, where a bag's counterpart is a
 * value the caller receives rather than supplies:
 * {@link lib.minecraft.renderer.option.AtlasSidecar AtlasSidecar} and
 * {@link lib.minecraft.renderer.option.AtlasTile AtlasTile} describe the grid an
 * {@link lib.minecraft.renderer.AtlasRenderer AtlasRenderer} run composed, and sit beside
 * {@link lib.minecraft.renderer.option.AtlasOptions AtlasOptions} because what a caller asks an
 * atlas for and what it gets back read as one vocabulary.
 *
 * <p><b>Parity.</b> Every renderer entry point takes an options record, so a default or a resolution
 * rule here reaches whatever that renderer draws - the same population the engine reaches, for the
 * same reason. The dump is blind to all of it: it serialises loaded pipeline data and never
 * constructs an options record. The vocabulary held under {@code asset} declares this same claim
 * beside its own, so what a bag names keeps the reach the bag has.
 */
@Parity(claim = "option-surface")
package lib.minecraft.renderer.option;

import lib.minecraft.renderer.parity.Parity;
