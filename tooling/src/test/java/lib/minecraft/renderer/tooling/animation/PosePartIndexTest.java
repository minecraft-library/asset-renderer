package lib.minecraft.renderer.tooling.animation;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Part-field resolution against the real client jar and the shipped mesh table.
 *
 * <p>The load-bearing assertion is the join itself: every bone this walk names has to be a bone the
 * geometry table actually holds for that model. A field mapped to a name no mesh carries would pose
 * nothing and say nothing, which is the failure this exists to make impossible.
 *
 * <p>Tagged {@code slow}: the walk runs against the downloaded client jar.
 */
@Tag("slow")
@DisplayName("a model's part fields resolve to bones its mesh declares")
class PosePartIndexTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The shipped table, relative to the renderer root every Test task runs at. */
    private static final @NotNull Path SHIPPED_GEOMETRY =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_geometry.json");

    private static ClassNodeCache cache;
    private static Diagnostics diagnostics;
    private static Map<String, PosePartIndex> byModel;
    private static Map<String, Set<String>> bonesByModel;

    @BeforeAll
    static void resolve() {
        cache = ClassNodeCache.open(ClientAcquisition.downloadJarToCache(ClientOptions.defaults()));
        diagnostics = Diagnostics.root("pose", Diagnostics.Output.NONE, null);
        bonesByModel = meshBones();
        byModel = new TreeMap<>();
        for (String model : bonesByModel.keySet())
            byModel.put(model, PosePartIndex.of(cache, model, diagnostics));
    }

    @AfterAll
    static void close() {
        if (cache != null) cache.close();
    }

    @Test
    @DisplayName("every bone a part field names is a bone the model's mesh declares")
    void everyResolvedBoneExists() {
        List<String> dangling = new ArrayList<>();
        for (Map.Entry<String, PosePartIndex> entry : byModel.entrySet()) {
            Set<String> mesh = bonesByModel.get(entry.getKey());
            String simple = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
            for (Map.Entry<String, String> scalar : entry.getValue().scalarBones().entrySet())
                if (!mesh.contains(scalar.getValue()))
                    dangling.add(simple + "." + scalar.getKey() + " -> " + scalar.getValue());
            for (Map.Entry<String, List<String>> array : entry.getValue().arrayBones().entrySet())
                for (String bone : array.getValue())
                    if (!mesh.contains(bone)) dangling.add(simple + "." + array.getKey() + "[] -> " + bone);
        }
        // The piglin is vanilla's own, not a walk failure. Its mesh clears the hat child that
        // HumanoidModel's constructor still caches a field for, so the field names a bone the mesh
        // does not declare - which is exactly why a pose has to be joined against the mesh rather
        // than trusted from the field map. Thirty-four other meshes do declare a hat.
        assertEquals(List.of("AdultPiglinModel.hat -> hat"), dangling,
            "part fields naming a bone no mesh of that model declares");
    }

    @Test
    @DisplayName("the array-valued part fields resolve to the count their constructor allocates")
    void arraysEnumerateEveryIndex() {
        // Measured from each constructor's anewarray. These are the models whose pose is written in
        // a loop, so an array short by one index is a limb that never moves. Silverfish carries two
        // arrays, which is why the count is per model rather than per field.
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("BlazeModel", 12);
        expected.put("BabySquidModel", 8);
        expected.put("EndermiteModel", 4);
        expected.put("EquineSaddleModel", 2);
        expected.put("GhastModel", 9);
        expected.put("GuardianModel", 15);
        expected.put("MagmaCubeModel", 8);
        expected.put("SilverfishModel", 10);
        expected.put("SquidModel", 8);

        Map<String, Integer> found = new LinkedHashMap<>();
        for (Map.Entry<String, PosePartIndex> entry : byModel.entrySet()) {
            String simple = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
            if (!expected.containsKey(simple)) continue;
            int total = entry.getValue().arrayBones().values().stream().mapToInt(List::size).sum();
            found.put(simple, total);
        }
        assertEquals(expected, found, "array part counts");
    }

    @Test
    @DisplayName("an indexed part name is the concatenation vanilla builds, not a guess")
    void indexedNamesAreTheVanillaSpelling() {
        PosePartIndex ghast = byModel.get("net/minecraft/client/model/monster/ghast/GhastModel");
        assertEquals("tentacle0", ghast.boneOf("tentacles", 0));
        assertEquals("tentacle8", ghast.boneOf("tentacles", 8));
        assertEquals(null, ghast.boneOf("tentacles", 9), "an index past the allocation resolves to nothing");
        assertEquals(null, ghast.boneOf("tentacles"), "an array field is not a scalar part");
    }

    @Test
    @DisplayName("nothing in the roster allocates parts this walk cannot name")
    void nothingUnresolved() {
        assertEquals(0, diagnostics.count(Diagnostics.Severity.WARN),
            () -> "unresolved part arrays: " + diagnostics.entries());
        assertEquals(0, diagnostics.count(Diagnostics.Severity.ERROR),
            () -> "errors: " + diagnostics.entries());
    }

    @Test
    @DisplayName("the scalar map covers the ordinary models too")
    void scalarFieldsResolve() {
        PosePartIndex humanoid = byModel.get("net/minecraft/client/model/HumanoidModel");
        assertTrue(humanoid.scalarBones().size() >= 6,
            () -> "expected the humanoid's own part fields, got " + humanoid.scalarBones());
        assertEquals("head", humanoid.boneOf("head"));
        assertEquals("right_arm", humanoid.boneOf("rightArm"), "a camelCase field resolves to its snake_case bone");
    }

    // ------------------------------------------------------------------------------------

    /** Every model class the geometry table names, with the union of the bones its meshes declare. */
    private static @NotNull Map<String, Set<String>> meshBones() {
        JsonObject geometries = GSON.fromJson(read(SHIPPED_GEOMETRY), JsonElement.class)
            .getAsJsonObject().getAsJsonObject("geometries");
        Map<String, Set<String>> out = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : geometries.entrySet()) {
            JsonObject mesh = entry.getValue().getAsJsonObject();
            String owner = mesh.getAsJsonObject("source").get("class").getAsString();
            Set<String> bones = out.computeIfAbsent(owner, key -> new LinkedHashSet<>());
            mesh.getAsJsonObject("bones").keySet().forEach(bones::add);
        }
        return out;
    }

    private static @NotNull Reader read(@NotNull Path path) {
        try {
            return Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

}
