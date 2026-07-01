package lib.minecraft.renderer.asset.rule;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Pure function that decides whether a {@link CitRule} matches a given {@link ItemContext}.
 * <p>
 * All conditions are ANDed: the rule matches only when every one of its conditions holds. Checks
 * run cheapest-to-most-expensive to minimize work on the common "no match" path - item id
 * membership, damage / stack size range checks, enchantment presence and level ranges, then the
 * NBT-path condition walks last.
 */
@UtilityClass
public class CitMatcher {

    /**
     * Returns whether the rule applies to the given context. Returns {@code false} immediately
     * when the context is {@link ItemContext#EMPTY} so CIT resolution is cleanly skipped for
     * callers that pass no item metadata.
     *
     * @param rule the rule to test
     * @param context the item context
     * @return true when every condition in the rule matches the context
     */
    public static boolean match(@NotNull CitRule rule, @NotNull ItemContext context) {
        if (context == ItemContext.EMPTY) return false;

        if (!rule.matchedItems().contains(context.itemId())) return false;

        if (rule.damageRange().isPresent() && !rule.damageRange().get().contains(context.damage())) return false;

        if (rule.stackSizeRange().isPresent() && !rule.stackSizeRange().get().contains(context.stackCount())) return false;

        // Every listed enchantment must be present - level is unconstrained here.
        for (String required : rule.enchantmentIds()) {
            if (!context.enchantments().containsKey(required)) return false;
        }

        // Level-constrained enchantments: present AND within the required range.
        for (Map.Entry<String, IntRange> entry : rule.enchantmentLevels().entrySet()) {
            Integer level = context.enchantments().get(entry.getKey());
            if (level == null || !entry.getValue().contains(level)) return false;
        }

        for (Map.Entry<String, NbtCondition> entry : rule.nbtConditions().entrySet()) {
            String path = entry.getKey();
            // display.Name is sourced from the dedicated displayName field when set, otherwise it
            // falls through to the flat NBT view like any other path; absent paths read as "".
            String actual = path.equals("display.Name") && context.displayName().isPresent()
                ? context.displayName().get()
                : context.nbt().getOrDefault(path, "");
            if (!entry.getValue().test(actual)) return false;
        }

        return true;
    }

}
