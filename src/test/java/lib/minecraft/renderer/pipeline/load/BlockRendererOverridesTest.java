package lib.minecraft.renderer.pipeline.load;

import lib.minecraft.renderer.pipeline.loader.BlockDefaultsLoader;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.pipeline.pack.Capability;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the block-entity geometry override channel: {@link BlockRendererOverrides}
 * gathering pack-root {@code renderer/*.json} files through the format-2 envelope with per-entry
 * later-wins, and {@link BlockModelLoader} / {@link BlockDefaultsLoader} overlaying them onto the
 * classpath snapshot. Uses {@code minecraft:conduit} (a clean single-block block entity) as the
 * override target.
 */
@DisplayName("BlockRendererOverrides gather + per-entry merge")
class BlockRendererOverridesTest {

    private static final Gson GSON = GsonSettings.defaults().create();
    private static final Diagnostics NONE = Diagnostics.root("test", Diagnostics.Output.NONE, null);
    private static final Path BUNDLED_MODELS = Path.of("src/main/resources/lib/minecraft/renderer/block_models.json");

    @TempDir
    Path tmp;

    @Test
    @DisplayName("gather merges per top-level entry with the higher pack winning")
    void gatherMergesPerEntryHigherWins() throws IOException {
        JsonObject lowModels = new JsonObject();
        lowModels.add("only_low", mark("low"));
        lowModels.add("shared", mark("low"));
        JsonObject highModels = new JsonObject();
        highModels.add("shared", mark("high"));
        highModels.add("only_high", mark("high"));

        Path low = writePack("low", "renderer/block_models.json", envelope("models", lowModels));
        Path high = writePack("high", "renderer/block_models.json", envelope("models", highModels));

        BlockRendererOverrides overrides = BlockRendererOverrides.gather(stack(low, high), NONE);
        assertThat(overrides.models().has("only_low"), is(true));
        assertThat(overrides.models().has("only_high"), is(true));
        assertThat("higher pack wins per entry",
            overrides.models().get("shared").getString("mark"), is("high"));
    }

    @Test
    @DisplayName("a renderer/block_models.json entry swaps the block-entity geometry through the reader")
    void readerAppliesModelEntrySwap() throws IOException {
        BlockModelLoader.LoadResult base = BlockModelLoader.load(NONE);
        String baseTexture = base.models().get("minecraft:conduit").textureId();

        JsonObject conduit = bundledModelEntry("minecraft:conduit").deepCopy();
        conduit.getAsJsonArray("blocks").get(0).getAsJsonObject()
            .addProperty("texture", "minecraft:textures/entity/conduit/overridden.png");
        JsonObject models = new JsonObject();
        models.add("minecraft:conduit", conduit);

        Path pack = writePack("swap", "renderer/block_models.json", envelope("models", models));
        BlockModelLoader.LoadResult loaded = BlockModelLoader.load(NONE, BlockRendererOverrides.gather(stack(pack), NONE));

        assertThat(loaded.models().get("minecraft:conduit").textureId(), is("minecraft:entity/conduit/overridden"));
        assertThat(loaded.models().get("minecraft:conduit").textureId(), is(not(baseTexture)));
    }

    @Test
    @DisplayName("a format-mismatched envelope rejects loudly with pack attribution")
    void formatMismatchRejectsLoudly() throws IOException {
        JsonObject bad = envelope("models", new JsonObject());
        bad.addProperty("format", 3);
        Path pack = writePack("bad", "renderer/block_models.json", bad);

        PipelineException ex = assertThrows(PipelineException.class, () -> BlockRendererOverrides.gather(stack(pack), NONE));
        assertThat(ex.getMessage().contains("bad"), is(true));
        assertThat(ex.getMessage().contains("renderer/block_models.json"), is(true));
    }

    @Test
    @DisplayName("a valid envelope missing its named sub-object is ignored, not fatal")
    void missingSubObjectIgnored() throws IOException {
        Path pack = writePack("empty", "renderer/block_models.json", envelope("//", null));
        BlockRendererOverrides overrides = BlockRendererOverrides.gather(stack(pack), NONE);
        assertThat(overrides.models().size() == 0, is(true));
        assertThat(overrides.isEmpty(), is(true));
    }

    @Test
    @DisplayName("a renderer/block_defaults.json entry overrides a default state key")
    void defaultsOverrideChangesStateKey() throws IOException {
        JsonObject blocks = new JsonObject();
        JsonObject state = new JsonObject();
        state.addProperty("facing", "east");
        blocks.add("minecraft:conduit", state);
        Path pack = writePack("defaults", "renderer/block_defaults.json", envelope("blocks", blocks));

        var defaults = BlockDefaultsLoader.load(NONE, BlockRendererOverrides.gather(stack(pack), NONE));
        assertThat(defaults.get("minecraft:conduit"), is("facing=east"));
    }

    @Test
    @DisplayName("a structurally-broken model override (no geometry) fails with a clear model-id message, not a bare NPE")
    void missingGeometryRejectsClearly() {
        JsonObject entry = new JsonObject();
        com.google.gson.JsonArray blocks = new com.google.gson.JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("block", "minecraft:foo");
        block.addProperty("texture", "minecraft:textures/x.png");
        blocks.add(block);
        entry.add("blocks", blocks); // deliberately no "geometry" key
        JsonObject models = new JsonObject();
        models.add("minecraft:foo", entry);

        var overrides = new BlockRendererOverrides(JsonNode.wrap(models), JsonNode.object(), JsonNode.object());
        lib.minecraft.renderer.exception.PipelineException ex = org.junit.jupiter.api.Assertions.assertThrows(
            lib.minecraft.renderer.exception.PipelineException.class, () -> BlockModelLoader.load(NONE, overrides));
        assertThat(ex.getMessage().contains("minecraft:foo"), is(true));
        assertThat(ex.getMessage().toLowerCase().contains("geometry"), is(true));
    }

    @Test
    @DisplayName("a non-object block_defaults override entry is warned + ignored, not silently dropped")
    void nonObjectDefaultsEntryWarned() {
        JsonObject blocks = new JsonObject();
        blocks.addProperty("minecraft:conduit", "facing=east"); // flat string, not {property:value}
        var overrides = new BlockRendererOverrides(JsonNode.object(), JsonNode.object(), JsonNode.wrap(blocks));

        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.err;
        dev.simplified.collection.ConcurrentMap<String, String> defaults;
        try {
            System.setErr(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
            defaults = BlockDefaultsLoader.load(NONE, overrides);
        } finally {
            System.setErr(original);
        }
        assertThat(buffer.toString(java.nio.charset.StandardCharsets.UTF_8).contains("minecraft:conduit"), is(true));
        // The classpath default is untouched (the malformed override was ignored, not applied).
        assertThat(defaults.get("minecraft:conduit"), is(not("facing=east")));
    }

    private static JsonObject mark(String value) {
        JsonObject object = new JsonObject();
        object.addProperty("mark", value);
        return object;
    }

    /** Wraps a sub-object in the internal format-2 envelope; a null sub-object omits the key. */
    private static JsonObject envelope(String key, JsonObject sub) {
        JsonObject root = new JsonObject();
        root.addProperty("format", 2);
        root.addProperty("source_version", "26.1");
        if (sub != null) root.add(key, sub);
        return root;
    }

    private static JsonObject bundledModelEntry(String modelId) throws IOException {
        JsonObject root = GSON.fromJson(Files.readString(BUNDLED_MODELS), JsonObject.class);
        return root.getAsJsonObject("models").getAsJsonObject(modelId);
    }

    private Path writePack(String id, String relativePath, JsonObject content) throws IOException {
        Path root = tmp.resolve(id);
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(content));
        return root;
    }

    /** Builds a stack led by the vanilla pack; each user pack's id is its directory basename. */
    private PackStack stack(Path... userPackRoots) {
        java.util.List<ResourcePack> packs = new java.util.ArrayList<>();
        packs.add(pack(PackId.VANILLA, tmp.resolve("vanilla")));
        for (Path root : userPackRoots)
            packs.add(pack(new PackId(root.getFileName().toString()), root));
        return PackStack.of(Concurrent.adoptList(packs).toUnmodifiable());
    }

    private static ResourcePack pack(PackId id, Path root) {
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE));
    }

}
