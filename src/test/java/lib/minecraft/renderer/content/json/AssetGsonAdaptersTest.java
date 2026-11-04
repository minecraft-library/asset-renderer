package lib.minecraft.renderer.content.json;

import com.google.gson.Gson;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins for the shared Gson leaf adapters the pipeline resolves through {@link GsonSettings#defaults()}:
 * the {@link Color} codec (a six-digit hex string read opaque, and {@code null}), {@link ResourceId}
 * (scalar {@code namespace:name} field), and the bare {@link TextureSize} {@code [w, h]} array, each read
 * through its registration rather than through anything on the record. Built from the runtime
 * {@link GsonSettings#defaults()} so the test exercises the exact registered adapter set - the
 * {@link Color} codec is a gson-extras built-in.
 */
@DisplayName("Shared asset Gson leaf adapters")
class AssetGsonAdaptersTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("Color forces alpha FF on a 6-digit value and is null-safe")
    void colorSixDigitAndNull() {
        assertEquals(new Color(0xFFFF00FF, true), GSON.fromJson("\"FF00FF\"", Color.class));
        assertNull(GSON.fromJson("null", Color.class));
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
    @DisplayName("TextureSize decodes its bare [w, h] array")
    void textureSizeBareArray() {
        assertEquals(new TextureSize(128, 32), GSON.fromJson("[128, 32]", TextureSize.class));
    }

    private record Holder(ResourceId id) {}

}
