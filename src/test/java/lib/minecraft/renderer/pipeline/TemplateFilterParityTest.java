package lib.minecraft.renderer.pipeline;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.pipeline.load.BlockRendererOverrides;
import lib.minecraft.renderer.pipeline.loader.BlockDefaultsLoader;
import lib.minecraft.renderer.pipeline.loader.BlockItemsLoader;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.loader.GlintItemsLoader;
import lib.minecraft.renderer.pipeline.pack.BlockStateLoader;
import lib.minecraft.renderer.pipeline.pack.BlockTagLoader;
import lib.minecraft.renderer.pipeline.pack.PackAcquisition;
import lib.minecraft.renderer.pipeline.pack.ResolvedModels;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelTreeLoader;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Parity gate for the structural empty-model filter that replaced the hardcoded
 * {@code TEMPLATE_BLOCK_NAMES} / {@code TEMPLATE_ITEM_NAMES} sets. Runs the real 26.1 pipeline and
 * asserts the filter keeps every model that actually renders a tile while dropping only the
 * parent / template models that render nothing.
 * <p>
 * The load-bearing checks are the <b>kept</b> assertions: every concrete variant render - slab
 * tops, door halves, stair corners, the multipart submodels, and every armor-trim /
 * clock / compass / light frame - must survive, because those were all in the stress-tested
 * reference atlas. Only pure inheritance parents ({@code generated}, {@code handheld},
 * {@code cross}, {@code slab}, {@code block}) and intentionally-invisible ids drop out.
 * <p>
 * Tagged {@code slow}: needs a real {@link ClientAssets}. Run with
 * {@code ./gradlew :asset-renderer:slowTest --tests "*TemplateFilterParityTest"}.
 */
@Tag("slow")
@DisplayName("Structural empty-model filter parity")
class TemplateFilterParityTest {

    /** Shared cache root with {@code PipelineIntegrationTest} so both slow tests reuse one extracted jar. */
    private static final File CACHE_ROOT = new File("cache/it");

    /** Block-entity model + variant maps the index loaders consume. */
    private static BlockModelLoader.LoadResult be;

    // The explicit block-index loader inputs (the same set of() computes over the stack).
    private static ConcurrentMap<String, ModelData> blockModels;
    private static ConcurrentMap<String, Block.Tint> blockTints;
    private static ConcurrentMap<String, String> itemDefinitions;
    private static ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> blockVariants;
    private static ConcurrentMap<String, Block.Multipart> blockMultiparts;
    private static ConcurrentMap<String, BlockTag> blockTags;
    private static ConcurrentMap<String, String> blockDefaultStateKeys;
    private static ConcurrentMap<String, String> blockItemAliases;

    // The explicit item-index loader inputs.
    private static ConcurrentMap<String, List<LayerTint>> itemTints;
    private static ConcurrentSet<String> glintItems;
    private static ConcurrentMap<String, ModelData> itemModels;
    private static ConcurrentMap<String, ItemModelTree> itemTrees;

    /** Runs the real 26.1 pipeline and computes every index-loader input once for both filter tests. */
    @BeforeAll
    static void setup() {
        ClientAssets assets = ClientAcquisition.acquire(ClientOptions.builder().version("26.1").cacheRoot(CACHE_ROOT).build());
        PackStack stack = PackAcquisition.acquire(assets);

        ResolvedModels models = ResolvedModels.load(stack);
        BlockStateLoader.BlockStates blockStates = BlockStateLoader.load(stack, models.blocks());
        blockModels = models.blocks();
        itemModels = models.items();
        blockVariants = blockStates.variants();
        blockMultiparts = blockStates.multiparts();

        blockTints = BlockTintsLoader.load();
        itemTrees = ItemModelTreeLoader.load(stack);
        itemDefinitions = ItemModelTreeLoader.deriveBlockItemModels(itemTrees);
        itemTints = ItemModelTreeLoader.deriveTints(itemTrees);
        glintItems = GlintItemsLoader.load();
        blockTags = BlockTagLoader.load(stack);

        Diagnostics defaultsDiag = Diagnostics.root("blockDefaults", Diagnostics.Output.NONE, null);
        blockDefaultStateKeys = BlockDefaultsLoader.load(defaultsDiag, BlockRendererOverrides.gather(stack, defaultsDiag));
        blockItemAliases = BlockItemsLoader.load(Diagnostics.root("blockItems", Diagnostics.Output.NONE, null));

        be = BlockModelLoader.load();
    }

    @Test
    @DisplayName("block filter keeps renderable variants, drops only empty templates")
    void blockFilter() {
        Set<String> built = new HashSet<>(BlockIndexBuilder.buildUnfiltered(
            blockModels, blockTints, itemDefinitions, blockVariants, blockMultiparts,
            blockTags, blockDefaultStateKeys, blockItemAliases, be.models(), be.variants(),
            itemTrees, itemModels).keySet());
        Set<String> kept = new HashSet<>(BlockIndexBuilder.load(
            blockModels, blockTints, itemDefinitions, blockVariants, blockMultiparts,
            blockTags, blockDefaultStateKeys, blockItemAliases, be.models(), be.variants(),
            itemTrees, itemModels).keySet());
        System.out.printf("[block] built=%d kept=%d dropped=%d%n", built.size(), kept.size(), built.size() - kept.size());

        // Concrete variant renders - real geometry + resolvable texture - must survive.
        for (String id : new String[]{
            "minecraft:stone", "minecraft:oak_stairs", "minecraft:yellow_carpet",
            "minecraft:acacia_slab_top", "minecraft:acacia_door_bottom_left", "minecraft:acacia_stairs_inner",
            "minecraft:tripwire_n", "minecraft:melon_stem_stage0"
        }) assertThat(id + " kept", kept.contains(id), is(true));

        // Pure parent / empty templates (only unresolved #variable textures) drop, plus invisibles.
        for (String id : new String[]{
            "minecraft:cross", "minecraft:slab", "minecraft:stairs", "minecraft:block", "minecraft:leaves",
            "minecraft:air", "minecraft:barrier"
        }) assertThat(id + " dropped", kept.contains(id), is(false));
    }

    @Test
    @DisplayName("item filter keeps renderable variants, drops only empty templates")
    void itemFilter() {
        Set<String> kept = new HashSet<>(ItemIndexBuilder.load(itemTints, glintItems, itemModels, itemTrees, be.models()).keySet());
        System.out.printf("[item] kept=%d%n", kept.size());

        // Every range / trim / sprite variant renders, so it stays.
        for (String id : new String[]{
            "minecraft:diamond_sword", "minecraft:apple",
            "minecraft:clock_00", "minecraft:clock_63", "minecraft:compass_16", "minecraft:recovery_compass_00",
            "minecraft:light_05", "minecraft:chainmail_helmet_diamond_trim", "minecraft:turtle_helmet_amethyst_trim"
        }) assertThat(id + " kept", kept.contains(id), is(true));

        // Pure parent templates (no layerN sprite, no elements) and invisible air drop.
        for (String id : new String[]{
            "minecraft:generated", "minecraft:handheld", "minecraft:air"
        }) assertThat(id + " dropped", kept.contains(id), is(false));
    }

}
