package lib.minecraft.renderer.pipeline;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.binding.BannerPattern;
import lib.minecraft.renderer.asset.pack.AnimationData;
import lib.minecraft.renderer.asset.pack.ColorMap;
import lib.minecraft.renderer.asset.pack.Texture;
import lib.minecraft.renderer.asset.pack.TexturePack;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.pipeline.loader.BlockIndexLoader;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pipeline.loader.ItemIndexLoader;
import lib.minecraft.renderer.pipeline.pack.CitMatcher;
import lib.minecraft.renderer.pipeline.pack.CitRule;
import lib.minecraft.renderer.pipeline.pack.CtmMatcher;
import lib.minecraft.renderer.pipeline.pack.CtmResolution;
import lib.minecraft.renderer.pipeline.pack.CtmRule;
import lib.minecraft.renderer.pipeline.pack.ItemContext;
import lib.minecraft.renderer.pipeline.util.Models;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lib.minecraft.renderer.tooling.ToolingColorMaps;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * The production {@link RendererContext} implementation, built once at bootstrap from a single
 * {@link Pipeline.Result}. Every {@code findX} / {@code resolveX} method is backed by an
 * eagerly-materialised index so warm-path lookups are pure map accesses; texture pixels stay on
 * disk until the first {@link #resolveTexture(String)} call and are memoised in a per-context cache.
 * <p>
 * Construction goes through {@link #of(Pipeline.Result)}, which delegates index building to the
 * pipeline loaders: {@link BlockIndexLoader} and {@link ItemIndexLoader} materialise the block /
 * item indexes (keyed by namespaced id, registry-filtered so parent templates and submodels never
 * become atlas tiles), {@link BlockModelLoader} supplies block-entity geometry, and
 * {@link EntityModelLoader} supplies the entity index. The context itself only wraps the finished,
 * unmodifiable indexes and serves lookups.
 * <p>
 * Biome colormaps and per-block tint targets are wired through to render time by
 * {@link ToolingColorMaps.Parser} and {@link BlockTintsLoader}; the lazy {@code textureCache} is
 * the only mutable map on the context.
 */
@RequiredArgsConstructor
public final class PipelineRendererContext implements RendererContext {

    private final @NotNull ConcurrentMap<String, TexturePack> packs;
    private final @NotNull ConcurrentMap<String, Block> blockIndex;
    private final @NotNull ConcurrentMap<String, Item> itemIndex;
    private final @NotNull ConcurrentMap<String, Entity> entityIndex;
    private final @NotNull ConcurrentMap<String, Texture> textures;
    private final @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMaps;
    private final @NotNull ConcurrentMap<String, BlockTag> blockTags;
    private final @NotNull ConcurrentMap<String, Integer> potionEffectColors;
    private final @NotNull ConcurrentMap<String, BannerPattern> bannerPatterns;
    private final @NotNull ConcurrentMap<String, Block.Entity> blockEntities;
    private final @NotNull ConcurrentMap<String, Integer> colorOverrides;
    private final @NotNull ConcurrentList<CitRule> citRules;
    private final @NotNull ConcurrentList<CtmRule> ctmRules;

    private final @NotNull ImageFactory imageFactory = new ImageFactory();
    private final @NotNull ConcurrentMap<String, PixelBuffer> textureCache = Concurrent.newMap();

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

        // Index the active packs by id once at bootstrap. Insertion-ordered so values() preserves
        // render priority (highest first); first-wins on a duplicate id. resolveTexture then does
        // an O(1) pack-by-id lookup instead of a per-call linear scan.
        ConcurrentMap<String, TexturePack> packs = Concurrent.newLinkedMap();
        for (TexturePack pack : result.getPacks())
            packs.putIfAbsent(pack.getId(), pack);

        return new PipelineRendererContext(
            packs,
            blockIndex,
            itemIndex,
            entityIndex,
            result.getTextures(),
            result.getColorMaps(),
            result.getBlockTags(),
            result.getPotionEffectColors(),
            result.getBannerPatterns(),
            blockEntities,
            result.getColorOverrides(),
            result.getCitRules(),
            result.getCtmRules()
        );
    }

    /**
     * Loads the entity index from {@link EntityModelLoader#load()}, materialising each
     * {@link EntityModelLoader.EntityDefinition} into an {@link Entity} DTO with overlay layers
     * flattened into the entity's own {@code Entity.Layer} list. Block-entity models render via
     * the block path now, so only mob entities reach the entity index.
     *
     * @return the populated entity index, keyed by namespaced entity id
     */
    private static @NotNull ConcurrentMap<String, Entity> loadEntityIndex() {
        return EntityModelLoader.load()
            .stream()
            .collect(Concurrent.toMap(Map.Entry::getKey, entry -> {
                String entityId = entry.getKey();
                EntityModelLoader.EntityDefinition definition = entry.getValue();

                return new Entity(
                    entityId,
                    "minecraft",
                    Models.localName(entityId),
                    definition.model(),
                    definition.textureRef(),
                    definition.overlays()
                        .stream()
                        .map(o -> new Entity.Layer(o.model(), o.textureRef(), o.emissive()))
                        .collect(Concurrent.toList())
                        .toUnmodifiable()
                );
            }))
            .toUnmodifiable();
    }

    @Override
    public @NotNull Optional<TexturePack> findPack(@NotNull String id) {
        return Optional.ofNullable(this.packs.get(id));
    }

    @Override
    public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
        String normalized = textureId.contains(":") ? textureId : VanillaSourcePaths.MINECRAFT_NAMESPACE + textureId;
        PixelBuffer cached = this.textureCache.get(normalized);
        if (cached != null) return Optional.of(cached);

        Texture texture = this.textures.get(normalized);
        if (texture == null) return Optional.empty();

        TexturePack owner = this.packs.get(texture.getPackId());
        if (owner == null) return Optional.empty();

        Path winning = null;
        for (Path root : owner.getAssetRoots()) {
            Path candidate = root.resolve(VanillaSourcePaths.TEXTURES_DIR).resolve(texture.getRelativePath());
            if (Files.isRegularFile(candidate)) winning = candidate;
        }
        if (winning == null) return Optional.empty();

        // PixelBuffer.wrap handles every BufferedImage layout the vanilla 1.21 pack ships -
        // INT_ARGB, INT_RGB, INT_BGR, 4BYTE_ABGR, 3BYTE_BGR, BYTE_INDEXED, BYTE_GRAY, BYTE_BINARY
        // (IndexColorModel), and TYPE_CUSTOM with ComponentColorModel of TYPE_GRAY (2-band
        // tRNS-keyed grayscale) - without applying the sRGB-gamma transform that would inflate
        // raw byte values on calibrated-gray sources.
        PixelBuffer buffer = this.imageFactory.fromFile(winning.toFile()).toPixelBuffer();
        this.textureCache.put(normalized, buffer);
        return Optional.of(buffer);
    }

    @Override
    public @NotNull Optional<ColorMap> findColorMap(@NotNull ColorMap.Type type) {
        return this.colorMaps.getOptional(type);
    }

    @Override
    public @NotNull Optional<Block> findBlock(@NotNull String id) {
        return this.blockIndex.getOptional(id);
    }

    @Override
    public @NotNull Optional<Item> findItem(@NotNull String id) {
        return this.itemIndex.getOptional(id);
    }

    @Override
    public @NotNull Optional<Entity> findEntity(@NotNull String id) {
        return this.entityIndex.getOptional(id);
    }

    @Override
    public @NotNull Optional<AnimationData> findAnimation(@NotNull String textureId) {
        String normalized = textureId.contains(":") ? textureId : VanillaSourcePaths.MINECRAFT_NAMESPACE + textureId;
        Texture texture = this.textures.get(normalized);
        return texture == null ? Optional.empty() : texture.getAnimation();
    }

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

    @Override
    public @NotNull ConcurrentList<String> knownItemIds() {
        ArrayList<String> ids = new ArrayList<>(this.itemIndex.keySet());
        ids.sort((a, b) -> {
            int cmp = String.CASE_INSENSITIVE_ORDER.compare(idPrefix(a), idPrefix(b));
            return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
        });
        return Concurrent.adoptList(ids);
    }

    @Override
    public @NotNull Optional<Integer> findPotionEffectColor(@NotNull String effectId) {
        return this.potionEffectColors.getOptional(effectId);
    }

    @Override
    public @NotNull Optional<BannerPattern> findBannerPattern(@NotNull String patternId) {
        return this.bannerPatterns.getOptional(patternId);
    }

    @Override
    public @NotNull ConcurrentList<BannerPattern> knownBannerPatterns() {
        return Concurrent.adoptList(new ArrayList<>(this.bannerPatterns.values()));
    }

    @Override
    public @NotNull Optional<Block.Entity> findBlockEntityEntry(@NotNull String blockId) {
        return this.blockEntities.getOptional(blockId);
    }

    @Override
    public @NotNull Optional<Integer> findColorOverride(@NotNull String key) {
        return this.colorOverrides.getOptional(key);
    }

    @Override
    public @NotNull Optional<String> resolveItemTextureOverride(@NotNull ItemContext context) {
        for (CitRule rule : this.citRules)
            if (CitMatcher.match(rule, context)) return Optional.of(rule.outputTextureId());
        return Optional.empty();
    }

    @Override
    public @NotNull Optional<CtmResolution> resolveCtm(
        @NotNull String blockId,
        @NotNull String baseTextureId,
        @NotNull CtmRule.Face face
    ) {
        for (CtmRule rule : this.ctmRules) {
            if (!rule.appliesTo(blockId, baseTextureId, face)) continue;
            Optional<CtmResolution> resolution = CtmMatcher.resolve(rule, blockId, baseTextureId);
            if (resolution.isPresent()) return resolution;
        }
        return Optional.empty();
    }

    /**
     * Returns the most specific tag name for a block (the tag with fewest members), or the
     * block's material prefix as a fallback for untagged blocks. Used as the primary sort key
     * so semantically related blocks cluster together in atlas output.
     */
    private @NotNull String primaryTag(@NotNull String blockId) {
        Block block = this.blockIndex.get(blockId);
        if (block != null && !block.getTags().isEmpty()) {
            return block.getTags()
                .stream()
                .filter(this.blockTags::containsKey)
                .min(Comparator.comparingInt(tag -> this.blockTags.get(tag).getValues().size()))
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
