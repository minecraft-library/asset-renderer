package lib.minecraft.renderer.vanilla;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Sanity checks on {@link Biome}: the {@link Biome.Vanilla#findById(String)} id round-trip and its
 * empty miss, and the two caller factories - the {@link Biome#of(String, float, float)} shorthand and
 * the {@link Biome#builder(String)} builder. The transcribed {@link Biome.Vanilla} rows themselves are
 * held to the client's own biome definitions by {@link BiomeVanillaTest}.
 */
@DisplayName("Biome - the vanilla roster and the caller factories")
class BiomeTest {

    @Test
    @DisplayName("findById() round-trips a known id")
    void byIdRoundtrip() {
        Optional<Biome.Vanilla> match = Biome.Vanilla.findById("minecraft:plains");
        assertThat(match.isPresent(), is(true));
        assertThat(match.get(), is(sameInstance(Biome.Vanilla.PLAINS)));
    }

    @Test
    @DisplayName("findById() returns empty for an unknown id")
    void byIdUnknown() {
        Optional<Biome.Vanilla> match = Biome.Vanilla.findById("minecraft:does_not_exist");
        assertThat(match.isPresent(), is(false));
    }

    @Test
    @DisplayName("Biome.of() creates a Custom record with empty overrides and NONE modifier")
    void customFactoryReturnsSensibleDefaults() {
        Biome custom = Biome.of("mymod:crystal_plains", 0.6f, 0.7f);
        assertThat(custom.id(), equalTo("mymod:crystal_plains"));
        assertThat(custom.temperature(), equalTo(0.6f));
        assertThat(custom.downfall(), equalTo(0.7f));
        assertThat(custom.grassColorOverride(), equalTo(Optional.empty()));
        assertThat(custom.foliageColorOverride(), equalTo(Optional.empty()));
        assertThat(custom.grassColorModifier(), is(Biome.GrassColorModifier.NONE));
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
