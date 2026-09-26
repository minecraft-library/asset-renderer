package lib.minecraft.renderer.vanilla.appearance.villager;

import dev.simplified.annotations.EnumLookup;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * A villager biome type - one of the seven built-in {@code VillagerType} registry values whose
 * robe texture forms the base clothing pass the {@code VillagerProfessionLayer} draws over the
 * body. {@link #PLAINS} (the default) resolves to the {@code <prefix>/type/plains} robe the layer
 * composites at zero state; the other biomes swap in their {@code <prefix>/type/<biome>} robe.
 * It carries two sub-paths, differing in nothing but the directory token:
 * {@link #overlaySubPath()} for the adult robe pass and {@link #babyOverlaySubPath()} for the
 * baby one, mirroring the layer's own {@code isBaby ? "baby" : "type"} swap.
 *
 * <p>Both sub-paths are prefix-relative. The prefix ({@code villager} / {@code zombie_villager}) is
 * supplied per-entity at render, so one axis serves both subjects.
 */
@EnumLookup
public enum VillagerType {

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
