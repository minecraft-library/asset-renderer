/**
 * The pack rule parsers - scan a pack's OptiFine / MCPatcher CIT and CTM trees and
 * {@code color.properties} into the {@link lib.minecraft.renderer.asset.pack.rule asset.pack.rule}
 * DTOs at pipeline time. Pipeline-only; the parsed rules are render-evaluated from
 * {@code asset.pack.rule}.
 *
 * <p>{@link lib.minecraft.renderer.pipeline.pack.rule.RuleScanner RuleScanner} walks a pack's active
 * roots into its per-pack {@link lib.minecraft.renderer.asset.pack.rule.RuleSet RuleSet} and folds the
 * whole stack into the merged view ({@code mergeAll});
 * {@link lib.minecraft.renderer.pipeline.pack.rule.CitParser CitParser} and
 * {@link lib.minecraft.renderer.pipeline.pack.rule.CtmParser CtmParser} parse one {@code .properties}
 * file each into a {@code CitRule} / {@code CtmRule};
 * {@link lib.minecraft.renderer.pipeline.pack.rule.RuleDiagnostics RuleDiagnostics} logs rejects, and
 * {@link lib.minecraft.renderer.pipeline.pack.rule.RuleRejection RuleRejection} is the package-private
 * control-flow signal that never escapes the layer.
 *
 * <p><b>Parity.</b> Vanilla ships no {@code optifine/} tree, so a vanilla-only scan yields nothing and
 * the whole rule layer stays inert - the byte-parity contract holds without gating.
 */
package lib.minecraft.renderer.pipeline.pack.rule;
