package lib.minecraft.renderer.option;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A villager (or zombie-villager) trade level badge - the five vanilla
 * {@code VillagerProfessionLayer.LEVEL_LOCATIONS} tiers, levels 1 to 5, drawn as a small emblem over
 * the profession clothes. Each tier carries a {@code <prefix>/profession_level/<badge>} texture; the
 * prefix ({@code villager} / {@code zombie_villager}) is supplied per-entity at render, so this enum
 * carries only the prefix-relative sub-path.
 *
 * <p>There is no badge-less tier, because vanilla has none. {@code VillagerData}'s constructor
 * raises any level it is handed to {@link #minimum() the first}, and the layer clamps a second time
 * into the same five, so every job villager wears a badge. What decides whether one draws at all is
 * the profession - see {@link VillagerProfession#drawsBadge()} - and the subject's age, both
 * answered before a level is ever read.
 */
public enum VillagerLevel {

    STONE,
    IRON,
    GOLD,
    EMERALD,
    DIAMOND;

    /**
     * The tier worn when none is named - vanilla's level one, which its two clamps make the floor
     * rather than a choice. Declaration order is the tier order, so the first constant is it.
     *
     * @return the lowest badge tier
     */
    public static @NotNull VillagerLevel minimum() {
        return values()[0];
    }

    /**
     * The prefix-relative badge sub-path for this tier (e.g. {@code profession_level/gold}). The
     * renderer prepends the entity texture prefix ({@code villager} / {@code zombie_villager}) to
     * form the full ref.
     *
     * @return the {@code profession_level/<badge>} sub-path
     */
    public @NotNull String overlaySubPath() {
        return "profession_level/" + name().toLowerCase(Locale.ROOT);
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
