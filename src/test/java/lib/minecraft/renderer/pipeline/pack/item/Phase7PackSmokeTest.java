package lib.minecraft.renderer.pipeline.pack.item;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.load.block.BlockEntityShadowDiagnostics;
import lib.minecraft.renderer.pipeline.load.block.BlockRendererOverrides;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.pipeline.pack.PackAcquisition;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end smoke test proving two pack-content-gated features work through the acquisition
 * stack: the {@code renderer/*.json} block-entity override channel and the legacy
 * {@code overrides} tolerate-and-map. A synthesised fixture pack ships a conduit-geometry override, a
 * shadowed {@code chest} model, and a legacy {@code custom_model_data} override; the test acquires it
 * over the cached vanilla pack and asserts each feature resolves - the legacy tree rendering the
 * override frame under a caller-supplied {@code customModelData} (the resolution proof). Tagged slow
 * because it reads the gitignored vanilla cache; skips when absent.
 */
@Tag("slow")
@DisplayName("Phase 7 override-channel + legacy-mapper pack smoke")
class Phase7PackSmokeTest {

    private static final Gson GSON = GsonSettings.defaults().create();
    private static final Diagnostics NONE = Diagnostics.root("test", Diagnostics.Output.NONE, null);
    private static final Path VANILLA = Path.of("cache/asset-renderer/vanilla/26.1");
    private static final Path BUNDLED_MODELS = Path.of("src/main/resources/lib/minecraft/renderer/block_models.json");

    @Test
    @DisplayName("a fixture pack drives the override channel + legacy mapper through the acquisition stack")
    void fixturePackExercisesBothFeatures(@TempDir Path work) throws IOException {
        assumeTrue(Files.isDirectory(VANILLA), () -> "vanilla pack not extracted: " + VANILLA);

        Path fixture = work.resolve("phase7fixture");
        writeFixture(fixture);

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(fixture.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, VANILLA));

        // --- Override channel: conduit geometry entry swaps its texture through the reader. ---
        BlockModelLoader.LoadResult be = BlockModelLoader.load(stack);
        assertThat("conduit texture overridden via renderer/block_models.json",
            be.models().get("minecraft:conduit").textureId(), is("minecraft:entity/conduit/phase7"));

        // --- BE shadowed-model diagnostic: the fixture's chest.json is named + shadowed. ---
        Set<String> beIds = new HashSet<>(be.models().keySet());
        beIds.addAll(be.variants().keySet());
        String shadowErr = captureShadowReport(stack, beIds);
        assertThat(shadowErr, containsString("phase7fixture"));
        assertThat(shadowErr, containsString("minecraft:chest"));

        // --- Legacy mapper: the diamond_sword overrides map to a tree that dispatches on the caller value. ---
        ConcurrentMap<String, ItemModelTree> trees = ItemModelTreeLoader.load(stack);
        ItemModelTree sword = trees.get("minecraft:diamond_sword");
        assertThat("legacy overrides synthesised an item tree", sword, notNullValue());

        assertThat("neutral context renders the base model",
            ItemModelWalker.resolve(sword, ItemModelContext.gui()).modelId().orElse("<none>"),
            is("minecraft:item/diamond_sword"));
        ItemModelContext cmd1 = new ItemModelContext("gui", false, false, null, null, 0f, 0f, 1f, null);
        assertThat("custom_model_data=1 renders the override frame",
            ItemModelWalker.resolve(sword, cmd1).modelId().orElse("<none>"),
            is("minecraft:item/diamond_sword_cmd1"));
    }

    private void writeFixture(Path fixture) throws IOException {
        write(fixture.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":15,\"description\":\"phase 7 fixture\"}}");

        // renderer/block_models.json: reuse the real conduit entry, swap its bound texture.
        JsonObject conduit = GSON.fromJson(Files.readString(BUNDLED_MODELS), JsonObject.class)
            .getAsJsonObject("models").getAsJsonObject("minecraft:conduit").deepCopy();
        conduit.getAsJsonArray("blocks").get(0).getAsJsonObject()
            .addProperty("texture", "minecraft:textures/entity/conduit/phase7.png");
        JsonObject models = new JsonObject();
        models.add("minecraft:conduit", conduit);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("format", 2);
        envelope.addProperty("source_version", "26.1");
        envelope.add("models", models);
        write(fixture.resolve("renderer/block_models.json"), GSON.toJson(envelope));

        // A shadowed vanilla-form chest model (block-entity id) -> shadowed-model diagnostic.
        write(fixture.resolve("assets/minecraft/models/block/chest.json"),
            "{\"parent\":\"block/block\"}");

        // Legacy models/item overrides (custom_model_data CIT idiom).
        write(fixture.resolve("assets/minecraft/models/item/diamond_sword.json"),
            "{\"parent\":\"item/handheld\",\"textures\":{\"layer0\":\"item/diamond_sword\"},"
                + "\"overrides\":[{\"predicate\":{\"custom_model_data\":1},\"model\":\"item/diamond_sword_cmd1\"}]}");
        write(fixture.resolve("assets/minecraft/models/item/diamond_sword_cmd1.json"),
            "{\"parent\":\"item/handheld\",\"textures\":{\"layer0\":\"item/diamond_sword_cmd1\"}}");
    }

    private static String captureShadowReport(PackStack stack, Set<String> beIds) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            BlockEntityShadowDiagnostics.report(stack, beIds);
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
