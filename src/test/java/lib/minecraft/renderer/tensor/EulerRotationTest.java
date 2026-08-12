package lib.minecraft.renderer.tensor;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.pipeline.util.PipelineGsonContributor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link EulerRotation}'s data-only contract: the {@link EulerRotation#NONE} identity constant, that
 * {@code pitch}/{@code yaw}/{@code roll} are exposed verbatim in degrees, and that the per-axis radian
 * accessors ({@link EulerRotation#pitchRadians()}, {@link EulerRotation#yawRadians()},
 * {@link EulerRotation#rollRadians()}) apply the degrees-to-radians conversion. The record composes no
 * rotation of its own, so those accessors are the whole of its arithmetic.
 * <p>
 * It carries one further member, {@link EulerRotation.Adapter}, the Gson codec every entity bone
 * rotation and every {@code display.*} transform in the shipped tables is read through, and the cases
 * below pin its exact {@code [pitch, yaw, roll]} array text as well as a round trip - a shape change
 * there moves every table carrying a rotation at once.
 * <p>
 * That adapter is applied <b>per field</b> with {@link JsonAdapter}, as {@link ModelTransform} does,
 * and is deliberately not among the global registrations {@link PipelineGsonContributor} makes. The
 * observed consequence is pinned here: handed the bare type, the shared {@link GsonSettings#defaults()}
 * {@link Gson} falls through to the reflective record codec and emits a pitch/yaw/roll <b>object</b>,
 * not the array. Only an annotated field gets the array.
 */
@DisplayName("EulerRotation degrees, radian conversion and its per-field Gson array adapter")
class EulerRotationTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("NONE is the zero rotation")
    void none() {
        assertThat(EulerRotation.NONE, equalTo(new EulerRotation(0f, 0f, 0f)));
    }

    @Test
    @DisplayName("per-axis radian accessors convert from degrees")
    void radianAccessors() {
        EulerRotation r = new EulerRotation(90f, 180f, 270f);
        assertThat(r.pitchRadians(), equalTo((float) Math.toRadians(90)));
        assertThat(r.yawRadians(), equalTo((float) Math.toRadians(180)));
        assertThat(r.rollRadians(), equalTo((float) Math.toRadians(270)));
    }

    @Test
    @DisplayName("components are exposed verbatim in degrees")
    void components() {
        EulerRotation r = new EulerRotation(12.5f, -45f, 7f);
        assertThat(r.pitch(), equalTo(12.5f));
        assertThat(r.yaw(), equalTo(-45f));
        assertThat(r.roll(), equalTo(7f));
    }

    @Test
    @DisplayName("an annotated field writes the exact [pitch,yaw,roll] array text and reads it back unchanged")
    void adapterRoundTripsTheArrayWireForm() {
        // The renderer's own iso pose, which every block icon's display.gui entry carries.
        Holder holder = new Holder(new EulerRotation(30f, 225f, 0f));

        assertThat(GSON.toJson(holder), equalTo("{\"rotation\":[30.0,225.0,0.0]}"));
        assertThat(GSON.fromJson("{\"rotation\":[30.0,225.0,0.0]}", Holder.class), equalTo(holder));

        Holder fractional = new Holder(new EulerRotation(22.5f, -90f, 7.75f));
        assertThat(GSON.fromJson(GSON.toJson(fractional), Holder.class), equalTo(fractional));
    }

    @Test
    @DisplayName("the annotated field's wire form is a three-element array in X, Y, Z order")
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
    @DisplayName("the bare type is NOT array-encoded - the adapter is per-field, never globally registered")
    void bareTypeFallsThroughToTheReflectiveObjectForm() {
        JsonElement bare = GSON.toJsonTree(new EulerRotation(30f, 225f, 0f), EulerRotation.class);

        assertThat(bare.isJsonArray(), is(false));
        assertThat(bare.isJsonObject(), is(true));
        assertThat(bare.getAsJsonObject().get("pitch").getAsFloat(), equalTo(30f));
        assertThat(bare.getAsJsonObject().get("yaw").getAsFloat(), equalTo(225f));
        assertThat(bare.getAsJsonObject().get("roll").getAsFloat(), equalTo(0f));
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
     * A stand-in for the shipped DTOs that carry a rotation, annotated exactly as
     * {@link ModelTransform} and the entity bone and cube rows are.
     *
     * @param rotation the Euler-angle triple in degrees, array-encoded by the annotated adapter
     */
    private record Holder(@JsonAdapter(EulerRotation.Adapter.class) EulerRotation rotation) {}

}
