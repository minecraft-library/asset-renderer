package lib.minecraft.renderer.asset.appearance;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

/**
 * The three axes a villager (or zombie-villager) render selects its clothing passes along, held
 * together because they are read together: {@link Type} is the biome robe forming the base pass,
 * {@link Profession} is the clothes and hat drawn over it, and {@link Level} is the trade badge over
 * those clothes.
 *
 * <p>Each carries only the prefix-relative sub-path of its texture. The prefix ({@code villager} /
 * {@code zombie_villager}) is supplied per-entity at render, so one axis serves both subjects.
 */
@UtilityClass
public class Villager {

    /**
     * A villager biome type - one of the seven built-in {@code VillagerType} registry values whose
     * robe texture forms the base clothing pass the {@code VillagerProfessionLayer} draws over the
     * body. {@link #PLAINS} (the default) resolves to the {@code <prefix>/type/plains} robe the layer
     * composites at zero state; the other biomes swap in their {@code <prefix>/type/<biome>} robe.
     * It carries two sub-paths, differing in nothing but the directory token:
     * {@link #overlaySubPath()} for the adult robe pass and {@link #babyOverlaySubPath()} for the
     * baby one, mirroring the layer's own {@code isBaby ? "baby" : "type"} swap.
     */
    @EnumLookup
    public enum Type {

        PLAINS,
        DESERT,
        JUNGLE,
        SAVANNA,
        SNOW,
        SWAMP,
        TAIGA;

        /**
         * The prefix-relative robe sub-path for this biome (e.g. {@code type/desert}); the renderer
         * prepends the entity texture prefix ({@code villager} / {@code zombie_villager}) to form the
         * full {@code textures/entity/} ref.
         *
         * @return the {@code type/<biome>} sub-path
         */
        public @NotNull String overlaySubPath() {
            return "type/" + name().toLowerCase(Locale.ROOT);
        }

        /**
         * The prefix-relative baby robe sub-path for this biome (e.g. {@code baby/desert}) - the
         * layer's {@code isBaby ? "baby" : "type"} directory swap; the renderer prepends the entity
         * texture prefix to form the full {@code textures/entity/} ref. The {@code baby/} directory
         * ships no {@code .mcmeta} sidecars, so the hat flag is still read off
         * {@link #overlaySubPath()}.
         *
         * @return the {@code baby/<biome>} sub-path
         */
        public @NotNull String babyOverlaySubPath() {
            return "baby/" + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A villager profession - the {@code VillagerProfession} registry value whose clothes + hat
     * texture the {@code VillagerProfessionLayer} draws over the biome robe. {@link #NONE} (the
     * default, an unemployed villager) draws no profession pass; each job plus {@link #NITWIT}
     * carries a {@code <prefix>/profession/<name>} texture. {@code NONE} and {@code NITWIT} draw no
     * level badge (see {@link #drawsBadge()}), mirroring the layer's per-profession badge gate.
     */
    @EnumLookup
    public enum Profession {

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

    /**
     * A villager trade level badge - the five vanilla
     * {@code VillagerProfessionLayer.LEVEL_LOCATIONS} tiers, levels 1 to 5, drawn as a small emblem
     * over the profession clothes. Each tier carries a {@code <prefix>/profession_level/<badge>}
     * texture.
     *
     * <p>There is no badge-less tier, because vanilla has none. {@code VillagerData}'s constructor
     * raises any level it is handed to {@link #minimum() the first}, and the layer clamps a second
     * time into the same five, so every job villager wears a badge. What decides whether one draws at
     * all is the profession - see {@link Profession#drawsBadge()} - and the subject's age, both
     * answered before a level is ever read.
     */
    @EnumLookup
    public enum Level {

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
        public static @NotNull Level minimum() {
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
    }

}
