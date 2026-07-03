/**
 * The texture subsystem: pack-aware texture resolution.
 *
 * <p>{@link lib.minecraft.renderer.engine.texture.Textures Textures} is the service every renderer
 * and engine composes for its three families of helpers - pack resolution
 * ({@code resolveTexture}, animation strip extraction, CIT override lookup), biome tint sampling
 * (the vanilla {@code GrassColorModifier} variants and water tint table), and colour overlay
 * (leather dye, dyed-item layers, ARGB tint compositing). A {@code ModelEngine} and
 * {@code RasterEngine} each hold one and vend it via {@code textures()}; the 2D / 3D scene contexts
 * carry it to their layers. Stateless beyond its
 * {@link lib.minecraft.renderer.engine.RendererContext RendererContext}.
 *
 * @see lib.minecraft.renderer.engine.texture.Textures
 * @see lib.minecraft.renderer.engine.RendererContext
 */
package lib.minecraft.renderer.engine.texture;
