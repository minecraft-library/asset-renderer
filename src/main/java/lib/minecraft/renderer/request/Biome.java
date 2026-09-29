package lib.minecraft.renderer.request;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.BiomeClimate.GrassColorModifier;
import lib.minecraft.renderer.vanilla.BiomeClimate;
import lib.minecraft.renderer.vanilla.TintSource;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A Minecraft biome identity carrying the temperature, downfall, and optional colour overrides
 * needed to resolve grass, foliage, and dry-foliage tints.
 * <p>
 * A caller supplies one per render. {@link #of(BiomeClimate)} takes a vanilla row whole - climate,
 * colour overrides and {@link GrassColorModifier} together - while
 * {@link #of(String, float, float) the shorthand factory} and {@link #builder(String)} describe a
 * modded or user-defined biome, the builder being the form that carries colour overrides.
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public sealed interface Biome permits Biome.Custom {

    /**
     * The tint point vanilla resolves a block at when it has no world context - a block-item GUI
     * icon, or a block an entity holds. Vanilla answers both through
     * {@code BlockTintSource.color(BlockState)} rather than {@code colorInWorld}, so the biome the
     * subject stands in never reaches the tint. It is not a single colormap point, because vanilla
     * resolves the three targets differently in hand:
     * <ul>
     * <li><b>grass</b> - {@code GrassColor.getDefaultColor() = get(0.5, 1.0)}, the grass colormap
     *     centre {@code (127, 127)}; reproduced by sampling at temperature {@code 0.5} / downfall
     *     {@code 1.0} with no grass override.</li>
     * <li><b>foliage</b> - the fixed {@code FoliageColor.FOLIAGE_DEFAULT} constant rather than a
     *     colormap sample at all; carried as a foliage override.</li>
     * <li><b>dry foliage</b> - the fixed {@code DryFoliageColor} default, likewise an override.</li>
     * </ul>
     * A block whose tint target is {@code CONSTANT} ignores this entirely and keeps its own baked
     * colour.
     */
    @NotNull Biome INVENTORY_DEFAULT = builder("inventory_default")
        .temperature(0.5f)
        .downfall(1.0f)
        .foliageColorOverride(0xFF48B518)
        .dryFoliageColorOverride(0xFF5C3C32)
        .build();

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
     * An optional hardcoded ARGB grass colour override. Present only for a biome whose definition
     * declares a grass colour, which then skips the colormap lookup.
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
     * An optional hardcoded ARGB water colour override. Present only for a biome whose definition
     * names a water colour other than the vanilla default {@code 0xFF3F76E4}. Unlike grass and
     * foliage, water has no colormap in vanilla - the tint is
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
     * discards the sampled value and returns {@link BiomeClimate#SWAMP_GRASS_WARM}. The modifier is only
     * applied to the grass tint - foliage and dry-foliage tints bypass it entirely, matching
     * vanilla's {@code Biome.getGrassColor} vs {@code Biome.getFoliageColor} split.
     *
     * @return the grass colour modifier
     */
    @NotNull GrassColorModifier grassColorModifier();

    /**
     * This biome's hardcoded ARGB override for the given tint target, empty when it declares none or
     * when the target carries no biome channel.
     *
     * @param target the tint target being resolved
     * @return the colour override if any
     */
    default @NotNull Optional<Integer> colorOverride(@NotNull TintSource target) {
        return switch (target) {
            case GRASS -> grassColorOverride();
            case FOLIAGE -> foliageColorOverride();
            case DRY_FOLIAGE -> dryFoliageColorOverride();
            case WATER -> waterColorOverride();
            case NONE, CONSTANT -> Optional.empty();
        };
    }

    /**
     * This biome's identifier with its namespace stripped - the spelling a pack addresses it by in
     * {@code color.properties}. An id carrying no namespace is used whole, which is what
     * {@link #INVENTORY_DEFAULT} relies on.
     *
     * @return the local name
     */
    default @NotNull String localName() {
        String id = id();
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /**
     * Post-processes a resolved colour with this biome's {@link GrassColorModifier}, or returns it
     * untouched when the target is not {@link TintSource#grassModified() grass-modified}.
     * <p>
     * Vanilla only runs the modifier on the grass tint - foliage, dry foliage and water pass through.
     * See {@code Biome.getGrassColor} vs {@code Biome.getFoliageColor} in the MC 26.1 client source:
     * only the former invokes {@code grassColorModifier.modifyColor}. The gate is on the target and
     * never on the modifier, so a swamp or dark-forest biome leaves its water colour alone.
     *
     * @param target the tint target the colour was resolved for
     * @param argb the resolved colour
     * @return the post-processed colour
     */
    default int applyModifier(@NotNull TintSource target, int argb) {
        return target.grassModified() ? grassColorModifier().modifyColor(argb) : argb;
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
     * Creates a biome carrying a vanilla row whole - its identifier, baked temperature and downfall,
     * every colour override it declares, and its grass colour modifier.
     *
     * @param climate the vanilla row to render against
     * @return a biome holding that row's climate and overrides
     */
    static @NotNull Biome of(@NotNull BiomeClimate climate) {
        return new Custom(climate.id(), climate.temperature(), climate.downfall(),
            climate.grassColorOverride(), climate.foliageColorOverride(),
            climate.dryFoliageColorOverride(), climate.waterColorOverride(),
            climate.grassColorModifier());
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
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    final class Builder {

        private final @NotNull String id;
        private float temperature = 0.5f;
        private float downfall = 0.5f;
        private @NotNull Optional<Integer> grassColorOverride = Optional.empty();
        private @NotNull Optional<Integer> foliageColorOverride = Optional.empty();
        private @NotNull Optional<Integer> dryFoliageColorOverride = Optional.empty();
        private @NotNull Optional<Integer> waterColorOverride = Optional.empty();
        private @NotNull GrassColorModifier grassColorModifier = GrassColorModifier.NONE;

        /**
         * Sets the biome temperature.
         *
         * @param temperature the temperature
         * @return this builder
         */
        public @NotNull Builder temperature(float temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Sets the biome downfall (humidity).
         *
         * @param downfall the downfall
         * @return this builder
         */
        public @NotNull Builder downfall(float downfall) {
            this.downfall = downfall;
            return this;
        }

        /**
         * Sets the hardcoded grass colour override, bypassing the colormap lookup.
         *
         * @param argb the ARGB grass colour
         * @return this builder
         */
        public @NotNull Builder grassColorOverride(int argb) {
            this.grassColorOverride = Optional.of(argb);
            return this;
        }

        /**
         * Sets the hardcoded foliage colour override, bypassing the colormap lookup.
         *
         * @param argb the ARGB foliage colour
         * @return this builder
         */
        public @NotNull Builder foliageColorOverride(int argb) {
            this.foliageColorOverride = Optional.of(argb);
            return this;
        }

        /**
         * Sets the hardcoded dry-foliage colour override, bypassing the colormap lookup.
         *
         * @param argb the ARGB dry-foliage colour
         * @return this builder
         */
        public @NotNull Builder dryFoliageColorOverride(int argb) {
            this.dryFoliageColorOverride = Optional.of(argb);
            return this;
        }

        /**
         * Sets the water colour override. Water has no colormap in vanilla, so this is the sole
         * source of a non-default water tint.
         *
         * @param argb the ARGB water colour
         * @return this builder
         */
        public @NotNull Builder waterColorOverride(int argb) {
            this.waterColorOverride = Optional.of(argb);
            return this;
        }

        /**
         * Sets the post-sample grass colour modifier applied after the colormap lookup.
         *
         * @param modifier the grass colour modifier
         * @return this builder
         */
        public @NotNull Builder grassColorModifier(@NotNull GrassColorModifier modifier) {
            this.grassColorModifier = modifier;
            return this;
        }

        /**
         * Builds the {@link Custom} biome from the accumulated state.
         *
         * @return a new {@link Custom} biome
         */
        public @NotNull Biome build() {
            return new Custom(this.id, this.temperature, this.downfall, this.grassColorOverride, this.foliageColorOverride, this.dryFoliageColorOverride, this.waterColorOverride, this.grassColorModifier);
        }

    }

}
