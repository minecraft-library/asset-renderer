package lib.minecraft.renderer.tooling;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Capacity pins for what the tooling flows emitted: two schema forms the readers handle that no
 * shipped 26.1 table exercises, each guarded so the first table that does exercise one becomes a
 * loud, look-gated event rather than a silent mis-bake.
 *
 * <ul>
 *   <li><b>grow[3]</b> - no 26.1 cube carries an {@code [x, y, z]} array grow (all scalar), so the
 *       per-axis capacity degenerates to the uniform path on every real cube.</li>
 *   <li><b>rotate_z</b> - no row emits a {@code rotate_z} transform, so the sealed
 *       {@code TransformOp} arm is vocabulary-only.</li>
 * </ul>
 */
@DisplayName("shipped tables carry neither an array-form grow nor a rotate_z transform")
class ShippedTableCapacityTest {

    /**
     * The renderer's shipped tables, read off disk rather than off this build's classpath - the
     * renderer is not on it, and these are gated as bytes on disk in any case. Relative to the
     * renderer root, which every task in this build pins as its working directory.
     */
    private static final @NotNull Path RESOURCE_DIR = Path.of("src/main/resources/lib/minecraft/renderer");

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
                        op.getAsJsonObject().get("op").getAsString(), is(not(equalTo("rotate_z"))));
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

    private static @NotNull JsonObject read(@NotNull String name) {
        Path table = RESOURCE_DIR.resolve(name);
        if (!Files.isRegularFile(table))
            throw new IllegalStateException("missing shipped table " + table.toAbsolutePath());
        try {
            return JsonParser.parseString(Files.readString(table)).getAsJsonObject();
        } catch (IOException failure) {
            throw new IllegalStateException("failed to read shipped table " + table.toAbsolutePath(), failure);
        }
    }
}
