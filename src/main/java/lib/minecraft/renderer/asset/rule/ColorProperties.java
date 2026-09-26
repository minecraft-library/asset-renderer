package lib.minecraft.renderer.asset.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A pack's {@code optifine/color.properties} (or {@code mcpatcher/} twin) - the parsed key-to-ARGB
 * override map. It uses a parse-all-store-all model with a forgiving hex parser, carries the
 * index-DTO {@link #id} / {@link #pack}, provides typed accessors for the render-relevant families,
 * and (via {@link RuleSet}) merges per-key across the pack stack.
 *
 * <p>Two families are live today through {@code RendererContext.findColorOverride}: the biome-tint pack
 * keys ({@code grass.*} / {@code foliage.*} / {@code water.*}) and {@code redstone.<power>}. The rest
 * ({@code potion.*}, {@code collar.*}, spawn-egg layers, ...) are parsed and reachable but not yet
 * consumed; unknown keys stay stored and harmless.
 *
 * @param id the pack-relative source id
 * @param pack the owning pack
 * @param overrides the parsed key-to-opaque-ARGB map
 */
public record ColorProperties(
    @NotNull ResourceId id,
    @NotNull PackId pack,
    @NotNull ConcurrentMap<String, Integer> overrides
) {

    /** The empty color properties - a pack that ships no {@code color.properties}. */
    public static final @NotNull ColorProperties EMPTY = new ColorProperties(
        new ResourceId("minecraft", "color.properties"), PackId.VANILLA, Concurrent.newMap());

    /**
     * The override for a raw property key, if present.
     *
     * @param key the property key
     * @return the opaque ARGB override, or empty when the key is absent
     */
    public @NotNull Optional<Integer> get(@NotNull String key) {
        return this.overrides.getOptional(key);
    }

    /**
     * The redstone-wire tint override for a power level - {@code redstone.<power>}.
     *
     * @param power the redstone power level, {@code 0..15}
     * @return the override, or empty when the pack does not supply it
     */
    public @NotNull Optional<Integer> redstoneTint(int power) {
        return get("redstone." + power);
    }

    /**
     * Whether this carries no overrides.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        return this.overrides.isEmpty();
    }

}
