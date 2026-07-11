package lib.minecraft.renderer.option;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * A villager (or zombie-villager) profession - the {@code VillagerProfession} registry value whose
 * clothes + hat texture the {@code VillagerProfessionLayer} draws over the biome robe. {@link #NONE}
 * (the default, an unemployed villager) draws no profession pass; each job plus {@link #NITWIT}
 * carries a {@code <prefix>/profession/<name>} texture.
 * {@code NONE} and {@code NITWIT} draw no level badge (see {@link #drawsBadge()}), mirroring the
 * layer's per-profession badge gate. The prefix ({@code villager} / {@code zombie_villager}) is
 * supplied per-entity at render, so this enum carries only the prefix-relative sub-path.
 */
public enum VillagerProfession {

    NONE,
    ARMORER,
    BUTCHER,
    CARTOGRAPHER,
    CLERIC,
    FARMER,
    FISHERMAN,
    FLETCHER,
    LEATHERWORKER,
    LIBRARIAN,
    MASON,
    SHEPHERD,
    TOOLSMITH,
    WEAPONSMITH,
    NITWIT;

    /**
     * The prefix-relative clothes sub-path for this profession (e.g. {@code profession/farmer}), or
     * empty for {@link #NONE}, which draws no profession pass. The renderer prepends the entity
     * texture prefix ({@code villager} / {@code zombie_villager}) to form the full ref.
     *
     * @return the {@code profession/<name>} sub-path, or empty when this profession draws nothing
     */
    public @NotNull Optional<String> overlaySubPath() {
        return this == NONE ? Optional.empty() : Optional.of("profession/" + name().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether this profession draws a level badge - true for every real job, false for {@link #NONE}
     * (unemployed) and {@link #NITWIT}, matching vanilla's badge gate (the {@code profession_level}
     * pass fires only when the profession is neither {@code NONE} nor {@code NITWIT}).
     *
     * @return {@code true} when a level badge should draw for this profession
     */
    public boolean drawsBadge() {
        return this != NONE && this != NITWIT;
    }

    /**
     * Looks up a profession by name (case-insensitive), e.g. {@code "weaponsmith"}.
     *
     * @param name the profession name
     * @return the matching profession, or {@code null} when the name is not a villager profession
     */
    public static @Nullable VillagerProfession ofName(@Nullable String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAProfession) {
            return null;
        }
    }
}
