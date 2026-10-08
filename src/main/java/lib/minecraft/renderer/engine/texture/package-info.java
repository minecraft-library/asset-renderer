/**
 * Producing a pixel buffer out of pixel buffers - the texture operations that name no subject and are
 * handed their inputs already resolved.
 *
 * <p>{@link lib.minecraft.renderer.engine.texture.Palette Palette} is the paletted-permutation op:
 * {@code permute} recolours a grayscale pattern through a key strip and a colour strip into a
 * full-opacity overlay, leaving every unmatched texel transparent. Which pattern the three buffers
 * came from and what the result is composited onto are the caller's -
 * {@link lib.minecraft.renderer.bake.texture.TrimKit TrimKit} for an armour trim overlay,
 * {@link lib.minecraft.renderer.content.pack.TextureSynthesizer TextureSynthesizer} for a sprite a
 * pack's atlas declares as a permutation rather than ships as a file.
 *
 * <p>{@link lib.minecraft.renderer.engine.texture.MissingSprite MissingSprite} is the generated
 * checkerboard an absent or unreadable texture draws, the degenerate case with no buffer in: it is
 * built at class load and ships as no file. A render reaches it through the substituting wrapper
 * {@link lib.minecraft.renderer.content.index.RendererContext#withMissingTexture() withMissingTexture()}
 * mints, so nothing is decided here.
 *
 * <p>A type no render reaches does not belong here, and neither does one whose code, imports and
 * javadoc aside, names a Minecraft subject - a block, an item, an entity, a {@code minecraft:} id or
 * a vanilla class - or one that imports from {@code vanilla}, {@code asset}, {@code request},
 * {@code content}, {@code bake} or the root package. A texture source
 * parsed from a pack, a value the caller constructs, a table vanilla compiles in, or a synthesiser
 * that keeps what it made past the call is not engine either.
 *
 * <p><b>Parity.</b> Everything here is part of a render, and the pipeline dump serialises loaded data
 * without calling a renderer, so the package declares the {@code engine-renders} claim as a demotion
 * of the dump's verdict on a change made here.
 *
 * @see lib.minecraft.renderer.engine.texture.Palette
 * @see lib.minecraft.renderer.content.index.RendererContext
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE, scope = Scope.SUBTREE)
package lib.minecraft.renderer.engine.texture;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
