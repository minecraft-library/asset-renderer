package lib.minecraft.renderer.content.index;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.index.BlockIndexBuilder.BlockTables;
import lib.minecraft.renderer.content.pack.BlockStateLoader;
import lib.minecraft.renderer.content.pack.BlockTag;
import lib.minecraft.renderer.content.pack.BlockTagLoader;
import lib.minecraft.renderer.content.pack.ItemModelTreeLoader;
import lib.minecraft.renderer.content.pack.PackAcquisition;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.content.read.BlockRendererOverrides;
import lib.minecraft.renderer.content.table.BlockDefaultsLoader;
import lib.minecraft.renderer.content.table.BlockItemsLoader;
import lib.minecraft.renderer.content.table.BlockTintsLoader;
import lib.minecraft.renderer.content.table.GlintItemsLoader;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
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
 * Of what drops, the ids the game registers whose model declares nothing to draw are kept apart as the
 * blocks and items that draw nothing, and this pins both populations whole: seven blocks and air alone,
 * with the fluid and portal stand-ins and every template outside them.
 * <p>
 * Needs a real {@link ClientAssets}, which it reads through the shared client-assets
 * extension rather than acquiring one of its own.
 */
@DisplayName("Structural empty-model filter parity")
@ExtendWith(ClientAssetsExtension.class)
class IndexTemplateFilterTest {

    /** The block index the real pipeline builds over the vanilla corpus. */
    private static IndexRows<Block> blockRows;

    /** The item index the real pipeline builds over the vanilla corpus. */
    private static IndexRows<Item> itemRows;

    /** Runs the real pipeline and builds the block and item index rows once for every test. */
    @BeforeAll
    static void setup() {
        PackStack stack = PackAcquisition.acquire(ClientAssetsExtension.assets());

        ResolvedModels models = ResolvedModels.load(stack);
        BlockStateLoader.BlockStates blockStates = BlockStateLoader.load(stack);

        Map<String, Block.Tint> blockTints = BlockTintsLoader.load();
        ConcurrentMap<String, ItemModelTree> itemTrees = ItemModelTreeLoader.load(stack);
        ConcurrentMap<String, String> itemDefinitions = ItemModelTreeLoader.deriveBlockItemModels(itemTrees);
        ConcurrentMap<String, ConcurrentList<LayerTint>> itemTints = ItemModelTreeLoader.deriveTints(itemTrees);
        Set<String> glintItems = GlintItemsLoader.load();
        ConcurrentMap<String, BlockTag> blockTags = BlockTagLoader.load(stack);

        ConcurrentMap<String, ConcurrentMap<String, String>> blockDefaultStates =
            BlockDefaultsLoader.load(BlockRendererOverrides.gather(stack.ascending()));
        Map<String, String> blockItemAliases = BlockItemsLoader.load();

        BlockModelLoader.LoadResult be = BlockModelLoader.load();

        BlockTables blockTables = new BlockTables(models.blocks(), blockTints, itemDefinitions, blockDefaultStates,
            blockItemAliases, be.models(), be.variants(), itemTrees, models.items());
        blockRows = BlockIndexBuilder.load(blockTables, blockStates, blockTags, stack);
        itemRows = ItemIndexBuilder.load(itemTints, glintItems, models, itemTrees, be.models(), blockRows);
    }

    @Test
    @DisplayName("block filter keeps renderable variants, drops only empty templates")
    void blockFilter() {
        Set<String> kept = new HashSet<>(blockRows.rows().keySet());

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
    @DisplayName("the blocks that draw nothing are the registered invisible and air blocks, not the templates or the stand-ins")
    void blocksDrawingNothing() {
        // Air, barrier, structure_void and moving_piston drop as primary rows; cave_air, void_air and
        // light are blockstate-only, naming block/air and a block/light_NN that bind only a particle.
        assertThat(blockRows.drawsNothing(), is(Set.of(
            "minecraft:air", "minecraft:barrier", "minecraft:structure_void", "minecraft:moving_piston",
            "minecraft:cave_air", "minecraft:void_air", "minecraft:light")));

        // The stand-ins declare nothing to draw as well, but vanilla draws them through a fluid or portal
        // renderer, and a template is no registered block.
        for (String id : new String[]{
            "minecraft:water", "minecraft:lava", "minecraft:end_portal", "minecraft:end_gateway", "minecraft:bubble_column",
            "minecraft:slab", "minecraft:cross", "minecraft:stone"
        }) assertThat(id + " is not a block that draws nothing", blockRows.drawsNothing().contains(id), is(false));

        for (String id : blockRows.drawsNothing())
            assertThat(id + " holds no row", blockRows.rows().containsKey(id), is(false));
    }

    @Test
    @DisplayName("the items that draw nothing are air alone, not the templates or the sprite-carrying blocks")
    void itemsDrawingNothing() {
        // Air's definition registers it and its model binds only a particle. Barrier, structure_void and
        // light carry a layer0 sprite, so they keep rows; no vanilla definition roots at minecraft:empty.
        assertThat(itemRows.drawsNothing(), is(Set.of("minecraft:air")));
        for (String id : new String[]{"minecraft:barrier", "minecraft:structure_void", "minecraft:light"})
            assertThat(id + " keeps its row", itemRows.rows().containsKey(id), is(true));
        for (String id : new String[]{"minecraft:generated", "minecraft:handheld"})
            assertThat(id + " is not an item that draws nothing", itemRows.drawsNothing().contains(id), is(false));
    }

    @Test
    @DisplayName("item filter keeps renderable variants, drops only empty templates")
    void itemFilter() {
        Set<String> kept = new HashSet<>(itemRows.rows().keySet());

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
