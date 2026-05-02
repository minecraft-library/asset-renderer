package lib.minecraft.renderer.engine;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.binding.BannerPattern;
import lib.minecraft.renderer.asset.pack.AnimationData;
import lib.minecraft.renderer.asset.pack.ColorMap;
import lib.minecraft.renderer.asset.pack.Texture;
import lib.minecraft.renderer.asset.pack.TexturePack;
import lib.minecraft.renderer.pipeline.pack.CtmResolution;
import lib.minecraft.renderer.pipeline.pack.CtmRule;
import lib.minecraft.renderer.pipeline.pack.ItemContext;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Ambient state supplied to renderers and the {@link TextureEngine}, abstracting the renderer's
 * view of active texture packs, biome colormaps, model repositories, and other lookup-side state
 * without coupling consumers to a specific implementation. Tests and in-memory callers can
 * supply lightweight stub implementations directly.
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
 * Bulk-iteration accessors that return {@link ConcurrentList} use bare names ({@link #activePacks},
 * {@link #knownBlockIds}, etc.) and provide empty defaults so individual stubs only need to
 * override what they care about.
 */
public interface RendererContext {

    /**
     * The active texture packs in render priority order - highest priority first.
     *
     * @return the pack list
     */
    @NotNull ConcurrentList<TexturePack> activePacks();

    /**
     * Looks up the parsed {@code .mcmeta} animation sidecar for the given texture, if any. The
     * default implementation returns empty so non-animated contexts do not need to override it;
     * animation-aware contexts should look up the associated {@code Texture} entity and forward
     * its {@link Texture#getAnimation() animation} field.
     *
     * @param textureId the namespaced texture identifier
     * @return the animation metadata, or empty when the texture has no sidecar
     */
    default @NotNull Optional<AnimationData> findAnimation(@NotNull String textureId) {
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
     * Resolves a Connected Textures rule for the given block face, walking the parsed
     * {@code optifine/ctm/**} and {@code mcpatcher/ctm/**} rule list in descending weight order
     * and returning the first rule whose {@code appliesTo} predicate accepts the
     * {@code (blockId, baseTextureId, face)} triple.
     * <p>
     * Non-neighbor methods (FIXED, RANDOM, REPEAT, OVERLAY, OVERLAY_FIXED) resolve fully via
     * {@code CtmMatcher.resolve}. Neighbor-based methods currently fall back to {@code tiles[0]}
     * when called through this entry point - resolving them properly requires a pre-computed
     * {@code NeighborPattern} which the single-block renderer doesn't yet supply. The
     * {@code BlockRenderer} hot path also doesn't yet consult this method, so no actual texture
     * substitution happens in render output today; the data is available for tooling and
     * external consumers.
     *
     * @param blockId the block id, e.g. {@code "minecraft:stone_bricks"}
     * @param baseTextureId the vanilla base texture id for the face, e.g. {@code "minecraft:block/stone_bricks"}
     * @param face the face being rendered
     * @return the resolution, or empty when no rule matches
     */
    default @NotNull Optional<CtmResolution> resolveCtm(
        @NotNull String blockId,
        @NotNull String baseTextureId,
        @NotNull CtmRule.Face face
    ) {
        return Optional.empty();
    }

    /**
     * Resolves the highest-priority Custom Item Texture override for a render-time
     * {@link lib.minecraft.renderer.pipeline.pack.ItemContext ItemContext}, walking the parsed
     * {@code optifine/cit/**} and {@code mcpatcher/cit/**} rule list in descending weight order
     * and returning the first rule whose
     * {@link lib.minecraft.renderer.pipeline.pack.CitMatcher} predicate accepts the context. The
     * default returns empty so test stubs do not need to override it.
     *
     * @param context the per-render item context (item id + NBT + enchantments + display name)
     * @return the namespaced output texture id, or empty when no rule matches
     */
    default @NotNull Optional<String> resolveItemTextureOverride(@NotNull ItemContext context) {
        return Optional.empty();
    }

    /**
     * Resolves a texture id to a decoded {@link PixelBuffer} by walking the active packs in
     * priority order. Returns empty only when no pack provides the texture.
     *
     * @param textureId the namespaced texture identifier, e.g. {@code "minecraft:block/grass_block_top"}
     * @return the decoded texture, or empty if unknown
     */
    @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId);

}
