/**
 * The shared dye-colour vocabulary. Holds {@link lib.minecraft.renderer.request.DyeColor DyeColor} -
 * the sixteen vanilla dyes plus arbitrary custom ARGB - a caller-supplied colour palette used
 * broadly across the renderer (banner / shield tinting, wool / leather dye, entity collar / pattern
 * tints, block tint constants). Kept free of engine state so it sits below {@code engine} in the
 * dependency order.
 *
 * <p>The other caller-input value types that once lived here moved to the subsystem that owns them:
 * {@link lib.minecraft.renderer.tensor.EulerRotation EulerRotation} to {@code tensor} (a rotation
 * value type beside {@code Quaternionf}); {@link lib.minecraft.renderer.engine.texture.Biome Biome}
 * to {@code engine.texture} (its sole consumer is the tint sampler); and the entity-appearance /
 * equipment vocabularies ({@code TintAxis}, {@code TropicalFishPattern}, {@code BannerLayer},
 * {@code ArmorMaterial}, {@code ArmorPiece}, {@code ArmorTrim}) to {@code option} /
 * {@code option.spec}, beside the {@code Options} they compose into.
 */
package lib.minecraft.renderer.request;
