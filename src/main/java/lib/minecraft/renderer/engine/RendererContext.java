package lib.minecraft.renderer.engine;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.BannerPattern;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelContext;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelNode;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelTree;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelWalker;
import lib.minecraft.renderer.pipeline.pack.rule.CitResult;
import lib.minecraft.renderer.pipeline.pack.rule.ItemContext;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The engine's resource-provider port: the read-only view of active texture packs, biome
 * colormaps, model repositories, and other lookup-side state that every renderer and engine
 * subsystem consumes, without coupling consumers to a specific implementation. The
 * {@link lib.minecraft.renderer.pipeline.Pipeline pipeline} supplies the production implementation;
 * tests and in-memory callers supply lightweight stubs directly.
 * <p>
 * Method naming follows two prefixes for {@link Optional}-returning lookups:
 * <ul>
 * <li><b>{@code findX(...)}</b> - direct keyed lookup. The argument is a single id, enum, or
 * other simple key; the return is whatever the context has stored under that key. Implementations
 * are expected to be O(1)-ish. Returns {@link Optional#empty()} when the key is unknown.</li>
 * <li><b>{@code resolveX(...)}</b> - derived or transformative lookup. Walks an internal rule
 * list, decodes a resource off disk, or combines multiple arguments to produce a result. Reach
 * for this prefix when the call is more than a map lookup.</li>
 * </ul>
 * Bulk-iteration accessors that return {@link ConcurrentList} use bare names ({@link #knownBlockIds},
 * {@link #knownItemIds}, etc.) and provide empty defaults so individual stubs only need to override
 * what they care about.
 */
public interface RendererContext {

    /**
     * Looks up an active resource pack by its id.
     *
     * @param id the pack id, e.g. {@link PackId#VANILLA}
     * @return the pack, or empty when no active pack has that id
     */
    @NotNull Optional<ResourcePack> findPack(@NotNull PackId id);

    /**
     * Looks up the parsed {@code .mcmeta} animation sidecar for the given texture, if any. The
     * default implementation returns empty so non-animated contexts do not need to override it;
     * animation-aware contexts should look up the texture's index row and forward its captured
     * sidecar's animation section.
     *
     * @param textureId the namespaced texture identifier
     * @return the animation metadata, or empty when the texture has no sidecar
     */
    default @NotNull Optional<AnimationData> findAnimation(@NotNull String textureId) {
        return Optional.empty();
    }

    /**
     * Looks up the parsed {@code gui.scaling} sidecar for a GUI-sprite texture, if any - the
     * nine-slice / tile / stretch metadata {@link lib.minecraft.renderer.engine.kit.NineSliceKit}
     * consumes for tooltip and menu chrome. The default returns empty so non-pack contexts do not need
     * to override it; the production context forwards the texture's index-row sidecar's
     * {@code gui.scaling} section.
     *
     * @param textureId the namespaced GUI-sprite texture id
     * @return the scaling metadata, or empty when the texture has no {@code gui.scaling} sidecar
     */
    default @NotNull Optional<MCMeta.GuiScaling> findGuiScaling(@NotNull String textureId) {
        return Optional.empty();
    }

    /**
     * Looks up a banner / shield pattern by its namespaced registry id
     * (e.g. {@code "minecraft:creeper"}). Banner and shield rendering share the same pattern
     * registry since MC 1.19.4; the pattern's {@code assetId} drives both atlas paths. The
     * default returns empty so test stubs do not need to override it.
     *
     * @param patternId the namespaced pattern id
     * @return the pattern descriptor, or empty when the pattern is unknown
     */
    default @NotNull Optional<BannerPattern> findBannerPattern(@NotNull String patternId) {
        return Optional.empty();
    }

    /**
     * Looks up a block entity by its namespaced identifier.
     *
     * @param id the block id
     * @return the block DTO, or empty if unknown
     */
    @NotNull Optional<Block> findBlock(@NotNull String id);

    /**
     * Looks up the block-entity metadata for a block id. Returns the {@link Block.Entity} carrying
     * the extracted geometry (from {@code tile_entity_models.json}), entity texture binding, icon
     * rotation, multi-block flag, per-entry tint, and atlas-time composition parts used by
     * {@link BlockRenderer BlockRenderer} for blocks whose vanilla rendering is hardcoded in
     * tile-entity renderers (banners, beds, chests, shulker boxes, signs, skulls, conduit,
     * decorated_pot, etc.).
     * <p>
     * Kept as a first-class lookup so atlas rendering and context wrappers like
     * {@code StaticTextureContext} can forward a single method call without chaining through
     * {@link Block}.
     *
     * @param blockId the block id
     * @return the entity metadata, or empty when the block has no block-entity mapping
     */
    default @NotNull Optional<Block.Entity> findBlockEntityEntry(@NotNull String blockId) {
        return Optional.empty();
    }

    /**
     * Looks up a biome colormap of the given kind from the highest-priority pack that supplies one.
     *
     * @param type the colormap kind
     * @return the matching colormap, or empty if none is registered
     */
    @NotNull Optional<ColorMap> findColorMap(@NotNull ColorMap.Type type);

    /**
     * Looks up a pack-supplied colour override by its raw {@code color.properties} key
     * ({@code grass.plains}, {@code foliage.dark_oak}, {@code redstone.0}, etc.). Returns the
     * highest-priority pack's override when multiple packs supply the same key, or empty when no
     * pack does. The default returns empty so test stubs do not need to override it.
     *
     * @param key the property key as it appears in {@code optifine/color.properties} or
     *     {@code mcpatcher/color.properties}
     * @return the ARGB override, or empty when no pack supplies this key
     */
    default @NotNull Optional<Integer> findColorOverride(@NotNull String key) {
        return Optional.empty();
    }

    /**
     * Looks up an entity definition by its namespaced identifier.
     *
     * @param id the entity id
     * @return the entity DTO, or empty if unknown
     */
    @NotNull Optional<Entity> findEntity(@NotNull String id);

    /**
     * Looks up an item entity by its namespaced identifier.
     *
     * @param id the item id
     * @return the item DTO, or empty if unknown
     */
    @NotNull Optional<Item> findItem(@NotNull String id);

    /**
     * Looks up the parsed item-definition dispatch tree for an item id, for the
     * render path to re-evaluate against a caller-supplied non-neutral {@code ItemModelContext} (trim
     * material, dye, clock time). The default returns empty so test stubs and the neutral render path
     * fall back to the pipeline-baked item.
     *
     * @param id the item id
     * @return the item's dispatch tree, or empty when the item has no definition file
     */
    default @NotNull Optional<ItemModelTree> findItemTree(@NotNull String id) {
        return Optional.empty();
    }

    /**
     * Looks up a parsed item {@link ModelData} by its FULL model id (e.g. {@code minecraft:item/bow_pulling_0}),
     * for the render path to materialise a tree-resolved or CIT-overridden model without collapsing
     * the id to a basename (which would collide across directories). The default returns empty so test
     * stubs and the neutral render path fall back to the pipeline-baked item.
     *
     * @param modelId the full namespaced model id
     * @return the parsed item model, or empty when no item model has that id
     */
    default @NotNull Optional<ModelData> findItemModel(@NotNull String modelId) {
        return Optional.empty();
    }

    /**
     * Resolves the {@code display.gui} transform a block's inventory icon renders through - the block
     * item's gui, the same source the in-game icon and the vanilla-reference harness use. Walks the
     * block-item's dispatch tree at the neutral {@link ItemModelContext#gui() gui context}; a
     * {@code special} leaf (chest, banner, skull, bed) resolves to its {@code base} item model - the
     * one carrying the gui - while a plain leaf uses its own resolved model, both falling back to
     * {@code <ns>:item/<name>}. Reads that model's flattened {@code display.gui}, finally falling back
     * to the block model's own gui. Returns empty when no gui is authored anywhere, in which case the
     * caller poses the icon at the default iso pose.
     *
     * @param block the block whose inventory-icon gui transform to resolve
     * @return the authored {@code display.gui}, or empty when none is readable
     */
    default @NotNull Optional<ModelTransform> resolveIconGui(@NotNull Block block) {
        String itemId = block.id().namespace() + ":" + block.id().name();
        Optional<ItemModelTree> tree = findItemTree(itemId);

        // The gui transform survives only on a direct model or special item wrapper. A dispatch or
        // composite root (select / condition / range_dispatch / composite - chest's date select,
        // the bed / copper_golem_statue composites) exposes no readable transform, so the in-game
        // icon - and the vanilla-reference harness - render it at the default iso pose. Such a root
        // has no gui override here.
        ItemModelNode root = tree.map(ItemModelTree::root).orElse(null);
        if (root != null && !(root instanceof ItemModelNode.Model) && !(root instanceof ItemModelNode.Special))
            return Optional.empty();

        // A special leaf carries the gui on its base item model (banner / skull on template_*); a plain
        // leaf uses its own resolved model. Both fall back to <ns>:item/<name>.
        String resolved = tree
            .map(t -> ItemModelWalker.resolve(t, ItemModelContext.gui()))
            .map(resolution -> resolution.special()
                .map(ItemModelNode.Special::base)
                .orElseGet(() -> resolution.modelId().orElse(null)))
            .orElse(null);
        String modelId = resolved != null ? resolved : block.id().namespace() + ":item/" + block.id().name();

        Optional<ModelTransform> itemGui = findItemModel(modelId).map(model -> model.getDisplay().get("gui"));
        if (itemGui.isPresent()) return itemGui;
        return Optional.ofNullable(block.model().getDisplay().get("gui"));
    }

    /**
     * Looks up the ARGB display colour for a potion effect, used by potion-bottle and tipped-arrow
     * rendering to tint the liquid / head layer. The default returns empty so test stubs do not
     * need to override it; the production context reads the bundled
     * {@code /lib/minecraft/renderer/potion_colors.json} snapshot.
     *
     * @param effectId the namespaced effect id, e.g. {@code "minecraft:strength"}
     * @return the effect colour, or empty when the effect is unknown
     */
    default @NotNull Optional<Integer> findPotionEffectColor(@NotNull String effectId) {
        return Optional.empty();
    }

    /**
     * Every banner pattern the context knows about, in no guaranteed order.
     * <p>
     * Used by bulk-iteration consumers (pattern pickers, preview grids) that want the whole set.
     */
    default @NotNull ConcurrentList<BannerPattern> knownBannerPatterns() {
        return Concurrent.newUnmodifiableList();
    }

    /**
     * Every block id this context knows about, in no guaranteed order.
     * <p>
     * Used by the bulk-iteration consumers ({@link AtlasRenderer AtlasRenderer},
     * future bulk preview tools) that want to render every available block without going through
     * a separate model registry. The default returns an empty list so individual-lookup callers
     * do not need to override it.
     */
    default @NotNull ConcurrentList<String> knownBlockIds() {
        return Concurrent.newUnmodifiableList();
    }

    /**
     * Every item id this context knows about, in no guaranteed order.
     * <p>
     * See {@link #knownBlockIds()} for the contract.
     */
    default @NotNull ConcurrentList<String> knownItemIds() {
        return Concurrent.newUnmodifiableList();
    }

    /**
     * Resolves the highest-precedence Custom Item Texture effect for a render-time
     * {@link ItemContext}, walking the merged CIT rule list first-match-wins and returning the effect
     * the winning rule applies. The result is a {@link CitResult} carrying the {@code layer0} texture,
     * named sub-texture replacements, a model override, and the glint policy. The default returns
     * {@link CitResult#NONE} so test stubs need not override it.
     *
     * <p>Connected Textures (CTM) has no render seam: it renders nothing, so the
     * merged CTM rules are parse-and-store only, with zero render-path callers (see
     * {@code CtmNeighborResolver}).
     *
     * @param context the per-render item context (item id + NBT + enchantments + display name)
     * @return the CIT effect, or {@link CitResult#NONE} when no rule matches
     */
    default @NotNull CitResult resolveItemTextureOverride(@NotNull ItemContext context) {
        return CitResult.NONE;
    }

    /**
     * Resolves a texture id to a decoded {@link PixelBuffer} by walking the active packs in
     * priority order. Returns empty only when no pack provides the texture.
     *
     * @param textureId the namespaced texture identifier, e.g. {@code "minecraft:block/grass_block_top"}
     * @return the decoded texture, or empty if unknown
     */
    @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId);

    /**
     * Resolves a texture within one specific pack, bypassing the stack-wide namespace-first dispatch -
     * the escape hatch for callers that need a pack-restricted lookup. The default returns empty so
     * test stubs do not need to override it.
     *
     * @param pack the pack to restrict resolution to
     * @param id the namespaced texture id
     * @return the decoded texture, or empty when the pack does not supply it
     */
    default @NotNull Optional<PixelBuffer> resolveTexture(@NotNull PackId pack, @NotNull ResourceId id) {
        return Optional.empty();
    }

}
