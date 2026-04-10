package dev.sbs.renderer.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The per-render context an {@link dev.sbs.renderer.Renderer item renderer} passes down so the
 * pack translation layer can decide whether any CIT rule applies to the current invocation.
 * <p>
 * Callers resolve item metadata (NBT, damage, display name, enchantments, stack count) from
 * their own game state and build a context per render. A missing piece of data simply means no
 * rule can match on that condition; the item still renders with its vanilla textures.
 *
 * @param itemId the namespaced item identifier, e.g. {@code "minecraft:diamond_sword"}
 * @param damage the current damage value (0 for items with no durability)
 * @param stackCount the stack size for the item slot
 * @param displayName the item display name if any, matched against CIT {@code nbt.display.Name} conditions
 * @param nbt a flat NBT view keyed by dot-separated paths, matched against CIT {@code nbt.*} conditions
 * @param enchantments the item enchantments keyed by namespaced id (e.g. {@code "minecraft:sharpness" -> 5})
 */
public record ItemContext(
    @NotNull String itemId,
    int damage,
    int stackCount,
    @NotNull Optional<String> displayName,
    @NotNull ConcurrentMap<String, String> nbt,
    @NotNull ConcurrentMap<String, Integer> enchantments
) {

    /** The empty context: no item id, no metadata. CIT rules never match against this context. */
    public static final @NotNull ItemContext EMPTY = new ItemContext(
        "",
        0,
        1,
        Optional.empty(),
        Concurrent.newMap(),
        Concurrent.newMap()
    );

    /**
     * Creates a minimal context describing only the item id. Useful for render calls that do not
     * care about CIT matching.
     *
     * @param itemId the namespaced item id
     * @return a new context
     */
    public static @NotNull ItemContext ofItem(@NotNull String itemId) {
        return new ItemContext(itemId, 0, 1, Optional.empty(), Concurrent.newMap(), Concurrent.newMap());
    }

}
