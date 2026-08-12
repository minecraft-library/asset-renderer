/**
 * The instruction-walk language: one drive loop under a typed descriptor chain. A walk is
 * built on {@link lib.minecraft.renderer.tooling.walk.AsmWalker AsmWalker} - sources, geometry,
 * fold attachment, trace - and narrows to
 * {@link lib.minecraft.renderer.tooling.walk.Walk Walk}, on which no fold stage exists, so a
 * filter can never starve a stateful cell. Folds declare their disciplines as tokens the
 * engine enforces; commits emit through
 * {@link lib.minecraft.renderer.tooling.walk.CommitWalk CommitWalk} and
 * {@link lib.minecraft.renderer.tooling.walk.PairWalk PairWalk}; branch-following rides
 * {@link lib.minecraft.renderer.tooling.walk.Tracer Tracer} with an always-on cycle guard; the
 * bytecode interpreters ride one {@link lib.minecraft.renderer.tooling.walk.Interp Interp}
 * chassis. Terminals are eager and ordered; misses are {@code null} or empty, never an
 * {@code Optional}; a looked-up source that failed to resolve is readable via
 * {@link lib.minecraft.renderer.tooling.walk.Missing Missing} before any run.
 */
package lib.minecraft.renderer.tooling.walk;
