/**
 * The generator build's own suite, one package per stage it covers.
 *
 * <p>Every case here drives hand-built ASM nodes, a jar written under a temporary directory, or
 * reflection over the tooling classes themselves. None runs a flow, so none reads the extracted
 * client jar and none needs one on disk.
 *
 * <p><b>Parity.</b> Because no case runs a flow, none writes a shipped table or a flow log, and no
 * stored artifact can see a change here. The gate is this suite, which the renderer's {@code check}
 * schedules by shelling into this build's wrapper - a separate build being one it compiles nothing
 * of.
 */
@Parity(claim = "tooling-suite")
package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.parity.Parity;
