package lib.minecraft.renderer.tooling.entity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.util.Diagnostics;
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
 * Slow parity test: walks the real 26.1 client jar through {@link MobRegistryDiscovery#discover}
 * and compares each field's {@code (entityId, entityClassInternalName, mobCategory)} triple
 * against {@code baseline/living_mobs.json}. Confirms the bytecode-driven discovery produces
 * the same living-mob set as the captured ground truth.
 *
 * <p>Divergence is expected on a Minecraft version bump - new mob types appear in discovery but
 * not in the baseline. Regenerate the baseline by dumping the {@link MobRegistryDiscovery#discover}
 * output as JSON and committing the replacement.
 */
@DisplayName("MobRegistryDiscovery parity")
@Tag("slow")
class MobRegistryDiscoveryParityTest {

    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");
    private static final Path BASELINE = Path.of("src/test/resources/lib/minecraft/renderer/baseline/living_mobs.json");

    @Test
    @DisplayName("discovered living mobs match baseline/living_mobs.json")
    void parity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            ConcurrentList<MobRegistryDiscovery.MobEntry> actual =
                MobRegistryDiscovery.discover(zip, new Diagnostics());

            JsonObject baseline = new Gson().fromJson(Files.readString(BASELINE), JsonObject.class);
            JsonArray expectedArr = baseline.getAsJsonArray("mobs");

            // Convert to field-keyed maps for set-compare + targeted per-entry assertions.
            Map<String, MobRegistryDiscovery.MobEntry> actualByField = new LinkedHashMap<>();
            for (MobRegistryDiscovery.MobEntry entry : actual)
                actualByField.put(entry.fieldName(), entry);

            Map<String, JsonObject> expectedByField = new LinkedHashMap<>();
            for (int i = 0; i < expectedArr.size(); i++) {
                JsonObject o = expectedArr.get(i).getAsJsonObject();
                expectedByField.put(o.get("fieldName").getAsString(), o);
            }

            assertThat("living-mob field set", actualByField.keySet(), equalTo(expectedByField.keySet()));

            for (Map.Entry<String, MobRegistryDiscovery.MobEntry> e : actualByField.entrySet()) {
                String field = e.getKey();
                JsonObject expected = expectedByField.get(field);
                assertThat("entityId for " + field,
                    e.getValue().entityId(), equalTo(expected.get("entityId").getAsString()));
                assertThat("entityClassInternalName for " + field,
                    e.getValue().entityClassInternalName(),
                    equalTo(expected.get("entityClassInternalName").getAsString()));
                assertThat("mobCategory for " + field,
                    e.getValue().mobCategory(), equalTo(expected.get("mobCategory").getAsString()));
            }
        }
    }

}
