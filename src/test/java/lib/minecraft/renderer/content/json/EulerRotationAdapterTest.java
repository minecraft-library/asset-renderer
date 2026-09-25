package lib.minecraft.renderer.content.json;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link EulerRotationAdapter}'s wire form: an {@link EulerRotation} is written as the three-element
 * array {@code [pitch, yaw, roll]} in X, Y, Z order and read back from the same text. Every entity bone
 * and cube rotation and every {@code display.*} transform in the shipped tables is read through it, so a
 * shape change here moves every table carrying a rotation at once.
 * <p>
 * {@link RendererGsonContributor} registers the adapter for the type, so the shared
 * {@link GsonSettings#defaults()} {@link Gson} reads a field of the type - the shape of
 * {@link ModelTransform} and the entity bone and cube rows - and the bare type alike as the array. A
 * caller's own {@link JsonAdapter} naming the adapter on a field stays valid, and writes the same
 * bytes as the registration.
 */
@DisplayName("EulerRotationAdapter's [pitch, yaw, roll] array wire form")
class EulerRotationAdapterTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("a field of the type writes the exact [pitch,yaw,roll] array text and reads it back unchanged")
    void adapterRoundTripsTheArrayWireForm() {
        // The renderer's own iso pose, which every block icon's display.gui entry carries.
        Holder holder = new Holder(new EulerRotation(30f, 225f, 0f));

        assertThat(GSON.toJson(holder), equalTo("{\"rotation\":[30.0,225.0,0.0]}"));
        assertThat(GSON.fromJson("{\"rotation\":[30.0,225.0,0.0]}", Holder.class), equalTo(holder));

        Holder fractional = new Holder(new EulerRotation(22.5f, -90f, 7.75f));
        assertThat(GSON.fromJson(GSON.toJson(fractional), Holder.class), equalTo(fractional));
    }

    @Test
    @DisplayName("a field's wire form is a three-element array in X, Y, Z order")
    void adapterWireFormIsAnArray() {
        JsonElement rotation = GSON.toJsonTree(new Holder(new EulerRotation(1, 2, 3)))
            .getAsJsonObject()
            .get("rotation");

        assertThat(rotation.isJsonArray(), is(true));
        assertThat(rotation.getAsJsonArray().size(), equalTo(3));
        assertThat(rotation.getAsJsonArray().get(0).getAsFloat(), equalTo(1f));
        assertThat(rotation.getAsJsonArray().get(1).getAsFloat(), equalTo(2f));
        assertThat(rotation.getAsJsonArray().get(2).getAsFloat(), equalTo(3f));
    }

    @Test
    @DisplayName("the bare type is the array form too - the adapter is registered for the type")
    void theBareTypeIsTheArrayForm() {
        JsonElement bare = GSON.toJsonTree(new EulerRotation(30f, 225f, 0f), EulerRotation.class);

        assertThat(bare.isJsonArray(), is(true));
        assertThat(bare.getAsJsonArray().size(), equalTo(3));
        assertThat(GSON.fromJson("[30.0,225.0,0.0]", EulerRotation.class), equalTo(new EulerRotation(30f, 225f, 0f)));
    }

    @Test
    @DisplayName("a field annotated with the adapter writes and reads the same bytes as the registration")
    void theAnnotatedFormAgreesWithTheRegistration() {
        EulerRotation rotation = new EulerRotation(22.5f, -90f, 7.75f);
        String registered = GSON.toJson(new Holder(rotation));

        assertThat(GSON.toJson(new AnnotatedHolder(rotation)), equalTo(registered));
        assertThat(GSON.fromJson(registered, AnnotatedHolder.class).rotation(), equalTo(rotation));
    }

    @Test
    @DisplayName("the adapter is null-safe on both sides, and a null field is omitted rather than written")
    void adapterHandlesNull() {
        assertNull(GSON.fromJson("{\"rotation\":null}", Holder.class).rotation());

        // Observed composition: the adapter emits a JSON null, and the shared settings do not
        // serialize nulls, so a null component leaves no member behind at all.
        assertThat(GSON.toJson(new Holder(null)), equalTo("{}"));
    }

    /**
     * A stand-in for the shipped DTOs that carry a rotation, bound through the registration exactly
     * as {@link ModelTransform} and the entity bone and cube rows are.
     *
     * @param rotation the Euler-angle triple in degrees, array-encoded by the registered adapter
     */
    private record Holder(EulerRotation rotation) {}

    /**
     * The same rotation field bound by a caller's own annotation rather than by the registration.
     *
     * @param rotation the Euler-angle triple in degrees, array-encoded by the annotated adapter
     */
    private record AnnotatedHolder(@JsonAdapter(EulerRotationAdapter.class) EulerRotation rotation) {}

}
