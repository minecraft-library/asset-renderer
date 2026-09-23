package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.atlas.AtlasOrder;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.index.BlockIndexBuilder.BlockTables;
import lib.minecraft.renderer.content.pack.BannerPatternLoader;
import lib.minecraft.renderer.content.pack.BlockModelLoader;
import lib.minecraft.renderer.content.pack.BlockStateLoader;
import lib.minecraft.renderer.content.pack.BlockTagLoader;
import lib.minecraft.renderer.content.pack.ColorMapLoader;
import lib.minecraft.renderer.content.pack.EquipmentModelLoader;
import lib.minecraft.renderer.content.pack.ItemModelTreeLoader;
import lib.minecraft.renderer.content.pack.PackAcquisition;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.PalettedPermutationLoader;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.content.pack.TextureSynthesizer;
import lib.minecraft.renderer.content.read.BlockRendererOverrides;
import lib.minecraft.renderer.content.table.BlockDefaultsLoader;
import lib.minecraft.renderer.content.table.BlockItemsLoader;
import lib.minecraft.renderer.content.table.BlockTintsLoader;
import lib.minecraft.renderer.content.table.EntityModelLoader;
import lib.minecraft.renderer.content.table.GlintItemsLoader;
import lib.minecraft.renderer.content.table.PotionColorLoader;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.vanilla.BannerPattern;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

/**
 * The entry point a caller builds a {@link RendererContext} through -
 * {@code RendererContext ctx = AssetContent.load(assets)}.
 */
@UtilityClass
@Parity(ignored = true)
public final class AssetContent {

    /**
     * Builds the production renderer context from the extracted client assets - the single loader
     * assembly point. Compiles the pack stack ({@link PackAcquisition#acquire}), resolves every model,
     * runs every domain loader, and materialises the block / item / entity indexes eagerly so each
     * {@code findX} lookup is a pure map access. Textures stay on disk until
     * {@link RendererContext#resolveTexture(String)} is first called.
     *
     * @param assets the extracted client assets (options + vanilla root)
     * @return a new context scoped to the given assets
     */
    public static @NotNull RendererContext load(@NotNull ClientAssets assets) {
        PackStack stack = PackAcquisition.acquire(assets);

        ResolvedModels models = ResolvedModels.load(stack);
        BlockStateLoader.BlockStates blockStates = BlockStateLoader.load(stack);

        ConcurrentMap<String, ConcurrentMap<String, String>> blockDefaultStates = BlockDefaultsLoader.load(BlockRendererOverrides.gather(stack.ascending()));
        ConcurrentMap<String, String> blockItemAliases = BlockItemsLoader.load();

        ConcurrentMap<TintSource, ColorMap> colorMaps = ColorMapLoader.load(stack);
        ConcurrentMap<String, Block.Tint> blockTints = BlockTintsLoader.load();
        ConcurrentMap<String, ItemModelTree> itemTrees = ItemModelTreeLoader.load(stack);
        ConcurrentMap<String, String> itemDefinitions = ItemModelTreeLoader.deriveBlockItemModels(itemTrees);
        ConcurrentMap<String, ConcurrentList<LayerTint>> itemTints = ItemModelTreeLoader.deriveTints(itemTrees);
        ConcurrentSet<String> glintItems = GlintItemsLoader.load();
        ConcurrentMap<String, BlockTag> blockTags = BlockTagLoader.load(stack);
        ConcurrentMap<String, Integer> potionEffectColors = PotionColorLoader.load();
        ConcurrentMap<String, BannerPattern> bannerPatterns = BannerPatternLoader.load(stack);

        BlockModelLoader.LoadResult beResult = BlockModelLoader.load(stack);
        ConcurrentMap<String, Block.BlockEntity> blockEntities = beResult.models();

        BlockTables blockTables = new BlockTables(
            models.blocks(),
            blockTints,
            itemDefinitions,
            blockDefaultStates,
            blockItemAliases,
            blockEntities,
            beResult.variants(),
            itemTrees,
            models.items()
        );
        ConcurrentMap<String, Block> blockIndex = BlockIndexBuilder.load(blockTables, blockStates, blockTags, stack);
        ConcurrentMap<String, Item> itemIndex = ItemIndexBuilder.load(
            itemTints, glintItems, models.items(), itemTrees, blockEntities);
        ConcurrentMap<String, Entity> entityIndex = EntityModelLoader.load();
        TextureSynthesizer synthesizer = new TextureSynthesizer(PalettedPermutationLoader.load(stack));
        ConcurrentMap<ResourceId, EquipmentModel> equipmentModels = EquipmentModelLoader.load(stack);

        return new IndexedRendererContext(
            stack,
            blockIndex,
            itemIndex,
            itemTrees,
            models.items(),
            entityIndex,
            colorMaps,
            blockTags,
            potionEffectColors,
            bannerPatterns,
            blockEntities,
            synthesizer,
            equipmentModels,
            AtlasOrder.sortedBlockIds(blockIndex, blockTags),
            AtlasOrder.sortedItemIds(itemIndex)
        );
    }

}
