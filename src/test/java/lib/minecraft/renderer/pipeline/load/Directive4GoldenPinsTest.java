package lib.minecraft.renderer.pipeline.load;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.option.HorseMarking;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * The three directive-4 golden pins (IMPLEMENTATION_PLAN 14.2): the 26.1 byte-moving set for the kit
 * adoptions is EMPTY (Fact C), so each adoption is schema-forward capacity guarded by a pin that turns
 * a future non-empty slot into a LOUD, LOOK-gated event rather than a silent mis-bake.
 *
 * <ul>
 *   <li><b>grow[3]</b> - no 26.1 cube carries an {@code [x, y, z]} array grow (all scalar), so the
 *       per-axis capacity degenerates to the uniform path on every real cube.</li>
 *   <li><b>rotate_z</b> - no v2 row emits a {@code rotate_z} transform, so the sealed
 *       {@code TransformOp} arm is vocabulary-only.</li>
 *   <li><b>textures_by_value</b> - the horse markings JSON table equals the {@link HorseMarking} enum
 *       table, so sourcing the render from the JSON table is byte-identical.</li>
 * </ul>
 */
@DisplayName("directive-4 golden pins (Fact C: byte-moving set empty in 26.1)")
class Directive4GoldenPinsTest {

    private static final @NotNull String V2 = "/lib/minecraft/renderer/v2/";
    private static final @NotNull String TEXTURE_PREFIX = "minecraft:textures/entity/";
    private static final @NotNull String TEXTURE_SUFFIX = ".png";

    @Test
    @DisplayName("grow[3]: no v2 geometry cube carries an [x,y,z] array grow (all scalar)")
    void noArrayGrow() {
        assertAllGrowsScalar("entity_geometry.json");
        assertAllGrowsScalar("block_geometry.json");
    }

    @Test
    @DisplayName("rotate_z: no v2 block-overlay row emits a rotate_z transform")
    void noRotateZ() {
        JsonObject families = read("entity_models.json").getAsJsonObject("families");
        for (Map.Entry<String, JsonElement> family : families.entrySet()) {
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

    @Test
    @DisplayName("textures_by_value: the horse markings JSON table equals the HorseMarking enum table")
    void markingTableEqualsEnum() {
        JsonObject horse = read("entity_models.json").getAsJsonObject("families").getAsJsonObject("minecraft:horse");
        Map<String, String> jsonTable = new LinkedHashMap<>();
        for (JsonElement layer : horse.getAsJsonArray("layers")) {
            JsonObject object = layer.getAsJsonObject();
            if (!"markings".equals(object.get("id").getAsString())) continue;
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("overlay").getAsJsonObject("textures_by_value").entrySet())
                jsonTable.put(entry.getKey(), strip(entry.getValue().getAsString()));
        }
        Map<String, String> enumTable = new LinkedHashMap<>();
        for (HorseMarking marking : HorseMarking.values())
            marking.overlayTexture().ifPresent(ref -> enumTable.put(marking.name().toLowerCase(java.util.Locale.ROOT), ref));
        assertThat("horse carries a markings table", jsonTable.isEmpty(), is(false));
        assertThat(jsonTable, equalTo(enumTable));
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

    private static @NotNull String strip(@NotNull String path) {
        return path.substring(TEXTURE_PREFIX.length(), path.length() - TEXTURE_SUFFIX.length());
    }

    private static @NotNull JsonObject read(@NotNull String name) {
        try (InputStream in = Directive4GoldenPinsTest.class.getResourceAsStream(V2 + name)) {
            if (in == null) throw new IllegalStateException("missing v2 resource " + name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("failed to read v2 resource " + name, failure);
        }
    }
}
