package lib.minecraft.renderer.tooling.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Slow full-pipeline consistency check for the committed {@code v2/block_models.json}. Rather than
 * re-running the {@code blockModels2} tooling in-process (which needs disk I/O and would clash with
 * the {@code V2JsonResourceShaTest} golden-reference guard), it reads the committed
 * {@code v2/block_models.json} directly and confirms it is internally consistent against a live
 * {@link BlockListDiscovery#discover} walk of the 26.1 client jar: every discovered
 * block-entity-model id must have a matching {@code models} entry carrying a {@code geometry}
 * coordinate that resolves to a non-empty bone tree in {@code v2/block_geometry.json}, plus the
 * {@code y_axis} / {@code tinted} metadata fields.
 *
 * <p>The v2 format splits geometry out of the model entry into a paired {@code block_geometry.json}
 * (the model carries a {@code geometry} ref rather than an inline {@code model.elements}/{@code bones}),
 * so this check resolves the ref across the two files.
 *
 * <p>Read against the committed files, not a fresh render, so a stale {@code block_models.json}
 * (out of sync with a bumped client jar) surfaces here as a missing-id or dangling-ref failure.
 */
@DisplayName("v2/block_models.json full-pipeline parity")
@Tag("slow")
class BlockEntityParityTest {

    private static final Path MODELS = Path.of("src/main/resources/lib/minecraft/renderer/v2/block_models.json");
    private static final Path GEOMETRY = Path.of("src/main/resources/lib/minecraft/renderer/v2/block_geometry.json");
    private static final Gson GSON = GsonSettings.defaults().create();

    /**
     * Asserts every id discovered from the client jar is present in {@code v2/block_models.json} under
     * {@code models}, and that each carries {@code y_axis}, {@code tinted}, and a {@code geometry}
     * coordinate resolving to a non-empty {@code bones} tree in {@code v2/block_geometry.json}. The
     * block-entity families we parse (the 19 wired into {@link BlockListDiscovery}'s dispatch) always
     * ship a geometry, so {@code geometry} is required unconditionally.
     */
    @Test
    @DisplayName("every catalog entity id has a v2 block_models entry whose geometry resolves")
    void allEntitiesPresent() throws IOException {
        JsonObject models = GSON.fromJson(Files.readString(MODELS), JsonObject.class).getAsJsonObject("models");
        JsonObject geometries = GSON.fromJson(Files.readString(GEOMETRY), JsonObject.class).getAsJsonObject("geometries");
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(Path.of("cache/asset-renderer/vanilla/26.1/client.jar").toFile());
        try {
            Map<String, BlockListDiscovery.EntityBlockMapping> catalog = BlockListDiscovery.discover(zip, new Diagnostics());
            for (String entityId : catalog.keySet()) {
                assertThat("entity '" + entityId + "' present in v2 block_models.json", models.has(entityId), equalTo(true));
                JsonObject entity = models.getAsJsonObject(entityId);
                assertThat("entity '" + entityId + "' has y_axis", entity.has("y_axis"), equalTo(true));
                assertThat("entity '" + entityId + "' has tinted", entity.has("tinted"), equalTo(true));
                // v2 carries a geometry coordinate ref (the two-file split); it must resolve to a
                // non-empty bone tree in the paired block_geometry.json.
                assertThat("entity '" + entityId + "' has geometry ref", entity.has("geometry"), equalTo(true));
                String coord = entity.get("geometry").getAsString();
                assertThat("entity '" + entityId + "' geometry '" + coord + "' resolves", geometries.has(coord), equalTo(true));
                JsonObject geometry = geometries.getAsJsonObject(coord);
                boolean hasBones = geometry.has("bones") && !geometry.getAsJsonObject("bones").keySet().isEmpty();
                assertThat("entity '" + entityId + "' geometry has non-empty bones", hasBones, equalTo(true));
            }
        } finally {
            zip.close();
        }
    }
}
