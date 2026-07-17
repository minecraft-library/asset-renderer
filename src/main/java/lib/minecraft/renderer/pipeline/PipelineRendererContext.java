package lib.minecraft.renderer.pipeline;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.BannerPattern;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.texture.TextureSynthesizer;
import lib.minecraft.renderer.pipeline.loader.BlockDefaultsLoader;
import lib.minecraft.renderer.pipeline.loader.BlockItemsLoader;
import lib.minecraft.renderer.pipeline.load.BlockRendererOverrides;
import lib.minecraft.renderer.pipeline.pack.BannerPatternLoader;
import lib.minecraft.renderer.pipeline.BlockIndexBuilder;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.pipeline.pack.BlockStateLoader;
import lib.minecraft.renderer.pipeline.pack.BlockTagLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.pack.ColorMapLoader;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pipeline.loader.GlintItemsLoader;
import lib.minecraft.renderer.pipeline.ItemIndexBuilder;
import lib.minecraft.renderer.pipeline.pack.PalettedPermutationLoader;
import lib.minecraft.renderer.pipeline.loader.PotionColorLoader;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackAcquisition;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResolvedModels;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelTree;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelTreeLoader;
import lib.minecraft.renderer.pipeline.pack.ResolvedTexture;
import lib.minecraft.renderer.pipeline.pack.rule.CitResult;
import lib.minecraft.renderer.pipeline.pack.rule.CitRule;
import lib.minecraft.renderer.pipeline.pack.rule.CitType;
import lib.minecraft.renderer.pipeline.pack.rule.GlintEvaluator;
import lib.minecraft.renderer.pipeline.pack.rule.GlintPolicy;
import lib.minecraft.renderer.pipeline.pack.rule.ItemContext;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
public final class PipelineRendererContext implements RendererContext {

    private final @NotNull PackStack stack;
    private final @NotNull ConcurrentMap<String, Block> blockIndex;
    private final @NotNull ConcurrentMap<String, Item> itemIndex;
    private final @NotNull ConcurrentMap<String, ItemModelTree> itemTrees;
    private final @NotNull ConcurrentMap<String, ModelData> itemModels;
    private final @NotNull ConcurrentMap<String, Entity> entityIndex;
    private final @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMaps;
    private final @NotNull ConcurrentMap<String, BlockTag> blockTags;
    private final @NotNull ConcurrentMap<String, Integer> potionEffectColors;
    private final @NotNull ConcurrentMap<String, BannerPattern> bannerPatterns;
    private final @NotNull ConcurrentMap<String, Block.Entity> blockEntities;
    private final @NotNull TextureSynthesizer synthesizer;

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
        BlockStateLoader.BlockStates blockStates = BlockStateLoader.load(stack, models.blocks());

        Diagnostics defaultsDiag = Diagnostics.root("blockDefaults", Diagnostics.Output.CONSOLE, null);
        ConcurrentMap<String, String> blockDefaultStateKeys = BlockDefaultsLoader.load(defaultsDiag, BlockRendererOverrides.gather(stack, defaultsDiag));
        ConcurrentMap<String, String> blockItemAliases = BlockItemsLoader.load(Diagnostics.root("blockItems", Diagnostics.Output.CONSOLE, null));

        ConcurrentMap<ColorMap.Type, ColorMap> colorMaps = ColorMapLoader.load(stack);
        ConcurrentMap<String, Block.Tint> blockTints = BlockTintsLoader.load();
        ConcurrentMap<String, ItemModelTree> itemTrees = ItemModelTreeLoader.load(stack);
        ConcurrentMap<String, String> itemDefinitions = ItemModelTreeLoader.deriveBlockItemModels(itemTrees);
        ConcurrentMap<String, List<LayerTint>> itemTints = ItemModelTreeLoader.deriveTints(itemTrees);
        ConcurrentSet<String> glintItems = GlintItemsLoader.load();
        ConcurrentMap<String, BlockTag> blockTags = BlockTagLoader.load(stack);
        ConcurrentMap<String, Integer> potionEffectColors = PotionColorLoader.load();
        ConcurrentMap<String, BannerPattern> bannerPatterns = BannerPatternLoader.load(stack);

        BlockModelLoader.LoadResult beResult = BlockModelLoader.load(stack);
        ConcurrentMap<String, Block.Entity> blockEntities = beResult.models();

        ConcurrentMap<String, Block> blockIndex = BlockIndexBuilder.load(
            models.blocks(), blockTints, itemDefinitions, blockStates.variants(), blockStates.multiparts(),
            blockTags, blockDefaultStateKeys, blockItemAliases, blockEntities, beResult.variants());
        ConcurrentMap<String, Item> itemIndex = ItemIndexBuilder.load(
            itemTints, glintItems, models.items(), itemTrees, blockEntities);
        ConcurrentMap<String, Entity> entityIndex = EntityModelLoader.load();
        TextureSynthesizer synthesizer = new TextureSynthesizer(PalettedPermutationLoader.load(stack));

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
            synthesizer
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
    public @NotNull Optional<ColorMap> findColorMap(@NotNull ColorMap.Type type) {
        return this.colorMaps.getOptional(type);
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
     * captured {@code .mcmeta} animation section is forwarded as an {@link AnimationData}.
     */
    @Override
    public @NotNull Optional<AnimationData> findAnimation(@NotNull String textureId) {
        return this.stack.indexed(ResourceId.parse(textureId))
            .flatMap(ResolvedTexture::meta)
            .flatMap(MCMeta::animation)
            .map(PipelineRendererContext::toAnimationData);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Bare texture ids are namespaced to {@code minecraft:} first, then the texture's index row's
     * captured {@code .mcmeta} {@code gui.scaling} section is forwarded.
     */
    @Override
    public @NotNull Optional<MCMeta.GuiScaling> findGuiScaling(@NotNull String textureId) {
        return this.stack.indexed(ResourceId.parse(textureId))
            .flatMap(ResolvedTexture::meta)
            .flatMap(MCMeta::gui);
    }

    /** Adapts a captured {@link MCMeta.Animation} section into the {@link AnimationData} the renderer consumes. */
    private static @NotNull AnimationData toAnimationData(@NotNull MCMeta.Animation animation) {
        ConcurrentList<AnimationData.FrameEntry> frames = Concurrent.newList();
        for (MCMeta.Frame frame : animation.frames())
            frames.add(new AnimationData.FrameEntry(frame.index(), frame.time()));
        return new AnimationData(animation.frametime(), animation.interpolate(), frames, animation.width(), animation.height());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sorted by {@link #primaryTag(String) primary tag} (most-specific tag, or material prefix
     * fallback) then id, both case-insensitive, so semantically related blocks cluster in atlas output.
     */
    @Override
    public @NotNull ConcurrentList<String> knownBlockIds() {
        ArrayList<String> ids = new ArrayList<>(this.blockIndex.keySet());
        ids.sort((a, b) -> {
            String groupA = primaryTag(a);
            String groupB = primaryTag(b);
            int cmp = String.CASE_INSENSITIVE_ORDER.compare(groupA, groupB);
            return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
        });
        return Concurrent.adoptList(ids);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sorted by {@link #idPrefix(String) material prefix} then id, both case-insensitive.
     */
    @Override
    public @NotNull ConcurrentList<String> knownItemIds() {
        ArrayList<String> ids = new ArrayList<>(this.itemIndex.keySet());
        ids.sort((a, b) -> {
            int cmp = String.CASE_INSENSITIVE_ORDER.compare(idPrefix(a), idPrefix(b));
            return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
        });
        return Concurrent.adoptList(ids);
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
        return Concurrent.adoptList(new ArrayList<>(this.bannerPatterns.values()));
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
     * Resolves the glint decision once via {@link GlintEvaluator} (the highest-precedence matching
     * {@code type=enchantment} rule, else the merged {@code useGlint} toggle), then walks the merged CIT
     * rule list first-match-wins, skipping non-{@link CitType#ITEM} rules (only item rules retexture
     * icons), and grafts the glint onto the winning rule's effect. When no item rule matches the glint
     * still rides through - so {@code useGlint=false} and enchantment glint replacements apply even to an
     * un-retextured icon.
     */
    @Override
    public @NotNull CitResult resolveItemTextureOverride(@NotNull ItemContext context) {
        GlintPolicy glint = GlintEvaluator.evaluate(this.stack.rules(), context);
        for (CitRule rule : this.stack.rules().citRules()) {
            if (rule.type() != CitType.ITEM) continue;
            if (rule.matches(context)) return CitResult.of(rule.output(), glint);
        }
        return glint == GlintPolicy.DEFAULT ? CitResult.NONE : CitResult.NONE.withGlint(glint);
    }

    /**
     * Returns the most specific tag name for a block (the tag with fewest members), or the
     * block's material prefix as a fallback for untagged blocks. Used as the primary sort key
     * so semantically related blocks cluster together in atlas output.
     */
    private @NotNull String primaryTag(@NotNull String blockId) {
        Block block = this.blockIndex.get(blockId);

        if (block != null && !block.tags().isEmpty()) {
            return block.tags()
                .stream()
                .filter(this.blockTags::containsKey)
                .min(Comparator.comparingInt(tag -> this.blockTags.get(tag).values().size()))
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
