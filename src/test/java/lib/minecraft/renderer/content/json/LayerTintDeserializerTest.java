package lib.minecraft.renderer.content.json;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.asset.Item.LayerTint;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * One {@code tints[]} entry read into its {@link LayerTint} variant: the grass and map-colour sources
 * carry what the definition declares rather than degrading to white, a grass entry without its climate
 * point is refused, and a source this renderer does not model still reads as untinted.
 */
@DisplayName("An item-definition tint reads into the variant its type names")
class LayerTintDeserializerTest {

    @Test
    @DisplayName("a grass tint carries its climate point, with or without the namespace")
    void grassCarriesItsClimate() {
        assertThat(read("{\"type\":\"minecraft:grass\",\"temperature\":0.5,\"downfall\":1.0}"),
            is(new LayerTint.Grass(0.5f, 1.0f)));
        assertThat(read("{\"type\":\"grass\",\"temperature\":0.8,\"downfall\":0.4}"),
            is(new LayerTint.Grass(0.8f, 0.4f)));
    }

    @Test
    @DisplayName("a map-colour tint carries its default, forced opaque")
    void mapColorCarriesItsDefault() {
        assertThat(read("{\"type\":\"minecraft:map_color\",\"default\":4603950}"),
            is(new LayerTint.MapColor(0xFF46402E)));
    }

    @Test
    @DisplayName("a grass tint missing a climate coordinate is refused rather than defaulted")
    void grassWithoutItsClimateIsRefused() {
        assertThrows(JsonParseException.class, () -> read("{\"type\":\"minecraft:grass\",\"downfall\":1.0}"));
        assertThrows(JsonParseException.class,
            () -> read("{\"type\":\"minecraft:grass\",\"temperature\":\"warm\",\"downfall\":1.0}"));
    }

    @Test
    @DisplayName("a grass tint outside the climate range vanilla admits is refused")
    void grassOutsideTheRangeIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> read("{\"type\":\"minecraft:grass\",\"temperature\":1.5,\"downfall\":1.0}"));
    }

    @Test
    @DisplayName("a source this renderer does not model reads as an untinted constant")
    void anUnmodelledSourceReadsAsWhite() {
        assertThat(read("{\"type\":\"minecraft:custom_model_data\",\"default\":16711680}"),
            is(new LayerTint.Constant(0xFFFFFFFF)));
    }

    /**
     * Reads one tint entry through the deserializer.
     *
     * @param json the entry's JSON text
     * @return the variant it reads into
     */
    private static @NotNull LayerTint read(@NotNull String json) {
        return new LayerTintDeserializer().deserialize(JsonParser.parseString(json), LayerTint.class, null);
    }

}
