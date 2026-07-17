package lib.minecraft.renderer.pipeline.load;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Golden pins for the kit adoptions: the 26.1 byte-moving set is empty, so each adoption is
 * schema-forward capacity guarded by a pin that turns a future non-empty slot into a loud,
 * look-gated event rather than a silent mis-bake.
 *
 * <ul>
 *   <li><b>grow[3]</b> - no 26.1 cube carries an {@code [x, y, z]} array grow (all scalar), so the
 *       per-axis capacity degenerates to the uniform path on every real cube.</li>
 *   <li><b>rotate_z</b> - no row emits a {@code rotate_z} transform, so the sealed
 *       {@code TransformOp} arm is vocabulary-only.</li>
 * </ul>
 */
@DisplayName("directive-4 golden pins (Fact C: byte-moving set empty in 26.1)")
class Directive4GoldenPinsTest {

    private static final @NotNull String RESOURCE_DIR = "/lib/minecraft/renderer/";

    @Test
    @DisplayName("grow[3]: no geometry cube carries an [x,y,z] array grow (all scalar)")
    void noArrayGrow() {
        assertAllGrowsScalar("entity_geometry.json");
        assertAllGrowsScalar("block_geometry.json");
    }

    @Test
    @DisplayName("rotate_z: no block-overlay row emits a rotate_z transform")
    void noRotateZ() {
        JsonObject models = read("entity_models.json").getAsJsonObject("models");
        for (Map.Entry<String, JsonElement> family : models.entrySet()) {
            if (!family.getValue().isJsonObject()) continue;
            JsonObject object = family.getValue().getAsJsonObject();
            if (!object.has("block_overlays")) continue;
            for (JsonElement row : object.getAsJsonArray("block_overlays")) {
                if (!row.getAsJsonObject().has("transforms")) continue;
                for (JsonElement op : row.getAsJsonObject().getAsJsonArray("transforms"))
                    assertThat("no rotate_z op in " + family.getKey(),
                        op.getAsJsonObject().get("op").getAsString(), is(not("rotate_z")));
            }
        }
    }

    private static void assertAllGrowsScalar(@NotNull String resource) {
        JsonObject geometries = read(resource).getAsJsonObject("geometries");
        for (Map.Entry<String, JsonElement> geometry : geometries.entrySet()) {
            if (geometry.getKey().startsWith("//") || !geometry.getValue().isJsonObject()) continue;
            JsonObject object = geometry.getValue().getAsJsonObject();
            if (!object.has("bones")) continue;
            for (Map.Entry<String, JsonElement> bone : object.getAsJsonObject("bones").entrySet()) {
                JsonObject boneObject = bone.getValue().getAsJsonObject();
                if (!boneObject.has("cubes")) continue;
                for (JsonElement cube : boneObject.getAsJsonArray("cubes")) {
                    JsonElement grow = cube.getAsJsonObject().get("grow");
                    if (grow != null)
                        assertThat(resource + " " + geometry.getKey() + "/" + bone.getKey() + " grow is scalar, not [x,y,z]",
                            grow.isJsonArray(), is(false));
                }
            }
        }
    }

    private static @NotNull org.hamcrest.Matcher<String> not(@NotNull String value) {
        return org.hamcrest.Matchers.not(equalTo(value));
    }

    private static @NotNull JsonObject read(@NotNull String name) {
        try (InputStream in = Directive4GoldenPinsTest.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) throw new IllegalStateException("missing bundled resource " + name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("failed to read bundled resource " + name, failure);
        }
    }
}
