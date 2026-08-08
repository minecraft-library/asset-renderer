package lib.minecraft.renderer.option;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A villager (or zombie-villager) biome type - one of the seven built-in {@code VillagerType}
 * registry values whose robe texture forms the base clothing pass the
 * {@code VillagerProfessionLayer} draws over the body. {@link #PLAINS} (the default) resolves to
 * the {@code <prefix>/type/plains} robe the layer composites at zero state; the other biomes swap
 * in their {@code <prefix>/type/<biome>} robe.
 * The prefix ({@code villager} / {@code zombie_villager}) is supplied per-entity at render, so this
 * enum carries only the prefix-relative sub-path. It carries two of them, differing in nothing but the
 * directory token: {@link #overlaySubPath()} for the adult robe pass and {@link #babyOverlaySubPath()}
 * for the baby one, mirroring the layer's own {@code isBaby ? "baby" : "type"} swap.
 */
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
     * The prefix-relative baby robe sub-path for this biome (e.g. {@code baby/desert}) - the layer's
     * {@code isBaby ? "baby" : "type"} directory swap; the renderer prepends the entity texture prefix
     * to form the full {@code textures/entity/} ref. The {@code baby/} directory ships no
     * {@code .mcmeta} sidecars, so the hat flag is still read off {@link #overlaySubPath()}.
     *
     * @return the {@code baby/<biome>} sub-path
     */
    public @NotNull String babyOverlaySubPath() {
        return "baby/" + name().toLowerCase(Locale.ROOT);
    }

    /**
     * Looks up a biome type by name (case-insensitive), e.g. {@code "desert"}.
     *
     * @param name the type name
     * @return the matching type, or {@code null} when the name is not a built-in villager type
     */
    public static @Nullable VillagerType ofName(@Nullable String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAType) {
            return null;
        }
    }
}
