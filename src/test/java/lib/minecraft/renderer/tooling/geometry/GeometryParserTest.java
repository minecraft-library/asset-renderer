package lib.minecraft.renderer.tooling.geometry;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exact-float value parity for the tooling {@link GeometryParser}: a plain factory (wolf) and a
 * gnarly one (ghast - MeshTransformer 4.5, seeded RandomSource tentacles, string-concat bone
 * names) must value-match the checked-in {@code entity_geometry.json} entries with floats EXACT -
 * any ULP delta is a different computation path, a finding, never noise (the match-vanilla
 * rule). A live parse must still reproduce the committed bytes exactly (a regen-drift guard).
 *
 * <p>Tagged {@code slow}: the parse runs against the real client jar, which is downloaded and
 * ASM-opened here.
 */
@Tag("slow")
@DisplayName("tooling GeometryParser exact-float value parity vs checked-in entries")
class GeometryParserTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private static ClassNodeCache cache;
    private static JsonObject referenceGeometries;

    @BeforeAll
    static void open() {
        cache = ClassNodeCache.open(ClientAcquisition.downloadJarToCache(ClientOptions.defaults()));
        referenceGeometries = GSON.fromJson(new InputStreamReader(
                Objects.requireNonNull(GeometryParserTest.class.getResourceAsStream(
                    "/lib/minecraft/renderer/entity_geometry.json")), StandardCharsets.UTF_8),
                JsonElement.class)
            .getAsJsonObject().getAsJsonObject("geometries");
    }

    @AfterAll
    static void close() {
        cache.close();
    }

    @Test
    @DisplayName("plain factory: AdultWolfModel#createBodyLayer matches its reference entry exactly")
    void wolfParsesExact() {
        // createBodyLayer(CubeDeformation) returns a MeshDefinition - texture dims come from
        // the LayerDefinitions.createRoots call site, carried as request overrides
        GeometryRequest request = GeometryRequest.body(
            "net/minecraft/client/model/animal/wolf/AdultWolfModel", "createBodyLayer",
            "minecraft:wolf", 64, 32, null, 1f);
        assertParsesExactly(request, "AdultWolfModel#createBodyLayer");
    }

    @Test
    @DisplayName("gnarly factory: GhastModel#createBodyLayer (MT 4.5, seeded random) matches its reference entry exactly")
    void ghastParsesExact() {
        // the 4.5 MeshTransformer is INLINE in the factory body - the walk captures it and
        // applyMeshTransformerScaling folds it; the request carries no external scale
        GeometryRequest request = GeometryRequest.body(
            "net/minecraft/client/model/monster/ghast/GhastModel", "createBodyLayer",
            "minecraft:ghast", null, null, null, 1f);
        assertParsesExactly(request, "GhastModel#createBodyLayer");
    }

    // ------------------------------------------------------------------------------------

    private static void assertParsesExactly(@NotNull GeometryRequest request, @NotNull String key) {
        JsonObject reference = referenceGeometries.getAsJsonObject(key);
        assertNotNull(reference, key + " missing from the checked-in resource");
        JsonTree parsedNode = GeometryParser.parse(cache, request,
            Diagnostics.root("geometryParserTest", Diagnostics.Output.NONE, null));
        assertNotNull(parsedNode, request.subjectId() + " parse returned null");
        JsonObject parsed = parsedNode.toGson().getAsJsonObject();

        // effective dims mirror GeometryFlow's rule: request overrides win over the parse. The
        // reference pairs the atlas dims as a texture_size[w,h] array (the parser emits scalars).
        int textureWidth = request.texWidthOverride() != null ? request.texWidthOverride() : parsed.get("textureWidth").getAsInt();
        int textureHeight = request.texHeightOverride() != null ? request.texHeightOverride() : parsed.get("textureHeight").getAsInt();
        assertEquals(reference.getAsJsonArray("texture_size").get(0).getAsInt(), textureWidth, "textureWidth");
        assertEquals(reference.getAsJsonArray("texture_size").get(1).getAsInt(), textureHeight, "textureHeight");

        JsonObject referenceBones = reference.getAsJsonObject("bones");
        JsonObject parsedBones = parsed.getAsJsonObject("bones");
        assertEquals(referenceBones.keySet(), parsedBones.keySet(), "bone name set");
        for (String bone : referenceBones.keySet()) {
            JsonObject expected = referenceBones.getAsJsonObject(bone);
            JsonObject actual = parsedBones.getAsJsonObject(bone);
            assertExactFloats(expected.getAsJsonArray("pivot"), actual.getAsJsonArray("pivot"), bone + ".pivot");
            assertExactFloats(expected.getAsJsonArray("rotation"), actual.getAsJsonArray("rotation"), bone + ".rotation");
            assertEquals(optFloat(expected, "scale", 1f), optFloat(actual, "scale", 1f), bone + ".scale");
            assertEquals(optString(expected, "parent"), optString(actual, "parent"), bone + ".parent");
            JsonArray expectedCubes = expected.getAsJsonArray("cubes");
            JsonArray actualCubes = actual.getAsJsonArray("cubes");
            assertEquals(expectedCubes.size(), actualCubes.size(), bone + ".cubes count");
            for (int i = 0; i < expectedCubes.size(); i++) {
                JsonObject expectedCube = expectedCubes.get(i).getAsJsonObject();
                JsonObject actualCube = actualCubes.get(i).getAsJsonObject();
                String at = bone + ".cubes[" + i + "]";
                assertExactFloats(expectedCube.getAsJsonArray("origin"), actualCube.getAsJsonArray("origin"), at + ".origin");
                assertExactFloats(expectedCube.getAsJsonArray("size"), actualCube.getAsJsonArray("size"), at + ".size");
                assertEquals(expectedCube.getAsJsonArray("uv").toString(), actualCube.getAsJsonArray("uv").toString(), at + ".uv");
                // the live parse must reproduce the committed grow exactly
                assertEquals(growMean(expectedCube), growMean(actualCube), at + ".grow vs reference grow");
                assertEquals(expectedCube.has("mirror") && expectedCube.get("mirror").getAsBoolean(),
                    actualCube.has("mirror") && actualCube.get("mirror").getAsBoolean(), at + ".mirror");
                assertFalse(actualCube.has("face_uv"), at + ": the emitted geometry carries no face_uv");
            }
        }
    }

    /** The cube's grow as a scalar mean: {@code (x + y + z) / 3f} for an [x,y,z] array, else the scalar (0 when absent). */
    private static float growMean(@NotNull JsonObject cube) {
        JsonElement grow = cube.get("grow");
        if (grow == null) return 0f;
        if (grow.isJsonArray()) {
            JsonArray parts = grow.getAsJsonArray();
            return (parts.get(0).getAsFloat() + parts.get(1).getAsFloat() + parts.get(2).getAsFloat()) / 3f;
        }
        return grow.getAsFloat();
    }

    private static void assertExactFloats(@NotNull JsonArray expected, @NotNull JsonArray actual, @NotNull String at) {
        assertEquals(expected.size(), actual.size(), at + " length");
        for (int i = 0; i < expected.size(); i++)
            assertEquals(Float.floatToIntBits(expected.get(i).getAsFloat()), Float.floatToIntBits(actual.get(i).getAsFloat()),
                at + "[" + i + "] expected " + expected.get(i) + " actual " + actual.get(i));
    }

    private static float optFloat(@NotNull JsonObject node, @NotNull String key, float dflt) {
        return node.has(key) ? node.get(key).getAsFloat() : dflt;
    }

    private static @Nullable String optString(@NotNull JsonObject node, @NotNull String key) {
        return node.has(key) ? node.get(key).getAsString() : null;
    }

}
