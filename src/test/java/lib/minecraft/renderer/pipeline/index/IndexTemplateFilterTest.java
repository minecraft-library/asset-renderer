package lib.minecraft.renderer.pipeline.index;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.pipeline.index.BlockIndexBuilder.BlockTables;
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
import lib.minecraft.renderer.pipeline.util.BlockRendererOverrides;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Membership coverage of the structural empty-model filter, over the real vanilla corpus. The filter
 * decides by structure rather than by name - an id whose model resolves neither geometry nor a
 * texture drops, and so does an intentionally-invisible id - and this asserts it keeps every model
 * that actually renders a tile while dropping only the parent / template models that render nothing.
 * <p>
 * The load-bearing checks are the <b>kept</b> assertions: every concrete variant render - slab
 * tops, door halves, stair corners, the multipart submodels, and every armor-trim /
 * clock / compass / light frame - must survive, because each of those is a tile some render draws.
 * Only pure inheritance parents ({@code generated}, {@code handheld}, {@code cross}, {@code slab},
 * {@code block}) and intentionally-invisible ids drop out.
 * <p>
 * Tagged {@code slow}: needs a real {@link ClientAssets}. Run with
 * {@code ./gradlew slowTest --tests "*IndexTemplateFilterTest"}.
 */
@Tag("slow")
@DisplayName("Structural empty-model filter parity")
@ExtendWith(ClientAssetsExtension.class)
class IndexTemplateFilterTest {

    /** Block-entity model + variant maps the index loaders consume. */
    private static BlockModelLoader.LoadResult be;

    // The explicit block-index loader inputs (the same set of() computes over the stack).
    private static PackStack stack;
    private static BlockTables blockTables;
    private static BlockStateLoader.BlockStates blockStates;
    private static ConcurrentMap<String, BlockTag> blockTags;

    // The explicit item-index loader inputs.
    private static ConcurrentMap<String, List<LayerTint>> itemTints;
    private static Set<String> glintItems;
    private static ConcurrentMap<String, ModelData> itemModels;
    private static ConcurrentMap<String, ItemModelTree> itemTrees;

    /** Runs the real pipeline and computes every index-loader input once for both filter tests. */
    @BeforeAll
    static void setup() {
        stack = PackAcquisition.acquire(ClientAssetsExtension.assets());

        ResolvedModels models = ResolvedModels.load(stack);
        blockStates = BlockStateLoader.load(stack);
        itemModels = models.items();

        Map<String, Block.Tint> blockTints = BlockTintsLoader.load();
        itemTrees = ItemModelTreeLoader.load(stack);
        ConcurrentMap<String, String> itemDefinitions = ItemModelTreeLoader.deriveBlockItemModels(itemTrees);
        itemTints = ItemModelTreeLoader.deriveTints(itemTrees);
        glintItems = GlintItemsLoader.load();
        blockTags = BlockTagLoader.load(stack);

        ConcurrentMap<String, ConcurrentMap<String, String>> blockDefaultStates =
            BlockDefaultsLoader.load(BlockRendererOverrides.gather(stack));
        Map<String, String> blockItemAliases = BlockItemsLoader.load();

        be = BlockModelLoader.load();

        blockTables = new BlockTables(models.blocks(), blockTints, itemDefinitions, blockDefaultStates,
            blockItemAliases, be.models(), be.variants(), itemTrees, itemModels);
    }

    @Test
    @DisplayName("block filter keeps renderable variants, drops only empty templates")
    void blockFilter() {
        Set<String> built = new HashSet<>(BlockIndexBuilder.buildUnfiltered(blockTables, blockStates, blockTags, stack).keySet());
        Set<String> kept = new HashSet<>(BlockIndexBuilder.load(blockTables, blockStates, blockTags, stack).keySet());

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
