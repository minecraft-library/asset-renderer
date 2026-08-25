package lib.minecraft.renderer.engine;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.asset.BannerPattern;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.ArmorMaterial;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.asset.pack.rule.RuleSet;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.engine.kit.AnimationKit;
import lib.minecraft.renderer.engine.texture.Biome;
import lib.minecraft.renderer.engine.texture.RedstoneTint;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Looks up the biome colormap serving the given tint target from the highest-priority pack that
     * supplies one. Only a target naming a {@link Block.TintTarget#colorMapName() colormap} can have
     * one registered; any other answers empty.
     *
     * @param target the tint target the colormap serves
     * @return the matching colormap, or empty if none is registered
     */
    @NotNull Optional<ColorMap> findColorMap(Block.@NotNull TintTarget target);

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
     * <li>A target carrying no {@link Block.TintTarget#packKeyPrefix() key prefix} -
     * {@link Block.TintTarget#NONE NONE} and {@link Block.TintTarget#CONSTANT CONSTANT} - has no
     * biome channel and answers opaque white; {@code CONSTANT} defers to the block DTO's own
     * constant and should not be routed here.</li>
     * <li>The pack's {@code color.properties} override for this target and biome.</li>
     * <li>The biome's own {@link Biome#colorOverride(Block.TintTarget) hardcoded override}
     * (badlands, cherry grove, water).</li>
     * <li>A sample from the target's {@link ColorMap} at {@code (temperature, downfall)}.</li>
     * <li>The target's {@link Block.TintTarget#defaultArgb() default} when no colormap is
     * registered - white, or vanilla's water colour for {@code WATER}, which samples none.</li>
     * </ol>
     * Every answer but the last is post-processed by
     * {@link Biome#applyModifier(Block.TintTarget, int)}; the default is not, because nothing
     * answered for the modifier to act on.
     *
     * @param target the tint target
     * @param biome the biome context
     * @return the sampled ARGB colour
     */
    default int sampleBiomeTint(Block.@NotNull TintTarget target, @NotNull Biome biome) {
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
     * byte-identical to {@link #resolveTexture}; an animated one has {@link AnimationKit#sampleFrame}
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
            .map(flipbook -> AnimationKit.sampleFrame(strip.get(), flipbook, tick))
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
     * A forwarding mixin for context wrappers: every {@link RendererContext} lookup forwards to
     * {@link #delegate()}, so an implementor overrides only the methods it changes and supplies the
     * wrapped context through {@code delegate()} (a record component named {@code delegate} satisfies it
     * directly).
     *
     * <p>Because every lookup is forwarded rather than defaulted, a wrapper that wants one to behave
     * differently from its delegate must say so explicitly - pinning an override rather than relying on
     * a silent empty default. A lookup a wrapper leaves alone reaches the real context, which is the
     * safe default for a pass-through view; the deliberate exceptions are stated at the override.
     *
     * <p>{@link #sampleBiomeTint} and {@link #sampleRedstoneTint} are deliberately absent: they resolve
     * against {@link #findColorMap} and {@link #findColorOverride}, which this mixin already forwards,
     * so forwarding them too would put a second copy of the resolution behind a wrapper that could
     * drift from the port's. {@link #findFlipbook} is absent for the same reason and one more: it
     * resolves against {@link #resolveTexture} as well as {@link #findAnimation}, and a wrapper that
     * overrides either - flattening a strip to one frame, or pinning the sidecar away - is answered by
     * the default, where a forward would pair the delegate's frame rectangle with the wrapper's pixels.
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
        @Override default @NotNull Optional<Block.Entity> findBlockEntityEntry(@NotNull String blockId) {
            return delegate().findBlockEntityEntry(blockId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Optional<ColorMap> findColorMap(Block.@NotNull TintTarget target) {
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
