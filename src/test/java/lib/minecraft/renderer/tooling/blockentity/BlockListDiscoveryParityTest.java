package lib.minecraft.renderer.tooling.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import java.util.zip.ZipFile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Slow parity test that walks the real 26.1 client jar through {@link BlockListDiscovery#discover}
 * and compares each entity-id's {@code blocks} list ({@code blockId} + {@code textureId}, in order)
 * against the captured {@code baseline/block_list.json}. Confirms the bytecode-driven discovery
 * that replaced the hardcoded {@code BlockListCatalog} reproduces the same block bindings as the
 * ground-truth snapshot.
 *
 * <p>Scope is deliberately narrow: it diffs only the {@code blocks} list (key set, per-entry
 * {@code blockId}, per-entry {@code textureId}), not the {@code parts} sub-model references or the
 * {@code variant} slot. Those are exercised structurally by {@link BlockListDiscoveryTest}.
 *
 * <p>When the baseline was authored against a different client jar, new block variants may appear
 * in discovery but not in the baseline; that drift surfaces here as a key-set or count mismatch so
 * the baseline can be regenerated deliberately rather than silently.
 */
@DisplayName("BlockListDiscovery parity")
@Tag("slow")
class BlockListDiscoveryParityTest {

    /** Cached deobfuscated 26.1 client jar - the live input the discovery walk runs against. */
    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");

    /** Captured ground-truth snapshot of the entity-id to blocks bindings. */
    private static final Path BASELINE = Path.of("src/test/resources/lib/minecraft/renderer/baseline/block_list.json");

    /** Shared Gson configured with the project's default settings. */
    private static final Gson GSON = GsonSettings.defaults().create();

    /**
     * Asserts the live discovery key set matches the baseline exactly, then walks each entity's
     * {@code blocks} list index-by-index, pinning both {@code blockId} and {@code textureId} against
     * the baseline entry at the same position (order is load-bearing).
     */
    @Test
    @DisplayName("BlockListDiscovery matches baseline/block_list.json")
    void parity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            Map<String, BlockListDiscovery.EntityBlockMapping> actual = BlockListDiscovery.discover(zip, new Diagnostics());
            JsonObject expectedJson = GSON.fromJson(Files.readString(BASELINE), JsonObject.class);
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
