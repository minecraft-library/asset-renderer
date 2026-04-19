package lib.minecraft.renderer.tooling.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.tooling.util.Diagnostics;
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
 * Slow parity test: walks the real 26.1 client jar through {@link BlockListDiscovery#discover}
 * and compares each entity-id's {@code blocks} list against {@code baseline/block_list.json}.
 * Confirms the bytecode-driven discovery produces the same output as the captured ground truth.
 *
 * <p>Divergences are expected in the PR that replaces {@code BlockListCatalog} - when the
 * baseline was authored against a pre-26.1 jar, new block variants may appear in discovery but
 * not in the baseline. Such drift is surfaced by this test so the baseline can be updated
 * deliberately.
 */
@DisplayName("BlockListDiscovery parity")
@Tag("slow")
class BlockListDiscoveryParityTest {

    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");
    private static final Path BASELINE = Path.of("src/test/resources/lib/minecraft/renderer/baseline/block_list.json");

    @Test
    @DisplayName("BlockListDiscovery matches baseline/block_list.json")
    void parity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            Map<String, BlockListDiscovery.EntityBlockMapping> actual = BlockListDiscovery.discover(zip, new Diagnostics());
            JsonObject expectedJson = new Gson().fromJson(Files.readString(BASELINE), JsonObject.class);
            assertThat("entity-model key set", actual.keySet(), equalTo(expectedJson.keySet()));
            for (Map.Entry<String, BlockListDiscovery.EntityBlockMapping> e : actual.entrySet()) {
                JsonObject exp = expectedJson.getAsJsonObject(e.getKey());
                JsonArray expBlocks = exp.getAsJsonArray("blocks");
                assertThat("blocks count for " + e.getKey(), e.getValue().blocks().size(), equalTo(expBlocks.size()));
                for (int i = 0; i < e.getValue().blocks().size(); i++) {
                    BlockListDiscovery.BlockMapping actualBm = e.getValue().blocks().get(i);
                    JsonObject expBm = expBlocks.get(i).getAsJsonObject();
                    assertThat("blockId for " + e.getKey() + "[" + i + "]", actualBm.blockId(),
                        equalTo(expBm.get("blockId").getAsString()));
                    assertThat("textureId for " + e.getKey() + "[" + i + "]", actualBm.textureId(),
                        equalTo(expBm.get("textureId").getAsString()));
                }
            }
        }
    }

}
