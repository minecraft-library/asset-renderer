package lib.minecraft.renderer.geometry;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * A Minecraft biome identity carrying the temperature, downfall, and optional colour overrides
 * needed to resolve grass, foliage, and dry-foliage tints.
 * <p>
 * Two flavours are supported:
 * <ul>
 * <li><b>{@link Vanilla}</b> - a fixed enum of every known vanilla biome with its baked temperature,
 * downfall, hardcoded colour overrides, and {@link GrassColorModifier}. Callers normally reference
 * these directly (e.g. {@code Biome.Vanilla.PLAINS}).</li>
 * <li><b>{@link Custom}</b> - a record for modded or user-defined biomes. Construct via the
 * {@link #of(String, float, float) shorthand factory} or the {@link #builder(String)} for
 * colour-override support.</li>
 * </ul>
 * The numeric values on {@code Vanilla} are populated from the Minecraft 26.1 deobfuscated client
 * source and the {@code data/minecraft/worldgen/biome/*.json} definitions extracted from the
 * official 26.1 client jar. The grass colour modifier constants ({@link #SWAMP_GRASS_WARM},
 * {@link #SWAMP_GRASS_COLD}) are verified against the bytecode of
 * {@code net.minecraft.world.level.biome.BiomeSpecialEffects$GrassColorModifier}.
 */
public sealed interface Biome permits Biome.Vanilla, Biome.Custom {

    /**
     * The warm swamp grass colour returned by vanilla's swamp {@link GrassColorModifier#SWAMP}
     * modifier when the {@code BIOME_INFO_NOISE} Perlin sample is at or above {@code -0.1}.
     * Matches the {@code ldc #24} constant in
     * {@code BiomeSpecialEffects$GrassColorModifier$3.modifyColor}.
     */
    int SWAMP_GRASS_WARM = 0xFF6A7039;

    /**
     * The cold swamp grass colour returned by vanilla's swamp {@link GrassColorModifier#SWAMP}
     * modifier when the {@code BIOME_INFO_NOISE} Perlin sample is below {@code -0.1}. Matches the
     * {@code ldc #23} constant in {@code BiomeSpecialEffects$GrassColorModifier$3.modifyColor}.
     * Only reachable by explicit caller override since icon rendering has no world coordinates.
     */
    int SWAMP_GRASS_COLD = 0xFF4C763C;

    /**
     * The biome identifier, e.g. {@code "minecraft:plains"}.
     *
     * @return the biome id
     */
    @NotNull String id();

    /**
     * The biome temperature, in the range {@code [-1.0, 2.0]} for vanilla biomes.
     *
     * @return the temperature
     */
    float temperature();

    /**
     * The biome downfall (also called humidity), in the range {@code [0.0, 1.0]} for vanilla biomes.
     *
     * @return the downfall
     */
    float downfall();

    /**
     * An optional hardcoded ARGB grass colour override. Present only for biomes that skip the
     * colormap lookup (badlands, cherry grove).
     *
     * @return the grass colour override if any
     */
    @NotNull Optional<Integer> grassColorOverride();

    /**
     * An optional hardcoded ARGB foliage colour override.
     *
     * @return the foliage colour override if any
     */
    @NotNull Optional<Integer> foliageColorOverride();

    /**
     * An optional hardcoded ARGB dry-foliage colour override.
     *
     * @return the dry-foliage colour override if any
     */
    @NotNull Optional<Integer> dryFoliageColorOverride();

    /**
     * An optional hardcoded ARGB water colour override. Present only for biomes that depart from
     * the vanilla default {@code 0xFF3F76E4} (swamps, oceans of various temperatures, cherry
     * grove, meadow). Unlike grass and foliage, water has no colormap in vanilla - the tint is
     * either the per-biome override below or the engine-level default applied at render time.
     *
     * @return the water colour override if any
     */
    default @NotNull Optional<Integer> waterColorOverride() {
        return Optional.empty();
    }

    /**
     * The post-sample grass colour modifier applied after a colormap lookup. {@code NONE} passes
     * through; {@code DARK_FOREST} darkens the result via a mask + offset + halve; {@code SWAMP}
     * discards the sampled value and returns {@link #SWAMP_GRASS_WARM}. The modifier is only
     * applied to the grass tint - foliage and dry-foliage tints bypass it entirely, matching
     * vanilla's {@code Biome.getGrassColor} vs {@code Biome.getFoliageColor} split.
     *
     * @return the grass colour modifier
     */
    @NotNull GrassColorModifier grassColorModifier();

    /**
     * Identifies which biome colormap drives a block face's tint, or flags that the tint comes
     * from a hardcoded constant on the block DTO.
     */
    enum TintTarget {

        /** The face is not biome-tinted. */
        NONE,

        /** Sample the grass colormap. Applies to grass blocks, tall grass, ferns, etc. */
        GRASS,

        /** Sample the foliage colormap. Applies to most leaves. */
        FOLIAGE,

        /** Sample the dry-foliage colormap. Applies to pale oak and a handful of other biomes. */
        DRY_FOLIAGE,

        /**
         * Use the biome's {@link Biome#waterColorOverride() water colour override} when present,
         * or the engine-level default {@code 0xFF3F76E4} otherwise. Vanilla water has no colormap;
         * biomes either carry an explicit {@code water_color} value or inherit the default.
         */
        WATER,

        /** Use the block's {@code tintConstant} field directly. Applies to redstone wire, stems, etc. */
        CONSTANT

    }

    /**
     * Post-sample grass colour modifier applied to the output of a colormap lookup.
     */
    enum GrassColorModifier {

        /** Pass through the sampled value unchanged. */
        NONE,

        /** Darkens the sampled value via {@code ((color & 0xFEFEFE) + 0x28340A) >> 1}. */
        DARK_FOREST,

        /**
         * Discards the sampled value and returns {@link Biome#SWAMP_GRASS_WARM}. Vanilla uses a
         * Perlin-noise world lookup to sometimes return {@link Biome#SWAMP_GRASS_COLD}; the cold
         * variant is not reachable from pure biome metadata.
         */
        SWAMP

    }

    /**
     * Creates a {@link Custom} biome with the given identifier, temperature, and downfall. All colour
     * overrides default to empty and the grass colour modifier defaults to {@code NONE}.
     *
     * @param id the biome identifier
     * @param temperature the biome temperature
     * @param downfall the biome downfall
     * @return a new {@link Custom} biome
     */
    static @NotNull Biome of(@NotNull String id, float temperature, float downfall) {
        return new Custom(id, temperature, downfall, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), GrassColorModifier.NONE);
    }

    /**
     * Creates a builder for a custom biome with the given identifier.
     *
     * @param id the biome identifier
     * @return a new builder
     */
    static @NotNull Builder builder(@NotNull String id) {
        return new Builder(id);
    }

    /**
     * Vanilla biomes with baked temperature, downfall, and colour overrides.
     * <p>
     * Values derived from the Minecraft 26.1 deobfuscated client source. Subject to verification
     * before production use - any caller that needs exact vanilla parity should cross-check against
     * the client's biome JSON files.
     */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    enum Vanilla implements Biome {

        // --- overworld temperate ---
        PLAINS              ("minecraft:plains",                    0.8f,  0.4f, GrassColorModifier.NONE),
        SUNFLOWER_PLAINS    ("minecraft:sunflower_plains",          0.8f,  0.4f, GrassColorModifier.NONE),
        FOREST              ("minecraft:forest",                    0.7f,  0.8f, GrassColorModifier.NONE),
        FLOWER_FOREST       ("minecraft:flower_forest",             0.7f,  0.8f, GrassColorModifier.NONE),
        BIRCH_FOREST        ("minecraft:birch_forest",              0.6f,  0.6f, GrassColorModifier.NONE),
        OLD_GROWTH_BIRCH_FOREST("minecraft:old_growth_birch_forest",0.6f,  0.6f, GrassColorModifier.NONE),
        DARK_FOREST         ("minecraft:dark_forest",               0.7f,  0.8f, GrassColorModifier.DARK_FOREST),
        PALE_GARDEN         ("minecraft:pale_garden",               0.7f,  0.8f, GrassColorModifier.NONE),
        MEADOW              ("minecraft:meadow",                    0.5f,  0.8f, Optional.of(0xFF0E4ECF), GrassColorModifier.NONE),
        CHERRY_GROVE        ("minecraft:cherry_grove",              0.5f,  0.8f, Optional.of(0xFFB5DB61), Optional.of(0xFFB5DB61), Optional.empty(), Optional.of(0xFF5DB7EF), GrassColorModifier.NONE),

        // --- overworld cold ---
        TAIGA               ("minecraft:taiga",                     0.25f, 0.8f, GrassColorModifier.NONE),
        SNOWY_TAIGA         ("minecraft:snowy_taiga",              -0.5f,  0.4f, GrassColorModifier.NONE),
        OLD_GROWTH_PINE_TAIGA("minecraft:old_growth_pine_taiga",    0.3f,  0.8f, GrassColorModifier.NONE),
        OLD_GROWTH_SPRUCE_TAIGA("minecraft:old_growth_spruce_taiga",0.25f, 0.8f, GrassColorModifier.NONE),
        GROVE               ("minecraft:grove",                    -0.2f,  0.8f, GrassColorModifier.NONE),
        SNOWY_SLOPES        ("minecraft:snowy_slopes",             -0.3f,  0.9f, GrassColorModifier.NONE),
        FROZEN_PEAKS        ("minecraft:frozen_peaks",             -0.7f,  0.9f, GrassColorModifier.NONE),
        JAGGED_PEAKS        ("minecraft:jagged_peaks",             -0.7f,  0.9f, GrassColorModifier.NONE),
        STONY_PEAKS         ("minecraft:stony_peaks",               1.0f,  0.3f, GrassColorModifier.NONE),
        SNOWY_PLAINS        ("minecraft:snowy_plains",              0.0f,  0.5f, GrassColorModifier.NONE),
        ICE_SPIKES          ("minecraft:ice_spikes",                0.0f,  0.5f, GrassColorModifier.NONE),

        // --- overworld warm ---
        DESERT              ("minecraft:desert",                    2.0f,  0.0f, GrassColorModifier.NONE),
        SAVANNA             ("minecraft:savanna",                   2.0f,  0.0f, GrassColorModifier.NONE),
        SAVANNA_PLATEAU     ("minecraft:savanna_plateau",           2.0f,  0.0f, GrassColorModifier.NONE),
        WINDSWEPT_SAVANNA   ("minecraft:windswept_savanna",         2.0f,  0.0f, GrassColorModifier.NONE),
        JUNGLE              ("minecraft:jungle",                    0.95f, 0.9f, GrassColorModifier.NONE),
        SPARSE_JUNGLE       ("minecraft:sparse_jungle",             0.95f, 0.8f, GrassColorModifier.NONE),
        BAMBOO_JUNGLE       ("minecraft:bamboo_jungle",             0.95f, 0.9f, GrassColorModifier.NONE),

        // --- overworld swamp / wet ---
        // Foliage override matches vanilla SWAMP / MANGROVE_SWAMP effects.foliage_color in the 26.1
        // biome JSON (extracted via slowTest). The grass tint comes from GrassColorModifier.SWAMP
        // which returns SWAMP_GRASS_WARM without Perlin-noise world context. Water overrides match
        // effects.water_color from the same biome JSON.
        SWAMP               ("minecraft:swamp",                     0.8f,  0.9f, Optional.empty(), Optional.of(SWAMP_GRASS_WARM), Optional.empty(), Optional.of(0xFF617B64), GrassColorModifier.SWAMP),
        MANGROVE_SWAMP      ("minecraft:mangrove_swamp",            0.8f,  0.9f, Optional.empty(), Optional.of(SWAMP_GRASS_WARM), Optional.empty(), Optional.of(0xFF3A7A6A), GrassColorModifier.SWAMP),

        // --- overworld windswept ---
        WINDSWEPT_HILLS     ("minecraft:windswept_hills",           0.2f,  0.3f, GrassColorModifier.NONE),
        WINDSWEPT_GRAVELLY_HILLS("minecraft:windswept_gravelly_hills",0.2f,0.3f, GrassColorModifier.NONE),
        WINDSWEPT_FOREST    ("minecraft:windswept_forest",          0.2f,  0.3f, GrassColorModifier.NONE),

        // --- overworld shores ---
        BEACH               ("minecraft:beach",                     0.8f,  0.4f, GrassColorModifier.NONE),
        SNOWY_BEACH         ("minecraft:snowy_beach",               0.05f, 0.3f, GrassColorModifier.NONE),
        STONY_SHORE         ("minecraft:stony_shore",               0.2f,  0.3f, GrassColorModifier.NONE),

        // --- overworld rivers ---
        RIVER               ("minecraft:river",                     0.5f,  0.5f, GrassColorModifier.NONE),
        FROZEN_RIVER        ("minecraft:frozen_river",              0.0f,  0.5f, Optional.of(0xFF3938C9), GrassColorModifier.NONE),

        // --- overworld oceans ---
        // Water overrides match effects.water_color from the 26.1 biome JSON. Deep variants of
        // lukewarm / cold / frozen inherit their shallow counterparts' water colour - populate
        // them here too so a deep-ocean caller gets the right tint without special-casing.
        OCEAN               ("minecraft:ocean",                     0.5f,  0.5f, GrassColorModifier.NONE),
        DEEP_OCEAN          ("minecraft:deep_ocean",                0.5f,  0.5f, GrassColorModifier.NONE),
        WARM_OCEAN          ("minecraft:warm_ocean",                0.5f,  0.5f, Optional.of(0xFF43D5EE), GrassColorModifier.NONE),
        LUKEWARM_OCEAN      ("minecraft:lukewarm_ocean",            0.5f,  0.5f, Optional.of(0xFF45ADF2), GrassColorModifier.NONE),
        DEEP_LUKEWARM_OCEAN ("minecraft:deep_lukewarm_ocean",       0.5f,  0.5f, Optional.of(0xFF45ADF2), GrassColorModifier.NONE),
        COLD_OCEAN          ("minecraft:cold_ocean",                0.5f,  0.5f, Optional.of(0xFF3D57D6), GrassColorModifier.NONE),
        DEEP_COLD_OCEAN     ("minecraft:deep_cold_ocean",           0.5f,  0.5f, Optional.of(0xFF3D57D6), GrassColorModifier.NONE),
        FROZEN_OCEAN        ("minecraft:frozen_ocean",              0.0f,  0.5f, Optional.of(0xFF3938C9), GrassColorModifier.NONE),
        DEEP_FROZEN_OCEAN   ("minecraft:deep_frozen_ocean",         0.5f,  0.5f, Optional.of(0xFF3938C9), GrassColorModifier.NONE),

        // --- overworld special ---
        MUSHROOM_FIELDS     ("minecraft:mushroom_fields",           0.9f,  1.0f, GrassColorModifier.NONE),
        DRIPSTONE_CAVES     ("minecraft:dripstone_caves",           0.8f,  0.4f, GrassColorModifier.NONE),
        LUSH_CAVES          ("minecraft:lush_caves",                0.5f,  0.5f, GrassColorModifier.NONE),
        DEEP_DARK           ("minecraft:deep_dark",                 0.8f,  0.4f, GrassColorModifier.NONE),

        // --- overworld badlands (hardcoded overrides) ---
        BADLANDS            ("minecraft:badlands",                  2.0f,  0.0f, Optional.of(0xFF90814D), Optional.of(0xFF9E814D), Optional.empty(), GrassColorModifier.NONE),
        ERODED_BADLANDS     ("minecraft:eroded_badlands",           2.0f,  0.0f, Optional.of(0xFF90814D), Optional.of(0xFF9E814D), Optional.empty(), GrassColorModifier.NONE),
        WOODED_BADLANDS     ("minecraft:wooded_badlands",           2.0f,  0.0f, Optional.of(0xFF90814D), Optional.of(0xFF9E814D), Optional.empty(), GrassColorModifier.NONE),

        // --- nether ---
        NETHER_WASTES       ("minecraft:nether_wastes",             2.0f,  0.0f, GrassColorModifier.NONE),
        CRIMSON_FOREST      ("minecraft:crimson_forest",            2.0f,  0.0f, GrassColorModifier.NONE),
        WARPED_FOREST       ("minecraft:warped_forest",             2.0f,  0.0f, GrassColorModifier.NONE),
        SOUL_SAND_VALLEY    ("minecraft:soul_sand_valley",          2.0f,  0.0f, GrassColorModifier.NONE),
        BASALT_DELTAS       ("minecraft:basalt_deltas",             2.0f,  0.0f, GrassColorModifier.NONE),

        // --- end ---
        THE_END             ("minecraft:the_end",                   0.5f,  0.5f, GrassColorModifier.NONE),
        END_HIGHLANDS       ("minecraft:end_highlands",             0.5f,  0.5f, GrassColorModifier.NONE),
        END_MIDLANDS        ("minecraft:end_midlands",              0.5f,  0.5f, GrassColorModifier.NONE),
        END_BARRENS         ("minecraft:end_barrens",               0.5f,  0.5f, GrassColorModifier.NONE),
        SMALL_END_ISLANDS   ("minecraft:small_end_islands",         0.5f,  0.5f, GrassColorModifier.NONE),
        THE_VOID            ("minecraft:the_void",                  0.5f,  0.5f, GrassColorModifier.NONE);

        private final @NotNull String id;
        private final float temperature;
        private final float downfall;
        private final @NotNull Optional<Integer> grassColorOverride;
        private final @NotNull Optional<Integer> foliageColorOverride;
        private final @NotNull Optional<Integer> dryFoliageColorOverride;
        private final @NotNull Optional<Integer> waterColorOverride;
        private final @NotNull GrassColorModifier grassColorModifier;

        // --- convenience overloads so the table stays readable ---

        Vanilla(@NotNull String id, float temperature, float downfall, @NotNull GrassColorModifier grassColorModifier) {
            this(id, temperature, downfall, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), grassColorModifier);
        }

        Vanilla(
            @NotNull String id, float temperature, float downfall,
            @NotNull Optional<Integer> waterColorOverride,
            @NotNull GrassColorModifier grassColorModifier
        ) {
            this(id, temperature, downfall, Optional.empty(), Optional.empty(), Optional.empty(), waterColorOverride, grassColorModifier);
        }

        Vanilla(
            @NotNull String id, float temperature, float downfall,
            @NotNull Optional<Integer> grassColorOverride,
            @NotNull Optional<Integer> foliageColorOverride,
            @NotNull Optional<Integer> dryFoliageColorOverride,
            @NotNull GrassColorModifier grassColorModifier
        ) {
            this(id, temperature, downfall, grassColorOverride, foliageColorOverride, dryFoliageColorOverride, Optional.empty(), grassColorModifier);
        }

        /**
         * Looks up a vanilla biome by its Minecraft identifier.
         *
         * @param id the biome identifier, e.g. {@code "minecraft:plains"}
         * @return the matching vanilla biome, or empty if no match exists
         */
        public static @NotNull Optional<Vanilla> byId(@NotNull String id) {
            return Arrays.stream(values())
                .filter(biome -> Objects.equals(biome.id, id))
                .findFirst();
        }

    }

    /**
     * A modded or user-defined biome.
     *
     * @param id the biome identifier
     * @param temperature the biome temperature
     * @param downfall the biome downfall
     * @param grassColorOverride the optional grass colour override
     * @param foliageColorOverride the optional foliage colour override
     * @param dryFoliageColorOverride the optional dry-foliage colour override
     * @param waterColorOverride the optional water colour override
     * @param grassColorModifier the post-sample grass colour modifier
     */
    record Custom(
        @NotNull String id,
        float temperature,
        float downfall,
        @NotNull Optional<Integer> grassColorOverride,
        @NotNull Optional<Integer> foliageColorOverride,
        @NotNull Optional<Integer> dryFoliageColorOverride,
        @NotNull Optional<Integer> waterColorOverride,
        @NotNull GrassColorModifier grassColorModifier
    ) implements Biome {}

    /**
     * Mutable builder for a {@link Custom} biome.
     */
    final class Builder {

        private final @NotNull String id;
        private float temperature = 0.5f;
        private float downfall = 0.5f;
        private @NotNull Optional<Integer> grassColorOverride = Optional.empty();
        private @NotNull Optional<Integer> foliageColorOverride = Optional.empty();
        private @NotNull Optional<Integer> dryFoliageColorOverride = Optional.empty();
        private @NotNull Optional<Integer> waterColorOverride = Optional.empty();
        private @NotNull GrassColorModifier grassColorModifier = GrassColorModifier.NONE;

        Builder(@NotNull String id) {
            this.id = id;
        }

        public @NotNull Builder temperature(float temperature) {
            this.temperature = temperature;
            return this;
        }

        public @NotNull Builder downfall(float downfall) {
            this.downfall = downfall;
            return this;
        }

        public @NotNull Builder grassColorOverride(int argb) {
            this.grassColorOverride = Optional.of(argb);
            return this;
        }

        public @NotNull Builder foliageColorOverride(int argb) {
            this.foliageColorOverride = Optional.of(argb);
            return this;
        }

        public @NotNull Builder dryFoliageColorOverride(int argb) {
            this.dryFoliageColorOverride = Optional.of(argb);
            return this;
        }

        public @NotNull Builder waterColorOverride(int argb) {
            this.waterColorOverride = Optional.of(argb);
            return this;
        }

        public @NotNull Builder grassColorModifier(@NotNull GrassColorModifier modifier) {
            this.grassColorModifier = modifier;
            return this;
        }

        public @NotNull Biome build() {
            return new Custom(this.id, this.temperature, this.downfall, this.grassColorOverride, this.foliageColorOverride, this.dryFoliageColorOverride, this.waterColorOverride, this.grassColorModifier);
        }

    }

}
