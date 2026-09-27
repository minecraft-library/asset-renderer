package lib.minecraft.renderer.content.json;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.MalformedJsonException;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.math.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A mesh cube's {@code grow} grammar as {@link CubeGrowFactory} binds it, driven through the runtime
 * {@link GsonSettings#defaults()} so the test exercises the factory the contributor installs rather
 * than one built by hand.
 *
 * <p>A scalar broadcasts to all three axes, an array reads per axis, an absent member leaves
 * {@link Vector3f#ZERO} and a JSON {@code null} reads as {@code null}; the cube's other members keep
 * their reflective binding through the registered adapters. A {@code NaN} or infinite value in a
 * cube is refused, a scalar {@code grow} included, although the document around it reads leniently.
 * The factory rewrites a scalar on a copy of the cube's object, and the last case holds that: a
 * document decoded twice is read the same both times and still carries its scalar afterwards.
 */
@DisplayName("A mesh cube's grow through the installed factory")
class CubeGrowFactoryTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("a scalar grow broadcasts to all three axes")
    void scalarBroadcasts() {
        EntityMesh.Cube cube = GSON.fromJson("{\"grow\": 0.25}", EntityMesh.Cube.class);
        assertThat(cube.getGrow(), equalTo(new Vector3f(0.25f, 0.25f, 0.25f)));
    }

    @Test
    @DisplayName("an array grow is read per axis")
    void arrayReadsPerAxis() {
        EntityMesh.Cube cube = GSON.fromJson("{\"grow\": [0.1, 0.2, 0.3]}", EntityMesh.Cube.class);
        assertThat(cube.getGrow(), equalTo(new Vector3f(0.1f, 0.2f, 0.3f)));
    }

    @Test
    @DisplayName("an absent grow leaves the cube at its authored size")
    void absentIsZero() {
        EntityMesh.Cube cube = GSON.fromJson("{\"size\": [2, 3, 4]}", EntityMesh.Cube.class);
        assertThat(cube.getGrow(), equalTo(Vector3f.ZERO));
    }

    @Test
    @DisplayName("a null grow reads as null")
    void nullIsNull() {
        EntityMesh.Cube cube = GSON.fromJson("{\"grow\": null}", EntityMesh.Cube.class);
        assertThat(cube.getGrow(), is(nullValue()));
    }

    @Test
    @DisplayName("the cube's other members still bind beside a rewritten grow")
    void otherMembersStillBind() {
        EntityMesh.Cube cube = GSON.fromJson(
            "{\"origin\": [1, 2, 3], \"size\": [4, 5, 6], \"grow\": 0.5, \"mirror\": true,"
                + " \"pivot\": [7, 8, 9], \"rotation\": [10, 20, 30]}",
            EntityMesh.Cube.class);

        assertThat(cube.getOrigin(), equalTo(new Vector3f(1f, 2f, 3f)));
        assertThat(cube.getSize(), equalTo(new Vector3f(4f, 5f, 6f)));
        assertThat(cube.getGrow(), equalTo(new Vector3f(0.5f, 0.5f, 0.5f)));
        assertThat(cube.isMirror(), is(true));
        assertThat(cube.getPivot(), equalTo(new Vector3f(7f, 8f, 9f)));
        assertThat(cube.getRotation(), equalTo(new EulerRotation(10f, 20f, 30f)));
    }

    @Test
    @DisplayName("a uniform grow is written as its scalar and an uneven one as its array")
    void writeMirrorsTheRead() {
        EntityMesh.Cube uniform = GSON.fromJson("{\"grow\": [0.5, 0.5, 0.5]}", EntityMesh.Cube.class);
        EntityMesh.Cube uneven = GSON.fromJson("{\"grow\": [0.1, 0.2, 0.3]}", EntityMesh.Cube.class);

        JsonElement scalar = GSON.toJsonTree(uniform).getAsJsonObject().get("grow");
        JsonElement array = GSON.toJsonTree(uneven).getAsJsonObject().get("grow");

        assertThat(scalar.isJsonPrimitive(), is(true));
        assertThat(scalar.getAsFloat(), equalTo(0.5f));
        assertThat(array.isJsonArray(), is(true));
        assertThat(GSON.fromJson(GSON.toJson(uneven), EntityMesh.Cube.class), equalTo(uneven));
    }

    @Test
    @DisplayName("a non-finite member of a cube is refused")
    void nonFiniteMemberIsRefused() {
        JsonIOException thrown = assertThrows(JsonIOException.class,
            () -> GSON.fromJson("{\"origin\": [NaN, 0, 0]}", EntityMesh.Cube.class));
        assertThat(thrown.getCause(), is(instanceOf(MalformedJsonException.class)));
    }

    @Test
    @DisplayName("a non-finite scalar grow is refused")
    void nonFiniteScalarGrowIsRefused() {
        JsonIOException thrown = assertThrows(JsonIOException.class,
            () -> GSON.fromJson("{\"grow\": Infinity}", EntityMesh.Cube.class));
        assertThat(thrown.getCause(), is(instanceOf(MalformedJsonException.class)));
    }

    @Test
    @DisplayName("decoding one document twice reads it the same and leaves its scalar grow in place")
    void theDocumentIsNotEdited() {
        JsonObject document = JsonParser.parseString("{\"size\": [2, 2, 2], \"grow\": 0.25}").getAsJsonObject();

        EntityMesh.Cube first = GSON.fromJson(document, EntityMesh.Cube.class);
        EntityMesh.Cube second = GSON.fromJson(document, EntityMesh.Cube.class);

        assertThat(document.get("grow").isJsonPrimitive(), is(true));
        assertThat(document.get("grow").getAsFloat(), equalTo(0.25f));
        assertThat(second, equalTo(first));
    }

}
