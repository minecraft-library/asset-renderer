package lib.minecraft.renderer.tooling.geometry;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The geometry ref-integrity gate: every {@code geometry} reference in a models file
 * resolves in its paired geometry file, both carry the same {@code source_version} stamp,
 * and - via the reverse closure - every geometry entry is referenced by at least one model
 * ref (a registered-but-unreferenced entry means a resolver registered a request and then
 * dropped the key).
 *
 * <p>A missing pair is assumption-skipped, never a pass. {@code legacy_id} is never
 * required.
 */
@DisplayName("geometry ref closure: models refs resolve, versions match, no orphan geometry")
class GeometryRefClosureTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();
    private static final @NotNull Path RESOURCE_DIR = Path.of("src/main/resources/lib/minecraft/renderer");

    @Test
    @DisplayName("entity pair: entity_models.json refs close over entity_geometry.json")
    void entityPairCloses() throws IOException {
        assertPairCloses("entity_models.json", "models", "entity_geometry.json");
    }

    @Test
    @DisplayName("block pair: block_models.json refs close over block_geometry.json")
    void blockPairCloses() throws IOException {
        assertPairCloses("block_models.json", "models", "block_geometry.json");
    }

    @Test
    @DisplayName("legacy_id is never emitted in either geometry file")
    void noLegacyIdInGeometryFiles() throws IOException {
        // The legacy_id fallback is never activated, so both geometry files must carry zero occurrences.
        for (String name : List.of("entity_geometry.json", "block_geometry.json")) {
            Path path = RESOURCE_DIR.resolve(name);
            assumeTrue(Files.exists(path), name + " not yet emitted (activates with its flow session)");
            assertTrue(!Files.readString(path).contains("legacy_id"),
                name + " must not contain legacy_id; the PRIMARY key-replay arm was selected");
        }
    }

    private static void assertPairCloses(@NotNull String modelsName, @NotNull String payloadKey, @NotNull String geometryName) throws IOException {
        Path modelsPath = RESOURCE_DIR.resolve(modelsName);
        Path geometryPath = RESOURCE_DIR.resolve(geometryName);
        assumeTrue(Files.exists(modelsPath) && Files.exists(geometryPath),
            "pair not yet emitted (activates with its flow session)");

        JsonObject models = GSON.fromJson(Files.readString(modelsPath), JsonElement.class).getAsJsonObject();
        JsonObject geometry = GSON.fromJson(Files.readString(geometryPath), JsonElement.class).getAsJsonObject();
        assertEquals(models.get("source_version").getAsString(), geometry.get("source_version").getAsString(),
            "source_version stamps must match");

        Set<String> geometryKeys = geometry.getAsJsonObject("geometries").keySet();
        Set<String> referenced = new LinkedHashSet<>();
        collectGeometryRefs(models.get(payloadKey), referenced);
        assertTrue(!referenced.isEmpty(), "no geometry refs found under '" + payloadKey + "'");

        Set<String> dangling = new LinkedHashSet<>(referenced);
        dangling.removeAll(geometryKeys);
        assertEquals(Set.of(), dangling, "dangling geometry refs (flow registered nothing for these)");

        Set<String> orphans = new LinkedHashSet<>(geometryKeys);
        orphans.removeAll(referenced);
        assertEquals(Set.of(), orphans, "orphan geometry entries (registered but unreferenced)");
    }

    /**
     * Recursively collects every string-valued {@code geometry} / {@code baby_geometry}
     * member under {@code element} ({@code baby_geometry} is the equipment rows' captured
     * baby mesh).
     */
    private static void collectGeometryRefs(@NotNull JsonElement element, @NotNull Set<String> out) {
        if (element instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> member : object.entrySet()) {
                boolean geometryRef = "geometry".equals(member.getKey()) || "baby_geometry".equals(member.getKey());
                if (geometryRef && member.getValue().isJsonPrimitive())
                    out.add(member.getValue().getAsString());
                else collectGeometryRefs(member.getValue(), out);
            }
        } else if (element instanceof JsonArray array) {
            for (JsonElement entry : array) collectGeometryRefs(entry, out);
        }
    }

}
