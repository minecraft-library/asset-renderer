/**
 * The texture subsystem: pack-aware texture resolution, plus the vanilla tables tint sampling reads.
 *
 * <p>{@link lib.minecraft.renderer.engine.texture.Textures Textures} is the service every renderer
 * and engine composes to resolve a texture identifier through the active pack stack -
 * {@code resolveTexture} / {@code resolveTextureAtTick} animation strip extraction, the
 * {@code minecraft:entity/} entity-texture prefix, and the CIT {@code layer0} override lookup. A
 * {@code ModelEngine} and {@code RasterEngine} each hold one and vend it via {@code textures()}; the
 * 2D / 3D scene contexts carry it to their layers. Stateless beyond its
 * {@link lib.minecraft.renderer.engine.RendererContext RendererContext}.
 *
 * <p>Two hand-transcribed vanilla tables sit beside it, each read by one of the port's tint
 * samplers: {@link lib.minecraft.renderer.engine.texture.Biome Biome} - the caller-supplied biome
 * identity (temperature, downfall, colour overrides, grass modifier), which answers for its own
 * overrides and applies its own modifier - and
 * {@link lib.minecraft.renderer.engine.texture.RedstoneTint RedstoneTint}, the redstone-wire
 * gradient by power level. Neither is pipeline-built, which is why they live here rather than with
 * the {@link lib.minecraft.renderer.asset asset} layer's decoded pack data.
 *
 * @see lib.minecraft.renderer.engine.texture.Textures
 * @see lib.minecraft.renderer.engine.RendererContext
 */
package lib.minecraft.renderer.engine.texture;
