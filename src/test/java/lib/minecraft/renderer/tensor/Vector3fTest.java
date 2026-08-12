package lib.minecraft.renderer.tensor;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.pipeline.util.PipelineGsonContributor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Vector3f}'s arithmetic and geometry: component-wise add/subtract/multiply/divide/negate,
 * index-based {@link Vector3f#get(int)} access (with the out-of-range guard), length and
 * length-squared, dot and right-hand-rule cross products, {@link Vector3f#normalize()} including its
 * degenerate-vector guard below {@link Vector3f#NORMALIZE_EPSILON}, and {@link Vector3f#lerp} endpoint
 * and midpoint interpolation. These exercise the scalar contract; the transparent Vector-API
 * dispatch on {@code transform}/{@code transformNormal} is out of scope here.
 * <p>
 * It also pins {@link Vector3f.Adapter}, the Gson codec every shipped table and every vanilla model
 * JSON reads a three-float triple through. {@link PipelineGsonContributor} registers it globally, so
 * the wire form is an {@code [x, y, z]} <b>array</b> for the bare type and for any field of that type -
 * a change to that shape moves every emitted table at once, which is why the exact JSON text is
 * asserted here rather than a round trip alone. The {@link Gson} under test is built from the runtime
 * {@link GsonSettings#defaults()} so the registration itself is what is exercised, not a hand-built
 * adapter.
 */
@DisplayName("Vector3f arithmetic, geometry and its Gson array adapter")
class Vector3fTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("component-wise arithmetic")
    void arithmetic() {
        Vector3f a = new Vector3f(1, 2, 3);
        Vector3f b = new Vector3f(4, 5, 6);
        assertThat(a.add(b), equalTo(new Vector3f(5, 7, 9)));
        assertThat(b.subtract(a), equalTo(new Vector3f(3, 3, 3)));
        assertThat(a.multiply(2), equalTo(new Vector3f(2, 4, 6)));
        assertThat(new Vector3f(2, 4, 6).divide(2), equalTo(new Vector3f(1, 2, 3)));
        assertThat(a.negate(), equalTo(new Vector3f(-1, -2, -3)));
    }

    @Test
    @DisplayName("get() indexes components and rejects out-of-range axes")
    void get() {
        Vector3f v = new Vector3f(7, 8, 9);
        assertThat(v.get(0), equalTo(7f));
        assertThat(v.get(1), equalTo(8f));
        assertThat(v.get(2), equalTo(9f));
        assertThrows(IndexOutOfBoundsException.class, () -> v.get(3));
    }

    @Test
    @DisplayName("lengthSquared sums the squared components and length takes their root")
    void length() {
        Vector3f v = new Vector3f(3, 4, 0);
        assertThat(v.lengthSquared(), equalTo(25f));
        assertThat(v.length(), equalTo(5f));
    }

    @Test
    @DisplayName("the dot product sums the pairwise products, and is zero on perpendicular vectors")
    void dot() {
        assertThat(new Vector3f(1, 2, 3).dot(new Vector3f(4, 5, 6)), equalTo(32f));
        assertThat(new Vector3f(1, 0, 0).dot(new Vector3f(0, 1, 0)), equalTo(0f));
    }

    @Test
    @DisplayName("cross product follows the right-hand rule")
    void cross() {
        assertThat(new Vector3f(1, 0, 0).cross(new Vector3f(0, 1, 0)), equalTo(new Vector3f(0, 0, 1)));
        assertThat(new Vector3f(0, 1, 0).cross(new Vector3f(0, 0, 1)), equalTo(new Vector3f(1, 0, 0)));
    }

    @Test
    @DisplayName("normalize scales to unit length and guards the degenerate case")
    void normalize() {
        assertThat(new Vector3f(0, 3, 0).normalize(), equalTo(new Vector3f(0, 1, 0)));
        assertThat(Vector3f.ZERO.normalize(), is(Vector3f.ZERO));
        // Length 1e-9 < NORMALIZE_EPSILON (1e-8): treated as degenerate, so normalize() short-
        // circuits to ZERO rather than dividing by a near-zero magnitude.
        assertThat(new Vector3f(1e-9f, 0, 0).normalize(), is(Vector3f.ZERO));
    }

    @Test
    @DisplayName("lerp interpolates between endpoints")
    void lerp() {
        Vector3f a = new Vector3f(0, 0, 0);
        Vector3f b = new Vector3f(10, -4, 2);
        assertThat(a.lerp(b, 0f), equalTo(a));
        assertThat(a.lerp(b, 1f), equalTo(b));
        assertThat(a.lerp(b, 0.5f), equalTo(new Vector3f(5, -2, 1)));
    }

    @Test
    @DisplayName("the registered adapter writes the exact [x,y,z] array text and reads it back unchanged")
    void adapterRoundTripsTheArrayWireForm() {
        Vector3f v = new Vector3f(0.5f, -1.25f, 16f);

        assertThat(GSON.toJson(v, Vector3f.class), equalTo("[0.5,-1.25,16.0]"));
        assertThat(GSON.fromJson("[0.5,-1.25,16.0]", Vector3f.class), equalTo(v));
        assertThat(GSON.fromJson(GSON.toJson(v, Vector3f.class), Vector3f.class), equalTo(v));
    }

    @Test
    @DisplayName("the wire form is a three-element array, never an object")
    void adapterWireFormIsAnArray() {
        JsonElement tree = GSON.toJsonTree(new Vector3f(1, 2, 3), Vector3f.class);

        assertThat(tree.isJsonArray(), is(true));
        assertThat(tree.isJsonObject(), is(false));
        assertThat(tree.getAsJsonArray().size(), equalTo(3));
    }

    @Test
    @DisplayName("a field of the type takes the same array form, so the registration reaches nested DTOs")
    void adapterAppliesToAField() {
        assertThat(GSON.toJson(new Holder(new Vector3f(1f, 1f, 1f))), equalTo("{\"scale\":[1.0,1.0,1.0]}"));
        assertThat(
            GSON.fromJson("{\"scale\":[16.0,0.0,-8.5]}", Holder.class).scale(),
            equalTo(new Vector3f(16f, 0f, -8.5f)));
    }

    @Test
    @DisplayName("the adapter is null-safe on both sides, and a null field is omitted rather than written")
    void adapterHandlesNull() {
        assertNull(GSON.fromJson("null", Vector3f.class));
        assertThat(GSON.toJson(null, Vector3f.class), equalTo("null"));

        // Observed composition: the adapter emits a JSON null, and the shared settings do not
        // serialize nulls, so a null component leaves no member behind at all.
        assertThat(GSON.toJson(new Holder(null)), equalTo("{}"));
        assertNull(GSON.fromJson("{\"scale\":null}", Holder.class).scale());
    }

    private record Holder(Vector3f scale) {}

}
