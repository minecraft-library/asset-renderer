package lib.minecraft.renderer.pipeline;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.BannerPattern;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.ArmorMaterial;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.ResolvedTexture;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.CitRule;
import lib.minecraft.renderer.asset.pack.rule.CitType;
import lib.minecraft.renderer.asset.pack.rule.CtmContext;
import lib.minecraft.renderer.asset.pack.rule.GlintPolicy;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.asset.pack.rule.RuleSet;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.texture.TextureSynthesizer;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.pipeline.index.BlockIndexBuilder.BlockTables;
import lib.minecraft.renderer.pipeline.index.BlockIndexBuilder;
import lib.minecraft.renderer.pipeline.index.ItemIndexBuilder;
import lib.minecraft.renderer.pipeline.loader.BlockDefaultsLoader;
import lib.minecraft.renderer.pipeline.loader.BlockItemsLoader;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pipeline.loader.GlintItemsLoader;
import lib.minecraft.renderer.pipeline.loader.PotionColorLoader;
import lib.minecraft.renderer.pipeline.pack.BannerPatternLoader;
import lib.minecraft.renderer.pipeline.pack.BlockStateLoader;
import lib.minecraft.renderer.pipeline.pack.BlockTagLoader;
import lib.minecraft.renderer.pipeline.pack.ColorMapLoader;
import lib.minecraft.renderer.pipeline.pack.EquipmentModelLoader;
import lib.minecraft.renderer.pipeline.pack.PackAcquisition;
import lib.minecraft.renderer.pipeline.pack.PalettedPermutationLoader;
import lib.minecraft.renderer.pipeline.pack.ResolvedModels;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelTreeLoader;
import lib.minecraft.renderer.pipeline.util.BlockRendererOverrides;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The production {@link RendererContext} implementation, built once at bootstrap from the extracted
 * {@link ClientAssets}. Every {@code findX} / {@code resolveX} method is backed by an
 * eagerly-materialised index so warm-path lookups are pure map accesses; texture pixels stay on
 * disk until the first {@link #resolveTexture(String)} call, which decodes and memoises them through
 * the {@link PackStack#pixels(ResourceId) pack stack}'s own cache.
 * <p>
 * Construction goes through {@link #of(ClientAssets)}, which delegates index building to the
 * pipeline loaders: {@link BlockIndexBuilder} and {@link ItemIndexBuilder} materialise the block /
 * item indexes (keyed by namespaced id, registry-filtered so parent templates and submodels never
 * become atlas tiles), {@link BlockModelLoader} supplies block-entity geometry, and
 * {@link EntityModelLoader} supplies the entity index. The context itself only wraps the finished,
 * unmodifiable indexes and the resolved {@link PackStack}, and serves lookups.
 * <p>
 * Biome colormaps and per-block tint targets are wired through to render time by
 * {@code ColorMapLoader} and {@link BlockTintsLoader}; the decoded-pixel cache now lives on the
 * {@link PackStack}, so the context holds only immutable indexes.
 */
@RequiredArgsConstructor
@Parity(ignored = true)
public final class PipelineRendererContext implements RendererContext {

    private final @NotNull PackStack stack;
    private final @NotNull ConcurrentMap<String, Block> blockIndex;
    private final @NotNull ConcurrentMap<String, Item> itemIndex;
    private final @NotNull ConcurrentMap<String, ItemModelTree> itemTrees;
    private final @NotNull ConcurrentMap<String, ModelData> itemModels;
    private final @NotNull ConcurrentMap<String, Entity> entityIndex;
    private final @NotNull ConcurrentMap<Block.TintTarget, ColorMap> colorMaps;
    private final @NotNull ConcurrentMap<String, BlockTag> blockTags;
    private final @NotNull ConcurrentMap<String, Integer> potionEffectColors;
    private final @NotNull ConcurrentMap<String, BannerPattern> bannerPatterns;
    private final @NotNull ConcurrentMap<String, Block.Entity> blockEntities;
    private final @NotNull TextureSynthesizer synthesizer;
    private final @NotNull ConcurrentMap<ResourceId, EquipmentModel> equipmentModels;

    /**
     * The block ids in atlas-grouping order (primary tag then id), precomputed once, shared unmodifiable.
     */
    private final @NotNull ConcurrentList<String> knownBlockIds;

    /**
     * The item ids in atlas-grouping order (material prefix then id), precomputed once, shared unmodifiable.
     */
    private final @NotNull ConcurrentList<String> knownItemIds;

    /**
     * Builds the production renderer context from the extracted client assets - the single loader
     * assembly point. Compiles the pack stack ({@link PackAcquisition#acquire}), resolves every model,
     * runs every domain loader, and materialises the block / item / entity indexes eagerly so each
     * {@code findX} lookup is a pure map access. Textures stay on disk until
     * {@link #resolveTexture(String)} is first called.
     *
     * @param assets the extracted client assets (options + vanilla root)
     * @return a new context scoped to the given assets
     */
    public static @NotNull PipelineRendererContext of(@NotNull ClientAssets assets) {
        PackStack stack = PackAcquisition.acquire(assets);

        ResolvedModels models = ResolvedModels.load(stack);
        BlockStateLoader.BlockStates blockStates = BlockStateLoader.load(stack);

        ConcurrentMap<String, ConcurrentMap<String, String>> blockDefaultStates = BlockDefaultsLoader.load(BlockRendererOverrides.gather(stack));
        ConcurrentMap<String, String> blockItemAliases = BlockItemsLoader.load();

        ConcurrentMap<Block.TintTarget, ColorMap> colorMaps = ColorMapLoader.load(stack);
        ConcurrentMap<String, Block.Tint> blockTints = BlockTintsLoader.load();
        ConcurrentMap<String, ItemModelTree> itemTrees = ItemModelTreeLoader.load(stack);
        ConcurrentMap<String, String> itemDefinitions = ItemModelTreeLoader.deriveBlockItemModels(itemTrees);
        ConcurrentMap<String, ConcurrentList<LayerTint>> itemTints = ItemModelTreeLoader.deriveTints(itemTrees);
        ConcurrentSet<String> glintItems = GlintItemsLoader.load();
        ConcurrentMap<String, BlockTag> blockTags = BlockTagLoader.load(stack);
        ConcurrentMap<String, Integer> potionEffectColors = PotionColorLoader.load();
        ConcurrentMap<String, BannerPattern> bannerPatterns = BannerPatternLoader.load(stack);

        BlockModelLoader.LoadResult beResult = BlockModelLoader.load(stack);
        ConcurrentMap<String, Block.Entity> blockEntities = beResult.models();

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

        return new PipelineRendererContext(
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
            sortedBlockIds(blockIndex, blockTags),
            sortedItemIds(itemIndex)
        );
    }

    /**
     * {@inheritDoc}
     * <p>
     * Bare texture ids are namespaced to {@code minecraft:} first. Returns the memoised buffer on a
     * cache hit; otherwise resolves the id through the pack stack (namespace-first dispatch then the
     * winning pack's root walk), decodes it once, and caches it. Empty when the id resolves to nothing.
     */
    @Override
    public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
        ResourceId id = ResourceId.parse(textureId);
        // Synthesis sits BEHIND resolution: only a stack miss consults the paletted-permutation
        // registry, so no present-texture path changes. On vanilla the registry
        // holds only the trim atlas, whose references the item renderer serves before resolution, so
        // this .or() never fires - byte-neutral.
        return this.stack.pixels(id).or(() -> this.synthesizer.synthesize(id, this::resolveTexture));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<ColorMap> findColorMap(Block.@NotNull TintTarget target) {
        return this.colorMaps.getOptional(target);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Block> findBlock(@NotNull String id) {
        return this.blockIndex.getOptional(id);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Item> findItem(@NotNull String id) {
        return this.itemIndex.getOptional(id);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<ItemModelTree> findItemTree(@NotNull String id) {
        return this.itemTrees.getOptional(id);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<ModelData> findItemModel(@NotNull String modelId) {
        return this.itemModels.getOptional(modelId);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Entity> findEntity(@NotNull String id) {
        return this.entityIndex.getOptional(id);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Bare texture ids are namespaced to {@code minecraft:} first, then the texture's index row's
     * captured sidecar is forwarded.
     */
    @Override
    public @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) {
        return this.stack.indexed(ResourceId.parse(textureId))
            .flatMap(ResolvedTexture::meta);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The sidecar's {@code animation} section, handed over as captured.
     */
    @Override
    public @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) {
        return this.findMeta(textureId).flatMap(MCMeta::animation);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Resolved once per texture and memoised on the pack stack, beside the decoded pixels it is a
     * function of.
     */
    @Override
    public @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId) {
        return this.stack.flipbook(ResourceId.parse(textureId));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sorted by {@link #primaryTag(String, ConcurrentMap, ConcurrentMap) primary tag} (most-specific tag, or material prefix
     * fallback) then id, both case-insensitive, so semantically related blocks cluster in atlas output.
     */
    @Override
    public @NotNull ConcurrentList<String> knownBlockIds() {
        return this.knownBlockIds;
    }

    /**
     * Sorts the block ids by primary tag then id (both case-insensitive); the shared, precomputed order.
     */
    private static @NotNull ConcurrentList<String> sortedBlockIds(@NotNull ConcurrentMap<String, Block> blockIndex, @NotNull ConcurrentMap<String, BlockTag> blockTags) {
        return blockIndex.keySet()
            .stream()
            .sorted((a, b) -> {
                String groupA = primaryTag(a, blockIndex, blockTags);
                String groupB = primaryTag(b, blockIndex, blockTags);
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(groupA, groupB);
                return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
            })
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sorted by {@link #idPrefix(String) material prefix} then id, both case-insensitive.
     */
    @Override
    public @NotNull ConcurrentList<String> knownItemIds() {
        return this.knownItemIds;
    }

    /**
     * Sorts the item ids by material prefix then id (both case-insensitive); the shared, precomputed order.
     */
    private static @NotNull ConcurrentList<String> sortedItemIds(@NotNull ConcurrentMap<String, Item> itemIndex) {
        return itemIndex.keySet()
            .stream()
            .sorted((a, b) -> {
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(idPrefix(a), idPrefix(b));
                return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
            })
            .collect(Concurrent.toUnmodifiableList());
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Integer> findPotionEffectColor(@NotNull String effectId) {
        return this.potionEffectColors.getOptional(effectId);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<BannerPattern> findBannerPattern(@NotNull String patternId) {
        return this.bannerPatterns.getOptional(patternId);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull ConcurrentList<BannerPattern> knownBannerPatterns() {
        return this.bannerPatterns.values()
            .stream()
            .collect(Concurrent.toList());
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Block.Entity> findBlockEntityEntry(@NotNull String blockId) {
        return this.blockEntities.getOptional(blockId);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Integer> findColorOverride(@NotNull String key) {
        return this.stack.rules().colors().get(key);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Resolves the glint decision once via {@link RuleSet#glintFor(ItemContext)} (the highest-precedence matching
     * {@code type=enchantment} rule, else the merged {@code useGlint} toggle), then walks the merged CIT
     * rule list first-match-wins, skipping non-{@link CitType#ITEM} rules (only item rules retexture
     * icons), and grafts the glint onto the winning rule's effect. When no item rule matches the glint
     * still rides through - so {@code useGlint=false} and enchantment glint replacements apply even to an
     * un-retextured icon.
     */
    @Override
    public @NotNull CitResult resolveItemTextureOverride(@NotNull ItemContext context) {
        GlintPolicy glint = this.stack.rules().glintFor(context);

        for (CitRule rule : this.stack.rules().citRules()) {
            if (rule.type() != CitType.ITEM) continue;
            if (rule.matches(context)) return CitResult.of(rule.output(), glint);
        }

        return glint == GlintPolicy.DEFAULT ? CitResult.NONE : CitResult.NONE.withGlint(glint);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Walks the merged CIT rule list first-match-wins for the subject the layer type names
     * ({@link LayerType#citType()}), returning the winning rule's output. Empty on a vanilla-only stack (no {@code optifine/} tree, so no rules). The glint
     * stays {@link GlintPolicy#DEFAULT}: armor enchant glint rides {@code ArmorPiece.enchanted} onto a
     * separate {@code PixelMask} channel, not the CIT glint the item override grafts, so a
     * {@code type=enchantment} rule never colours a CIT-armor override.
     */
    @Override
    public @NotNull CitResult resolveArmorTextureOverride(
        @NotNull ArmorMaterial material, @NotNull LayerType layerType, @NotNull ItemContext item) {
        CitType want = layerType.citType();

        for (CitRule rule : this.stack.rules().citRules()) {
            if (rule.type() != want) continue;
            if (rule.matches(item)) return CitResult.of(rule.output(), GlintPolicy.DEFAULT);
        }

        return CitResult.NONE;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Delegates to {@link RuleSet#connectedTextureFor(CtmContext)} on the merged rules, mapping the
     * renderer {@link Face} onto its CTM grammar face. Empty on a vanilla-only stack (no
     * {@code optifine/} tree, so no CTM rules), which keeps the block render byte-identical.
     */
    @Override
    public @NotNull Optional<ResourceId> resolveConnectedTexture(
        @NotNull String blockId, @NotNull Map<String, String> state,
        @NotNull String baseTextureId, @NotNull Face face) {
        return this.stack.rules().connectedTextureFor(new CtmContext(blockId, state, baseTextureId, face));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Serves the parsed {@code equipment/*.json} index: the ordered layers for the asset under the
     * layer type, or an empty list when the stack ships no such asset (an unresolvable id yields
     * {@link EquipmentModel#MISSING}). The index is held internal, exposed only through this seam.
     */
    @Override
    public @NotNull List<EquipmentModel.Layer> resolveEquipmentLayers(
        @NotNull ResourceId assetId, @NotNull LayerType layerType) {
        return this.equipmentModels.getOrDefault(assetId, EquipmentModel.MISSING).getLayers(layerType);
    }

    /**
     * Returns the most specific tag name for a block (the tag with fewest members), or the
     * block's material prefix as a fallback for untagged blocks. Used as the primary sort key
     * so semantically related blocks cluster together in atlas output.
     */
    private static @NotNull String primaryTag(@NotNull String blockId,
        @NotNull ConcurrentMap<String, Block> blockIndex, @NotNull ConcurrentMap<String, BlockTag> blockTags) {
        Block block = blockIndex.get(blockId);

        if (block != null && !block.tags().isEmpty()) {
            return block.tags()
                .stream()
                .filter(blockTags::containsKey)
                .min(Comparator.comparingInt(tag -> blockTags.get(tag).values().size()))
                .orElse(blockId);
        }

        return idPrefix(blockId);
    }

    /**
     * Returns the material prefix of a namespaced id, used as a grouping key when no richer
     * signal (such as block tags) is available. Strips the namespace and the trailing
     * {@code _suffix}, then prepends {@code ~} so heuristic groups sort distinctly from real
     * tag groups. {@code "minecraft:oak_stairs"} becomes {@code "~oak"}.
     */
    private static @NotNull String idPrefix(@NotNull String id) {
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        int lastUnderscore = name.lastIndexOf('_');
        return lastUnderscore > 0 ? "~" + name.substring(0, lastUnderscore) : "~" + name;
    }

}
