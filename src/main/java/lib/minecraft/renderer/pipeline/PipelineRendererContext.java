package lib.minecraft.renderer.pipeline;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.BannerPattern;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.pipeline.loader.BlockIndexLoader;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pipeline.loader.ItemIndexLoader;
import lib.minecraft.renderer.pipeline.pack.IndexedTexture;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelTree;
import lib.minecraft.renderer.pipeline.pack.ResolvedTexture;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.pack.rule.CitResult;
import lib.minecraft.renderer.pipeline.pack.rule.CitRule;
import lib.minecraft.renderer.pipeline.pack.rule.CitType;
import lib.minecraft.renderer.pipeline.pack.rule.GlintEvaluator;
import lib.minecraft.renderer.pipeline.pack.rule.GlintPolicy;
import lib.minecraft.renderer.pipeline.pack.rule.ItemContext;
import lib.minecraft.renderer.pipeline.pack.rule.RuleSet;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;

/**
 * The production {@link RendererContext} implementation, built once at bootstrap from a single
 * {@link Pipeline.Result}. Every {@code findX} / {@code resolveX} method is backed by an
 * eagerly-materialised index so warm-path lookups are pure map accesses; texture pixels stay on
 * disk until the first {@link #resolveTexture(String)} call and are memoised in a per-context cache
 * keyed by the resolved {@code (PackId, ResourceId)}.
 * <p>
 * Construction goes through {@link #of(Pipeline.Result)}, which delegates index building to the
 * pipeline loaders: {@link BlockIndexLoader} and {@link ItemIndexLoader} materialise the block /
 * item indexes (keyed by namespaced id, registry-filtered so parent templates and submodels never
 * become atlas tiles), {@link BlockModelLoader} supplies block-entity geometry, and
 * {@link EntityModelLoader} supplies the entity index. The context itself only wraps the finished,
 * unmodifiable indexes and the resolved {@link PackStack}, and serves lookups.
 * <p>
 * Biome colormaps and per-block tint targets are wired through to render time by
 * {@code ColorMapLoader} and {@link BlockTintsLoader}; the lazy {@code textureCache} is
 * the only mutable map on the context.
 */
@RequiredArgsConstructor
public final class PipelineRendererContext implements RendererContext {

    private final @NotNull PackStack stack;
    private final @NotNull ConcurrentMap<String, Block> blockIndex;
    private final @NotNull ConcurrentMap<String, Item> itemIndex;
    private final @NotNull ConcurrentMap<String, ItemModelTree> itemTrees;
    private final @NotNull ConcurrentMap<String, Entity> entityIndex;
    private final @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMaps;
    private final @NotNull ConcurrentMap<String, BlockTag> blockTags;
    private final @NotNull ConcurrentMap<String, Integer> potionEffectColors;
    private final @NotNull ConcurrentMap<String, BannerPattern> bannerPatterns;
    private final @NotNull ConcurrentMap<String, Block.Entity> blockEntities;
    private final @NotNull RuleSet rules;

    private final @NotNull ImageFactory imageFactory = new ImageFactory();

    /**
     * Per-context memoisation cache of decoded {@link PixelBuffer}s keyed by the resolved
     * {@code (PackId, ResourceId)}, populated lazily on the first resolution of each texture, so a
     * stack-wide and a pack-restricted lookup that land on the same file share one buffer. The only
     * mutable state on the context.
     */
    private final @NotNull ConcurrentMap<CacheKey, PixelBuffer> textureCache = Concurrent.newMap();

    /**
     * Builds a context from a completed pipeline result.
     * <p>
     * Block, item, and entity indexes are materialised eagerly via the pipeline loaders so every
     * {@code findX} lookup is a pure map access. Textures stay on disk until
     * {@link #resolveTexture(String)} is called for them the first time.
     *
     * @param result the pipeline result to wrap
     * @return a new context scoped to the given result
     */
    public static @NotNull PipelineRendererContext of(@NotNull Pipeline.Result result) {
        BlockModelLoader.LoadResult beResult = BlockModelLoader.load();
        ConcurrentMap<String, Block.Entity> blockEntities = beResult.models();
        ConcurrentMap<String, Block> blockIndex = BlockIndexLoader.load(result, blockEntities, beResult.variants());
        ConcurrentMap<String, Item> itemIndex = ItemIndexLoader.load(result, blockEntities);
        ConcurrentMap<String, Entity> entityIndex = loadEntityIndex();

        return new PipelineRendererContext(
            result.getStack(),
            blockIndex,
            itemIndex,
            result.getItemTrees(),
            entityIndex,
            result.getColorMaps(),
            result.getBlockTags(),
            result.getPotionEffectColors(),
            result.getBannerPatterns(),
            blockEntities,
            result.getRules()
        );
    }

    /**
     * Loads the entity index natively from {@link EntityModelLoader#load()}, keyed by namespaced entity
     * id. The loaded {@link Entity} is the full definition the renderer consumes; block-entity models
     * render via the block path now, so only mob entities reach the entity index.
     *
     * @return the populated entity index, keyed by namespaced entity id
     */
    private static @NotNull ConcurrentMap<String, Entity> loadEntityIndex() {
        return EntityModelLoader.load();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<ResourcePack> findPack(@NotNull PackId id) {
        return this.stack.byId(id);
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
        Optional<IndexedTexture> indexed = this.stack.indexed(id);
        if (indexed.isPresent()) {
            PixelBuffer cached = this.textureCache.get(new CacheKey(indexed.get().pack(), id));
            if (cached != null) return Optional.of(cached);
        }
        return decode(this.stack.resolve(id));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull PackId pack, @NotNull ResourceId id) {
        return decode(this.stack.resolveIn(pack, id));
    }

    /** Decodes a resolved texture, memoising on the resolved {@code (PackId, ResourceId)} key. */
    private @NotNull Optional<PixelBuffer> decode(@NotNull Optional<ResolvedTexture> resolved) {
        return resolved.map(texture -> {
            CacheKey key = new CacheKey(texture.pack(), texture.id());
            PixelBuffer cached = this.textureCache.get(key);
            if (cached != null) return cached;

            // PixelBuffer.wrap handles every BufferedImage layout the vanilla 1.21 pack ships -
            // INT_ARGB, INT_RGB, INT_BGR, 4BYTE_ABGR, 3BYTE_BGR, BYTE_INDEXED, BYTE_GRAY, BYTE_BINARY
            // (IndexColorModel), and TYPE_CUSTOM with ComponentColorModel of TYPE_GRAY (2-band
            // tRNS-keyed grayscale) - without applying the sRGB-gamma transform that would inflate
            // raw byte values on calibrated-gray sources.
            PixelBuffer buffer = this.imageFactory.fromFile(texture.file().toFile()).toPixelBuffer();
            this.textureCache.put(key, buffer);
            return buffer;
        });
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
            .flatMap(IndexedTexture::meta)
            .flatMap(MCMeta::animation)
            .map(PipelineRendererContext::toAnimationData);
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
        return this.rules.colors().get(key);
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
        GlintPolicy glint = GlintEvaluator.evaluate(this.rules, context);
        for (CitRule rule : this.rules.citRules()) {
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

    /**
     * The texture cache key: the resolved pack plus the resolved id, so a stack-wide and a
     * pack-restricted lookup that land on the same file share one decoded buffer.
     *
     * @param pack the resolved owning pack
     * @param id the resolved namespaced texture id
     */
    private record CacheKey(@NotNull PackId pack, @NotNull ResourceId id) {}

}
