package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.asset.rule.CitRule;
import lib.minecraft.renderer.asset.rule.CitType;
import lib.minecraft.renderer.asset.rule.CtmRule;
import lib.minecraft.renderer.asset.rule.RuleSet;
import lib.minecraft.renderer.asset.rule.TileRef;
import lib.minecraft.renderer.content.rule.CtmNeighbors;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.port.answer.CtmContext;
import lib.minecraft.renderer.port.answer.GlintPolicy;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The pack-rule lookups the index answers the port's CIT glint and connected-texture questions with.
 */
@UtilityClass
@Parity(claim = "pack-rule-layer")
@Parity(subject = {Subject.BLOCK, Subject.ITEM, Subject.MENU})
final class RuleLookup {

    /**
     * Resolves the glint decision for one item render - the highest-precedence matching
     * {@code type=enchantment} CIT rule replaces the glint texture; else a merged
     * {@code useGlint == false} suppresses it; else the default. Rules DECIDE, the compose terminal
     * APPLIES; the enchantment walk reuses the merged, weight-ordered {@link RuleSet#citRules()}, so
     * "highest-precedence" is first-match in that order.
     *
     * @param rules the merged rule set the walk reads
     * @param context the per-render item context
     * @return the glint decision the compose terminal applies
     */
    static @NotNull GlintPolicy glint(@NotNull RuleSet rules, @NotNull ItemContext context) {
        for (CitRule rule : rules.citRules()) {
            if (rule.type() != CitType.ENCHANTMENT) continue;
            // A type=enchantment rule replaces the glint texture; a rule that matched but carries no
            // texture (only a model / sub-textures) cannot replace it, so it is skipped rather than
            // suppressing the search for a later replacer.
            if (context.matches(rule) && rule.output().texture().isPresent())
                return new GlintPolicy.Replaced(rule.output().texture().get());
        }
        return rules.useGlint().equals(Optional.of(false)) ? GlintPolicy.SUPPRESSED : GlintPolicy.DEFAULT;
    }

    /**
     * Resolves the Connected Textures substitution for one face of an isolated block icon - the
     * highest-precedence matching non-overlay rule replaces the face's base texture with its
     * no-neighbor tile. Walks the merged, tile-target-before-block {@link RuleSet#ctmRules()}
     * first-match-wins: a rule matches when it targets this face ({@code faces=}), its target matches the
     * subject ({@code matchTiles} base-texture name or {@code matchBlocks} id + state filters), and it is
     * not an overlay method (overlays composite only on a neighbor transition, so they never base-replace
     * an isolated subject). On the first match the no-neighbor tile is selected via
     * {@link CtmNeighbors#select}: a concrete tile substitutes and stops the walk, {@code <default>}
     * leaves the base texture and stops, {@code <skip>} falls through to the next rule.
     *
     * <p>{@code connect} / biome / height predicates are world-state and never consulted, so headlessly a
     * non-overlay rule is an unconditional match on target and face - the documented no-neighbor behavior.
     * Rules decide; the render path applies the returned id.
     *
     * @param rules the merged rule set the walk reads
     * @param context the per-face query - the rendered block id, state, base texture id, and face
     * @return the substitute texture id, or empty when no non-overlay rule replaces the base
     */
    static @NotNull Optional<ResourceId> connectedTexture(@NotNull RuleSet rules, @NotNull CtmContext context) {
        for (CtmRule rule : rules.ctmRules()) {
            if (rule.method().isOverlay()) continue;
            if (!rule.faces().contains(context.face())) continue;
            if (!rule.target().matches(context.blockId(), context.baseTextureId(), context.state())) continue;
            Optional<TileRef> selected = CtmNeighbors.select(rule, context.blockId().hashCode());
            if (selected.isEmpty()) continue;
            TileRef tile = selected.get();
            if (tile instanceof TileRef.Skip) continue;
            if (tile instanceof TileRef.Texture texture) return Optional.of(texture.id());
            return Optional.empty();
        }
        return Optional.empty();
    }

}
