package lib.minecraft.renderer.vanilla.appearance.villager;

import dev.simplified.annotations.EnumLookup;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

/**
 * A villager profession - the {@code VillagerProfession} registry value whose clothes + hat
 * texture the {@code VillagerProfessionLayer} draws over the biome robe. {@link #NONE} (the
 * default, an unemployed villager) draws no profession pass; each job plus {@link #NITWIT}
 * carries a {@code <prefix>/profession/<name>} texture. {@code NONE} and {@code NITWIT} draw no
 * level badge (see {@link #drawsBadge()}), mirroring the layer's per-profession badge gate.
 *
 * <p>The sub-path is prefix-relative. The prefix ({@code villager} / {@code zombie_villager}) is
 * supplied per-entity at render, so one axis serves both subjects.
 */
@EnumLookup
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
     * The prefix-relative clothes sub-path for this profession (e.g. {@code profession/farmer}),
     * or empty for {@link #NONE}, which draws no profession pass. The renderer prepends the entity
     * texture prefix ({@code villager} / {@code zombie_villager}) to form the full ref.
     *
     * @return the {@code profession/<name>} sub-path, or empty when this profession draws nothing
     */
    public @NotNull Optional<String> overlaySubPath() {
        return this == NONE ? Optional.empty() : Optional.of("profession/" + name().toLowerCase(Locale.ROOT));
    }

    /**
     * The profession pass' prefix-qualified texture ref, empty at the {@code NONE} profession.
     *
     * @param texturePrefix the entity texture prefix ({@code villager} / {@code zombie_villager})
     *     the sub-path is qualified with
     * @return the profession texture ref, or empty when no profession is selected
     */
    public @NotNull Optional<String> textureRef(@NotNull String texturePrefix) {
        return overlaySubPath().map(sub -> texturePrefix + "/" + sub);
    }

    /**
     * Whether this profession draws a level badge - true for every real job, false for
     * {@link #NONE} (unemployed) and {@link #NITWIT}, matching vanilla's badge gate (the
     * {@code profession_level} pass fires only when the profession is neither {@code NONE} nor
     * {@code NITWIT}).
     *
     * @return {@code true} when a level badge should draw for this profession
     */
    public boolean drawsBadge() {
        return this != NONE && this != NITWIT;
    }

}
