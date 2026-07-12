package lib.minecraft.renderer.pipeline.pack.rule;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Computes the {@link GlintPolicy} for one item render from the merged rule set (03-rules §7, D3.9) -
 * rules DECIDE, the compose terminal APPLIES. The two pack inputs that touch glint are matched
 * {@code type=enchantment} CIT rules (a single-texture glint replacement) and the global
 * {@code useGlint=false} toggle; this evaluator folds them into one decision the item renderer threads
 * into the finalize stage.
 *
 * <p>Precedence (03-rules §7): the highest-precedence matching {@code type=enchantment} rule wins with
 * {@link GlintPolicy.Replaced}; else a merged {@code useGlint == false} yields {@link GlintPolicy#SUPPRESSED};
 * else {@link GlintPolicy#DEFAULT}. The enchantment walk reuses the same merged, weight-ordered
 * {@link RuleSet#citRules()} list, so "highest-precedence" is first-match in that order.
 */
@UtilityClass
public class GlintEvaluator {

    /**
     * Resolves the glint decision for a render-time item context.
     *
     * @param rules the merged rule set
     * @param context the per-render item context
     * @return the glint decision the compose terminal applies
     */
    public static @NotNull GlintPolicy evaluate(@NotNull RuleSet rules, @NotNull ItemContext context) {
        for (CitRule rule : rules.citRules()) {
            if (rule.type() != CitType.ENCHANTMENT) continue;
            // A type=enchantment rule replaces the glint texture; a rule that matched but carries no
            // texture (only a model / sub-textures) cannot replace it, so it is skipped rather than
            // suppressing the search for a later replacer.
            if (rule.matches(context) && rule.output().texture().isPresent())
                return new GlintPolicy.Replaced(rule.output().texture().get());
        }
        return rules.useGlint().equals(Optional.of(false)) ? GlintPolicy.SUPPRESSED : GlintPolicy.DEFAULT;
    }

}
