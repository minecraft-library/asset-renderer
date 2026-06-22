/**
 * Caller-composed appearance value types - the typed vocabulary a caller binds into a render
 * {@link lib.minecraft.renderer.options options} object to choose how a subject looks. These are
 * not parsed-from-pack assets (those live in {@link lib.minecraft.renderer.asset asset}) and not
 * geometry; they are fixed reference tables and small composition records the caller supplies per
 * render.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.appearance.DyeColor DyeColor} - the sixteen vanilla dyes plus arbitrary custom ARGB; drives banner /
 *       shield tinting, wool / leather dye, and block tint constants.</li>
 *   <li>{@link lib.minecraft.renderer.appearance.Biome Biome} - biome identity (temperature, downfall, colour overrides, grass modifier)
 *       used to resolve grass / foliage / water tints. Its nested {@link lib.minecraft.renderer.appearance.Biome.TintTarget Biome.TintTarget} flags
 *       which colormap a parsed block face samples.</li>
 *   <li>{@link lib.minecraft.renderer.appearance.ArmorMaterial ArmorMaterial}, {@link lib.minecraft.renderer.appearance.ArmorTrim ArmorTrim}, {@link lib.minecraft.renderer.appearance.ArmorPiece ArmorPiece} - the equipped-armor
 *       vocabulary: material atlas, trim slot / colour / pattern, and the composed per-slot piece.</li>
 *   <li>{@link lib.minecraft.renderer.appearance.BannerLayer BannerLayer} - one banner / shield layer pairing a {@link lib.minecraft.renderer.asset.BannerPattern BannerPattern} with a
 *       {@link lib.minecraft.renderer.appearance.DyeColor DyeColor}.</li>
 * </ul>
 */
package lib.minecraft.renderer.appearance;
