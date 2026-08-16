/**
 * The generator flows: ASM walks over the extracted Minecraft client jar that produce the shipped
 * JSON tables the renderer loads at run time. Its own Gradle build with its own wrapper, driven by
 * the renderer under the same task names.
 *
 * <p>Every subpackage below is one stage of that: the walk language in
 * {@link lib.minecraft.renderer.tooling.walk walk}, the emitters and their session envelope, and one
 * package per corpus the flows read - blocks, block entities, colormaps, defaults, entities,
 * geometry and vanilla registries. Nothing here names a renderer type: the vocabulary a shipped
 * table is written in travels as values, and the one module this build shares with the renderer is
 * the client-jar acquisition leaf.
 *
 * <p><b>Parity.</b> Every artifact this repository stores is structurally blind to a change here.
 * They all read the SHIPPED JSON that a generator refactor does not regenerate, so a green suite and
 * five green sums say nothing either way. What answers is re-running the flow and diffing the
 * emitted bytes and the flow's own diagnostics log against a capture taken at the clean tree - and
 * the log because a byte-identical table is not the same claim as an unchanged run: each index build
 * records its own entries, so reordering two of them is invisible in every table and plain in the
 * log.
 *
 * @see lib.minecraft.renderer.tooling.walk.AsmWalker
 */
@Parity(claim = "tooling-blindness", mode = Mode.DEMOTE, scope = Scope.SUBTREE)
@Parity(claim = "tooling-log-order")
package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
