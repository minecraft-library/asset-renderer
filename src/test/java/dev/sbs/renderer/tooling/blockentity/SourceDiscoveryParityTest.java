package dev.sbs.renderer.tooling.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Slow parity test: runs {@link SourceDiscovery} against the cached 26.1 client jar and
 * compares the (filtered) result against the hand-curated
 * {@code baseline/sources.json} fixture. The filter applies the block-list catalog's entity
 * id set since those are the entities that ship in the atlas - enchanting_table and lectern
 * both discover a BookModel source but neither appears in the baseline.
 */
@DisplayName("SourceDiscovery parity")
@Tag("slow")
class SourceDiscoveryParityTest {

    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");
    private static final Path BASELINE = Path.of("src/test/resources/renderer/baseline/sources.json");

    @Test
    @DisplayName("SourceDiscovery matches baseline/sources.json")
    void parity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            Diagnostics diag = new Diagnostics();
            ConcurrentList<Source> all = SourceDiscovery.discover(zip, diag);
            Map<String, BlockListDiscovery.EntityBlockMapping> blockList = BlockListDiscovery.discover(zip, diag);

            ConcurrentList<Source> filtered = dev.simplified.collection.Concurrent.newList();
            for (Source s : all)
                if (blockList.containsKey(s.entityId())) filtered.add(s);

            JsonArray expectedJson = new Gson().fromJson(Files.readString(BASELINE), JsonArray.class);
            Map<String, JsonObject> expectedById = new LinkedHashMap<>();
            for (JsonElement e : expectedJson) {
                JsonObject o = e.getAsJsonObject();
                // Banner ids share: use entityId + methodName + paramInt as the composite key.
                expectedById.put(sourceKey(o), o);
            }

            Set<String> discoveredKeys = new LinkedHashSet<>();
            for (Source s : filtered) {
                String key = sourceKey(s);
                discoveredKeys.add(key);
                JsonObject exp = expectedById.get(key);
                assertThat("baseline contains source: " + key, exp, org.hamcrest.Matchers.notNullValue());
                if (exp == null) continue;
                assertThat("classEntry for " + key, s.classEntry(), equalTo(exp.get("classEntry").getAsString()));
                assertThat("methodName for " + key, s.methodName(), equalTo(exp.get("methodName").getAsString()));
                assertThat("yAxis for " + key, s.yAxis().name(), equalTo(exp.get("yAxis").getAsString()));
                assertThat("inventoryYRotation for " + key, s.inventoryYRotation(), equalTo(exp.get("inventoryYRotation").getAsFloat()));
                Integer expW = exp.get("texWidthOverride").isJsonNull() ? null : exp.get("texWidthOverride").getAsInt();
                Integer expH = exp.get("texHeightOverride").isJsonNull() ? null : exp.get("texHeightOverride").getAsInt();
                assertThat("texWidthOverride for " + key, s.texWidthOverride(), equalTo(expW));
                assertThat("texHeightOverride for " + key, s.texHeightOverride(), equalTo(expH));
            }
            assertThat("discovered size matches baseline size", filtered, hasSize(expectedJson.size()));
            assertThat("every baseline entry was discovered", discoveredKeys, equalTo(expectedById.keySet()));
        }
    }

    private static String sourceKey(JsonObject o) {
        String pi = o.get("paramIntValues").isJsonNull() ? "null" : o.get("paramIntValues").toString();
        return o.get("entityId").getAsString() + "|" + o.get("methodName").getAsString() + "|" + pi;
    }

    private static String sourceKey(Source s) {
        String pi = s.paramIntValues() == null ? "null" : java.util.Arrays.toString(s.paramIntValues()).replace(" ", "");
        return s.entityId() + "|" + s.methodName() + "|" + pi;
    }

}
