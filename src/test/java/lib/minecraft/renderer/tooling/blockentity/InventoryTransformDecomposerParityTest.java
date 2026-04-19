package lib.minecraft.renderer.tooling.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.tooling.ToolingBlockEntities;
import org.jetbrains.annotations.NotNull;
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
import static org.hamcrest.Matchers.*;

/**
 * Slow parity test: runs {@link InventoryTransformDecomposer#decomposeAll} against the cached
 * 26.1 client jar and asserts the bytecode-extracted tuples match
 * {@code baseline/inventory_transforms.json} for the 15 entities whose renderer transform
 * fully encodes their inventory pose.
 *
 * <p>The 16th entity, {@code minecraft:skull_dragon_head}, is excluded from the bytecode
 * parity check because its distinguishing {@code tz=1.25} comes from the
 * {@code DragonHeadModel} geometry rather than the renderer's
 * {@code createGroundTransformation}. The shared {@code SkullBlockRenderer} factory
 * produces {@code [8, 0, 8, 180, 0, 0]} for every skull variant; the dragon's
 * {@code tz=1.25} override is carried by {@code block_entities_overrides.json} and merged at
 * generation time by {@link ToolingBlockEntities}.
 */
@DisplayName("InventoryTransformDecomposer parity")
@Tag("slow")
class InventoryTransformDecomposerParityTest {

    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");
    private static final Path BASELINE = Path.of("src/test/resources/lib/minecraft/renderer/baseline/inventory_transforms.json");

    /**
     * Entity id to renderer internal name. The decomposer's policy table names the factory
     * method for each renderer - this parity map names the renderer class per entity id. Must
     * stay in sync with {@link ToolingBlockEntities}'s
     * {@code mapEntityIdToRenderer}.
     */
    private static @NotNull Map<String, String> entityIdToRenderer() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("minecraft:bed_head", "net/minecraft/client/renderer/blockentity/BedRenderer");
        m.put("minecraft:bed_foot", "net/minecraft/client/renderer/blockentity/BedRenderer");
        m.put("minecraft:shulker_box", "net/minecraft/client/renderer/blockentity/ShulkerBoxRenderer");
        m.put("minecraft:skull_head", "net/minecraft/client/renderer/blockentity/SkullBlockRenderer");
        m.put("minecraft:skull_humanoid_head", "net/minecraft/client/renderer/blockentity/SkullBlockRenderer");
        m.put("minecraft:skull_piglin_head", "net/minecraft/client/renderer/blockentity/SkullBlockRenderer");
        m.put("minecraft:skull_dragon_head", "net/minecraft/client/renderer/blockentity/SkullBlockRenderer");
        m.put("minecraft:decorated_pot", "net/minecraft/client/renderer/blockentity/DecoratedPotRenderer");
        m.put("minecraft:decorated_pot_sides", "net/minecraft/client/renderer/blockentity/DecoratedPotRenderer");
        m.put("minecraft:conduit", "net/minecraft/client/renderer/blockentity/ConduitRenderer");
        m.put("minecraft:sign", "net/minecraft/client/renderer/blockentity/StandingSignRenderer");
        m.put("minecraft:hanging_sign", "net/minecraft/client/renderer/blockentity/HangingSignRenderer");
        m.put("minecraft:banner", "net/minecraft/client/renderer/blockentity/BannerRenderer");
        m.put("minecraft:banner_flag", "net/minecraft/client/renderer/blockentity/BannerRenderer");
        m.put("minecraft:wall_banner", "net/minecraft/client/renderer/blockentity/BannerRenderer");
        m.put("minecraft:wall_banner_flag", "net/minecraft/client/renderer/blockentity/BannerRenderer");
        return m;
    }

    @Test
    @DisplayName("decomposer output matches baseline/inventory_transforms.json for 15/16 entities")
    void parity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            Diagnostics diag = new Diagnostics();
            Map<String, float[]> actual = InventoryTransformDecomposer.decomposeAll(zip, entityIdToRenderer(), diag);
            JsonObject expectedJson = new Gson().fromJson(Files.readString(BASELINE), JsonObject.class);

            int decomposed = 0;
            for (Map.Entry<String, String> e : entityIdToRenderer().entrySet()) {
                String id = e.getKey();
                float[] tuple = actual.get(id);
                if ("minecraft:skull_dragon_head".equals(id)) {
                    // Dragon head: decomposer must produce the shared skull tuple
                    // [8, 0, 8, 180, 0, 0]; the baseline's tz=1.25 is supplied by
                    // block_entities_overrides.json at generation time.
                    assertThat("skull_dragon_head: decomposer returns shared skull shape", tuple, notNullValue());
                    assertTuple(id, tuple, new float[]{ 8f, 0f, 8f, 180f, 0f, 0f });
                    decomposed++;
                    continue;
                }
                JsonArray expectedArr = expectedJson.has(id) ? expectedJson.getAsJsonArray(id) : null;
                assertThat("baseline entry present for " + id, expectedArr, notNullValue());
                assertThat("decomposer produced tuple for " + id, tuple, notNullValue());
                float[] expected = new float[expectedArr.size()];
                for (int i = 0; i < expected.length; i++) expected[i] = expectedArr.get(i).getAsFloat();
                assertTuple(id, tuple, expected);
                decomposed++;
            }
            assertThat("all 16 policy entries produced a tuple (dragon=shared, 15 others exact)", decomposed, equalTo(16));
        }
    }

    @Test
    @DisplayName("decomposer output has no spurious entries beyond policy coverage")
    void noSpuriousEntries() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            Diagnostics diag = new Diagnostics();
            Map<String, float[]> actual = InventoryTransformDecomposer.decomposeAll(zip, entityIdToRenderer(), diag);
            // The decomposer must only emit ids it was asked to resolve; it never invents new ones.
            assertThat(actual.keySet(), is(entityIdToRenderer().keySet()));
        }
    }

    private static void assertTuple(@NotNull String id, float @NotNull [] actual, float @NotNull [] expected) {
        assertThat("tuple length for " + id, actual.length, equalTo(expected.length));
        for (int i = 0; i < expected.length; i++) {
            float diff = actual[i] - expected[i];
            assertThat("tuple[" + i + "] for " + id + " actual=" + actual[i] + " expected=" + expected[i],
                (double) Math.abs(diff), lessThan(1e-3));
        }
    }
}
