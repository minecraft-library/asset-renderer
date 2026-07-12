package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The OptiFine CIT enchantment filter - the {@code enchantments=} id list and the {@code levels=}
 * range list, replacing HEAD's invented {@code id=range} pairs (07-optifine defect #2). Matching has
 * the grammar's two modes (07-optifine §2.1): with {@link #ids} present the item must carry AT LEAST
 * ONE listed enchantment (OptiFine's "any of these" list) and (when {@link #levels} is present) that
 * matched enchantment's level must fall in range; with {@link #ids} empty the levels match the TOTAL
 * level sum across every enchantment, and an empty spec matches any enchantment.
 *
 * <p>The list is ANY, not ALL: the canonical idiom {@code enchantments=sharpness smite} pairs
 * mutually-exclusive vanilla enchantments (an item holds at most one), so an all-must-be-present read
 * could never match.
 *
 * @param ids the accepted enchantment ids, {@code minecraft:}-defaulted; empty means any enchantment
 * @param levels the accepted levels, absent when the rule constrains only presence
 */
public record EnchantmentSpec(@NotNull ConcurrentList<ResourceId> ids, @NotNull Optional<IntRanges> levels) {

    /**
     * Whether an item's enchantments satisfy this filter.
     *
     * @param enchantments the item's enchantments, keyed by namespaced id to level
     * @return {@code true} when the two-mode grammar is satisfied
     */
    public boolean matches(@NotNull ConcurrentMap<String, Integer> enchantments) {
        if (!this.ids.isEmpty()) {
            for (ResourceId id : this.ids) {
                Integer level = enchantments.get(id.id());
                if (level == null) continue;
                if (this.levels.isEmpty() || this.levels.get().contains(level)) return true;
            }
            return false;
        }
        if (this.levels.isPresent()) {
            int sum = enchantments.values().stream().mapToInt(Integer::intValue).sum();
            return this.levels.get().contains(sum);
        }
        return !enchantments.isEmpty();
    }

}
