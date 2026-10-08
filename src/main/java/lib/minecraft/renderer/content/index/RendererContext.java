package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.AssignVia;
import dev.simplified.annotations.ClassBuilder;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.diagnostic.Substitutions;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.BannerPattern;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.equipment.ArmorMaterial;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The lookup surface every renderer reads its assets through: the read-only view of active texture
 * packs, biome colormaps, model repositories, and other lookup-side state that every renderer and kit
 * consumes, without coupling consumers to a specific implementation. {@link #load(ClientAssets)}
 * builds the production implementation out of the client assets a {@link ClientAcquisition} run
 * extracts, and {@link #builder()} an in-memory one from maps; tests supply lightweight stubs
 * directly.
 * <p>
 * Method naming follows two prefixes for the lookups that may answer nothing:
 * <ul>
 * <li><b>{@code findX(...)}</b> - direct keyed lookup. The argument is a single id, enum, or
 * other simple key; the return is whatever the context has stored under that key. Implementations
 * are expected to be O(1)-ish. Answers an absent {@link Possible} when the key is unknown.</li>
 * <li><b>{@code resolveX(...)}</b> - derived or transformative lookup. Walks an internal rule
 * list, decodes a resource off disk, or combines multiple arguments to produce a result. Reach
 * for this prefix when the call is more than a map lookup.</li>
 * </ul>
 * A lookup answering {@link Possible} tells two ways of answering nothing apart: absent where the
 * context does not know the key, and empty where it knows the key and holds nothing under it - a
 * registered block or item that draws nothing, a texture file whose contents yield no pixels, a
 * texture served with no sidecar, a block carrying no block entity, a tint target naming no colormap,
 * or a connected-texture rule that keeps the face's own texture. A lookup with no such case answers
 * present or absent only, and its documentation says why.
 * <p>
 * Bulk-iteration accessors that return {@link ConcurrentList} use bare names ({@link #knownBlockIds},
 * {@link #knownItemIds}, etc.) and provide empty defaults so individual stubs only need to override
 * what they care about.
 * <p>
 * The context declares lookups and nothing derived from them. A texture's frame at a tick is
 * {@link Flipbook#atTick} over {@link #resolveTexture} and {@link #findFlipbook}; a biome or
 * redstone tint is {@code bake.texture.Tints} over {@link #findColorOverride} and
 * {@link #findColorMap}. So a wrapper that overrides a lookup is picked up by everything derived from
 * it, because the derivation asks the wrapper.
 */
@Parity(ignored = true)
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public interface RendererContext {

    /**
     * Looks up the {@code animation} section of a texture's parsed {@code .mcmeta} sidecar. The
     * default reads it off {@link #findMeta}, so a context answers the section and the document it sits
     * in alike.
     *
     * @param textureId the namespaced texture identifier
     * @return the animation section; empty when the texture is served and ships no sidecar, a sidecar
     *     that does not parse, or one with no animation section, and absent when {@link #resolveTexture}
     *     answers absent
     */
    default @NotNull Possible<MCMeta.Animation> findAnimation(@NotNull String textureId) {
        return this.findMeta(textureId).flatMap(meta -> Possible.ofOptional(meta.animation()));
    }

    /**
     * Looks up a texture's animation sidecar resolved against the strip it plays over - the
     * {@link Flipbook playback table} {@link Flipbook#atTick} samples, and the cadence a schedule is
     * derived from.
     * <p>
     * A context holding a texture index answers the table it resolved when the sidecar was parsed,
     * which is what keeps a flipbook's entry sequence off the per-fetch path. The default derives it
     * from {@link #findAnimation} and {@link #resolveTexture} through
     * {@link Flipbook#of(Possible, Supplier)}, which asks for the sidecar first so a texture that ships
     * no animation decodes nothing.
     *
     * @param textureId the namespaced texture identifier
     * @return the resolved playback table; empty when the texture is served and plays nothing - no
     *     sidecar, a sidecar that does not parse or has no animation section, a strip that cannot be
     *     read, or one holding no whole frame - and absent when {@link #resolveTexture} answers absent
     */
    default @NotNull Possible<Flipbook> findFlipbook(@NotNull String textureId) {
        return Flipbook.of(this.findAnimation(textureId), () -> this.resolveTexture(textureId));
    }

    /**
     * Looks up the parsed {@code .mcmeta} sidecar for a texture - the whole document, whose sections
     * the caller reads off the record. The default answers for a context holding no sidecars, so every
     * texture it serves has none; the production context forwards the texture's index row's captured
     * sidecar.
     *
     * @param textureId the namespaced texture id
     * @return the parsed sidecar; empty when the texture is served and ships none, or ships one that
     *     does not parse, and absent when {@link #resolveTexture} answers absent
     */
    default @NotNull Possible<MCMeta> findMeta(@NotNull String textureId) {
        return this.resolveTexture(textureId).isAbsent() ? Possible.absent() : Possible.empty();
    }

    /**
     * Looks up a banner / shield pattern by its namespaced registry id
     * (e.g. {@code "minecraft:creeper"}). Banner and shield rendering share the same pattern
     * registry since MC 1.19.4; the pattern's {@code assetId} drives both atlas paths. The
     * default answers absent so test stubs do not need to override it.
     * <p>
     * It never answers empty: a registered pattern always carries its asset id, and a pattern whose mask
     * texture no pack ships is still answered here, the missing mask being {@link #resolveTexture}'s to
     * report.
     *
     * @param patternId the namespaced pattern id
     * @return the pattern descriptor, or absent when no pattern is registered under the id
     */
    default @NotNull Possible<BannerPattern> findBannerPattern(@NotNull String patternId) {
        return Possible.absent();
    }

    /**
     * Looks up a block by its namespaced identifier.
     * <p>
     * A block the game registers whose model declares nothing to draw, such as air, a light block or a
     * barrier, is known and holds nothing, so it answers empty. A fluid or a portal is registered and
     * its block model declares nothing either, yet vanilla draws it, through a renderer other than the
     * block one; so it answers absent, as a template model and a block the index declines do.
     *
     * @param id the block id
     * @return the block DTO; empty for a registered block that draws nothing, and absent for an id that
     *     is no registered block or one this context has no block to draw - a template model, a block
     *     the index declines, or a fluid or portal another renderer draws
     */
    @NotNull Possible<Block> findBlock(@NotNull String id);

    /**
     * Looks up the block-entity metadata for a block id. Returns the {@link Block.BlockEntity} carrying
     * the extracted geometry (from {@code block_models.json}), entity texture binding, icon
     * rotation, multi-block flag, per-entry tint, and atlas-time composition parts used by
     * {@code BlockRenderer} for blocks whose vanilla rendering is hardcoded in
     * tile-entity renderers (banners, beds, chests, shulker boxes, signs, skulls, conduit,
     * decorated_pot, etc.).
     * <p>
     * Kept as a first-class lookup so a caller holding only an id - the atlas, deciding which item
     * tiles its block pass has already drawn - asks one lookup rather than chaining through
     * {@link Block}. The default derives it from {@link #findBlock}: the entry the block carries, empty
     * for a block carrying none and for one {@link #findBlock} answers empty, and absent for an id
     * {@link #findBlock} answers absent.
     *
     * @param blockId the block id
     * @return the block-entity entry; empty for a known block carrying none or drawing nothing, and
     *     absent for an id this context does not know
     */
    default @NotNull Possible<Block.BlockEntity> findBlockEntityEntry(@NotNull String blockId) {
        return this.findBlock(blockId).flatMap(block -> Possible.ofOptional(block.entity()));
    }

    /**
     * Looks up the biome colormap serving the given tint target from the highest-priority pack that
     * supplies one. Only a target naming a {@link TintSource#colorMapName() colormap} can have one.
     *
     * @param target the tint target the colormap serves
     * @return the matching colormap; empty when the target names no colormap, and absent when it names
     *     one this context was built without - a context loaded from a pack stack holds every colormap a
     *     target names, since its load fails for one no pack ships
     */
    @NotNull Possible<ColorMap> findColorMap(@NotNull TintSource target);

    /**
     * Looks up a pack-supplied colour override by its raw {@code color.properties} key
     * ({@code grass.plains}, {@code foliage.dark_oak}, {@code redstone.0}, etc.). Returns the
     * highest-priority pack's override when multiple packs supply the same key, or absent when no
     * pack does. The default answers absent so test stubs do not need to override it.
     * <p>
     * It never answers empty: a key written blank, or with a value that does not parse as a colour,
     * reads as unset, so the caller falls through to its next source as it does for a key no pack
     * writes.
     *
     * @param key the property key as it appears in {@code optifine/color.properties} or
     *     {@code mcpatcher/color.properties}
     * @return the ARGB override, or absent when no pack supplies a parseable colour for this key
     */
    default @NotNull Possible<Integer> findColorOverride(@NotNull String key) {
        return Possible.absent();
    }

    /**
     * Looks up an entity definition by its namespaced identifier.
     * <p>
     * A held row whose body mesh holds no bone draws nothing: it is known and holds nothing, so it
     * answers empty. The shipped table carries such a row for each registered type vanilla draws
     * nothing for - one its renderer registry binds to the no-op renderer, as it does
     * {@code minecraft:area_effect_cloud}, {@code minecraft:interaction} and {@code minecraft:marker} -
     * and a row a caller supplies with no bone answers the same way.
     *
     * @param id the entity id
     * @return the entity DTO; empty for a held row whose body mesh holds no bone, and absent when this
     *     context holds no row for the id - an id that is no entity type, or a type it has no row to draw
     */
    @NotNull Possible<Entity> findEntity(@NotNull String id);

    /**
     * Looks up an item by its namespaced identifier.
     * <p>
     * An item the pack stack registers - one it ships an {@code items/*.json} definition for - that
     * draws nothing is known and holds nothing, so it answers empty: one whose model declares nothing
     * to draw, as {@code minecraft:air}'s does, and one whose definition's root is
     * {@code minecraft:empty} where no model of its own name draws.
     *
     * @param id the item id
     * @return the item DTO; empty for a registered item that draws nothing, and absent for an id that is
     *     no registered item, or a template model
     */
    @NotNull Possible<Item> findItem(@NotNull String id);

    /**
     * Looks up the parsed item-definition dispatch tree for an item id, for the
     * render path to re-evaluate against a caller-supplied non-neutral {@code ItemModelContext} (trim
     * material, clock time, the stack's components). The default answers absent so test stubs and the
     * neutral render path fall back to the pipeline-baked item. A definition the loader refused, or one
     * whose root is a node type in a mod's namespace, answers present - its
     * {@linkplain ItemModelTree#isRejected() rejected} tree, which the render draws as vanilla's missing
     * item model. A definition whose root is {@code minecraft:empty} declares that the item draws
     * nothing, so it answers empty, and the render draws no model for it.
     *
     * @param id the item id
     * @return the item's dispatch tree; empty when the winning definition's root is
     *     {@code minecraft:empty}, and absent when no pack ships a definition for the item
     */
    default @NotNull Possible<ItemModelTree> findItemTree(@NotNull String id) {
        return Possible.absent();
    }

    /**
     * Looks up the model an item definition names, by its FULL model id (e.g.
     * {@code minecraft:item/bow_pulling_0}), for the render path to materialise a tree-resolved or
     * CIT-overridden model without collapsing the id to a basename (which would collide across
     * directories). The lookup spans every model under a pack's {@code models/} tree - an item model, a
     * block model, or one in neither subtree - as vanilla's one model map does, and a bare id reads as
     * {@code minecraft:}. The neutral render path reads it as well, keeping the pipeline-baked item
     * only where the model the walk lands on is that item's own. The default answers absent, so
     * wherever the render walks a leaf, the neutral path's included, the frame draws the missing model.
     *
     * @param modelId the full namespaced model id, or a bare one in the {@code minecraft} namespace
     * @return the parsed model; empty when a model is loaded under the id and declares nothing to draw
     *     as an item - no element face naming a texture and no {@code layerN} binding - which the render
     *     draws as nothing, and absent when no pack ships a model with that id
     */
    default @NotNull Possible<ModelData> findItemModel(@NotNull String modelId) {
        return Possible.absent();
    }

    /**
     * Looks up the ARGB display colour for a potion effect, used by potion-bottle and tipped-arrow
     * rendering to tint the liquid / head layer. The default answers absent so test stubs do not
     * need to override it; the production context reads the bundled
     * {@code /lib/minecraft/renderer/potion_colors.json} snapshot.
     * <p>
     * It never answers empty: every registered vanilla effect carries a colour, so an effect with no
     * row is one the table does not know.
     *
     * @param effectId the namespaced effect id, e.g. {@code "minecraft:strength"}
     * @return the effect colour, or absent when the effect has no row
     */
    default @NotNull Possible<Integer> findPotionEffectColor(@NotNull String effectId) {
        return Possible.absent();
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
     * Every block id {@link #findBlock} answers present for, for a bulk consumer that walks every
     * available block without going through a separate model registry - {@code AtlasRenderer}, or a
     * preview gallery.
     * <p>
     * The order is the implementation's. The production context answers related blocks next to each
     * other ({@link IndexedRendererContext#knownBlockIds()}), which is what a consumer laying them out
     * side by side wants; a caller needing another order sorts its own copy. The default returns an
     * empty list so individual-lookup callers do not need to override it.
     */
    default @NotNull ConcurrentList<String> knownBlockIds() {
        return Concurrent.newUnmodifiableList();
    }

    /**
     * Every item id {@link #findItem} answers present for, for a bulk consumer that walks every
     * available item.
     * <p>
     * See {@link #knownBlockIds()} for the contract; the production context answers related items next
     * to each other ({@link IndexedRendererContext#knownItemIds()}).
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
     * tile. Walks the merged CTM rules first-match-wins; the base-replacing
     * methods ({@code ctm} family / {@code fixed} / {@code random} / {@code repeat} / {@code top})
     * substitute a matched face's tile, while overlays and world-state predicates stay inert. The default
     * serves no rules, so it matches none and every stub is inert.
     *
     * <p>{@code faces} targeting uses the model-local face, which equals the world face for the full-cube
     * blocks CTM applies to; the flat {@code BlockFace2D} sprite path is not wired.
     *
     * @param blockId the rendered block's namespaced id
     * @param state the rendered block state, keyed by property name to its value
     * @param baseTextureId the concrete resolved texture id of the face
     * @param face the renderer block face being drawn
     * @return the substitute texture id; empty when the first deciding non-overlay rule selects
     *     {@code <default>}, keeping the base texture, and absent when no non-overlay rule decides the
     *     face - none matches, or every one that does selects {@code <skip>}
     */
    default @NotNull Possible<ResourceId> resolveConnectedTexture(
        @NotNull String blockId, @NotNull Map<String, String> state,
        @NotNull String baseTextureId, @NotNull Face face) {
        return Possible.absent();
    }

    /**
     * Resolves the ordered equipment texture layers for an asset id under a layer type - the
     * data-driven source for worn-armor, elytra, and mob-equipment textures, exposing the parsed
     * {@code equipment/*.json} model the way {@link #resolveTexture} exposes pack bytes. The default
     * returns an empty list, so every stub and a stack with no equipment index resolves to no layers;
     * a slot with no layers names no texture and simply does not texture, where a layer naming a
     * texture no pack supplies draws the checkerboard, or is refused, as the request reading it says.
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
     * Resolves a texture id to a decoded {@link PixelBuffer} by walking the active packs in priority
     * order, or through a paletted permutation registered under the id where no pack ships it.
     *
     * @param textureId the namespaced texture identifier, e.g. {@code "minecraft:block/grass_block_top"}
     * @return the decoded texture; empty when the id is served and yields no pixels - a file a pack ships
     *     whose bytes are empty or do not decode, whose sidecar does not parse, or whose animation's
     *     frame size does not divide it, or a registered permutation that cannot be produced - and
     *     absent when nothing serves the id
     */
    @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId);

    /**
     * Answers textures out of the given source, falling through to this context for every id the
     * source does not serve. A source that serves an id answers for it even where what it serves cannot
     * be drawn - its empty stands, and this context is not asked for that id.
     *
     * <p>A substituted texture is reported as carrying no animation: {@link #findAnimation} and
     * {@link #findFlipbook} answer empty for it and {@link #findMeta} answers this context's document
     * with its animation section cleared, or empty where this context ships no sidecar for it. The three
     * move together on purpose - the paragraph on {@link Forwarding} explains why
     * pinning one without the other leaves a wrapper contradicting itself, and a caller supplying raw
     * buffers has no strip for a sidecar to describe.
     *
     * @param source the substitute texture lookup, answering absent for an id it does not serve and empty
     *     for one it serves whose pixels cannot be had
     * @return a context answering through the source
     */
    default @NotNull RendererContext withTextures(
        @NotNull Function<String, Possible<PixelBuffer>> source) {
        RendererContext delegate = this;
        return new Forwarding() {

            @Override public @NotNull RendererContext delegate() {
                return delegate;
            }

            @Override public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
                // A source serving the id answers for it, unreadable or not; only an id it does not serve
                // asks the delegate.
                return source.apply(textureId).orAbsent(() -> delegate.resolveTexture(textureId));
            }

            @Override public @NotNull Possible<MCMeta.Animation> findAnimation(@NotNull String textureId) {
                return source.apply(textureId).isAbsent()
                    ? delegate.findAnimation(textureId)
                    : Possible.empty();
            }

            @Override public @NotNull Possible<Flipbook> findFlipbook(@NotNull String textureId) {
                return source.apply(textureId).isAbsent()
                    ? delegate.findFlipbook(textureId)
                    : Possible.empty();
            }

            @Override public @NotNull Possible<MCMeta> findMeta(@NotNull String textureId) {
                if (source.apply(textureId).isAbsent()) return delegate.findMeta(textureId);
                // A texture the source serves and the delegate does not is there with no sidecar, so the
                // delegate's absent answer becomes an empty one.
                return delegate.findMeta(textureId)
                    .map(meta -> new MCMeta(
                        meta.id(), meta.pack(), Optional.empty(),
                        meta.texture(), meta.gui(), meta.villager()))
                    .orAbsent(Possible::empty);
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

            @Override public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String id) {
                return textureId.equals(id) ? Possible.of(buffer) : delegate.resolveTexture(id);
            }

            @Override public @NotNull Possible<MCMeta> findMeta(@NotNull String id) {
                return textureId.equals(id) ? Possible.empty() : delegate.findMeta(id);
            }

            @Override public @NotNull Possible<MCMeta.Animation> findAnimation(@NotNull String id) {
                return textureId.equals(id) ? Possible.empty() : delegate.findAnimation(id);
            }

            @Override public @NotNull Possible<Flipbook> findFlipbook(@NotNull String id) {
                return textureId.equals(id) ? Possible.empty() : delegate.findFlipbook(id);
            }
        };
    }

    /**
     * Builds the production context from the extracted client assets - the call a caller starts with.
     * Compiles the pack stack with its OptiFine rules merged in, runs every domain loader and
     * shipped-table reader, and joins the results into eager indexes, so each {@code findX} lookup is
     * a map access; textures stay on disk until {@link #resolveTexture(String)} is first called.
     *
     * @param assets the extracted client assets (options + vanilla root)
     * @return a new context scoped to the given assets
     * @throws ContentException if a colormap a tint target names is shipped by no pack in the stack or
     *     cannot be decoded, as vanilla's resource reload fails on the same stack
     */
    static @NotNull RendererContext load(@NotNull ClientAssets assets) {
        return IndexedRendererContext.load(assets);
    }

    /**
     * Builds the in-memory context the generated {@link Builder} materialises, for a caller holding its
     * assets in maps rather than loading them from a client. {@code builder().build()} is the wholly
     * empty context: every lookup starts empty, so a call to the builder is a statement that the
     * context serves that lookup. Each lookup map is copied when the context is built, and a texture
     * map when the builder takes it.
     *
     * @param textures the texture source every resolve consults, answering absent for an id it does not
     *     serve and empty for one it serves without pixels; the builder also takes the buffers keyed by
     *     namespaced texture id
     * @param blocks the block definitions keyed by namespaced id
     * @param items the item definitions keyed by namespaced id
     * @param entities the entity definitions keyed by namespaced id
     * @param colorMaps the biome colormaps keyed by the tint target each serves
     * @param colorOverrides the pack colour overrides keyed by their {@code color.properties} key
     * @return the in-memory context
     */
    @ClassBuilder
    private static @NotNull RendererContext of(
        @AssignVia(method = "byId") @Nullable Function<String, Possible<PixelBuffer>> textures,
        @Nullable Map<String, Block> blocks,
        @Nullable Map<String, Item> items,
        @Nullable Map<String, Entity> entities,
        @Nullable Map<TintSource, ColorMap> colorMaps,
        @Nullable Map<String, Integer> colorOverrides
    ) {
        return new MapRendererContext(textures, blocks, items, entities, colorMaps, colorOverrides);
    }

    /**
     * Builds a texture source answering a texture id out of the given buffers, and every id they do not
     * hold as absent.
     *
     * @param byId the buffers keyed by namespaced texture id
     * @return the texture source over a copy of those buffers
     */
    private static @NotNull Function<String, Possible<PixelBuffer>> byId(@NotNull Map<String, PixelBuffer> byId) {
        Map<String, PixelBuffer> buffers = Map.copyOf(byId);
        return textureId -> buffers.containsKey(textureId) ? Possible.of(buffers.get(textureId)) : Possible.absent();
    }

    /**
     * Answers entity lookups out of the given definitions, falling through to this context for every
     * id they do not hold. A held definition whose body mesh holds no bone draws nothing, so it answers
     * empty, whatever this context answers for its id.
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

            @Override public @NotNull Possible<Entity> findEntity(@NotNull String id) {
                if (!entities.containsKey(id)) return delegate.findEntity(id);

                Entity entity = entities.get(id);
                return entity.drawsNothing() ? Possible.empty() : Possible.of(entity);
            }
        };
    }

    /**
     * Draws the checkerboard for every texture this context does not supply, and for every one it
     * supplies that yields no pixels, reporting each id in its own words - missing or unreadable - the
     * first time any substituting context is asked for it.
     *
     * <p>Only the pixels are substituted, and that is what makes everything derived from
     * {@link #resolveTexture} total: {@link Flipbook#atTick} over this context's answers always holds
     * pixels. Serving every texture, it answers none of the three metadata lookups absent: an id this
     * context resolves keeps the sidecar, animation and playback table it resolved, and the
     * checkerboard is a texture with no sidecar, so an id it does not resolve answers all three empty.
     * An id it resolves to no pixels already plays nothing, so no table is ever paired with the sprite.
     * The three ask nothing of {@link #resolveTexture}, so reading metadata reports nothing.
     *
     * @return a context whose texture lookup always answers pixels
     */
    default @NotNull RendererContext withMissingTexture() {
        RendererContext delegate = this;
        return new Forwarding() {

            @Override public @NotNull RendererContext delegate() {
                return delegate;
            }

            @Override public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
                Possible<PixelBuffer> resolved = delegate.resolveTexture(textureId);
                return switch (resolved.getState()) {
                    case PRESENT -> resolved;
                    case EMPTY -> {
                        Substitutions.unreadableTexture(textureId);
                        yield Possible.of(MissingSprite.sprite());
                    }
                    case ABSENT -> {
                        Substitutions.texture(textureId);
                        yield Possible.of(MissingSprite.sprite());
                    }
                };
            }

            @Override public @NotNull Possible<MCMeta> findMeta(@NotNull String textureId) {
                return delegate.findMeta(textureId).orAbsent(Possible::empty);
            }

            @Override public @NotNull Possible<MCMeta.Animation> findAnimation(@NotNull String textureId) {
                return delegate.findAnimation(textureId).orAbsent(Possible::empty);
            }

            @Override public @NotNull Possible<Flipbook> findFlipbook(@NotNull String textureId) {
                return delegate.findFlipbook(textureId).orAbsent(Possible::empty);
            }
        };
    }

    /**
     * Answers the named textures as ones no pack serves, and through to this context for every other
     * id: {@link #resolveTexture} answers absent for them, and so do the three metadata lookups.
     *
     * <p>All four texture lookups are pinned together for the reason {@link Forwarding} states: a
     * hidden texture has no pixels, no sidecar, no animation and no playback table, and a wrapper
     * answering only the first would still describe one through the others. Ids are compared after
     * {@link ResourceId#parse parsing}, so a bare id and its namespaced spelling name one texture.
     *
     * @param textureIds the texture ids this context hides
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

            @Override public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
                return isHidden(textureId) ? Possible.absent() : delegate.resolveTexture(textureId);
            }

            @Override public @NotNull Possible<MCMeta.Animation> findAnimation(@NotNull String textureId) {
                return isHidden(textureId) ? Possible.absent() : delegate.findAnimation(textureId);
            }

            @Override public @NotNull Possible<MCMeta> findMeta(@NotNull String textureId) {
                return isHidden(textureId) ? Possible.absent() : delegate.findMeta(textureId);
            }

            @Override public @NotNull Possible<Flipbook> findFlipbook(@NotNull String textureId) {
                return isHidden(textureId) ? Possible.absent() : delegate.findFlipbook(textureId);
            }
        };
    }

    /**
     * Answers the named textures as ones no pack serves, and through to this context for every other
     * id.
     *
     * @param textureIds the texture ids this context hides
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
     * safe default for a pass-through view. Nothing derived from the lookups sits on the context, so there
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
     *
     * <p>Whatever a wrapper pins, a metadata view answers absent exactly where {@link #resolveTexture}
     * does: a texture the wrapper serves and its delegate does not has empty metadata rather than absent,
     * a texture it hides is absent on all four, and a texture served with no pixels keeps the sidecar it
     * ships while playing nothing.
     */
    interface Forwarding extends RendererContext {

        /**
         * The wrapped context every non-overridden method forwards to.
         *
         * @return the delegate context
         */
        @NotNull RendererContext delegate();

        /** {@inheritDoc} */
        @Override default @NotNull Possible<MCMeta.Animation> findAnimation(@NotNull String textureId) {
            return delegate().findAnimation(textureId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<Flipbook> findFlipbook(@NotNull String textureId) {
            return delegate().findFlipbook(textureId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<MCMeta> findMeta(@NotNull String textureId) {
            return delegate().findMeta(textureId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<BannerPattern> findBannerPattern(@NotNull String patternId) {
            return delegate().findBannerPattern(patternId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<Block> findBlock(@NotNull String id) {
            return delegate().findBlock(id);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<Block.BlockEntity> findBlockEntityEntry(@NotNull String blockId) {
            return delegate().findBlockEntityEntry(blockId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<ColorMap> findColorMap(@NotNull TintSource target) {
            return delegate().findColorMap(target);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<Integer> findColorOverride(@NotNull String key) {
            return delegate().findColorOverride(key);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<Entity> findEntity(@NotNull String id) {
            return delegate().findEntity(id);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<Item> findItem(@NotNull String id) {
            return delegate().findItem(id);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<ItemModelTree> findItemTree(@NotNull String id) {
            return delegate().findItemTree(id);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<ModelData> findItemModel(@NotNull String modelId) {
            return delegate().findItemModel(modelId);
        }

        /** {@inheritDoc} */
        @Override default @NotNull Possible<Integer> findPotionEffectColor(@NotNull String effectId) {
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
        @Override default @NotNull Possible<ResourceId> resolveConnectedTexture(
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
        @Override default @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
            return delegate().resolveTexture(textureId);
        }

    }

}
