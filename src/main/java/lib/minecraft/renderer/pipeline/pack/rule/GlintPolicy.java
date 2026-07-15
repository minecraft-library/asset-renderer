package lib.minecraft.renderer.pipeline.pack.rule;

import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

/**
 * The glint decision a CIT rule walk produces for one render - rules DECIDE, the compose terminal
 * APPLIES. The decision rides on {@link CitResult} through the item options into
 * the finalize stage, which stays the sole applier of glint x animation.
 *
 * <p>The evaluation that chooses {@link Suppressed} / {@link Replaced} (matched {@code type=enchantment}
 * rules, the global {@code useGlint=false} toggle) lives in {@link GlintEvaluator}; the
 * result rides {@link CitResult} and the item renderer translates it into a {@code Finalize.Glint}.
 */
public sealed interface GlintPolicy permits GlintPolicy.Default, GlintPolicy.Suppressed, GlintPolicy.Replaced {

    /** The shared vanilla-behaviour policy. */
    @NotNull GlintPolicy DEFAULT = new Default();

    /** The shared glint-suppressed policy - a merged {@code useGlint=false} with no matching enchantment rule. */
    @NotNull GlintPolicy SUPPRESSED = new Suppressed();

    /** Vanilla glint behaviour - the item glints when its own flags say so. */
    record Default() implements GlintPolicy {}

    /** Glint suppressed - a merged {@code useGlint=false} with no matching enchantment rule. */
    record Suppressed() implements GlintPolicy {}

    /**
     * Glint replaced by a custom texture - the effect of a matched {@code type=enchantment} rule.
     *
     * @param texture the replacement glint texture id
     */
    record Replaced(@NotNull ResourceId texture) implements GlintPolicy {}

}
