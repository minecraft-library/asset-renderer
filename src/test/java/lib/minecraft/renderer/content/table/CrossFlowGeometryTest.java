package lib.minecraft.renderer.content.table;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The meshes the two shipped geometry tables key - one coordinate per mesh inside each table, and only
 * the known coordinates across the two.
 *
 * <p>Inside one table a coordinate is the one name of one mesh, so two coordinates carrying one
 * payload are one mesh minted under two keys - the generator coining a second name for a bone set it
 * already holds, as a factory whose whole body delegates to another's or an overlay deforming a mesh
 * exactly as a layer vanilla registers does. Nothing else in the build reads that as a fault: the
 * tooling flow gate reads the added key as an ordinary addition, and the reference closure is
 * satisfied because something references it. So that half carries no ledger at all.
 *
 * <p>Across the two tables it is different. The entity flow and the block-entity flow each construct
 * their own {@code GeometryManifest}, so neither can see what the other registered and a subject both
 * of them reach is baked twice - the same bone set, under the same key, in two tables. Merging the
 * manifests is not the answer: it trips the forward guard on the one coordinate that does it today,
 * and a single table would leave every entry of each flow orphaned against the other's model file,
 * which is the reverse-closure assertion being weakened rather than a test being moved.
 *
 * <p>So that half reports rather than merges. A duplicate across the two tables is not on its own an
 * error - the copper golem is a real subject that a block render and an entity render both need - and
 * what earns the digest its place is naming the NEXT one rather than fixing this one.
 */
@DisplayName("each geometry table names a mesh once, and the coordinates both flows bake are the ones already known")
class CrossFlowGeometryTest {

    /**
     * The renderer's shipped tables, read off disk rather than off this build's classpath, relative to
     * the renderer root every task in this build pins as its working directory.
     */
    private static final @NotNull Path RESOURCE_DIR = Path.of("src/main/resources/lib/minecraft/renderer");

    @Test
    @DisplayName("a coordinate in both tables is one of the known cross-flow bakes, and carries one payload")
    void crossFlowDuplicatesAreKnown() {
        Map<String, String> entity = payloadDigests("entity_geometry.json");
        Map<String, String> block = payloadDigests("block_geometry.json");

        Map<String, String> shared = new TreeMap<>();
        entity.forEach((coordinate, digest) -> {
            String other = block.get(coordinate);
            if (other == null) return;
            // The payload rather than the whole entry, so a coordinate both flows bake DIFFERENTLY
            // reads as a disagreement rather than as a duplicate.
            shared.put(coordinate, digest.equals(other) ? "one payload" : "TWO PAYLOADS");
        });

        assertEquals(Map.of("CopperGolemModel#createBodyLayer", "one payload"), shared,
            "coordinates both geometry flows bake");
    }

    @Test
    @DisplayName("no two coordinates in one geometry table carry one payload")
    void everyCoordinateInATableCarriesItsOwnPayload() {
        Map<String, List<String>> entity = sharedPayloads("entity_geometry.json");
        Map<String, List<String>> block = sharedPayloads("block_geometry.json");

        assertEquals(Map.of(), entity,
            () -> "entity_geometry.json coordinates carrying one mesh under two keys: " + entity.values());
        assertEquals(Map.of(), block,
            () -> "block_geometry.json coordinates carrying one mesh under two keys: " + block.values());
    }

    /**
     * Groups one table's coordinates by payload, keeping each payload more than one coordinate carries.
     *
     * @param table the shipped table's file name
     * @return each shared payload's digest to the coordinates carrying it, sorted
     */
    private static @NotNull Map<String, List<String>> sharedPayloads(@NotNull String table) {
        Map<String, List<String>> byPayload = new TreeMap<>();
        new TreeMap<>(payloadDigests(table)).forEach((coordinate, digest) ->
            byPayload.computeIfAbsent(digest, key -> new ArrayList<>()).add(coordinate));
        byPayload.values().removeIf(coordinates -> coordinates.size() < 2);
        return byPayload;
    }

    /**
     * Digests each coordinate's payload - its entry with the {@code source} twin left out, so
     * {@code texture_size}, {@code bones} and {@code cull} where it has one.
     *
     * <p>The digest is over the emitted spelling rather than the parsed structure: two payloads match
     * when the generator spelled them alike, member order and number spellings included and whitespace
     * aside. That is exact for one mesh minted twice, because the emitter spells one bone set one way.
     *
     * @param table the shipped table's file name
     * @return each coordinate to its payload's digest, in table order
     */
    private static @NotNull Map<String, String> payloadDigests(@NotNull String table) {
        JsonObject geometries = read(table).getAsJsonObject("geometries");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : geometries.entrySet()) {
            JsonObject payload = entry.getValue().getAsJsonObject().deepCopy();
            // The twin restates the key and the key is what this joins on, so digesting it would only
            // make two identical bone sets look different when one of them was reached another way.
            payload.remove("source");
            out.put(entry.getKey(), sha256(payload.toString()));
        }
        return out;
    }

    /** A UTF-8 SHA-256, hex. */
    private static @NotNull String sha256(@NotNull String content) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available", unavailable);
        }
    }

    /** One shipped table, parsed. */
    private static @NotNull JsonObject read(@NotNull String table) {
        try {
            return JsonParser.parseString(Files.readString(RESOURCE_DIR.resolve(table))).getAsJsonObject();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + table + " from " + RESOURCE_DIR, failure);
        }
    }

}
