package lib.minecraft.renderer.request;

import lib.minecraft.renderer.vanilla.BiomeClimate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * The two caller factories over {@link Biome} - the {@link Biome#of(String, float, float)}
 * shorthand and the {@link Biome#builder(String)} builder, which is the one public mutable builder
 * in the request vocabulary.
 */
@DisplayName("Biome - the caller factories")
class BiomeTest {

    @Test
    @DisplayName("Biome.of() creates a Custom record with empty overrides and NONE modifier")
    void customFactoryReturnsSensibleDefaults() {
        Biome custom = Biome.of("mymod:crystal_plains", 0.6f, 0.7f);
        assertThat(custom.id(), equalTo("mymod:crystal_plains"));
        assertThat(custom.temperature(), equalTo(0.6f));
        assertThat(custom.downfall(), equalTo(0.7f));
        assertThat(custom.grassColorOverride(), equalTo(Optional.empty()));
        assertThat(custom.foliageColorOverride(), equalTo(Optional.empty()));
        assertThat(custom.grassColorModifier(), is(BiomeClimate.GrassColorModifier.NONE));
    }

    @Test
    @DisplayName("Biome.builder() supports colour overrides and a modifier")
    void builderSupportsOverrides() {
        Biome custom = Biome.builder("mymod:bloom_forest")
            .temperature(0.9f)
            .downfall(0.6f)
            .foliageColorOverride(0xFFFF77AA)
            .grassColorModifier(BiomeClimate.GrassColorModifier.DARK_FOREST)
            .build();

        assertThat(custom.id(), containsString("bloom_forest"));
        assertThat(custom.foliageColorOverride().orElse(0), equalTo(0xFFFF77AA));
        assertThat(custom.grassColorModifier(), is(BiomeClimate.GrassColorModifier.DARK_FOREST));
    }

}
