package dev.sbs.renderer.engine;

import dev.sbs.renderer.asset.Block;
import dev.sbs.renderer.asset.pack.ColorMap;
import dev.sbs.renderer.asset.Entity;
import dev.sbs.renderer.asset.Item;
import dev.sbs.renderer.asset.pack.Texture;
import dev.sbs.renderer.asset.pack.TexturePack;
import dev.sbs.renderer.asset.pack.AnimationData;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Ambient state supplied to every {@link TextureEngine} instance, abstracting the renderer's view
 * of the active texture packs, biome colormaps, and model repositories without coupling the
 * engine layer to the pipeline layer.
 * <p>
 * The production implementation is {@code dev.sbs.renderer.pipeline.PipelineRendererContext},
 * built once at bootstrap from an {@code AssetPipeline.Result}; tests and in-memory callers can
 * supply lightweight stub implementations directly.
 */
public interface RendererContext {

    /**
     * The active texture packs in render priority order - highest priority first.
     *
     * @return the pack list
     */
    @NotNull ConcurrentList<TexturePack> activePacks();

    /**
     * Resolves a texture id to a decoded {@link PixelBuffer} by walking the active packs in
     * priority order. Returns empty only when no pack provides the texture.
     *
     * @param textureId the namespaced texture identifier, e.g. {@code "minecraft:block/grass_block_top"}
     * @return the decoded texture, or empty if unknown
     */
    @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId);

    /**
     * Returns a biome colormap of the given kind from the highest-priority pack that supplies one.
     *
     * @param type the colormap kind
     * @return the matching colormap, or empty if none is registered
     */
    @NotNull Optional<ColorMap> colorMap(@NotNull ColorMap.Type type);

    /**
     * Looks up a block entity by its namespaced identifier.
     *
     * @param id the block id
     * @return the block DTO, or empty if unknown
     */
    @NotNull Optional<Block> findBlock(@NotNull String id);

    /**
     * Looks up an item entity by its namespaced identifier.
     *
     * @param id the item id
     * @return the item DTO, or empty if unknown
     */
    @NotNull Optional<Item> findItem(@NotNull String id);

    /**
     * Looks up an entity definition by its namespaced identifier.
     *
     * @param id the entity id
     * @return the entity DTO, or empty if unknown
     */
    @NotNull Optional<Entity> findEntity(@NotNull String id);

    /**
     * Returns the parsed {@code .mcmeta} animation sidecar for the given texture, if any. The
     * default implementation returns empty so non-animated contexts do not need to override it;
     * animation-aware contexts should look up the associated {@code Texture} entity and forward
     * its {@link Texture#getAnimation() animation} field.
     *
     * @param textureId the namespaced texture identifier
     * @return the animation metadata, or empty when the texture has no sidecar
     */
    default @NotNull Optional<AnimationData> animationFor(@NotNull String textureId) {
        return Optional.empty();
    }

    /**
     * Returns every block id this context knows about, in no guaranteed order.
     * <p>
     * Used by the bulk-iteration consumers ({@link dev.sbs.renderer.AtlasRenderer AtlasRenderer},
     * future bulk preview tools) that want to render every available block without going through
     * a separate model registry. The default returns an empty list so individual-lookup callers
     * do not need to override it; production contexts ({@code PipelineRendererContext}) supply
     * the full set.
     *
     * @return the list of known block ids
     */
    default @NotNull ConcurrentList<String> knownBlockIds() {
        return Concurrent.newList();
    }

    /**
     * Returns every item id this context knows about, in no guaranteed order. See
     * {@link #knownBlockIds()} for the contract.
     *
     * @return the list of known item ids
     */
    default @NotNull ConcurrentList<String> knownItemIds() {
        return Concurrent.newList();
    }

    /**
     * Returns the ARGB display colour for a potion effect, used by potion-bottle and tipped-arrow
     * rendering to tint the liquid / head layer. The default returns empty so test stubs do not
     * need to override it; the production context reads the bundled
     * {@code /renderer/potion_colors.json} snapshot.
     *
     * @param effectId the namespaced effect id, e.g. {@code "minecraft:strength"}
     * @return the effect colour, or empty when the effect is unknown
     */
    default @NotNull Optional<Integer> potionEffectColor(@NotNull String effectId) {
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
    default @NotNull Optional<dev.sbs.renderer.asset.binding.BannerPattern> findBannerPattern(@NotNull String patternId) {
        return Optional.empty();
    }

    /**
     * Returns every banner pattern the context knows about, in no guaranteed order. Used by
     * bulk consumers (pattern pickers, preview grids) that want the whole set.
     *
     * @return the list of known patterns
     */
    default @NotNull ConcurrentList<dev.sbs.renderer.asset.binding.BannerPattern> knownBannerPatterns() {
        return Concurrent.newList();
    }

    /**
     * Looks up the block-entity metadata for a block id. Returns the {@link Block.Entity} carrying
     * the extracted geometry (from {@code tile_entity_models.json}), entity texture binding, icon
     * rotation, multi-block flag, per-entry tint, and atlas-time composition parts used by
     * {@link dev.sbs.renderer.BlockRenderer BlockRenderer} for blocks whose vanilla rendering is
     * hardcoded in tile-entity renderers (banners, beds, chests, shulker boxes, signs, skulls,
     * conduit, decorated_pot, etc.).
     * <p>
     * Equivalent to {@code findBlock(blockId).flatMap(Block::getEntity)} once step 4 of the
     * refactor drops the synthetic-Block injection. Kept as a first-class lookup so atlas
     * rendering and context wrappers like {@code StaticTextureContext} can forward a single
     * method call without chaining through {@link Block}.
     *
     * @param blockId the block id
     * @return the entity metadata, or empty when the block has no block-entity mapping
     */
    default @NotNull Optional<Block.Entity> findBlockEntityEntry(@NotNull String blockId) {
        return Optional.empty();
    }

}
