package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.pack.PackAcquisition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end coverage of the {@code renderer/*.json} block-entity override channel: a synthesised
 * fixture pack carrying a conduit-geometry override, acquired over the cached vanilla pack, reaching
 * {@link BlockModelLoader#load(PackStack)} and rebinding the shipped entry's texture. Tagged slow
 * because it reads the gitignored vanilla cache; skips when absent.
 */
@Tag("slow")
@DisplayName("block-entity override channel through the acquisition stack")
class BlockRendererOverrideChannelTest {

    private static final Gson GSON = GsonSettings.defaults().create();
    private static final Path VANILLA = Path.of("cache/asset-renderer/vanilla/26.1");
    private static final Path BUNDLED_MODELS = Path.of("src/main/resources/lib/minecraft/renderer/block_models.json");

    @Test
    @DisplayName("a fixture pack's renderer/block_models.json rebinds a shipped block-entity texture")
    void fixturePackDrivesTheOverrideChannel(@TempDir Path work) throws IOException {
        assumeTrue(Files.isDirectory(VANILLA), () -> "vanilla pack not extracted: " + VANILLA);

        Path fixture = work.resolve("overridefixture");
        writeFixture(fixture);

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(fixture.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, VANILLA));

        BlockModelLoader.LoadResult be = BlockModelLoader.load(stack);
        assertThat("conduit texture overridden via renderer/block_models.json",
            be.models().get("minecraft:conduit").textureId(), is("minecraft:entity/conduit/override"));
    }

    private void writeFixture(Path fixture) throws IOException {
        write(fixture.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":15,\"description\":\"override channel fixture\"}}");

        // renderer/block_models.json: reuse the real conduit entry, swap its bound texture.
        JsonObject conduit = GSON.fromJson(Files.readString(BUNDLED_MODELS), JsonObject.class)
            .getAsJsonObject("models").getAsJsonObject("minecraft:conduit").deepCopy();
        conduit.getAsJsonArray("blocks").get(0).getAsJsonObject()
            .addProperty("texture", "minecraft:textures/entity/conduit/override.png");
        JsonObject models = new JsonObject();
        models.add("minecraft:conduit", conduit);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("format", 2);
        envelope.addProperty("source_version", "26.1");
        envelope.add("models", models);
        write(fixture.resolve("renderer/block_models.json"), GSON.toJson(envelope));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
