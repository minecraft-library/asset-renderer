package lib.minecraft.renderer.asset.model;

import com.google.gson.Gson;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Focused coverage for the {@link EntityModelData} schema - texture dimensions default to
 * {@code 64 x 64}, Gson deserialisation populates the declared fields, and equality discriminates
 * by content.
 */
class EntityModelDataTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("defaults to 64x64 texture dimensions")
    void defaultsTextureDimensions() {
        EntityModelData model = new EntityModelData();
        assertThat(model.getTextureWidth(), is(64));
        assertThat(model.getTextureHeight(), is(64));
        assertThat(model.getBones().isEmpty(), is(true));
    }

    @Test
    @DisplayName("deserialises textureWidth / textureHeight from JSON")
    void deserialisesTextureDimensions() {
        String json = "{\"textureWidth\": 128, \"textureHeight\": 32}";
        EntityModelData model = GSON.fromJson(json, EntityModelData.class);
        assertThat(model.getTextureWidth(), is(128));
        assertThat(model.getTextureHeight(), is(32));
    }

    @Test
    @DisplayName("roundtrips through the Gson serializer")
    void roundtripsThroughGson() {
        String original = "{\"textureWidth\": 64, \"textureHeight\": 32}";
        EntityModelData model = GSON.fromJson(original, EntityModelData.class);
        String reserialized = GSON.toJson(model);
        EntityModelData reloaded = GSON.fromJson(reserialized, EntityModelData.class);
        assertThat(reloaded, equalTo(model));
        assertThat(reloaded.hashCode(), is(model.hashCode()));
    }

    @Test
    @DisplayName("equals / hashCode differentiate on texture dimensions")
    void equalityRespectsTextureDimensions() {
        EntityModelData a = GSON.fromJson("{\"textureWidth\": 64}", EntityModelData.class);
        EntityModelData b = GSON.fromJson("{\"textureWidth\": 128}", EntityModelData.class);
        EntityModelData c = GSON.fromJson("{\"textureWidth\": 128}", EntityModelData.class);

        assertThat(a, is(not(equalTo(b))));
        assertThat(b, is(equalTo(c)));
        assertThat(b.hashCode(), is(c.hashCode()));
    }

}
