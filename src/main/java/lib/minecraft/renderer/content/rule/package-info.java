/**
 * Parsing a pack's third-party rule files into the rule grammar - a pack's OptiFine / MCPatcher CIT
 * and CTM trees and its {@code color.properties}, read into the
 * {@link lib.minecraft.renderer.asset.rule asset.rule} records while the context is built.
 *
 * <p>{@link lib.minecraft.renderer.content.rule.RuleScanner RuleScanner} walks a pack's active
 * roots into its per-pack {@link lib.minecraft.renderer.asset.rule.RuleSet RuleSet} and folds the
 * whole stack into the merged view ({@code mergeAll});
 * {@link lib.minecraft.renderer.content.rule.CitParser CitParser} and
 * {@link lib.minecraft.renderer.content.rule.CtmParser CtmParser} parse one {@code .properties}
 * file each into a {@code CitRule} / {@code CtmRule}, and
 * {@link lib.minecraft.renderer.content.rule.ColorPropertiesParser ColorPropertiesParser} reads a
 * {@code color.properties} body into a {@code ColorProperties};
 * {@link lib.minecraft.renderer.diagnostic.RuleDiagnostics RuleDiagnostics} logs rejects, and
 * {@link lib.minecraft.renderer.exception.RuleRejection RuleRejection} is the fail-closed signal a
 * parser catches at the top of each parse, so it never escapes the parse that raised it.
 *
 * <p>{@link lib.minecraft.renderer.content.rule.CtmNeighbors CtmNeighbors} is the one member read after
 * the build: it picks the tile a matched CTM rule contributes for the isolated neighbourhood a
 * headless render always supplies, called at lookup time by
 * {@link lib.minecraft.renderer.asset.rule.RuleSet#connectedTextureFor RuleSet.connectedTextureFor}
 * and answering a {@code TileRef} of the same grammar.
 *
 * <p>A type that emits something other than an {@code asset.rule} record does not belong here.
 *
 * <p><b>Parity.</b> Vanilla ships no {@code optifine/} tree, so a vanilla-only scan yields nothing and
 * the whole rule layer stays inert against a vanilla reference. Every member declares its own claims.
 */
package lib.minecraft.renderer.content.rule;
