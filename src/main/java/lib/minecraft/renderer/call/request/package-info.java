/**
 * What the caller supplies for one render call - the {@code *Options} bag that is the argument to
 * {@link lib.minecraft.renderer.Renderer#render Renderer.render}, and every value a caller builds to
 * fill one. A type the caller does not construct does not belong here.
 *
 * <p><b>The bags.</b> Each renderer takes one bag whole, and each of those implements the
 * {@link lib.minecraft.renderer.call.request.RenderOptions RenderOptions} marker, held here beside them,
 * that bounds {@link lib.minecraft.renderer.Renderer Renderer}'s type parameter. A concern several bags share is a
 * bag of its own nested into those that carry it:
 * {@link lib.minecraft.renderer.call.request.OutputOptions OutputOptions} (the render frame),
 * {@link lib.minecraft.renderer.call.request.AnimationOptions AnimationOptions},
 * {@link lib.minecraft.renderer.call.request.ArmorOptions ArmorOptions} (the worn slots),
 * {@link lib.minecraft.renderer.call.request.SkinOptions SkinOptions} /
 * {@link lib.minecraft.renderer.call.request.TextureOptions TextureOptions} (the player's texture sources),
 * {@link lib.minecraft.renderer.call.request.DecorationOptions DecorationOptions} (an item icon's
 * composed marks) and
 * {@link lib.minecraft.renderer.call.request.AppearanceOptions AppearanceOptions} (the entity axis
 * selections). A value type only one bag names nests inside it, as
 * {@code MenuOptions.MenuSlotContent} and {@code FluidOptions.CornerHeights} do.
 *
 * <p><b>The values a bag carries.</b> A value a reader beyond its bag also names is a type of its
 * own: {@link lib.minecraft.renderer.call.request.ArmorPiece ArmorPiece} (one worn slot) and its
 * {@link lib.minecraft.renderer.call.request.ArmorTrim ArmorTrim},
 * {@link lib.minecraft.renderer.call.request.BannerLayer BannerLayer} (one pattern tinted by one dye),
 * {@link lib.minecraft.renderer.call.request.ThemeStyle ThemeStyle} (the palette a menu's drawn
 * chrome is painted in), {@link lib.minecraft.renderer.call.request.ChromeStyle ChromeStyle} (the chrome a
 * tooltip's background and border are drawn in), and the two contexts an item render hands down -
 * {@link lib.minecraft.renderer.call.request.ItemContext ItemContext}, which answers whether a pack's CIT
 * rule applies to the item, and {@link lib.minecraft.renderer.call.request.ItemModelContext ItemModelContext},
 * which walks an item-definition tree to the branch that renders.
 *
 * <p><b>The splice points.</b> The layers a caller's {@code layerDecorator} splices against are named
 * in {@link lib.minecraft.renderer.call.slot slot}, beside this package: one {@code LayerSlot} per
 * renderer, kept together so the slot taxonomy reads as one vocabulary.
 *
 * <p><b>What a bag names is not held here.</b> The vanilla vocabulary a selection is drawn from is a
 * fact about Minecraft whichever side supplies it, so it sits in
 * {@link lib.minecraft.renderer.vanilla vanilla} and this package points at it: the appearance axes in
 * {@link lib.minecraft.renderer.vanilla.appearance vanilla.appearance}, the armor slot and material
 * vocabulary in {@link lib.minecraft.renderer.vanilla.equipment vanilla.equipment}, the dye palette at
 * {@link lib.minecraft.renderer.vanilla.DyeColor DyeColor} and the biomes at
 * {@link lib.minecraft.renderer.vanilla.Biome Biome}.
 *
 * <p>Nor is what a render hands back, which sits in {@link lib.minecraft.renderer.call.result result},
 * below this package: a composite's input - a {@code GridOptions.GridTile}, a {@code LayoutOptions}
 * child, a {@code MenuOptions.MenuSlotContent} - takes a child's result, and this package points down
 * at it.
 */
package lib.minecraft.renderer.call.request;
