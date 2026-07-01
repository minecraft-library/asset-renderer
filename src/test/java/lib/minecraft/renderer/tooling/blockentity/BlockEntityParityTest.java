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
 * Slow full-pipeline consistency check for the committed {@code block_models.json}. Rather than
 * re-running the {@code blockModels} tooling in-process (which needs disk I/O and would clash with
 * the {@code JsonResourceShaTest} golden-reference guard), it reads the committed
 * {@code src/main/resources/lib/minecraft/renderer/block_models.json} directly and confirms it is
 * internally consistent against a live {@link BlockListDiscovery#discover} walk of the 26.1 client
 * jar: every discovered block-entity-model id must have a matching {@code models} entry carrying
 * geometry ({@code model.elements}, non-empty) and the {@code y_axis} / {@code tinted} metadata
 * fields.
 *
 * <p>Read against the committed file, not a fresh render, so a stale {@code block_models.json}
 * (out of sync with a bumped client jar) surfaces here as a missing-id or empty-elements failure.
 */
@DisplayName("block_models.json full-pipeline parity")
@Tag("slow")
class BlockEntityParityTest {

    private static final Path OUTPUT = Path.of("src/main/resources/lib/minecraft/renderer/block_models.json");
    private static final Gson GSON = GsonSettings.defaults().create();

    /**
     * Asserts every id discovered from the client jar is present in {@code block_models.json} under
     * {@code models}, and that each carries {@code y_axis}, {@code tinted}, and a {@code model} with
     * a non-empty {@code elements} array. The block-entity families we parse (the 19 wired into
     * {@link BlockListDiscovery}'s dispatch) always ship a model, so {@code model} is required
     * unconditionally.
     */
    @Test
    @DisplayName("every catalog entity id has a block_models.json entry with elements")
    void allEntitiesPresent() throws IOException {
        JsonObject root = GSON.fromJson(Files.readString(OUTPUT), JsonObject.class);
        JsonObject entities = root.getAsJsonObject("models");
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(Path.of("cache/asset-renderer/vanilla/26.1/client.jar").toFile());
        try {
            Map<String, BlockListDiscovery.EntityBlockMapping> catalog = BlockListDiscovery.discover(zip, new Diagnostics());
            for (String entityId : catalog.keySet()) {
                assertThat("entity '" + entityId + "' present in block_models.json", entities.has(entityId), equalTo(true));
                JsonObject entity = entities.getAsJsonObject(entityId);
                assertThat("entity '" + entityId + "' has y_axis", entity.has("y_axis"), equalTo(true));
                assertThat("entity '" + entityId + "' has tinted", entity.has("tinted"), equalTo(true));
                // model may be absent for a few (the 19 we parse all have models; loose catalog keys
                // might not - but our 19 always do).
                assertThat("entity '" + entityId + "' has model", entity.has("model"), equalTo(true));
                JsonObject model = entity.getAsJsonObject("model");
                assertThat("entity '" + entityId + "' model has elements", model.has("elements"), equalTo(true));
                assertThat("entity '" + entityId + "' model has non-empty elements",
                    model.getAsJsonArray("elements").isEmpty(), equalTo(false));
            }
        } finally {
            zip.close();
        }
    }
}
