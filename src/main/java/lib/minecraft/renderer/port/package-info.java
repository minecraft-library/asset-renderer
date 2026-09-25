/**
 * The lookup seam a renderer reads its world through - textures and their sidecars, block, item and
 * entity definitions, colormaps and colour overrides, and the pack rules a render consults.
 *
 * <p>{@link lib.minecraft.renderer.port.RendererContext RendererContext} is the seam. Its lookups are
 * named by what they do: {@code findX} is a keyed lookup answering an {@code Optional},
 * {@code resolveX} a derived one that walks rules or decodes bytes, {@code requireX} the refusing arm
 * of a resolve, {@code knownX} a bulk listing, and {@code sampleX} a colour resolved from a pack
 * override, the subject's own data and a vanilla fallback. The implementation a caller gets comes from
 * {@link lib.minecraft.renderer.content.index.AssetContent#load AssetContent.load}.
 *
 * <p>The interface builds its own variants.
 * {@link lib.minecraft.renderer.port.RendererContext#builder() builder()} opens an in-memory context
 * whose every lookup answers empty until the builder supplies it, materialised as the package-private
 * {@code MapRendererContext}. Five wrappers each answer one family of lookups differently and forward
 * the rest: {@link lib.minecraft.renderer.port.RendererContext#withTextures withTextures} substitutes a
 * texture source, {@link lib.minecraft.renderer.port.RendererContext#withTexture withTexture} reserves
 * one synthetic texture that carries no sidecar,
 * {@link lib.minecraft.renderer.port.RendererContext#withEntities withEntities} supplies entity
 * definitions, {@link lib.minecraft.renderer.port.RendererContext#withMissingTexture withMissingTexture}
 * draws the checkerboard for every texture the context lacks and reports each such id once through
 * {@link lib.minecraft.renderer.diagnostic.Substitutions Substitutions}, and
 * {@link lib.minecraft.renderer.port.RendererContext#hiding hiding} answers empty for the named
 * textures. Each is a {@link lib.minecraft.renderer.port.RendererContext.Forwarding Forwarding}, the
 * mixin that forwards every lookup to its delegate except the six derived from others, which stay
 * defaulted so that a wrapper's override carries through to everything built on it.
 *
 * <p>The values a lookup returns that no parser builds are in
 * {@link lib.minecraft.renderer.port.answer port.answer}.
 *
 * <p>A type that is neither the interface nor one its signatures name does not belong here.
 */
package lib.minecraft.renderer.port;
