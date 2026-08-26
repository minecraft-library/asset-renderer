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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The geometry ref-integrity gate: every {@code geometry} reference in a models file
 * resolves in its paired geometry file, both carry the same {@code source_version} stamp,
 * and - via the reverse closure - every geometry entry is referenced by at least one model
 * ref (a registered-but-unreferenced entry means a resolver registered a request and then
 * dropped the key).
 *
 * <p>Every pair ships, so a missing file is read as the failure it is rather than skipped.
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

    private static void assertPairCloses(@NotNull String modelsName, @NotNull String payloadKey, @NotNull String geometryName) throws IOException {
        Path modelsPath = RESOURCE_DIR.resolve(modelsName);
        Path geometryPath = RESOURCE_DIR.resolve(geometryName);

        JsonObject models = GSON.fromJson(Files.readString(modelsPath), JsonElement.class).getAsJsonObject();
        JsonObject geometry = GSON.fromJson(Files.readString(geometryPath), JsonElement.class).getAsJsonObject();
        assertEquals(models.get("source_version").getAsString(), geometry.get("source_version").getAsString(),
            "source_version stamps must match");

        Set<String> geometryKeys = geometry.getAsJsonObject("geometries").keySet();
        Set<String> referenced = new LinkedHashSet<>();
        collectGeometryRefs(models.get(payloadKey), referenced);
        assertFalse(referenced.isEmpty(), "no geometry refs found under '" + payloadKey + "'");

        Set<String> dangling = new LinkedHashSet<>(referenced);
        dangling.removeAll(geometryKeys);
        assertEquals(Set.of(), dangling, "dangling geometry refs (flow registered nothing for these)");

        Set<String> orphans = new LinkedHashSet<>(geometryKeys);
        orphans.removeAll(referenced);
        assertEquals(Set.of(), orphans, "orphan geometry entries (registered but unreferenced)");
    }

    /** The members that name a mesh, any of which is a reference the pair has to close over. */
    private static final Set<String> GEOMETRY_MEMBERS =
        Set.of("geometry", "baby_geometry", "no_hat_geometry");

    /**
     * Recursively collects every string-valued mesh reference under {@code element}:
     * {@code geometry}, the equipment rows' captured {@code baby_geometry}, and the
     * {@code no_hat_geometry} an overlay pass names its suppressed form with.
     */
    private static void collectGeometryRefs(@NotNull JsonElement element, @NotNull Set<String> out) {
        if (element instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> member : object.entrySet()) {
                if (GEOMETRY_MEMBERS.contains(member.getKey()) && member.getValue().isJsonPrimitive())
                    out.add(member.getValue().getAsString());
                else collectGeometryRefs(member.getValue(), out);
            }
        } else if (element instanceof JsonArray array) {
            for (JsonElement entry : array) collectGeometryRefs(entry, out);
        }
    }

}
