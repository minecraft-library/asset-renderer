/**
 * The generator build's own suite: one package per stage it covers, and
 * {@link lib.minecraft.renderer.tooling.gate gate} for the checks that read real input.
 *
 * <p>Outside {@code gate}, every case drives hand-built ASM nodes, a jar written under a temporary
 * directory, or reflection over the tooling classes themselves. The {@code gate} cases read the
 * cached client jar, assuming their class away where nothing has cached one, or the tables this
 * repository ships. None runs a flow.
 *
 * <p><b>Parity.</b> Because no case runs a flow, none writes a shipped table or a flow log, and no
 * stored artifact can see a change here. The gate is this suite, {@code :tooling:test}, which the
 * renderer's {@code check} schedules under the {@code toolingTest} alias.
 */
@Parity(claim = "tooling-suite")
package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.parity.Parity;
