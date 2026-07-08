/**
 * The texture subsystem: pack-aware texture resolution + tint sampling.
 *
 * <p>{@link lib.minecraft.renderer.engine.texture.Textures Textures} is the service every renderer
 * and engine composes for its two families of helpers - pack resolution ({@code resolveTexture} /
 * {@code resolveTextureAtTick} animation strip extraction, the {@code minecraft:entity/}
 * entity-texture prefix, and the CIT {@code layer0} override lookup) and tint sampling (biome grass
 * / foliage / water via the vanilla {@code GrassColorModifier} variants + water table, and the
 * redstone-wire-by-power tint, each honouring pack {@code color.properties} overrides). A
 * {@code ModelEngine} and {@code RasterEngine} each hold one and vend it via {@code textures()}; the
 * 2D / 3D scene contexts carry it to their layers. Stateless beyond its
 * {@link lib.minecraft.renderer.engine.RendererContext RendererContext}.
 *
 * <p>{@link lib.minecraft.renderer.engine.texture.Biome Biome} - the caller-supplied biome identity
 * (temperature, downfall, colour overrides, grass modifier) that
 * {@code Textures.sampleBiomeTint} resolves grass / foliage / water tints against - lives here
 * beside its sole consumer.
 *
 * @see lib.minecraft.renderer.engine.texture.Textures
 * @see lib.minecraft.renderer.engine.RendererContext
 */
package lib.minecraft.renderer.engine.texture;
