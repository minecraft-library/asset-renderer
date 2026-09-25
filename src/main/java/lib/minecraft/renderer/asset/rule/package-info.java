/**
 * The third-party pack-rule grammar as parsed - the OptiFine CIT / CTM rules and
 * {@code color.properties} overrides the renderer consults. Parsed at acquisition (by
 * {@link lib.minecraft.renderer.content.rule content.rule}'s scanner / parsers), render-evaluated
 * here.
 *
 * <p>{@link lib.minecraft.renderer.asset.rule.RuleSet RuleSet} is the merged per-stack payload -
 * the weight-ordered {@link lib.minecraft.renderer.asset.rule.CitRule CitRule} list (an
 * {@link lib.minecraft.renderer.request.ItemContext#matches(lib.minecraft.renderer.asset.rule.CitRule)
 * ItemContext} answers whether each one applies), the
 * {@link lib.minecraft.renderer.asset.rule.CtmRule CtmRule} store (its non-overlay methods
 * resolved for an isolated block icon by the port's
 * {@link lib.minecraft.renderer.port.RendererContext#resolveConnectedTexture connected-texture lookup};
 * overlays and world-state predicates parse-and-hold), the merged
 * {@link lib.minecraft.renderer.asset.rule.ColorProperties ColorProperties} overrides, and the
 * global glint toggle, which the port's
 * {@link lib.minecraft.renderer.port.RendererContext#resolveItemTextureOverride item-texture override}
 * folds with a matching {@code type=enchantment} rule into a
 * {@link lib.minecraft.renderer.port.answer.GlintPolicy GlintPolicy}. The rest are the parts a rule
 * is written in: a CIT rule's type, filters and output, and a CTM rule's method, target, block
 * matches, tiles and held extras.
 * A type that neither matches nor is matched against does not belong here.
 *
 * <p><b>NBT conditionals.</b> The value predicates a rule matches with are the
 * {@link lib.minecraft.renderer.asset.rule.filter filter} package under this one.
 * {@link lib.minecraft.renderer.asset.rule.filter.NbtPath NbtPath} walks
 * lists / wildcards / {@code count}, {@link lib.minecraft.renderer.asset.rule.filter.NbtPredicate
 * NbtPredicate} carries the {@code pattern:}/{@code regex:}/{@code range:}/{@code exists:}/{@code raw:}
 * prefixes, and {@code NbtValues} normalizes the nbt-factory gaps;
 * {@link lib.minecraft.renderer.asset.rule.filter.NbtRule NbtRule} joins a path, a predicate, and the
 * {@code !} negation.
 *
 * <p><b>Parity.</b> Vanilla ships no {@code optifine/} tree, so every rule-layer behavior is inert
 * with only vanilla loaded - the byte-parity contract holds without gating.
 */
@Parity(claim = "pack-rule-layer")
package lib.minecraft.renderer.asset.rule;

import lib.minecraft.renderer.parity.Parity;
