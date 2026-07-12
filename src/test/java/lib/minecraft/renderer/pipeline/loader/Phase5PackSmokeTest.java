package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.pipeline.pack.IndexedTexture;
import lib.minecraft.renderer.pipeline.pack.PackAcquisition;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.resolver.ModelResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Smoke test proving the phase-5 multi-namespace loaders reach the on-disk hypixel-skyblock pack's
 * {@code hypixel_skyblock} namespace - its ~1171 item models index by qualified id and its ~985
 * item-definition files are on the scanned path. No render assertions: the full item-definition
 * trees stay unconsumed until the item-tree walker lands in a later phase, so the block-item
 * projection correctly yields zero hypixel entries (they are item models, not block models). Tagged
 * slow because it reads the gitignored pack cache; skips gracefully when absent.
 */
@Tag("slow")
@DisplayName("Phase 5 multi-namespace pack smoke (hypixel-skyblock)")
class Phase5PackSmokeTest {

    private static final Path VANILLA = Path.of("cache/asset-renderer/vanilla/26.1");
    private static final Path PACKS = Path.of("cache/asset-renderer/packs");
    private static final String NS = "hypixel_skyblock";

    @Test
    @DisplayName("hypixel-skyblock item models + item-defs index under the hypixel_skyblock namespace")
    void hypixelNamespaceIndexes(@TempDir Path cache) throws IOException {
        assumeTrue(Files.isDirectory(VANILLA), () -> "vanilla pack not extracted: " + VANILLA);
        Path hypixel = PACKS.resolve("hypixel-skyblock");
        assumeTrue(Files.isDirectory(hypixel), () -> "hypixel-skyblock pack not present under " + PACKS);

        PackStack bare = PackAcquisition.acquire(List.of(hypixel), cache, VANILLA);
        ConcurrentMap<ResourceId, IndexedTexture> textures = TextureIndexer.index(bare);
        PackStack stack = bare.withTextureIndex(textures);

        assertThat(stack.namespaces().contains(NS), is(true));

        // 1171 item models (all under hypixel_skyblock/models/item/) index by qualified id.
        ConcurrentMap<String, ModelData> itemModels = ModelResolver.loadItemModels(stack);
        long hypixelModels = itemModels.keySet().stream().filter(id -> id.startsWith(NS + ":")).count();
        assertThat("hypixel_skyblock item models indexed", hypixelModels, greaterThan(1000L));
        assertThat(itemModels.containsKey(NS + ":item/abiphones/abiphone_basic"), is(true));

        // 985 item-definition files sit on the scanned path (the item-tree walker consumes them later).
        Path itemsDir = hypixel.resolve("assets/" + NS + "/items");
        assertThat("hypixel_skyblock item-def files present", countJson(itemsDir), greaterThan(900L));

        // The phase-5 block-item projection is item-model-only for this pack -> zero block items.
        ConcurrentMap<String, String> blockItemDefs = ItemDefinitionLoader.load(stack);
        long hypixelBlockItems = blockItemDefs.keySet().stream().filter(id -> id.startsWith(NS + ":")).count();
        assertThat("hypixel ships no block items (all item-model refs)", hypixelBlockItems, is(0L));
    }

    private static long countJson(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).count();
        }
    }

}
