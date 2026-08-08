/**
 * The pack rule DTOs the renderer consults - OptiFine CIT / CTM rules, NBT conditionals, and
 * {@code color.properties}. Pipeline-parsed at acquisition (by
 * {@link lib.minecraft.renderer.pipeline.pack.rule pipeline.pack.rule}'s scanner / parsers),
 * render-evaluated here.
 *
 * <p>{@link lib.minecraft.renderer.asset.pack.rule.RuleSet RuleSet} is the merged per-stack payload -
 * the weight-ordered {@link lib.minecraft.renderer.asset.pack.rule.CitRule CitRule} list (each
 * self-evaluating via {@code matches(ItemContext)}), the
 * {@link lib.minecraft.renderer.asset.pack.rule.CtmRule CtmRule} store (its non-overlay methods
 * resolved for an isolated block icon by
 * {@link lib.minecraft.renderer.asset.pack.rule.RuleSet#connectedTextureFor(lib.minecraft.renderer.asset.pack.rule.CtmContext)
 * connectedTextureFor}; overlays and world-state predicates parse-and-hold), the merged
 * {@link lib.minecraft.renderer.asset.pack.rule.ColorProperties ColorProperties} overrides, and the
 * global glint toggle - plus
 * {@link lib.minecraft.renderer.asset.pack.rule.RuleSet#glintFor(lib.minecraft.renderer.asset.pack.rule.ItemContext)
 * glintFor}, which folds a matching {@code type=enchantment} rule and the {@code useGlint} flag into a
 * {@link lib.minecraft.renderer.asset.pack.rule.GlintPolicy GlintPolicy}.
 *
 * <p><b>NBT conditionals.</b> {@link lib.minecraft.renderer.asset.pack.rule.NbtPath NbtPath} walks
 * lists / wildcards / {@code count}, {@link lib.minecraft.renderer.asset.pack.rule.NbtPredicate
 * NbtPredicate} carries the {@code pattern:}/{@code regex:}/{@code range:}/{@code exists:}/{@code raw:}
 * prefixes, and {@code NbtValues} normalizes the nbt-factory gaps;
 * {@link lib.minecraft.renderer.asset.pack.rule.NbtRule NbtRule} joins a path, a predicate, and the
 * {@code !} negation.
 *
 * <p><b>Parity.</b> Vanilla ships no {@code optifine/} tree, so every rule-layer behavior is inert
 * with only vanilla loaded - the byte-parity contract holds without gating.
 */
package lib.minecraft.renderer.asset.pack.rule;
