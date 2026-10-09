package lib.minecraft.renderer.asset.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A pack's {@code optifine/color.properties} - the parsed key-to-ARGB override map. It uses a
 * parse-all-store-all model with a forgiving hex parser, carries the index-DTO {@link #id} /
 * {@link #pack}, and provides typed accessors for the render-relevant families.
 *
 * <p>A stack reads one such file whole: the one the highest-priority pack shipping a loadable copy
 * holds, so a key that file does not write is unset even where a lower pack's file writes it (see
 * {@link RuleSet}).
 *
 * <p>{@code redstone.<power>} is live through {@code RendererContext.findColorOverride}. The rest
 * ({@code lilypad}, {@code potion.*}, {@code collar.*}, spawn-egg layers, ...) are parsed and reachable
 * but not consumed; unknown keys stay stored and harmless.
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

    /**
     * The color properties holding no override, under the nominal {@code color.properties} id and
     * {@link PackId#VANILLA} - what a stack in which no pack ships a {@code color.properties} reads as.
     */
    public static final @NotNull ColorProperties EMPTY = new ColorProperties(
        new ResourceId("minecraft", "color.properties"), PackId.VANILLA, Concurrent.newUnmodifiableMap());

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
