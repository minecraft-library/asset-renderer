/**
 * The generator flows: ASM walks over the extracted Minecraft client jar that produce the shipped
 * JSON tables the renderer loads at run time. The {@code :tooling} subproject of the renderer's
 * build, driven from the renderer under the same task names.
 *
 * <p>This package holds the eight flows, one entry point per task, and every subpackage below is a
 * stage they run. The stages are ordered, each naming only what sits beneath it: the failure type
 * {@link lib.minecraft.renderer.tooling.exception.ToolingException ToolingException} and the vanilla
 * source names at the bottom, then the ASM kit, the run and its table writer, the interpreter
 * chassis, the walk language in {@link lib.minecraft.renderer.tooling.walk walk}, the policy SPI,
 * geometry, the jar-wide indexes and animation, and above those one package per corpus the flows
 * read - blocks, block entities, colormaps, entities and items - with the flows on top.
 *
 * <p>The renderer's own classes are on this build's classpath, so a generator reaches for a
 * production type rather than re-declaring it, up to the content index and nothing above it, and
 * part of the vocabulary a shipped table is written in travels as that type. Nothing in the
 * renderer names a type here.
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
