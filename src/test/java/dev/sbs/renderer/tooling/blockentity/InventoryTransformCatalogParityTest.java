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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Slow parity test: locks the hardcoded {@link InventoryTransformCatalog#lookup} output
 * against {@code baseline/inventory_transforms.json}.
 */
@DisplayName("InventoryTransformCatalog parity")
@Tag("slow")
class InventoryTransformCatalogParityTest {

    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");
    private static final Path BASELINE = Path.of("src/test/resources/renderer/baseline/inventory_transforms.json");

    @Test
    @DisplayName("InventoryTransformCatalog matches baseline/inventory_transforms.json")
    void parity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            Map<String, float[]> actual = InventoryTransformCatalog.lookup(zip, new LinkedHashMap<>(), new Diagnostics());
            JsonObject expectedJson = new Gson().fromJson(Files.readString(BASELINE), JsonObject.class);
            assertThat("entity-id key set", actual.keySet(), equalTo(expectedJson.keySet()));
            for (Map.Entry<String, float[]> e : actual.entrySet()) {
                JsonArray expArr = expectedJson.getAsJsonArray(e.getKey());
                assertThat("transform length for " + e.getKey(), e.getValue().length, equalTo(expArr.size()));
                for (int i = 0; i < e.getValue().length; i++) {
                    assertThat("transform[" + i + "] for " + e.getKey(),
                        e.getValue()[i], equalTo(expArr.get(i).getAsFloat()));
                }
            }
        }
    }
}
