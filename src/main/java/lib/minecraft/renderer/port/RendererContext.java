package lib.minecraft.renderer.port;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.rule.RuleSet;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.port.answer.CitResult;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.BannerPattern;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.equipment.ArmorMaterial;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The engine's resource-provider port: the read-only view of active texture packs, biome
 * colormaps, model repositories, and other lookup-side state that every renderer and engine
 * subsystem consumes, without coupling consumers to a specific implementation. The
 * {@link ClientAcquisition pipeline} supplies the production implementation;
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
 * <p>
 * The port declares lookups and nothing derived from them. A texture's frame at a tick is
 * {@link Flipbook#atTick} over {@link #resolveTexture} and {@link #findFlipbook}; a biome or
 * redstone tint is {@code bake.texture.Tints} over {@link #findColorOverride} and
 * {@link #findColorMap}. So a wrapper that overrides a lookup is picked up by everything derived from
 * it, because the derivation asks the wrapper.
 */
@Parity(ignored = true)
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public interface RendererContext {

    /**
     * Looks up the parsed {@code .mcmeta} animation sidecar for the given texture, if any. The
     * default implementation returns empty so non-animated contexts do not need to override it;
     * animation-aware contexts should look up the texture's index row and adapt its captured
     * sidecar's animation section.
     *
     * @param textureId the namespaced texture identifier
     * @return the animation metadata, or empty when the texture has no sidecar
     */
    default @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) {
        return Optional.empty();
    }

    /**
     * Looks up a texture's animation sidecar resolved against the strip it plays over - the
     * {@link Flipbook playback table} {@link Flipbook#atTick} samples, and the cadence a schedule is
     * derived from. Empty when the texture ships no sidecar, does not resolve, or holds no whole
     * frame.
     * <p>
     * A context holding a texture index answers the table it resolved when the sidecar was parsed,
     * which is what keeps a flipbook's entry sequence off the per-fetch path. One without derives it
     * from its own {@link #findAnimation} and {@link #resolveTexture} through
     * {@link Flipbook#of(Optional, java.util.function.Supplier)}, which asks for the sidecar first so
     * a texture that ships no animation decodes nothing.
     *
     * @param textureId the namespaced texture identifier
     * @return the resolved playback table, or empty when the texture plays back no animation
     */
    @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId);

    /**
     * Looks up the parsed {@code .mcmeta} sidecar for a texture, if any - the whole document, whose
     * sections the caller reads off the record. The default returns empty so non-pack contexts do not
     * need to override it; the production context forwards the texture's index row's captured sidecar.
     *
     * @param textureId the namespaced texture id
     * @return the parsed sidecar, or empty when the texture ships none
     */
    default @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) {
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
     * Looks up the block-entity metadata for a block id. Returns the {@link Block.BlockEntity} carrying
     * the extracted geometry (from {@code tile_entity_models.json}), entity texture binding, icon
     * rotation, multi-block flag, per-entry tint, and atlas-time composition parts used by
     * {@code BlockRenderer} for blocks whose vanilla rendering is hardcoded in
     * tile-entity renderers (banners, beds, chests, shulker boxes, signs, skulls, conduit,
     * decorated_pot, etc.).
     * <p>
     * Kept as a first-class lookup so atlas rendering and context wrappers like
     * the {@link #withTextures} factory can answer a single lookup without chaining through
     * {@link Block}.
     *
     * @param blockId the block id
     * @return the entity metadata, or empty when the block has no block-entity mapping
     */
    default @NotNull Optional<Block.BlockEntity> findBlockEntityEntry(@NotNull String blockId) {
        return Optional.empty();
    }

    /**
     * Looks up the biome colormap serving the given tint target from the highest-priority pack that
     * supplies one. Only a target naming a {@link TintSource#colorMapName() colormap} can have
     * one registered; any other answers empty.
     *
     * @param target the tint target the colormap serves
     * @return the matching colormap, or empty if none is registered
     */
    @NotNull Optional<ColorMap> findColorMap(@NotNull TintSource target);

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
     * Used by the bulk-iteration consumers ({@code AtlasRenderer},
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
     * <p>Connected Textures resolve through {@link #resolveConnectedTexture} - the base-replacing methods
     * substitute a matched face's tile for an isolated block icon; overlays and world-state predicates
     * stay inert.
     *
     * @param context the per-render item context (item id + NBT + enchantments + display name)
     * @return the CIT effect, or {@link CitResult#NONE} when no rule matches
     */
    default @NotNull CitResult resolveItemTextureOverride(@NotNull ItemContext context) {
        return CitResult.NONE;
    }

    /**
     * Resolves the highest-precedence CIT armor / elytra retexture for an equipped piece - the
     * {@code type=armor} / {@code type=elytra} analogue of {@link #resolveItemTextureOverride}, walking
     * the merged CIT rule list first-match-wins for the layer type's subject and returning the winning
     * rule's effect. The default returns {@link CitResult#NONE} so every stub and a vanilla-only stack
     * leaves the equipment model's own texture in force.
     *
     * @param material the equipped piece's armor material
     * @param layerType the render layer being textured; {@link LayerType#WINGS} selects the elytra
     *     subject, any other the armor subject
     * @param item the per-render item context (the equipped item's id + NBT) the rule matches against
     * @return the CIT effect, or {@link CitResult#NONE} when no rule matches
     */
    default @NotNull CitResult resolveArmorTextureOverride(
        @NotNull ArmorMaterial material, @NotNull LayerType layerType, @NotNull ItemContext item) {
        return CitResult.NONE;
    }

    /**
     * Resolves the Connected Textures substitution for one face of an isolated block icon - the
     * highest-precedence matching non-overlay rule replaces the face's base texture with its no-neighbor
     * tile. Walks the merged CTM rules first-match-wins (see
     * {@link RuleSet#connectedTextureFor}); the base-replacing
     * methods ({@code ctm} family / {@code fixed} / {@code random} / {@code repeat} / {@code top})
     * substitute a matched face's tile, while overlays and world-state predicates stay inert. The default
     * returns empty so every stub and vanilla-only stack is inert.
     *
     * <p>{@code faces} targeting uses the model-local face, which equals the world face for the full-cube
     * blocks CTM applies to; the flat {@code BlockFace2D} sprite path is not wired.
     *
     * @param blockId the rendered block's namespaced id
     * @param state the rendered block state, keyed by property name to its value
     * @param baseTextureId the concrete resolved texture id of the face
     * @param face the renderer block face being drawn
     * @return the substitute texture id, or empty when no rule replaces the base
     */
    default @NotNull Optional<ResourceId> resolveConnectedTexture(
        @NotNull String blockId, @NotNull Map<String, String> state,
        @NotNull String baseTextureId, @NotNull Face face) {
        return Optional.empty();
    }

    /**
     * Resolves the ordered equipment texture layers for an asset id under a layer type - the
     * data-driven source for worn-armor, elytra, and mob-equipment textures, exposing the parsed
     * {@code equipment/*.json} model the way {@link #resolveTexture} exposes pack bytes. The default
     * returns an empty list, so every stub and a stack with no equipment index resolves to no layers;
     * a slot with no layers simply does not texture (the no-missing-texture-fallback contract).
     *
     * @param assetId the equipment asset id (e.g. {@code minecraft:iron}, {@code minecraft:elytra})
     * @param layerType the render layer whose subdir the textures sit under
     * @return the ordered base-to-overlay layers, or an empty list when the stack ships no such asset
     */
    default @NotNull List<EquipmentModel.Layer> resolveEquipmentLayers(
        @NotNull ResourceId assetId, @NotNull LayerType layerType) {
        return List.of();
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
     * Answers textures out of the given source, falling through to this context for every id the
     * source does not serve.
     *
     * <p>A substituted texture is reported as carrying no animation: {@link #findAnimation} and
     * {@link #findFlipbook} answer empty for it and {@link #findMeta} answers this context's document
     * with its animation section cleared. The three move together on purpose - the paragraph on {@link Forwarding} explains why
     * pinning one without the other leaves a wrapper contradicting itself, and a caller supplying raw
     * buffers has no strip for a sidecar to describe.
     *
     * @param source the substitute texture lookup, answering empty for an id it does not serve
     * @return a context answering through the source
     */
    default @NotNull RendererContext withTextures(
        @NotNull Function<String, Optional<PixelBuffer>> source) {
        RendererContext delegate = this;
        return new Forwarding() {

            @Override public @NotNull RendererContext delegate() {
                return delegate;
            }

            @Override public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
                Optional<PixelBuffer> substituted = source.apply(textureId);
                return substituted.isPresent() ? substituted : delegate.resolveTexture(textureId);
            }

            @Override public @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) {
                return source.apply(textureId).isPresent()
                    ? Optional.empty()
                    : delegate.findAnimation(textureId);
            }

            @Override public @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId) {
                return source.apply(textureId).isPresent()
                    ? Optional.empty()
                    : delegate.findFlipbook(textureId);
            }

            @Override public @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) {
                if (source.apply(textureId).isEmpty()) return delegate.findMeta(textureId);
                return delegate.findMeta(textureId).map(meta -> new MCMeta(
                    meta.id(), meta.pack(), Optional.empty(),
                    meta.texture(), meta.gui(), meta.villager()));
            }
        };
    }

    /**
     * Reserves one texture id for a supplied buffer, answering no sidecar at all for it.
     *
     * <p>Distinct from {@link #withTextures} in what it says about metadata, and the difference is
     * the point: this reserves a <i>synthetic</i> texture - one no pack supplies - so there is no
     * sidecar to describe it and {@link #findMeta}, {@link #findAnimation} and {@link #findFlipbook}
     * all answer empty, whatever the delegate would have said. {@code withTextures} substitutes the pixels of a
     * texture that still exists, so its sidecar survives minus the animation it no longer plays.
     *
     * @param textureId the id this context answers for
     * @param buffer the pixels to answer with
     * @return a context serving that one texture
     */
    default @NotNull RendererContext withTexture(
        @NotNull String textureId, @NotNull PixelBuffer buffer) {
        RendererContext delegate = this;
        return new Forwarding() {

            @Override public @NotNull RendererContext delegate() {
                return delegate;
            }

            @Override public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String id) {
                return textureId.equals(id) ? Optional.of(buffer) : delegate.resolveTexture(id);
            }

            @Override public @NotNull Optional<MCMeta> findMeta(@NotNull String id) {
                return textureId.equals(id) ? Optional.empty() : delegate.findMeta(id);
            }

            @Override public @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String id) {
                return textureId.equals(id) ? Optional.empty() : delegate.findAnimation(id);
            }

            @Override public @NotNull Optional<Flipbook> findFlipbook(@NotNull String id) {
                return textureId.equals(id) ? Optional.empty() : delegate.findFlipbook(id);
            }
        };
    }

    /**
     * Opens a builder over an in-memory context whose every lookup answers empty until the builder
     * supplies it - {@code builder().build()} is the wholly empty context.
     *
     * @return a new builder
     */
    static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * A builder for an in-memory {@link RendererContext}, for a caller holding its assets in maps
     * rather than loading them from a client. Every lookup starts empty, so a call here is a statement
     * that the context serves that lookup.
     */
    final class Builder {

        private @NotNull Function<String, Optional<PixelBuffer>> textures = textureId -> Optional.empty();
        private @NotNull Map<String, Block> blocks = Map.of();
        private @NotNull Map<String, Item> items = Map.of();
        private @NotNull Map<String, Entity> entities = Map.of();
        private @NotNull Map<TintSource, ColorMap> colorMaps = Map.of();
        private @NotNull Map<String, Integer> colorOverrides = Map.of();

        private Builder() {}

        /**
         * Answers every texture lookup through the given source.
         *
         * @param source the texture source, answering empty for an id it does not serve
         * @return this builder
         */
        public @NotNull Builder textures(@NotNull Function<String, Optional<PixelBuffer>> source) {
            this.textures = source;
            return this;
        }

        /**
         * Answers a texture id out of the given buffers, and every id absent from them with empty.
         *
         * @param byId the buffers keyed by namespaced texture id
         * @return this builder
         */
        public @NotNull Builder texturesById(@NotNull Map<String, PixelBuffer> byId) {
            Map<String, PixelBuffer> buffers = Map.copyOf(byId);
            return this.textures(textureId -> Optional.ofNullable(buffers.get(textureId)));
        }

        /**
         * Supplies the block definitions.
         *
         * @param blocks the block definitions keyed by namespaced id
         * @return this builder
         */
        public @NotNull Builder blocks(@NotNull Map<String, Block> blocks) {
            this.blocks = Map.copyOf(blocks);
            return this;
        }

        /**
         * Supplies the item definitions.
         *
         * @param items the item definitions keyed by namespaced id
         * @return this builder
         */
        public @NotNull Builder items(@NotNull Map<String, Item> items) {
            this.items = Map.copyOf(items);
            return this;
        }

        /**
         * Supplies the entity definitions.
         *
         * @param entities the entity definitions keyed by namespaced id
         * @return this builder
         */
        public @NotNull Builder entities(@NotNull Map<String, Entity> entities) {
            this.entities = Map.copyOf(entities);
            return this;
        }

        /**
         * Supplies the biome colormaps; a target absent from the map answers empty.
         *
         * @param colorMaps the colormaps keyed by the tint target each serves
         * @return this builder
         */
        public @NotNull Builder colorMaps(@NotNull Map<TintSource, ColorMap> colorMaps) {
            this.colorMaps = Map.copyOf(colorMaps);
            return this;
        }

        /**
         * Supplies the pack colour overrides; a key absent from the map answers empty.
         *
         * @param colorOverrides the overrides keyed by their {@code color.properties} key
         * @return this builder
         */
        public @NotNull Builder colorOverrides(@NotNull Map<String, Integer> colorOverrides) {
            this.colorOverrides = Map.copyOf(colorOverrides);
            return this;
        }

        /**
         * Materialises the context.
         *
         * @return the in-memory context
         */
        public @NotNull RendererContext build() {
            return new MapRendererContext(this.textures, this.blocks, this.items, this.entities,
                this.colorMaps, this.colorOverrides);
        }

    }

    /**
     * Answers entity lookups out of the given definitions, falling through to this context for every
     * id they do not hold.
     *
     * @param entities the entity definitions keyed by namespaced id
     * @return a context answering entities through the given definitions
     */
    default @NotNull RendererContext withEntities(@NotNull Map<String, Entity> entities) {
        RendererContext delegate = this;
        return new Forwarding() {

            @Override public @NotNull RendererContext delegate() {
                return delegate;
            }

            @Override public @NotNull Optional<Entity> findEntity(@NotNull String id) {
                Entity entity = entities.get(id);
                return entity != null ? Optional.of(entity) : delegate.findEntity(id);
            }
        };
    }

    /**
     * Draws the checkerboard for every texture this context does not supply, reporting each such id
     * the first time any substituting context is asked for it.
     *
     * <p>Only the pixels are substituted, and that is what makes everything derived from
     * {@link #resolveTexture} total: {@link Flipbook#atTick} over this context's answers never answers
     * empty. {@link #findFlipbook} is forwarded - an id this context resolves keeps the playback table
     * it resolved, and an id it does not resolve has none, so no table is ever paired with the
     * sprite.
     *
     * @return a context that never answers a texture lookup empty
     */
    default @NotNull RendererContext withMissingTexture() {
        RendererContext delegate = this;
        return new Forwarding() {

            @Override public @NotNull RendererContext delegate() {
                return delegate;
            }

            @Override public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
                return delegate.resolveTexture(textureId)
                    .or(() -> Optional.of(MissingTextureReport.substitute(textureId)));
            }
        };
    }

    /**
     * Answers empty for the named textures, and through to this context for every other id.
     *
     * <p>All four texture lookups are pinned together for the reason {@link Forwarding} states: a
     * hidden texture has no pixels, no sidecar, no animation and no playback table, and a wrapper
     * answering only the first would still describe one through the others. Ids are compared after
     * {@link ResourceId#parse parsing}, so a bare id and its namespaced spelling name one texture.
     *
     * @param textureIds the texture ids this context answers empty for
     * @return a context hiding those textures
     */
    default @NotNull RendererContext hiding(@NotNull Set<String> textureIds) {
        RendererContext delegate = this;
        Set<String> hidden = textureIds.stream()
            .map(id -> ResourceId.parse(id).id())
            .collect(Collectors.toUnmodifiableSet());
        return new Forwarding() {

            @Override public @NotNull RendererContext delegate() {
                return delegate;
            }

            private boolean isHidden(@NotNull String textureId) {
                return hidden.contains(ResourceId.parse(textureId).id());
            }

            @Override public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
                return isHidden(textureId) ? Optional.empty() : delegate.resolveTexture(textureId);
            }

            @Override public @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) {
                return isHidden(textureId) ? Optional.empty() : delegate.findAnimation(textureId);
            }

            @Override public @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) {
                return isHidden(textureId) ? Optional.empty() : delegate.findMeta(textureId);
            }

            @Override public @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId) {
                return isHidden(textureId) ? Optional.empty() : delegate.findFlipbook(textureId);
            }
        };
    }

    /**
     * Answers empty for the named textures, and through to this context for every other id.
     *
     * @param textureIds the texture ids this context answers empty for
     * @return a context hiding those textures
     */
    default @NotNull RendererContext hiding(@NotNull String... textureIds) {
        return hiding(Set.of(textureIds));
    }

    /**
     * A forwarding mixin for context wrappers: every {@link RendererContext} lookup forwards to
     * {@link #delegate()}, so an implementor overrides only the methods it changes and supplies the
     * wrapped context through {@code delegate()} (a record component named {@code delegate} satisfies it
     * directly).
     *
     * <p>Every lookup is forwarded rather than defaulted, so a wrapper that wants one to behave
     * differently from its delegate must say so explicitly - pinning an override rather than relying on
     * a silent empty default. A lookup a wrapper leaves alone reaches the real context, which is the
     * safe default for a pass-through view. Nothing derived from the lookups sits on the port, so there
     * is no derived answer for a forward to reach past: a frame at a tick or a tint computed over a
     * wrapper asks the wrapper.
     *
     * <p><b>A wrapper that pins one lookup owes a thought to the lookups that describe the same
     * thing, and the debt runs both ways.</b> The texture lookups are four views of one texture -
     * {@link #resolveTexture}, {@link #findMeta}, {@link #findAnimation} and {@link #findFlipbook} -
     * and the concrete context reads the animation out of the sidecar and the playback table out of
     * both the animation and the strip. A wrapper overriding only the animation says nothing animates
     * while still handing back a populated animation section through the sidecar; one substituting a
     * strip while the playback table stays forwarded pairs the delegate's frame rectangle with the
     * wrapper's pixels. A wrapper that changes what a texture is pins all four.
     */
    interface Forwarding extends RendererContext {

        /**
         * The wrapped context every non-overridden method forwards to.
         *
         * @return the delegate context
         */
        @NotNull RendererContext delegate();

        /** {@inheritDoc} */
        @Override default @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) {
            return delegate().findAnimation(textureId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId) {
            return delegate().findFlipbook(textureId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) {
            return delegate().findMeta(textureId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<BannerPattern> findBannerPattern(@NotNull String patternId) {
            return delegate().findBannerPattern(patternId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<Block> findBlock(@NotNull String id) {
            return delegate().findBlock(id);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<Block.BlockEntity> findBlockEntityEntry(@NotNull String blockId) {
            return delegate().findBlockEntityEntry(blockId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<ColorMap> findColorMap(@NotNull TintSource target) {
            return delegate().findColorMap(target);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<Integer> findColorOverride(@NotNull String key) {
            return delegate().findColorOverride(key);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<Entity> findEntity(@NotNull String id) {
            return delegate().findEntity(id);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<Item> findItem(@NotNull String id) {
            return delegate().findItem(id);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<ItemModelTree> findItemTree(@NotNull String id) {
            return delegate().findItemTree(id);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<ModelData> findItemModel(@NotNull String modelId) {
            return delegate().findItemModel(modelId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<Integer> findPotionEffectColor(@NotNull String effectId) {
            return delegate().findPotionEffectColor(effectId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull ConcurrentList<BannerPattern> knownBannerPatterns() {
            return delegate().knownBannerPatterns();
        }

        /** {@inheritDoc} */
        @Override default @NotNull ConcurrentList<String> knownBlockIds() {
            return delegate().knownBlockIds();
        }

        /** {@inheritDoc} */
        @Override default @NotNull ConcurrentList<String> knownItemIds() {
            return delegate().knownItemIds();
        }

        /** {@inheritDoc} */
        @Override default @NotNull CitResult resolveItemTextureOverride(@NotNull ItemContext context) {
            return delegate().resolveItemTextureOverride(context);
        }

        /** {@inheritDoc} */
        @Override default @NotNull CitResult resolveArmorTextureOverride(
            @NotNull ArmorMaterial material, @NotNull LayerType layerType, @NotNull ItemContext item) {
            return delegate().resolveArmorTextureOverride(material, layerType, item);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<ResourceId> resolveConnectedTexture(
            @NotNull String blockId, @NotNull Map<String, String> state,
            @NotNull String baseTextureId, @NotNull Face face) {
            return delegate().resolveConnectedTexture(blockId, state, baseTextureId, face);
        }

        /** {@inheritDoc} */
        @Override default @NotNull List<EquipmentModel.Layer> resolveEquipmentLayers(
            @NotNull ResourceId assetId, @NotNull LayerType layerType) {
            return delegate().resolveEquipmentLayers(assetId, layerType);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
            return delegate().resolveTexture(textureId);
        }

    }

}
