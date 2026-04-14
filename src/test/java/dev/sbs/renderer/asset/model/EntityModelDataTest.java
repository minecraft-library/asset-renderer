package dev.sbs.renderer.asset.model;

import com.google.gson.Gson;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Focused coverage for the {@link EntityModelData} schema, mainly the
 * {@link EntityModelData#isNegateY() negateY} Y-down-to-Y-up compatibility flag added for
 * Minecraft Java Edition {@code ModelPart} imports.
 */
class EntityModelDataTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("defaults to Y-up (negateY=false)")
    void defaultsYUp() {
        EntityModelData model = new EntityModelData();
        assertThat(model.isNegateY(), is(false));
    }

    @Test
    @DisplayName("deserializes negate_y from JSON with snake_case key")
    void deserializesNegateYFromSnakeCaseKey() {
        String json = "{\"negate_y\": true}";
        EntityModelData model = GSON.fromJson(json, EntityModelData.class);
        assertThat(model.isNegateY(), is(true));
    }

    @Test
    @DisplayName("omitted negate_y falls back to default")
    void omittedNegateYUsesDefault() {
        String json = "{\"textureWidth\": 128}";
        EntityModelData model = GSON.fromJson(json, EntityModelData.class);
        assertThat(model.isNegateY(), is(false));
        assertThat(model.getTextureWidth(), is(128));
    }

    @Test
    @DisplayName("roundtrips negate_y through the Gson serializer")
    void roundtripNegateY() {
        String original = "{\"negate_y\": true, \"textureWidth\": 64, \"textureHeight\": 32}";
        EntityModelData model = GSON.fromJson(original, EntityModelData.class);
        String reserialized = GSON.toJson(model);
        EntityModelData reloaded = GSON.fromJson(reserialized, EntityModelData.class);
        assertThat(reloaded.isNegateY(), is(true));
        assertThat(reloaded, equalTo(model));
    }

    @Test
    @DisplayName("equals / hashCode differentiate on the negate_y flag")
    void equalityRespectsNegateY() {
        EntityModelData a = GSON.fromJson("{\"negate_y\": false}", EntityModelData.class);
        EntityModelData b = GSON.fromJson("{\"negate_y\": true}", EntityModelData.class);
        EntityModelData c = GSON.fromJson("{\"negate_y\": true}", EntityModelData.class);

        assertThat(a, is(not(equalTo(b))));
        assertThat(b, is(equalTo(c)));
        assertThat(b.hashCode(), is(c.hashCode()));
    }

}
