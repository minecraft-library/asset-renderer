package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A pack's parsed rule payload, at two granularities: one per {@link ResourcePack} (built
 * by {@link RuleScanner}) and one MERGED view the stack owns, built once at pipeline time by
 * {@link #merge(PackStack)} and consumed by every render.
 *
 * <p>Merge order: CIT rules by weight DESC, then FILENAME (a platform-deterministic tie-break), then
 * higher-priority pack; CTM rules partitioned tile-target
 * before block-target then the same key; {@code color.properties} merged per-KEY with
 * the highest-priority pack winning each key; {@code useGlint} taken from the highest pack shipping it.
 *
 * @param pack the owning pack, or {@link PackId#VANILLA} nominally for a merged view
 * @param citRules the CIT rules, ordered so the first match wins
 * @param ctmRules the CTM rules, tile-target first (parse-and-store only; CTM renders nothing)
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
     * Builds the merged view over a resolved stack - scans every pack and folds their rules into one
     * deterministically-ordered payload.
     *
     * @param stack the resolved pack stack
     * @return the merged rule set
     */
    public static @NotNull RuleSet merge(@NotNull PackStack stack) {
        Map<PackId, Integer> priority = new HashMap<>();
        List<RuleSet> perPack = new ArrayList<>();
        int index = 0;
        for (ResourcePack pack : stack.ascending()) {
            priority.put(pack.id(), index++);
            perPack.add(RuleScanner.scan(pack));
        }

        List<CitRule> cit = new ArrayList<>();
        for (RuleSet rules : perPack) cit.addAll(rules.citRules());
        cit.sort(citComparator(priority));

        List<CtmRule> tiles = new ArrayList<>();
        List<CtmRule> blocks = new ArrayList<>();
        for (RuleSet rules : perPack)
            for (CtmRule rule : rules.ctmRules()) (rule.isTileTarget() ? tiles : blocks).add(rule);
        Comparator<CtmRule> ctmComparator = ctmComparator(priority);
        tiles.sort(ctmComparator);
        blocks.sort(ctmComparator);
        List<CtmRule> ctm = new ArrayList<>(tiles);
        ctm.addAll(blocks);

        LinkedHashMap<String, Integer> colors = new LinkedHashMap<>();
        Optional<Boolean> useGlint = Optional.empty();
        for (RuleSet rules : perPack) {
            colors.putAll(rules.colors().overrides());
            if (rules.useGlint().isPresent()) useGlint = rules.useGlint();
        }

        ColorProperties mergedColors = new ColorProperties(
            new ResourceId("minecraft", "color.properties"), PackId.VANILLA, Concurrent.adoptMap(colors).toUnmodifiable());
        return new RuleSet(PackId.VANILLA,
            Concurrent.adoptList(cit).toUnmodifiable(), Concurrent.adoptList(ctm).toUnmodifiable(), mergedColors, useGlint);
    }

    /** Weight DESC, then filename ASC, then higher-priority pack, then full id - a total, deterministic order. */
    private static @NotNull Comparator<CitRule> citComparator(@NotNull Map<PackId, Integer> priority) {
        return Comparator.comparingInt(CitRule::weight).reversed()
            .thenComparing(CitRule::filename)
            .thenComparing(rule -> priority.getOrDefault(rule.pack(), 0), Comparator.<Integer>reverseOrder())
            .thenComparing(rule -> rule.id().name());
    }

    /** Weight DESC, then filename ASC, then higher-priority pack, then full id (partition is applied before this). */
    private static @NotNull Comparator<CtmRule> ctmComparator(@NotNull Map<PackId, Integer> priority) {
        return Comparator.comparingInt(CtmRule::weight).reversed()
            .thenComparing(CtmRule::filename)
            .thenComparing(rule -> priority.getOrDefault(rule.pack(), 0), Comparator.<Integer>reverseOrder())
            .thenComparing(rule -> rule.id().name());
    }

}
