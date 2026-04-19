package lib.minecraft.renderer.pipeline.pack;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Pure function that decides whether a {@link CitRule} matches a given {@link ItemContext}.
 * <p>
 * Checks are ordered cheapest-to-most-expensive to minimize work for the common "no match" path:
 * item id contains lookup, damage / stack size range checks, enchantment presence, NBT path
 * walks.
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
     * @return true if every condition in the rule matches the context
     */
    public static boolean match(@NotNull CitRule rule, @NotNull ItemContext context) {
        if (context == ItemContext.EMPTY) return false;

        if (!rule.matchedItems().contains(context.itemId())) return false;

        if (rule.damageRange().isPresent() && !rule.damageRange().get().contains(context.damage())) return false;

        if (rule.stackSizeRange().isPresent() && !rule.stackSizeRange().get().contains(context.stackCount())) return false;

        for (String required : rule.enchantmentIds()) {
            if (!context.enchantments().containsKey(required)) return false;
        }

        for (Map.Entry<String, IntRange> entry : rule.enchantmentLevels().entrySet().stream().toList()) {
            Integer level = context.enchantments().get(entry.getKey());
            if (level == null || !entry.getValue().contains(level)) return false;
        }

        for (Map.Entry<String, NbtCondition> entry : rule.nbtConditions().entrySet().stream().toList()) {
            String path = entry.getKey();
            String actual = path.equals("display.Name") && context.displayName().isPresent()
                ? context.displayName().get()
                : context.nbt().getOrDefault(path, "");
            if (!entry.getValue().test(actual)) return false;
        }

        return true;
    }

}
