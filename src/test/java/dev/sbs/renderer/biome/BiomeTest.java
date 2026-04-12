package dev.sbs.renderer.biome;

import dev.sbs.renderer.geometry.Biome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Sanity checks on {@link Biome} - verifies every vanilla entry has plausible metadata, the
 * custom factory produces the expected record shape, and the lookup helpers behave.
 */
class BiomeTest {

    @Test
    @DisplayName("every vanilla biome has a minecraft-namespaced id")
    void everyVanillaBiomeHasNamespacedId() {
        for (Biome.Vanilla biome : Biome.Vanilla.values()) {
            assertThat("biome id", biome.id(), startsWith("minecraft:"));
            assertThat("biome id has body", biome.id().length(), greaterThanOrEqualTo("minecraft:".length() + 1));
        }
    }

    @Test
    @DisplayName("every vanilla biome has temperature in [-1, 2] and downfall in [0, 1]")
    void everyVanillaBiomeHasPlausibleMetrics() {
        for (Biome.Vanilla biome : Biome.Vanilla.values()) {
            assertThat(biome.id() + " temperature", (double) biome.temperature(), greaterThanOrEqualTo(-1.0));
            assertThat(biome.id() + " temperature", (double) biome.temperature(), lessThanOrEqualTo(2.0));
            assertThat(biome.id() + " downfall", (double) biome.downfall(), greaterThanOrEqualTo(0.0));
            assertThat(biome.id() + " downfall", (double) biome.downfall(), lessThanOrEqualTo(1.0));
        }
    }

    @Test
    @DisplayName("plains uses the standard temperate values")
    void plainsHasStandardValues() {
        Biome.Vanilla plains = Biome.Vanilla.PLAINS;
        assertThat(plains.id(), equalTo("minecraft:plains"));
        assertThat(plains.temperature(), equalTo(0.8f));
        assertThat(plains.downfall(), equalTo(0.4f));
        assertThat(plains.grassColorOverride(), equalTo(Optional.empty()));
        assertThat(plains.grassColorModifier(), is(Biome.GrassColorModifier.NONE));
    }

    @Test
    @DisplayName("badlands has hardcoded grass and foliage overrides")
    void badlandsHasColourOverrides() {
        Biome.Vanilla badlands = Biome.Vanilla.BADLANDS;
        assertThat(badlands.grassColorOverride().isPresent(), is(true));
        assertThat(badlands.foliageColorOverride().isPresent(), is(true));
    }

    @Test
    @DisplayName("swamp has the SWAMP grass color modifier")
    void swampHasModifier() {
        assertThat(Biome.Vanilla.SWAMP.grassColorModifier(), is(Biome.GrassColorModifier.SWAMP));
    }

    @Test
    @DisplayName("dark forest has the DARK_FOREST grass color modifier")
    void darkForestHasModifier() {
        assertThat(Biome.Vanilla.DARK_FOREST.grassColorModifier(), is(Biome.GrassColorModifier.DARK_FOREST));
    }

    @Test
    @DisplayName("Biome.of() creates a Custom record with empty overrides and NONE modifier")
    void customFactoryReturnsSensibleDefaults() {
        Biome custom = Biome.of("mymod:crystal_plains", 0.6f, 0.7f);
        assertThat(custom, is(not(sameInstance(null))));
        assertThat(custom.id(), equalTo("mymod:crystal_plains"));
        assertThat(custom.temperature(), equalTo(0.6f));
        assertThat(custom.downfall(), equalTo(0.7f));
        assertThat(custom.grassColorOverride(), equalTo(Optional.empty()));
        assertThat(custom.foliageColorOverride(), equalTo(Optional.empty()));
        assertThat(custom.grassColorModifier(), is(Biome.GrassColorModifier.NONE));
    }

    @Test
    @DisplayName("Biome.Vanilla.byId() round-trips a known id")
    void byIdRoundtrip() {
        Optional<Biome.Vanilla> match = Biome.Vanilla.byId("minecraft:plains");
        assertThat(match.isPresent(), is(true));
        assertThat(match.get(), is(sameInstance(Biome.Vanilla.PLAINS)));
    }

    @Test
    @DisplayName("Biome.Vanilla.byId() returns empty for an unknown id")
    void byIdUnknown() {
        Optional<Biome.Vanilla> match = Biome.Vanilla.byId("minecraft:does_not_exist");
        assertThat(match.isPresent(), is(false));
    }

    @Test
    @DisplayName("Biome.builder() supports colour overrides and a modifier")
    void builderSupportsOverrides() {
        Biome custom = Biome.builder("mymod:bloom_forest")
            .temperature(0.9f)
            .downfall(0.6f)
            .foliageColorOverride(0xFFFF77AA)
            .grassColorModifier(Biome.GrassColorModifier.DARK_FOREST)
            .build();

        assertThat(custom.id(), containsString("bloom_forest"));
        assertThat(custom.foliageColorOverride().orElse(0), equalTo(0xFFFF77AA));
        assertThat(custom.grassColorModifier(), is(Biome.GrassColorModifier.DARK_FOREST));
    }

}
