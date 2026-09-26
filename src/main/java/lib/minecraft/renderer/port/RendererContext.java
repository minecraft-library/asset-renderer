package lib.minecraft.renderer.port;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
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
import lib.minecraft.renderer.asset.rule.RuleSet;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.port.answer.CitResult;
import lib.minecraft.renderer.request.Biome;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.BannerPattern;
import lib.minecraft.renderer.vanilla.RedstoneTint;
import lib.minecraft.renderer.vanilla.TintSource;
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
     * Resolves a texture's animation sidecar against the strip it plays over - the
     * {@link Flipbook playback table} {@link #resolveTextureAtTick} samples, and the cadence a
     * schedule is derived from. Empty when the texture ships no sidecar, does not resolve, or holds
     * no whole frame.
     * <p>
     * The default resolves the table on every call; an implementation holding a texture index
     * overrides it to answer the one it resolved when the sidecar was parsed, which is what keeps a
     * flipbook's entry sequence off the per-fetch path. It asks for the sidecar before the strip, so a
     * texture that ships no animation - which is nearly all of them, and every one a context pinning
     * {@link #findAnimation} empty serves - decodes nothing.
     *
     * @param textureId the namespaced texture identifier
     * @return the resolved playback table, or empty when the texture plays back no animation
     */
    default @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId) {
        return findAnimation(textureId).flatMap(animation ->
            resolveTexture(textureId).flatMap(strip -> Flipbook.of(strip, animation)));
    }

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
     * Samples the biome tint for the given target, reading each answer off the target's own table
     * and the biome's own data.
     * <p>
     * Priority order:
     * <ol>
     * <li>A target carrying no {@link TintSource#packKeyPrefix() key prefix} -
     * {@link TintSource#NONE NONE} and {@link TintSource#CONSTANT CONSTANT} - has no
     * biome channel and answers opaque white; {@code CONSTANT} defers to the block DTO's own
     * constant and should not be routed here.</li>
     * <li>The pack's {@code color.properties} override for this target and biome.</li>
     * <li>The biome's own {@link Biome#colorOverride(TintSource) hardcoded override}
     * (badlands, cherry grove, water).</li>
     * <li>A sample from the target's {@link ColorMap} at {@code (temperature, downfall)}.</li>
     * <li>The target's {@link TintSource#defaultArgb() default} when no colormap is
     * registered - white, or vanilla's water colour for {@code WATER}, which samples none.</li>
     * </ol>
     * Every answer but the last is post-processed by
     * {@link Biome#applyModifier(TintSource, int)}; the default is not, because nothing
     * answered for the modifier to act on.
     *
     * @param target the tint target
     * @param biome the biome context
     * @return the sampled ARGB colour
     */
    default int sampleBiomeTint(@NotNull TintSource target, @NotNull Biome biome) {
        Optional<String> prefix = target.packKeyPrefix();
        if (prefix.isEmpty()) return ColorMath.WHITE;

        Optional<Integer> packOverride = findColorOverride(prefix.get() + biome.localName());
        if (packOverride.isPresent()) return biome.applyModifier(target, packOverride.get());

        Optional<Integer> override = biome.colorOverride(target);
        if (override.isPresent()) return biome.applyModifier(target, override.get());

        Optional<ColorMap> map = target.colorMapName().isPresent() ? findColorMap(target) : Optional.empty();
        if (map.isEmpty()) return target.defaultArgb();

        return biome.applyModifier(target, map.get().sample(biome.temperature(), biome.downfall()));
    }

    /**
     * Resolves the redstone-wire ARGB tint for a power level, consulting the active pack's
     * {@code redstone.<power>} {@code color.properties} override before falling back to the bundled
     * vanilla {@link RedstoneTint} table - the same pack-override-then-vanilla shape as
     * {@link #sampleBiomeTint}.
     * <p>
     * The vanilla lookup is resolved into a local before the override is consulted, so an
     * out-of-range power is rejected without a pack ever being asked about it.
     *
     * @param power the redstone wire power level, {@code 0..15}
     * @return the resolved ARGB tint
     * @throws IllegalArgumentException if {@code power} is outside {@code [0, 15]}
     */
    default int sampleRedstoneTint(int power) {
        int vanilla = RedstoneTint.vanilla(power);
        return findColorOverride("redstone." + power).orElse(vanilla);
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
     * Resolves a texture id to the frame that should be displayed at the given tick. A texture with
     * no {@code .mcmeta} sidecar answers its source buffer unchanged, so tick {@code 0} is
     * byte-identical to {@link #resolveTexture}; an animated one has {@link Flipbook#frameAt}
     * extract the strip frame for {@code tick} out of its {@link #findFlipbook playback table},
     * blending adjacent frames when {@link Flipbook#interpolate()} is set.
     *
     * @param textureId the namespaced texture identifier
     * @param tick the current animation tick (free-running, signed)
     * @return the frame to render at this tick, or empty if the texture is unknown
     */
    default @NotNull Optional<PixelBuffer> resolveTextureAtTick(@NotNull String textureId, int tick) {
        Optional<PixelBuffer> strip = resolveTexture(textureId);
        if (strip.isEmpty()) return strip;
        return findFlipbook(textureId)
            .map(flipbook -> flipbook.frameAt(strip.get(), tick))
            .or(() -> strip);
    }

    /**
     * Resolves a texture id the way {@link #resolveTexture} does, refusing an absent texture rather
     * than answering empty for one. The {@code require} prefix marks that arm throughout: a caller
     * that can carry on without the texture reaches for the {@code resolve} form and reads the
     * {@link Optional}, and a caller for which a missing texture is a broken render reaches for this.
     *
     * @param textureId the namespaced texture identifier
     * @return the decoded texture
     * @throws RenderException if no pack provides the texture
     */
    default @NotNull PixelBuffer requireTexture(@NotNull String textureId) {
        return resolveTexture(textureId)
            .orElseThrow(() -> new RenderException("No texture registered for id '%s'", textureId));
    }

    /**
     * Resolves the frame at a tick the way {@link #resolveTextureAtTick} does, refusing an absent
     * texture rather than answering empty for one.
     *
     * @param textureId the namespaced texture identifier
     * @param tick the current animation tick (free-running, signed)
     * @return the frame to render at this tick
     * @throws RenderException if no pack provides the texture
     */
    default @NotNull PixelBuffer requireTextureAtTick(@NotNull String textureId, int tick) {
        return resolveTextureAtTick(textureId, tick)
            .orElseThrow(() -> new RenderException("No texture registered for id '%s'", textureId));
    }

    /**
     * Answers textures out of the given source, falling through to this context for every id the
     * source does not serve.
     *
     * <p>A substituted texture is reported as carrying no animation: {@link #findAnimation} answers
     * empty for it and {@link #findMeta} answers this context's document with its animation section
     * cleared. The two move together on purpose - the paragraph on {@link Forwarding} explains why
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
     * sidecar to describe it and both {@link #findMeta} and {@link #findAnimation} answer empty,
     * whatever the delegate would have said. {@code withTextures} substitutes the pixels of a
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
     * <p>Only the pixels are substituted, and that is what makes every texture lookup built on
     * {@link #resolveTexture} total: {@link #resolveTextureAtTick}, {@link #requireTexture} and
     * {@link #requireTextureAtTick} compute on the wrapper and never answer empty or raise.
     * {@link #findFlipbook} is forwarded rather than derived - an id this context resolves keeps the
     * playback table it resolved, and an id it does not resolve has none, so no table is ever paired
     * with the sprite.
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

            @Override public @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId) {
                return delegate.findFlipbook(textureId);
            }
        };
    }

    /**
     * Answers empty for the named textures, and through to this context for every other id.
     *
     * <p>All three texture lookups are pinned together for the reason {@link Forwarding} states: a
     * hidden texture has no pixels, no sidecar and no animation, and a wrapper answering only the
     * first would still describe one through the other two. Ids are compared after
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
     * <p>Most lookups are forwarded rather than defaulted, so a wrapper that wants one to behave
     * differently from its delegate must say so explicitly - pinning an override rather than relying on
     * a silent empty default. A lookup a wrapper leaves alone reaches the real context, which is the
     * safe default for a pass-through view.
     *
     * <p><b>Six are deliberately not forwarded, and every one of them derives its answer from a lookup
     * that is.</b> Leaving them defaulted is what makes them compute on {@code this}, so they pick a
     * wrapper's override up rather than answering past it. That is the whole reason a wrapper can
     * change one lookup and have everything built on it follow.
     * <ul>
     * <li>{@link #sampleBiomeTint} and {@link #sampleRedstoneTint} resolve against
     * {@link #findColorMap} and {@link #findColorOverride}, which this mixin already forwards, so
     * forwarding them too would put a second copy of the resolution behind a wrapper that could drift
     * from the port's.</li>
     * <li>{@link #findFlipbook} is absent for that reason and one more: it resolves against
     * {@link #resolveTexture} as well as {@link #findAnimation}, and a wrapper that overrides either -
     * flattening a strip to one frame, or pinning the sidecar away - is answered by the default, where
     * a forward would pair the delegate's frame rectangle with the wrapper's pixels.</li>
     * <li>{@link #resolveTextureAtTick}, {@link #requireTexture} and {@link #requireTextureAtTick} all
     * bottom out in {@link #resolveTexture}. Leaving them defaulted is what makes overriding that one
     * method total for the texture path; every wrapper in the tree relies on it, and adding these three
     * to the mixin would quietly answer past all of them.</li>
     * </ul>
     *
     * <p><b>A wrapper that pins one lookup owes a thought to whatever is derived from it, and the debt
     * runs both ways.</b> Pinning a derived lookup while its source stays forwarded lets the two
     * disagree: the concrete context reads {@link #findAnimation} out of {@link #findMeta}, so a
     * wrapper overriding only the first says nothing animates while still handing back a populated
     * animation section through the second. Pinning a source whose derived lookup is forwarded is the
     * same fault mirrored - the derived answer keeps coming from the delegate and describes a texture
     * the wrapper no longer serves.
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
