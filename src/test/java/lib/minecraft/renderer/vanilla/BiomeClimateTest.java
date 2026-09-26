package lib.minecraft.renderer.vanilla;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

/**
 * Sanity checks on the transcribed vanilla climate table. Sweeps {@link BiomeClimate} for plausible
 * metadata (every entry has a {@code minecraft:}-namespaced id, temperature in {@code [-1, 2]},
 * downfall in {@code [0, 1]}) and spot-checks a few known entries: PLAINS' temperate values,
 * BADLANDS' hardcoded grass/foliage overrides, and the SWAMP / DARK_FOREST
 * {@link BiomeClimate.GrassColorModifier}s, plus the {@link BiomeClimate#findById(String)} id
 * round-trip and its empty miss.
 */
@DisplayName("BiomeClimate - the transcribed vanilla roster")
class BiomeClimateTest {

    @Test
    @DisplayName("every vanilla biome has a minecraft-namespaced id")
    void everyVanillaBiomeHasNamespacedId() {
        for (BiomeClimate biome : BiomeClimate.values()) {
            assertThat("biome id", biome.id(), startsWith("minecraft:"));
            assertThat("biome id has body", biome.id().length(), greaterThanOrEqualTo("minecraft:".length() + 1));
        }
    }

    @Test
    @DisplayName("every vanilla biome has temperature in [-1, 2] and downfall in [0, 1]")
    void everyVanillaBiomeHasPlausibleMetrics() {
        for (BiomeClimate biome : BiomeClimate.values()) {
            assertThat(biome.id() + " temperature", (double) biome.temperature(), greaterThanOrEqualTo(-1.0));
            assertThat(biome.id() + " temperature", (double) biome.temperature(), lessThanOrEqualTo(2.0));
            assertThat(biome.id() + " downfall", (double) biome.downfall(), greaterThanOrEqualTo(0.0));
            assertThat(biome.id() + " downfall", (double) biome.downfall(), lessThanOrEqualTo(1.0));
        }
    }

    @Test
    @DisplayName("plains uses the standard temperate values")
    void plainsHasStandardValues() {
        BiomeClimate plains = BiomeClimate.PLAINS;
        assertThat(plains.id(), equalTo("minecraft:plains"));
        assertThat(plains.temperature(), equalTo(0.8f));
        assertThat(plains.downfall(), equalTo(0.4f));
        assertThat(plains.grassColorOverride(), equalTo(Optional.empty()));
        assertThat(plains.grassColorModifier(), is(BiomeClimate.GrassColorModifier.NONE));
    }

    @Test
    @DisplayName("badlands has hardcoded grass and foliage overrides")
    void badlandsHasColourOverrides() {
        BiomeClimate badlands = BiomeClimate.BADLANDS;
        assertThat(badlands.grassColorOverride().isPresent(), is(true));
        assertThat(badlands.foliageColorOverride().isPresent(), is(true));
    }

    @Test
    @DisplayName("swamp has the SWAMP grass color modifier")
    void swampHasModifier() {
        assertThat(BiomeClimate.SWAMP.grassColorModifier(), is(BiomeClimate.GrassColorModifier.SWAMP));
    }

    @Test
    @DisplayName("dark forest has the DARK_FOREST grass color modifier")
    void darkForestHasModifier() {
        assertThat(BiomeClimate.DARK_FOREST.grassColorModifier(),
            is(BiomeClimate.GrassColorModifier.DARK_FOREST));
    }

    @Test
    @DisplayName("findById() round-trips a known id")
    void byIdRoundtrip() {
        Optional<BiomeClimate> match = BiomeClimate.findById("minecraft:plains");
        assertThat(match.isPresent(), is(true));
        assertThat(match.get(), is(sameInstance(BiomeClimate.PLAINS)));
    }

    @Test
    @DisplayName("findById() returns empty for an unknown id")
    void byIdUnknown() {
        Optional<BiomeClimate> match = BiomeClimate.findById("minecraft:does_not_exist");
        assertThat(match.isPresent(), is(false));
    }

}
