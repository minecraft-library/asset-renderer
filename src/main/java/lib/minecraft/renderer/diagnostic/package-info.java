/**
 * The tree-structured run log that every stage reports through, on the rendering side and the
 * generator side alike.
 *
 * <p>{@link lib.minecraft.renderer.diagnostic.Diagnostics Diagnostics} is the log. A root scope is
 * opened with a name, an {@link lib.minecraft.renderer.diagnostic.Diagnostics.Output Output} mode and an
 * optional log file path, and child scopes are keyed by plain string tags, so a scope's path mirrors
 * whatever tree its caller walks - an install's entity and style ids, a generator's flow and pass.
 * Each scope records {@link lib.minecraft.renderer.diagnostic.Diagnostics.Entry Entry} lines at a
 * {@link lib.minecraft.renderer.diagnostic.Diagnostics.Severity Severity} from a format string and its
 * arguments. Recording is unconditional, the output mode gates only emission, and counts aggregate
 * over a scope's whole subtree. No refusal is decided here: a refusal throws on its own facts, and the
 * entry beside it is the post-mortem.
 *
 * <p>Two narrower channels sit beside it.
 * {@link lib.minecraft.renderer.diagnostic.RuleDiagnostics RuleDiagnostics} names a rejected pack rule
 * by its pack id, file id, key, value and reason, all as strings.
 * {@link lib.minecraft.renderer.diagnostic.DebugChannel DebugChannel} is the parity-debug trace
 * surface, three channels each armed by a system property: a per-pixel fragment trace over a screen
 * rectangle ({@code -Dasset.entity.pixel.dump}), whose fragment lines take screen coordinates, depths,
 * texel coordinates and ARGB samples; a canvas-fit bounds trace ({@code -Dasset.entity.fit.dump}); and
 * a per-polygon screen-bounds trace bracketed per entity on the calling thread
 * ({@code -Dasset.entity.bounds.dump}).
 *
 * <p>A type nothing reports through does not belong here.
 *
 * <p><b>Parity.</b> Every member declares its own claims.
 */
package lib.minecraft.renderer.diagnostic;
