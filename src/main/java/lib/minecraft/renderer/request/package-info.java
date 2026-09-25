/**
 * What the caller supplies for one render call - the {@code *Options} bag that is the argument to
 * {@link lib.minecraft.renderer.Renderer#render Renderer.render}, and every value a caller builds to
 * fill one. A type the caller does not construct does not belong here.
 *
 * <p><b>The bags.</b> Each renderer takes one bag whole, and each of those implements the
 * {@link lib.minecraft.renderer.request.RenderOptions RenderOptions} marker, held here beside them,
 * that bounds {@link lib.minecraft.renderer.Renderer Renderer}'s type parameter. A concern several bags share is a
 * bag of its own nested into those that carry it:
 * {@link lib.minecraft.renderer.request.OutputOptions OutputOptions} (the render frame),
 * {@link lib.minecraft.renderer.request.AnimationOptions AnimationOptions},
 * {@link lib.minecraft.renderer.request.ArmorOptions ArmorOptions} (the worn slots),
 * {@link lib.minecraft.renderer.request.SkinOptions SkinOptions} /
 * {@link lib.minecraft.renderer.request.TextureOptions TextureOptions} (the player's texture sources),
 * {@link lib.minecraft.renderer.request.DecorationOptions DecorationOptions} (an item icon's
 * composed marks) and
 * {@link lib.minecraft.renderer.request.AppearanceOptions AppearanceOptions} (the entity axis
 * selections). A value type only one bag names nests inside it, as
 * {@code MenuOptions.MenuSlotContent} and {@code FluidOptions.CornerHeights} do.
 *
 * <p><b>The values a bag carries.</b> A value a reader beyond its bag also names is a type of its
 * own: {@link lib.minecraft.renderer.request.ArmorPiece ArmorPiece} (one worn slot) and its
 * {@link lib.minecraft.renderer.request.ArmorTrim ArmorTrim},
 * {@link lib.minecraft.renderer.request.BannerLayer BannerLayer} (one pattern tinted by one dye),
 * {@link lib.minecraft.renderer.request.Biome Biome} (the climate and colour overrides a tint resolves
 * against), {@link lib.minecraft.renderer.request.ThemeStyle ThemeStyle} (the palette a menu's drawn
 * chrome is painted in), {@link lib.minecraft.renderer.request.ChromeStyle ChromeStyle} (the chrome a
 * tooltip's background and border are drawn in), and the two contexts an item render hands down -
 * {@link lib.minecraft.renderer.request.ItemContext ItemContext}, which answers whether a pack's CIT
 * rule applies to the item, and {@link lib.minecraft.renderer.request.ItemModelContext ItemModelContext},
 * which walks an item-definition tree to the branch that renders.
 *
 * <p><b>What a bag names is not held here.</b> The vanilla vocabulary a selection is drawn from is a
 * fact about Minecraft whichever side supplies it, so it sits in
 * {@link lib.minecraft.renderer.vanilla vanilla} and this package points at it: the appearance axes in
 * {@link lib.minecraft.renderer.vanilla.appearance vanilla.appearance}, the armor slot and material
 * vocabulary in {@link lib.minecraft.renderer.vanilla.equipment vanilla.equipment} and the dye palette at
 * {@link lib.minecraft.renderer.vanilla.DyeColor DyeColor}. The splice points a caller's
 * {@code layerDecorator} targets are named in {@link lib.minecraft.renderer.slot slot}, one
 * {@code LayerSlot} per renderer.
 *
 * <p>Nor is a renderer's output vocabulary, where a bag's counterpart is a value the caller receives
 * rather than supplies: {@link lib.minecraft.renderer.atlas.AtlasSidecar AtlasSidecar} and
 * {@link lib.minecraft.renderer.atlas.AtlasTile AtlasTile} describe the grid an
 * {@link lib.minecraft.renderer.AtlasRenderer AtlasRenderer} run composed, and sit in
 * {@link lib.minecraft.renderer.atlas atlas} with the renderer that emits them.
 *
 * <p><b>Parity.</b> Every renderer entry point takes an options record, so a default or a resolution
 * rule here reaches whatever that renderer draws - the same population the engine reaches, for the
 * same reason. The dump is blind to all of it: it serialises loaded content and never constructs an
 * options record. The vocabulary a bag names under {@code vanilla} declares this same claim beside
 * its own, so what a bag names keeps the reach the bag has.
 */
@Parity(claim = "option-surface")
package lib.minecraft.renderer.request;

import lib.minecraft.renderer.parity.Parity;
