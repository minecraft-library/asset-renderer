package dev.sbs.renderer.tooling.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Slow parity test: locks the hardcoded {@link BlockListCatalog#lookup} output against the
 * {@code baseline/block_list.json} fixture. Confirms the catalog hasn't drifted from the
 * last-captured ground truth.
 */
@DisplayName("BlockListCatalog parity")
@Tag("slow")
class BlockListCatalogParityTest {

    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");
    private static final Path BASELINE = Path.of("src/test/resources/renderer/baseline/block_list.json");

    @Test
    @DisplayName("BlockListCatalog matches baseline/block_list.json")
    void parity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            Map<String, BlockListCatalog.EntityBlockMapping> actual = BlockListCatalog.lookup(zip, new Diagnostics());
            JsonObject expectedJson = new Gson().fromJson(Files.readString(BASELINE), JsonObject.class);
            assertThat("entity-model key set", actual.keySet(), equalTo(expectedJson.keySet()));
            for (Map.Entry<String, BlockListCatalog.EntityBlockMapping> e : actual.entrySet()) {
                JsonObject exp = expectedJson.getAsJsonObject(e.getKey());
                JsonArray expBlocks = exp.getAsJsonArray("blocks");
                assertThat("blocks count for " + e.getKey(), e.getValue().blocks().size(), equalTo(expBlocks.size()));
                for (int i = 0; i < e.getValue().blocks().size(); i++) {
                    BlockListCatalog.BlockMapping actualBm = e.getValue().blocks().get(i);
                    JsonObject expBm = expBlocks.get(i).getAsJsonObject();
                    assertThat("blockId for " + e.getKey() + "[" + i + "]", actualBm.blockId(), equalTo(expBm.get("blockId").getAsString()));
                    assertThat("textureId for " + e.getKey() + "[" + i + "]", actualBm.textureId(), equalTo(expBm.get("textureId").getAsString()));
                }
            }
        }
    }
}
