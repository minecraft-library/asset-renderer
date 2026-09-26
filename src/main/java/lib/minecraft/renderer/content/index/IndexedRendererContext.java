package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.equipment.ArmorMaterial;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.rule.CitRule;
import lib.minecraft.renderer.asset.rule.CitType;
import lib.minecraft.renderer.asset.rule.RuleSet;
import lib.minecraft.renderer.atlas.AtlasOrder;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.TextureSynthesizer;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.port.answer.CitResult;
import lib.minecraft.renderer.port.answer.CtmContext;
import lib.minecraft.renderer.port.answer.GlintPolicy;
import lib.minecraft.renderer.port.answer.ResolvedTexture;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.BannerPattern;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link RendererContext} whose every {@code findX} / {@code resolveX} answer comes off an
 * eagerly-materialised index, built once at bootstrap from the extracted {@link ClientAssets} by
 * {@link AssetContent#load(ClientAssets)}.
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
@Parity(subject = Subject.ENGINE)
@Parity(ignored = true)
public final class IndexedRendererContext implements RendererContext {

    private final @NotNull PackStack stack;
    private final @NotNull ConcurrentMap<String, Block> blockIndex;
    private final @NotNull ConcurrentMap<String, Item> itemIndex;
    private final @NotNull ConcurrentMap<String, ItemModelTree> itemTrees;
    private final @NotNull ConcurrentMap<String, ModelData> itemModels;
    private final @NotNull ConcurrentMap<String, Entity> entityIndex;
    private final @NotNull ConcurrentMap<TintSource, ColorMap> colorMaps;
    private final @NotNull ConcurrentMap<String, BlockTag> blockTags;
    private final @NotNull ConcurrentMap<String, Integer> potionEffectColors;
    private final @NotNull ConcurrentMap<String, BannerPattern> bannerPatterns;
    private final @NotNull ConcurrentMap<String, Block.BlockEntity> blockEntities;
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
    public @NotNull Optional<ColorMap> findColorMap(@NotNull TintSource target) {
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
     * Sorted by {@link AtlasOrder#primaryTag(String, ConcurrentMap, ConcurrentMap) primary tag} (most-specific tag, or material prefix
     * fallback) then id, both case-insensitive, so semantically related blocks cluster in atlas output.
     */
    @Override
    public @NotNull ConcurrentList<String> knownBlockIds() {
        return this.knownBlockIds;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sorted by {@link AtlasOrder#idPrefix(String) material prefix} then id, both case-insensitive.
     */
    @Override
    public @NotNull ConcurrentList<String> knownItemIds() {
        return this.knownItemIds;
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
    public @NotNull Optional<Block.BlockEntity> findBlockEntityEntry(@NotNull String blockId) {
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

}
