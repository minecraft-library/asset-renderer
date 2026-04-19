package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.options.ItemOptions;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The per-render context an {@link Renderer item renderer} passes down so the
 * pack translation layer can decide whether any CIT rule applies to the current invocation.
 * <p>
 * Callers resolve item metadata (NBT, damage, display name, enchantments, stack count, potion
 * effects) from their own game state and build a context per render. A missing piece of data
 * simply means no rule can match on that condition; the item still renders with its vanilla
 * textures.
 *
 * @param itemId the namespaced item identifier, e.g. {@code "minecraft:diamond_sword"}
 * @param damage the current damage value (0 for items with no durability)
 * @param stackCount the stack size for the item slot
 * @param displayName the item display name if any, matched against CIT {@code nbt.display.Name} conditions
 * @param nbt a flat NBT view keyed by dot-separated paths, matched against CIT {@code nbt.*} conditions
 * @param enchantments the item enchantments keyed by namespaced id (e.g. {@code "minecraft:sharpness" -> 5})
 * @param potionEffects the potion effects on this item (for potions and tipped arrows), in
 *     application order; the first entry drives the liquid/head tint when no explicit
 *     {@link ItemOptions#getPotionColor()} override is present
 */
public record ItemContext(
    @NotNull String itemId,
    int damage,
    int stackCount,
    @NotNull Optional<String> displayName,
    @NotNull ConcurrentMap<String, String> nbt,
    @NotNull ConcurrentMap<String, Integer> enchantments,
    @NotNull ConcurrentList<String> potionEffects
) {

    /** The empty context: no item id, no metadata. CIT rules never match against this context. */
    public static final @NotNull ItemContext EMPTY = new ItemContext(
        "",
        0,
        1,
        Optional.empty(),
        Concurrent.newMap(),
        Concurrent.newMap(),
        Concurrent.newList()
    );

    /**
     * Creates a minimal context describing only the item id. Useful for render calls that do not
     * care about CIT matching.
     *
     * @param itemId the namespaced item id
     * @return a new context
     */
    public static @NotNull ItemContext ofItem(@NotNull String itemId) {
        return new ItemContext(itemId, 0, 1, Optional.empty(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newList());
    }

}
