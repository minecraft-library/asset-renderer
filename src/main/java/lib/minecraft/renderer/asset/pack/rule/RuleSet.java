package lib.minecraft.renderer.asset.pack.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.rule.RuleScanner;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A pack's parsed rule payload, at two granularities: one per pack (built by {@link RuleScanner#scan})
 * and one MERGED view the stack owns, built once at pipeline time by {@link RuleScanner#mergeAll} and
 * consumed by every render.
 *
 * @param pack the owning pack, or {@link PackId#VANILLA} nominally for a merged view
 * @param citRules the CIT rules, ordered so the first match wins
 * @param ctmRules the CTM rules, tile-target first (isolated-block base substitution evaluated by
 *     {@link #connectedTextureFor}; overlays and world-state predicates stay parse-and-store)
 * @param colors the merged colour overrides
 * @param useGlint the effective global {@code useGlint}, if any pack ships it
 */
public record RuleSet(
    @NotNull PackId pack,
    @NotNull ConcurrentList<CitRule> citRules,
    @NotNull ConcurrentList<CtmRule> ctmRules,
    @NotNull ColorProperties colors,
    @NotNull Optional<Boolean> useGlint
) {

    /**
     * An empty rule set for a pack that carries no OptiFine tree.
     *
     * @param pack the owning pack
     * @return the empty rule set
     */
    public static @NotNull RuleSet empty(@NotNull PackId pack) {
        return new RuleSet(pack, Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList(),
            new ColorProperties(new ResourceId("minecraft", "color.properties"), pack, Concurrent.<String, Integer>newMap().toUnmodifiable()), Optional.empty());
    }

    /**
     * Resolves the glint decision for one item render - the highest-precedence matching
     * {@code type=enchantment} CIT rule replaces the glint texture; else a merged
     * {@code useGlint == false} suppresses it; else the default. Rules DECIDE, the compose terminal
     * APPLIES; the enchantment walk reuses the merged, weight-ordered {@link #citRules()}, so
     * "highest-precedence" is first-match in that order.
     *
     * @param context the per-render item context
     * @return the glint decision the compose terminal applies
     */
    public @NotNull GlintPolicy glintFor(@NotNull ItemContext context) {
        for (CitRule rule : citRules()) {
            if (rule.type() != CitType.ENCHANTMENT) continue;
            // A type=enchantment rule replaces the glint texture; a rule that matched but carries no
            // texture (only a model / sub-textures) cannot replace it, so it is skipped rather than
            // suppressing the search for a later replacer.
            if (rule.matches(context) && rule.output().texture().isPresent())
                return new GlintPolicy.Replaced(rule.output().texture().get());
        }
        return useGlint().equals(Optional.of(false)) ? GlintPolicy.SUPPRESSED : GlintPolicy.DEFAULT;
    }

    /**
     * Resolves the Connected Textures substitution for one face of an isolated block icon - the
     * highest-precedence matching non-overlay rule replaces the face's base texture with its
     * no-neighbor tile. Walks the merged, tile-target-before-block {@link #ctmRules()} first-match-wins:
     * a rule matches when it targets this face ({@code faces=}), its target matches the subject
     * ({@code matchTiles} base-texture name or {@code matchBlocks} id + state filters), and it is not an
     * overlay method (overlays composite only on a neighbor transition, so they never base-replace an
     * isolated subject). On the first match the no-neighbor tile is selected via
     * {@link CtmNeighborResolver#select}: a concrete tile substitutes and stops the walk, {@code <default>}
     * leaves the base texture and stops, {@code <skip>} falls through to the next rule.
     *
     * <p>{@code connect} / biome / height predicates are world-state and never consulted, so headlessly a
     * non-overlay rule is an unconditional match on target and face - the documented no-neighbor behavior.
     * Rules decide; the render path applies the returned id.
     *
     * @param context the per-face query - the rendered block id, state, base texture id, and face
     * @return the substitute texture id, or empty when no non-overlay rule replaces the base
     */
    public @NotNull Optional<ResourceId> connectedTextureFor(@NotNull CtmContext context) {
        for (CtmRule rule : ctmRules()) {
            if (rule.method().isOverlay()) continue;
            if (!rule.faces().contains(context.face())) continue;
            if (!rule.target().matches(context.blockId(), context.baseTextureId(), context.state())) continue;
            Optional<TileRef> selected = CtmNeighborResolver.select(rule, CtmNeighborhood.ISOLATED, context.blockId().hashCode());
            if (selected.isEmpty()) continue;
            TileRef tile = selected.get();
            if (tile instanceof TileRef.Skip) continue;
            if (tile instanceof TileRef.Texture texture) return Optional.of(texture.id());
            return Optional.empty();
        }
        return Optional.empty();
    }

}
