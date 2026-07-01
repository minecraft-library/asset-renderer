package lib.minecraft.renderer.tooling.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Slow parity test pinning {@link SourceDiscovery#discover} against the cached 26.1 client jar,
 * compared field-by-field to the hand-curated {@code baseline/sources.json} fixture. This is the
 * end-to-end counterpart to the fast synthetic-bytecode mutation tests - it proves the discovery
 * walk emits exactly the real vanilla source set, not just that it follows bytecode shape.
 *
 * <p>The discovered set is filtered down to the {@link BlockListDiscovery} catalog's entity id
 * set, since those are the entities that actually ship in the atlas: {@code enchanting_table} and
 * {@code lectern} both discover a {@code BookModel} source through their renderers, but neither
 * appears in the baseline, so the block-list membership check drops them.
 *
 * <p>Each side is keyed by the composite {@code entityId|methodName|paramIntValues} so banner
 * variants (which share the {@code minecraft:banner} / {@code minecraft:wall_banner} ids but split
 * on the standing/wall {@code paramInt}) resolve to distinct entries. The test asserts every
 * per-field value ({@code classEntry}, {@code methodName}, {@code yAxis}, {@code inventoryYRotation},
 * the nullable {@code texWidthOverride} / {@code texHeightOverride}), then closes with a
 * size-equality and a key-set-equality check so neither side has extra or missing entries.
 */
@DisplayName("SourceDiscovery parity")
@Tag("slow")
class SourceDiscoveryParityTest {

    /** The cached deobfuscated 26.1 client jar discovery walks. */
    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");
    /** The hand-curated ground-truth source list discovery is diffed against. */
    private static final Path BASELINE = Path.of("src/test/resources/lib/minecraft/renderer/baseline/sources.json");
    /** Project-standard Gson, used to parse the baseline JSON array. */
    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("SourceDiscovery matches baseline/sources.json")
    void parity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            Diagnostics diag = new Diagnostics();
            ConcurrentList<Source> all = SourceDiscovery.discover(zip, diag);
            Map<String, BlockListDiscovery.EntityBlockMapping> blockList = BlockListDiscovery.discover(zip, diag);

            ConcurrentList<Source> filtered = Concurrent.newList();
            for (Source s : all)
                if (blockList.containsKey(s.entityId())) filtered.add(s);

            JsonArray expectedJson = GSON.fromJson(Files.readString(BASELINE), JsonArray.class);
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

    /**
     * Builds the {@code entityId|methodName|paramIntValues} composite key from a baseline JSON
     * entry. Must stay format-aligned with the {@link Source} overload below - the paramInt
     * component is stringified with no interior spaces so a baseline {@code [1]} and a
     * discovered {@code new int[]{1}} produce byte-identical keys.
     */
    private static String sourceKey(JsonObject o) {
        String pi = o.get("paramIntValues").isJsonNull() ? "null" : o.get("paramIntValues").toString();
        return o.get("entityId").getAsString() + "|" + o.get("methodName").getAsString() + "|" + pi;
    }

    /**
     * Builds the {@code entityId|methodName|paramIntValues} composite key from a discovered
     * {@link Source}, format-matched to the {@link JsonObject} overload above (spaces stripped
     * from {@link Arrays#toString} so {@code [1, 0]} collapses to {@code [1,0]}).
     */
    private static String sourceKey(Source s) {
        String pi = s.paramIntValues() == null ? "null" : Arrays.toString(s.paramIntValues()).replace(" ", "");
        return s.entityId() + "|" + s.methodName() + "|" + pi;
    }

}
