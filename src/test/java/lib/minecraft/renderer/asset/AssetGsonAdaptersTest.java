package lib.minecraft.renderer.asset;

import com.google.gson.Gson;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins for the shared Gson leaf adapters the pipeline registers through
 * {@code PipelineGsonContributor}: {@link ArgbColor} (hex string, reflective map value, malformed
 * fallback) and {@link ResourceId} (scalar {@code namespace:name} field and the per-field
 * model-id-dialect variant). Built from the runtime {@link GsonSettings#defaults()} so the test
 * exercises the exact registered adapter set.
 */
@DisplayName("Shared asset Gson leaf adapters")
class AssetGsonAdaptersTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("ArgbColor decodes an 8-digit hex string keeping its own alpha")
    void argbColorEightDigit() {
        assertEquals(new ArgbColor(0xFF2552A5), GSON.fromJson("\"0xFF2552A5\"", ArgbColor.class));
    }

    @Test
    @DisplayName("ArgbColor forces alpha FF on a 6-digit value and falls back to white on malformed")
    void argbColorSixDigitAndMalformed() {
        assertEquals(new ArgbColor(0xFFFF00FF), GSON.fromJson("\"FF00FF\"", ArgbColor.class));
        assertEquals(ArgbColor.WHITE, GSON.fromJson("\"zzz\"", ArgbColor.class));
        assertNull(GSON.fromJson("null", ArgbColor.class));
    }

    @Test
    @DisplayName("ArgbColor reflects straight into a Map value (the potion / tints shape)")
    void argbColorReflectiveMapValue() {
        Map<String, ArgbColor> effects = GSON.fromJson(
            "{\"minecraft:absorption\":\"0xFF2552A5\",\"minecraft:blindness\":\"0xFF1F1F23\"}",
            new TypeToken<Map<String, ArgbColor>>() {}.getType());

        assertEquals(new ArgbColor(0xFF2552A5), effects.get("minecraft:absorption"));
        assertEquals(new ArgbColor(0xFF1F1F23), effects.get("minecraft:blindness"));
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
