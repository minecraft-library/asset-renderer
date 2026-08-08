package lib.minecraft.renderer.asset;

import com.google.gson.Gson;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import dev.simplified.gson.GsonSettings;
import dev.simplified.gson.exception.JsonException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins for the shared Gson leaf adapters the pipeline resolves through {@link GsonSettings#defaults()}:
 * the {@link Color} codec (hex string, reflective map value, malformed input surfacing a
 * {@link JsonException}) and {@link ResourceId} (scalar {@code namespace:name} field and the per-field
 * model-id-dialect variant). Built from the runtime {@link GsonSettings#defaults()} so the test
 * exercises the exact registered adapter set - the {@link Color} codec is a gson-extras built-in.
 */
@DisplayName("Shared asset Gson leaf adapters")
class AssetGsonAdaptersTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("Color decodes an 8-digit hex string keeping its own alpha")
    void colorEightDigit() {
        assertEquals(new Color(0xFF2552A5, true), GSON.fromJson("\"0xFF2552A5\"", Color.class));
    }

    @Test
    @DisplayName("Color forces alpha FF on a 6-digit value and is null-safe")
    void colorSixDigitAndNull() {
        assertEquals(new Color(0xFFFF00FF, true), GSON.fromJson("\"FF00FF\"", Color.class));
        assertNull(GSON.fromJson("null", Color.class));
    }

    @Test
    @DisplayName("Color surfaces a JsonException on malformed input - the codec no longer substitutes a default")
    void colorMalformedThrows() {
        assertThrows(JsonException.class, () -> GSON.fromJson("\"zzz\"", Color.class));
    }

    @Test
    @DisplayName("Color reflects straight into a Map value (the potion / tints shape)")
    void colorReflectiveMapValue() {
        Map<String, Color> effects = GSON.fromJson(
            "{\"minecraft:absorption\":\"0xFF2552A5\",\"minecraft:blindness\":\"0xFF1F1F23\"}",
            new TypeToken<Map<String, Color>>() {}.getType());

        assertEquals(new Color(0xFF2552A5, true), effects.get("minecraft:absorption"));
        assertEquals(new Color(0xFF1F1F23, true), effects.get("minecraft:blindness"));
    }

    @Test
    @DisplayName("ResourceId decodes a namespace:name string as a scalar field")
    void resourceIdScalarField() {
        Holder holder = GSON.fromJson("{\"id\":\"minecraft:grass_block\"}", Holder.class);
        assertEquals(new ResourceId("minecraft", "grass_block"), holder.id());

        Holder bare = GSON.fromJson("{\"id\":\"stone\"}", Holder.class);
        assertEquals(new ResourceId("minecraft", "stone"), bare.id());
    }

    @Test
    @DisplayName("the per-field ModelIdAdapter collapses a namespaced model id to its trailing name")
    void resourceIdModelIdField() {
        ModelHolder holder = GSON.fromJson("{\"model\":\"minecraft:block/grass_block\"}", ModelHolder.class);
        assertEquals(new ResourceId("minecraft", "grass_block"), holder.model());
    }

    private record Holder(ResourceId id) {}

    private record ModelHolder(@JsonAdapter(ResourceId.ModelIdAdapter.class) ResourceId model) {}

}
