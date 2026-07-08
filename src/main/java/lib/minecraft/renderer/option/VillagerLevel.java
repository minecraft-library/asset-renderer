package lib.minecraft.renderer.option;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * A villager (or zombie-villager) trade level badge - one of the five vanilla
 * {@code VillagerProfessionLayer.LEVEL_LOCATIONS} tiers (levels 1..5) drawn as a small emblem over
 * the profession clothes. {@link #NONE} (the default) draws no badge, so the default appearance is
 * byte-identical; the five tiers each carry a {@code <prefix>/profession_level/<badge>} texture. The
 * badge draws only when the selected {@link VillagerProfession#drawsBadge() profession draws a badge}
 * (a real job), matching the layer. The prefix ({@code villager} / {@code zombie_villager}) is
 * supplied per-entity at render, so this enum carries only the prefix-relative sub-path.
 */
public enum VillagerLevel {

    NONE,
    STONE,
    IRON,
    GOLD,
    EMERALD,
    DIAMOND;

    /**
     * The prefix-relative badge sub-path for this tier (e.g. {@code profession_level/gold}), or empty
     * for {@link #NONE}, which draws no badge. The renderer prepends the entity texture prefix
     * ({@code villager} / {@code zombie_villager}) to form the full ref.
     *
     * @return the {@code profession_level/<badge>} sub-path, or empty when this tier draws nothing
     */
    public @NotNull Optional<String> overlaySubPath() {
        return this == NONE ? Optional.empty() : Optional.of("profession_level/" + name().toLowerCase(Locale.ROOT));
    }

    /**
     * Looks up a badge tier by name (case-insensitive), e.g. {@code "diamond"}.
     *
     * @param name the tier name
     * @return the matching tier, or {@code null} when the name is not a villager badge tier
     */
    public static @Nullable VillagerLevel ofName(@Nullable String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notALevel) {
            return null;
        }
    }
}
