package lib.minecraft.renderer.asset.mesh;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.math.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of the {@link EntityMesh} schema - the entity-model DTO the bytecode-walk tooling
 * produces and Gson reads back.
 * <p>
 * Focused on the texture-atlas dimensions and value semantics: the no-arg constructor defaults to a
 * {@code 64 x 64} atlas with no bones, Gson populates the {@code texture_size} array through the
 * {@link TextureSize} adapter, a deserialise, serialise and deserialise roundtrip is stable under
 * {@code equals} and {@code hashCode}, and equality discriminates on texture dimensions. Built from the
 * shared {@link GsonSettings#defaults()} configuration so the test exercises the same adapter set as the
 * runtime loader.
 * <p>
 * Beside the schema, the scale above a part: the declared parent's rest, or the whole-mesh factor
 * for a part the chain composes on the root.
 */
@DisplayName("EntityMesh schema")
class EntityMeshTest {

    /** Serializer over the runtime adapter set */
    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("defaults to 64x64 texture dimensions")
    void defaultsTextureDimensions() {
        EntityMesh model = new EntityMesh();
        assertThat(model.getTextureWidth(), is(64));
        assertThat(model.getTextureHeight(), is(64));
        assertThat(model.getBones().isEmpty(), is(true));
    }

    @Test
    @DisplayName("deserialises the texture_size [w, h] array")
    void deserialisesTextureDimensions() {
        String json = "{\"texture_size\": [128, 32]}";
        EntityMesh model = GSON.fromJson(json, EntityMesh.class);
        assertThat(model.getTextureWidth(), is(128));
        assertThat(model.getTextureHeight(), is(32));
    }

    @Test
    @DisplayName("roundtrips through the Gson serializer")
    void roundtripsThroughGson() {
        String original = "{\"texture_size\": [64, 32]}";
        EntityMesh model = GSON.fromJson(original, EntityMesh.class);
        String reserialized = GSON.toJson(model);
        EntityMesh reloaded = GSON.fromJson(reserialized, EntityMesh.class);
        assertThat(reloaded, equalTo(model));
        assertThat(reloaded.hashCode(), is(model.hashCode()));
    }

    @Test
    @DisplayName("equals / hashCode differentiate on texture dimensions")
    void equalityRespectsTextureDimensions() {
        EntityMesh a = GSON.fromJson("{\"texture_size\": [64, 64]}", EntityMesh.class);
        EntityMesh b = GSON.fromJson("{\"texture_size\": [128, 64]}", EntityMesh.class);
        EntityMesh c = GSON.fromJson("{\"texture_size\": [128, 64]}", EntityMesh.class);

        assertThat(a, is(not(equalTo(b))));
        assertThat(b, is(equalTo(c)));
        assertThat(b.hashCode(), is(c.hashCode()));
    }

    @Test
    @DisplayName("the scale above a part is its declared parent's rest, and the whole-mesh factor at the root")
    void scaleAboveReadsTheParentElseTheRoot() {
        // A part hangs from the root by the three tests the chain composition applies: no parent,
        // itself as its parent, or a parent the mesh does not declare.
        EntityMesh agedDown = new EntityMesh();
        agedDown.getBones().put("body", bone(0.5f, null));
        agedDown.getBones().put("tail", bone(0.25f, "body"));
        agedDown.getBones().put("looped", bone(0.75f, "looped"));
        agedDown.getBones().put("dangling", bone(0.75f, "missing"));
        assertThat(agedDown.getFlattenedScale(), is(1f));
        assertThat(agedDown.scaleAbove("tail"), is(0.5f));
        assertThat(agedDown.scaleAbove("body"), is(1f));
        assertThat(agedDown.scaleAbove("looped"), is(1f));
        assertThat(agedDown.scaleAbove("dangling"), is(1f));

        // Each route to the root answers the whole-mesh factor, which only a mesh flattened off one
        // tells apart from one, and the corpus's one dangling parent sits on a mesh flattened at one.
        EntityMesh flattened = new EntityMesh();
        flattened.getBones().put("body", bone(2f, null));
        flattened.getBones().put("tail", bone(2f, "body"));
        flattened.getBones().put("looped", bone(2f, "looped"));
        flattened.getBones().put("dangling", bone(2f, "missing"));
        assertThat(flattened.getFlattenedScale(), is(2f));
        assertThat(flattened.scaleAbove("body"), is(2f));
        assertThat(flattened.scaleAbove("tail"), is(2f));
        assertThat(flattened.scaleAbove("looped"), is(2f));
        assertThat(flattened.scaleAbove("dangling"), is(2f));
        assertThat(flattened.scaleAbove("undeclared"), is(2f));
    }

    /** A cubeless bone at the origin resting at a scale of its own. */
    private static EntityMesh.Bone bone(float rest, String parent) {
        return new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, rest,
            Concurrent.newList(), parent);
    }

}
