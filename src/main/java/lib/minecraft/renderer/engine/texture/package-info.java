/**
 * The vanilla tables and texture sources the port's texture and tint answers are resolved against.
 *
 * <p>Pack-aware texture resolution itself lives on
 * {@link lib.minecraft.renderer.engine.RendererContext RendererContext}, which is where every
 * lookup's inputs already were: {@code resolveTexture} / {@code resolveTextureAtTick} and their
 * {@code require} arms, plus the two tint samplers. What is left here is the data those answers
 * read, none of it pipeline-built, which is what keeps it out of the
 * {@link lib.minecraft.renderer.asset asset} layer:
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.texture.Biome Biome} - the caller-supplied biome
 *       identity (temperature, downfall, colour overrides, grass modifier), which answers for its
 *       own overrides and applies its own modifier.</li>
 *   <li>{@link lib.minecraft.renderer.engine.texture.RedstoneTint RedstoneTint} - vanilla's
 *       redstone-wire gradient by power level, transcribed from {@code RedstoneWireBlock.COLORS}.</li>
 *   <li>{@link lib.minecraft.renderer.engine.texture.PalettedPermutationSource PalettedPermutationSource}
 *       and {@link lib.minecraft.renderer.engine.texture.TextureSynthesizer TextureSynthesizer} - the
 *       sources that synthesise a texture the pack ships no file for.</li>
 * </ul>
 *
 * @see lib.minecraft.renderer.engine.RendererContext
 */
package lib.minecraft.renderer.engine.texture;
