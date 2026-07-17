package lib.minecraft.renderer.asset.model;

import com.google.gson.Gson;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Verifies the {@link EntityModelData} schema - the entity-model DTO produced by the
 * bytecode-walk tooling and read back through Gson.
 * <p>
 * Focused on the texture-atlas dimensions and value semantics: the no-arg constructor defaults to a
 * {@code 64 x 64} atlas with no bones, Gson populates the {@code texture_size} array through the
 * {@link TextureSize} adapter, a deserialise &rarr; serialise &rarr; deserialise roundtrip is stable
 * under {@code equals}/{@code hashCode}, and equality discriminates on texture dimensions. Uses the
 * shared {@link GsonSettings#defaults()} configuration so the test exercises the same adapter set
 * as the runtime loader.
 */
class EntityModelDataTest {

    /**
     * Shared serializer built from the runtime {@link GsonSettings#defaults()} so the schema is
     * exercised under the same adapters the production loader uses.
     */
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
    @DisplayName("deserialises the texture_size [w, h] array")
    void deserialisesTextureDimensions() {
        String json = "{\"texture_size\": [128, 32]}";
        EntityModelData model = GSON.fromJson(json, EntityModelData.class);
        assertThat(model.getTextureWidth(), is(128));
        assertThat(model.getTextureHeight(), is(32));
    }

    @Test
    @DisplayName("roundtrips through the Gson serializer")
    void roundtripsThroughGson() {
        String original = "{\"texture_size\": [64, 32]}";
        EntityModelData model = GSON.fromJson(original, EntityModelData.class);
        String reserialized = GSON.toJson(model);
        EntityModelData reloaded = GSON.fromJson(reserialized, EntityModelData.class);
        assertThat(reloaded, equalTo(model));
        assertThat(reloaded.hashCode(), is(model.hashCode()));
    }

    @Test
    @DisplayName("equals / hashCode differentiate on texture dimensions")
    void equalityRespectsTextureDimensions() {
        EntityModelData a = GSON.fromJson("{\"texture_size\": [64, 64]}", EntityModelData.class);
        EntityModelData b = GSON.fromJson("{\"texture_size\": [128, 64]}", EntityModelData.class);
        EntityModelData c = GSON.fromJson("{\"texture_size\": [128, 64]}", EntityModelData.class);

        assertThat(a, is(not(equalTo(b))));
        assertThat(b, is(equalTo(c)));
        assertThat(b.hashCode(), is(c.hashCode()));
    }

}
