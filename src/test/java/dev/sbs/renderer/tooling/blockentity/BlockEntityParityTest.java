package dev.sbs.renderer.tooling.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Slow end-to-end parity test. The full pipeline is exercised via the committed
 * {@code src/main/resources/renderer/block_entities.json} file rather than re-running the
 * tooling in-process (which needs disk I/O and would interfere with the BlockEntitiesGoldenTest
 * fixture). Here we only confirm the committed file is internally consistent - every entity
 * id in the catalog has a matching {@code entities} entry with geometry and the expected
 * metadata fields.
 */
@DisplayName("block_entities.json full-pipeline parity")
@Tag("slow")
class BlockEntityParityTest {

    private static final Path OUTPUT = Path.of("src/main/resources/renderer/block_entities.json");

    @Test
    @DisplayName("every catalog entity id has a block_entities.json entry with elements")
    void allEntitiesPresent() throws IOException {
        JsonObject root = new Gson().fromJson(Files.readString(OUTPUT), JsonObject.class);
        JsonObject entities = root.getAsJsonObject("entities");
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(Path.of("cache/asset-renderer/vanilla/26.1/client.jar").toFile());
        try {
            java.util.Map<String, BlockListCatalog.EntityBlockMapping> catalog = BlockListCatalog.lookup(zip, new Diagnostics());
            for (String entityId : catalog.keySet()) {
                assertThat("entity '" + entityId + "' present in block_entities.json", entities.has(entityId), equalTo(true));
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
