package lib.minecraft.renderer.asset.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.content.rule.RuleScanner;
import lib.minecraft.renderer.vanilla.id.PackId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A pack's parsed rule payload, at two granularities: one per pack (built by {@link RuleScanner#scan})
 * and one MERGED view the stack owns, built once at pipeline time by {@link RuleScanner#mergeAll} and
 * consumed by every render.
 *
 * @param pack the owning pack, or {@link PackId#VANILLA} nominally for a merged view
 * @param citRules the CIT rules, ordered so the first match wins
 * @param ctmRules the CTM rules, tile-target first (walked first-match-wins for an isolated block's
 *     base substitution; overlays and world-state predicates stay parse-and-store)
 * @param colors the colour overrides, if a file is read: per pack, the {@code optifine/color.properties}
 *     it ships when that file can be loaded, present with no key for a file holding no usable one; in
 *     the merged view, the overrides of the highest-priority pack shipping one, under the nominal
 *     {@code color.properties} id and {@link PackId#VANILLA}
 * @param useGlint the effective global {@code useGlint}, if any pack ships it
 */
public record RuleSet(
    @NotNull PackId pack,
    @NotNull ConcurrentList<CitRule> citRules,
    @NotNull ConcurrentList<CtmRule> ctmRules,
    @NotNull Optional<ColorProperties> colors,
    @NotNull Optional<Boolean> useGlint
) {

    /**
     * An empty rule set for a pack that carries no OptiFine tree.
     *
     * @param pack the owning pack
     * @return the empty rule set
     */
    public static @NotNull RuleSet empty(@NotNull PackId pack) {
        return new RuleSet(pack, Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList(), Optional.empty(), Optional.empty());
    }

}
