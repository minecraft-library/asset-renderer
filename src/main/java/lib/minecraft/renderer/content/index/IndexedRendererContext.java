package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.rule.CitRule;
import lib.minecraft.renderer.asset.rule.CitType;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.index.BlockIndexBuilder.BlockTables;
import lib.minecraft.renderer.content.pack.BannerPatternLoader;
import lib.minecraft.renderer.content.pack.BlockStateLoader;
import lib.minecraft.renderer.content.pack.BlockTag;
import lib.minecraft.renderer.content.pack.BlockTagLoader;
import lib.minecraft.renderer.content.pack.ColorMapLoader;
import lib.minecraft.renderer.content.pack.EquipmentModelLoader;
import lib.minecraft.renderer.content.pack.ItemModelTreeLoader;
import lib.minecraft.renderer.content.pack.PackAcquisition;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.PalettedPermutationLoader;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.content.pack.ResolvedTexture;
import lib.minecraft.renderer.content.pack.TextureSynthesizer;
import lib.minecraft.renderer.content.read.BlockRendererOverrides;
import lib.minecraft.renderer.content.rule.CitTypes;
import lib.minecraft.renderer.content.table.BlockDefaultsLoader;
import lib.minecraft.renderer.content.table.BlockItemsLoader;
import lib.minecraft.renderer.content.table.BlockTintsLoader;
import lib.minecraft.renderer.content.table.GlintItemsLoader;
import lib.minecraft.renderer.content.table.PotionColorLoader;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.BannerPattern;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.equipment.ArmorMaterial;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The {@link RendererContext} whose every {@code findX} / {@code resolveX} answer comes off an
 * eagerly-materialised index, built once at bootstrap from the extracted {@link ClientAssets} by
 * {@link #load(ClientAssets)}.
 * <p>
 * Warm-path lookups are pure map accesses; texture pixels stay on disk until the first
 * {@link #resolveTexture(String)} call, which decodes and memoises them through the
 * {@link PackStack#pixels(ResourceId) pack stack}'s own cache. This type only wraps the finished,
 * unmodifiable indexes and the resolved {@link PackStack}, and serves lookups.
 * <p>
 * Biome colormaps and per-block tint targets are wired through to render time by the colormap and
 * block-tint tables; the decoded-pixel cache lives on the {@link PackStack}, so this holds only
 * immutable indexes.
 */
@RequiredArgsConstructor
@Parity(ignored = true)
public final class IndexedRendererContext implements RendererContext {

    private final @NotNull PackStack stack;
    private final @NotNull ConcurrentMap<String, Block> blockIndex;

    /**
     * The registered block ids that draw nothing, which {@link #findBlock(String)} answers empty for -
     * none of them a key of the block index.
     */
    private final @NotNull Set<String> blocksDrawingNothing;

    private final @NotNull ConcurrentMap<String, Item> itemIndex;

    /**
     * The registered item ids that draw nothing, which {@link #findItem(String)} answers empty for -
     * none of them a key of the item index.
     */
    private final @NotNull Set<String> itemsDrawingNothing;

    private final @NotNull ConcurrentMap<String, ItemModelTree> itemTrees;

    /**
     * Every model under every pack's {@code models/} tree whatever its path - the {@code block/} and
     * {@code item/} models, the ones outside both, and the missing model - which
     * {@link #findItemModel(String)} looks a model up in.
     */
    private final @NotNull ResolvedModels models;

    private final @NotNull ConcurrentMap<String, Entity> entityIndex;
    private final @NotNull ConcurrentMap<TintSource, ColorMap> colorMaps;
    private final @NotNull ConcurrentMap<String, BlockTag> blockTags;
    private final @NotNull ConcurrentMap<String, Integer> potionEffectColors;
    private final @NotNull ConcurrentMap<String, BannerPattern> bannerPatterns;
    private final @NotNull ConcurrentMap<String, Block.BlockEntity> blockEntities;
    private final @NotNull TextureSynthesizer synthesizer;
    private final @NotNull ConcurrentMap<ResourceId, EquipmentModel> equipmentModels;

    /**
     * Builds the production context from the extracted client assets - the single loader assembly
     * point, which {@link RendererContext#load(ClientAssets)} opens. Compiles the pack stack
     * ({@link PackAcquisition#acquire}), resolves every model, runs every domain loader, and
     * materialises the block / item / entity indexes eagerly so each {@code findX} lookup is a pure map
     * access. Textures stay on disk until {@link #resolveTexture(String)} is first called.
     *
     * @param assets the extracted client assets (options + vanilla root)
     * @return a new context scoped to the given assets
     */
    static @NotNull IndexedRendererContext load(@NotNull ClientAssets assets) {
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
        IndexRows<Block> blockRows = BlockIndexBuilder.load(blockTables, blockStates, blockTags, stack);
        IndexRows<Item> itemRows = ItemIndexBuilder.load(
            itemTints, glintItems, models.items(), itemTrees, blockEntities);
        ConcurrentMap<String, Block> blockIndex = blockRows.rows();
        ConcurrentMap<String, Item> itemIndex = itemRows.rows();
        ConcurrentMap<String, Entity> entityIndex = EntityModelLoader.loadAll();
        TextureSynthesizer synthesizer = new TextureSynthesizer(PalettedPermutationLoader.load(stack));
        ConcurrentMap<ResourceId, EquipmentModel> equipmentModels = EquipmentModelLoader.load(stack);

        return new IndexedRendererContext(
            stack,
            blockIndex,
            blockRows.drawsNothing(),
            itemIndex,
            itemRows.drawsNothing(),
            itemTrees,
            models,
            entityIndex,
            colorMaps,
            blockTags,
            potionEffectColors,
            bannerPatterns,
            blockEntities,
            synthesizer,
            equipmentModels,
            groupedBlockIds(blockIndex, blockRows.drawsNothing(), blockTags),
            groupedItemIds(itemIndex, itemRows.drawsNothing())
        );
    }

    /**
     * Every block id the index knows, the rows and the ids that draw nothing alike, grouped so related
     * blocks sit together - most specific tag, then id - precomputed once and shared unmodifiable.
     */
    private final @NotNull ConcurrentList<String> knownBlockIds;

    /**
     * Every item id the index knows, the rows and the ids that draw nothing alike, grouped so related
     * items sit together - material prefix, then id - precomputed once and shared unmodifiable.
     */
    private final @NotNull ConcurrentList<String> knownItemIds;

    /**
     * {@inheritDoc}
     * <p>
     * Bare texture ids are namespaced to {@code minecraft:} first. Returns the memoised buffer on a
     * cache hit; otherwise resolves the id through the pack stack (namespace-first dispatch then the
     * winning pack's root walk), decodes it once, and caches it - a file that does not decode, whose
     * sidecar does not parse, or whose animation's frame size does not divide it is remembered as empty.
     * Only an id the stack does not serve consults the paletted-permutation registry, so a file a pack
     * ships shadows a permutation under the same id even when the texture cannot be read.
     */
    @Override
    public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
        ResourceId id = ResourceId.parse(textureId);
        // Synthesis sits BEHIND resolution: only an id the stack does not serve consults the
        // paletted-permutation registry, so no served-texture path changes. On vanilla the registry
        // holds only the trim atlas, whose references the item renderer serves before resolution, so
        // this orAbsent never fires - byte-neutral.
        return this.stack.pixels(id).orAbsent(() -> this.synthesizer.synthesize(id, this::resolveTexture));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Possible<ColorMap> findColorMap(@NotNull TintSource target) {
        if (target.colorMapName().isEmpty()) return Possible.empty();
        return this.colorMaps.containsKey(target) ? Possible.of(this.colorMaps.get(target)) : Possible.absent();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Possible<Block> findBlock(@NotNull String id) {
        if (this.blockIndex.containsKey(id)) return Possible.of(this.blockIndex.get(id));
        return this.blocksDrawingNothing.contains(id) ? Possible.empty() : Possible.absent();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Possible<Item> findItem(@NotNull String id) {
        if (this.itemIndex.containsKey(id)) return Possible.of(this.itemIndex.get(id));
        return this.itemsDrawingNothing.contains(id) ? Possible.empty() : Possible.absent();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Possible<ItemModelTree> findItemTree(@NotNull String id) {
        if (!this.itemTrees.containsKey(id)) return Possible.absent();

        // A refused definition is rooted at the absent node and stays present, its rejected tree being
        // what draws vanilla's missing item model.
        ItemModelTree tree = this.itemTrees.get(id);
        return tree.root() instanceof ItemModelNode.Empty ? Possible.empty() : Possible.of(tree);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Answers from every model under every pack's {@code models/} tree, block models and the models
     * outside {@code block/} and {@code item/} included, as vanilla's one model map does, and reads a
     * bare id as a {@code minecraft:} one first. A loaded model that declares nothing to draw as an
     * item - an item template, {@code item/air}, a Bedrock geometry file - answers empty.
     */
    @Override
    public @NotNull Possible<ModelData> findItemModel(@NotNull String modelId) {
        return this.models.find(modelId);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Answers out of every assembled row, so a row whose body mesh holds no bone - the shipped table's
     * row for each type vanilla draws nothing for - is held, and answers empty.
     */
    @Override
    public @NotNull Possible<Entity> findEntity(@NotNull String id) {
        if (!this.entityIndex.containsKey(id)) return Possible.absent();

        Entity entity = this.entityIndex.get(id);
        return entity.drawsNothing() ? Possible.empty() : Possible.of(entity);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Bare texture ids are namespaced to {@code minecraft:} first, then the texture's index row's
     * captured sidecar is forwarded - empty where the row captured none or one that does not parse, and
     * whether or not the image beside it decodes, since the sidecar is a file of its own. A texture
     * served with no row - a prefix naming a pack, or a paletted permutation - has no sidecar the index
     * holds and answers empty too, so only an id this context does not serve answers absent.
     */
    @Override
    public @NotNull Possible<MCMeta> findMeta(@NotNull String textureId) {
        Optional<ResolvedTexture> row = this.stack.indexed(ResourceId.parse(textureId));
        if (row.isPresent()) return row.get().meta().orAbsent(Possible::empty);
        return RendererContext.super.findMeta(textureId);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Resolved once per texture and memoised on the pack stack, beside the decoded pixels it is a
     * function of. A texture only the paletted-permutation registry serves ships no sidecar, so it plays
     * nothing.
     */
    @Override
    public @NotNull Possible<Flipbook> findFlipbook(@NotNull String textureId) {
        return this.stack.flipbook(ResourceId.parse(textureId))
            .orAbsent(() -> this.resolveTexture(textureId).isAbsent() ? Possible.absent() : Possible.empty());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Grouped so related blocks sit next to each other: by the block's most specific tag - the one with
     * the fewest members - or its material prefix when it carries none, then by id, both
     * case-insensitive. A block that draws nothing holds no row to carry a tag, so it always groups by
     * its material prefix.
     */
    @Override
    public @NotNull ConcurrentList<String> knownBlockIds() {
        return this.knownBlockIds;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Grouped so related items sit next to each other: by material prefix, then by id, both
     * case-insensitive.
     */
    @Override
    public @NotNull ConcurrentList<String> knownItemIds() {
        return this.knownItemIds;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Possible<Integer> findPotionEffectColor(@NotNull String effectId) {
        return this.potionEffectColors.containsKey(effectId)
            ? Possible.of(this.potionEffectColors.get(effectId))
            : Possible.absent();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Possible<BannerPattern> findBannerPattern(@NotNull String patternId) {
        return this.bannerPatterns.containsKey(patternId)
            ? Possible.of(this.bannerPatterns.get(patternId))
            : Possible.absent();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull ConcurrentList<BannerPattern> knownBannerPatterns() {
        return this.bannerPatterns.values()
            .stream()
            .collect(Concurrent.toList());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Reads the block-entity table first, so an id the table holds answers its entry even where the
     * block index lacks the block; every other id answers as the interface derives it from
     * {@link #findBlock}.
     */
    @Override
    public @NotNull Possible<Block.BlockEntity> findBlockEntityEntry(@NotNull String blockId) {
        if (this.blockEntities.containsKey(blockId)) return Possible.of(this.blockEntities.get(blockId));
        return RendererContext.super.findBlockEntityEntry(blockId);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Possible<Integer> findColorOverride(@NotNull String key) {
        // A key no pack supplies a parseable colour for is not there, which a bare ofOptional would call empty.
        return Possible.ofOptional(this.stack.rules().colors().get(key)).or(Possible::absent);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Resolves the glint decision once via {@link RuleLookup#glint} (the highest-precedence matching
     * {@code type=enchantment} rule, else the merged {@code useGlint} toggle), then walks the merged CIT
     * rule list first-match-wins, skipping non-{@link CitType#ITEM} rules (only item rules retexture
     * icons), and grafts the glint onto the winning rule's effect. When no item rule matches the glint
     * still rides through - so {@code useGlint=false} and enchantment glint replacements apply even to an
     * un-retextured icon.
     */
    @Override
    public @NotNull CitResult resolveItemTextureOverride(@NotNull ItemContext context) {
        GlintPolicy glint = RuleLookup.glint(this.stack.rules(), context);

        for (CitRule rule : this.stack.rules().citRules()) {
            if (rule.type() != CitType.ITEM) continue;
            if (context.matches(rule)) return CitResult.of(rule.output(), glint);
        }

        return glint == GlintPolicy.DEFAULT ? CitResult.NONE : CitResult.NONE.withGlint(glint);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Walks the merged CIT rule list first-match-wins for the subject the layer type names
     * ({@link CitTypes#of}), returning the winning rule's output. Empty on a vanilla-only stack (no {@code optifine/} tree, so no rules). The glint
     * stays {@link GlintPolicy#DEFAULT}: armor enchant glint rides {@code ArmorPiece.enchanted} onto a
     * separate {@code PixelMask} channel, not the CIT glint the item override grafts, so a
     * {@code type=enchantment} rule never colours a CIT-armor override.
     */
    @Override
    public @NotNull CitResult resolveArmorTextureOverride(
        @NotNull ArmorMaterial material, @NotNull LayerType layerType, @NotNull ItemContext item) {
        CitType want = CitTypes.of(layerType);

        for (CitRule rule : this.stack.rules().citRules()) {
            if (rule.type() != want) continue;
            if (item.matches(rule)) return CitResult.of(rule.output(), GlintPolicy.DEFAULT);
        }

        return CitResult.NONE;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Delegates to {@link RuleLookup#connectedTexture} on the merged rules, handing it the renderer
     * {@link Face} as drawn - the rules hold the same face type, so nothing is converted. Absent on a
     * vanilla-only stack (no {@code optifine/} tree, so no CTM rules), which keeps the block render
     * byte-identical.
     */
    @Override
    public @NotNull Possible<ResourceId> resolveConnectedTexture(
        @NotNull String blockId, @NotNull Map<String, String> state,
        @NotNull String baseTextureId, @NotNull Face face) {
        return RuleLookup.connectedTexture(this.stack.rules(), new CtmContext(blockId, state, baseTextureId, face));
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
     * Orders every block id the index knows so related blocks sit next to each other - by
     * {@link #groupKey group key}, then by id, both case-insensitive. The rows and the ids that draw
     * nothing never share an id, so each id is listed once.
     *
     * @param blockIndex the materialised block index
     * @param drawsNothing the registered block ids that draw nothing
     * @param blockTags the materialised block tag index
     * @return the block ids in grouped order
     */
    private static @NotNull ConcurrentList<String> groupedBlockIds(@NotNull ConcurrentMap<String, Block> blockIndex,
        @NotNull Set<String> drawsNothing, @NotNull ConcurrentMap<String, BlockTag> blockTags) {
        return Stream.concat(blockIndex.keySet().stream(), drawsNothing.stream())
            .sorted((a, b) -> {
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(
                    groupKey(a, blockIndex, blockTags), groupKey(b, blockIndex, blockTags));
                return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
            })
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * Orders every item id the index knows so related items sit next to each other - by
     * {@link #materialPrefix material prefix}, then by id, both case-insensitive. The rows and the ids
     * that draw nothing never share an id, so each id is listed once.
     *
     * @param itemIndex the materialised item index
     * @param drawsNothing the registered item ids that draw nothing
     * @return the item ids in grouped order
     */
    private static @NotNull ConcurrentList<String> groupedItemIds(@NotNull ConcurrentMap<String, Item> itemIndex,
        @NotNull Set<String> drawsNothing) {
        return Stream.concat(itemIndex.keySet().stream(), drawsNothing.stream())
            .sorted((a, b) -> {
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(materialPrefix(a), materialPrefix(b));
                return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
            })
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * Returns the key a block is grouped under - its most specific tag, the one with the fewest
     * members, or its {@link #materialPrefix material prefix} when it carries no tag the index holds.
     *
     * @param blockId the namespaced block id
     * @param blockIndex the materialised block index
     * @param blockTags the materialised block tag index
     * @return the grouping key
     */
    private static @NotNull String groupKey(@NotNull String blockId,
        @NotNull ConcurrentMap<String, Block> blockIndex, @NotNull ConcurrentMap<String, BlockTag> blockTags) {
        Block block = blockIndex.get(blockId);

        if (block != null && !block.tags().isEmpty()) {
            return block.tags()
                .stream()
                .filter(blockTags::containsKey)
                .min(Comparator.comparingInt(tag -> blockTags.get(tag).values().size()))
                .orElse(blockId);
        }

        return materialPrefix(blockId);
    }

    /**
     * Returns the material prefix of a namespaced id, the grouping key when no richer signal such as
     * a block tag is available. Strips the namespace and the trailing {@code _suffix}, then prepends
     * {@code ~} so a prefix group sorts apart from the tag groups: {@code "minecraft:oak_stairs"} becomes
     * {@code "~oak"}.
     *
     * @param id the namespaced id
     * @return the material prefix, prefixed with {@code ~}
     */
    private static @NotNull String materialPrefix(@NotNull String id) {
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        int lastUnderscore = name.lastIndexOf('_');
        return lastUnderscore > 0 ? "~" + name.substring(0, lastUnderscore) : "~" + name;
    }

}
