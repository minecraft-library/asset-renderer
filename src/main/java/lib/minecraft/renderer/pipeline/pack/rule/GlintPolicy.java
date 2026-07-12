package lib.minecraft.renderer.pipeline.pack.rule;

import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

/**
 * The glint decision a CIT rule walk produces for one render - rules DECIDE, the compose terminal
 * APPLIES (D3.9, 03-rules §7). The decision rides on {@link CitResult} through the item options into
 * the finalize stage, which stays the sole applier so doc 04 keeps ownership of glint x animation.
 *
 * <p>The evaluation that chooses {@link Suppressed} / {@link Replaced} (matched {@code type=enchantment}
 * rules, the global {@code useGlint=false} toggle) lands in sub-commit 3c; the CIT walk emits
 * {@link #DEFAULT} until then.
 */
public sealed interface GlintPolicy permits GlintPolicy.Default, GlintPolicy.Suppressed, GlintPolicy.Replaced {

    /** The shared vanilla-behaviour policy. */
    @NotNull GlintPolicy DEFAULT = new Default();

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
