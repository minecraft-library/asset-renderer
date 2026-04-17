package dev.sbs.renderer.pipeline;

import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.TextureEngine;
import dev.sbs.renderer.geometry.Biome;
import dev.sbs.renderer.geometry.BlockFace;
import dev.sbs.renderer.asset.Block;
import dev.sbs.renderer.asset.BlockTag;
import dev.sbs.renderer.asset.binding.BannerPattern;
import dev.sbs.renderer.asset.pack.ColorMap;
import dev.sbs.renderer.asset.Entity;
import dev.sbs.renderer.asset.Item;
import dev.sbs.renderer.asset.pack.Texture;
import dev.sbs.renderer.asset.pack.TexturePack;
import dev.sbs.renderer.asset.pack.AnimationData;
import dev.sbs.renderer.asset.model.BlockModelData;
import dev.sbs.renderer.asset.model.EntityModelData;
import dev.sbs.renderer.asset.model.ItemModelData;
import dev.sbs.renderer.asset.model.ModelElement;
import dev.sbs.renderer.asset.model.ModelFace;
import dev.sbs.renderer.pipeline.loader.BlockEntityLoader;
import dev.sbs.renderer.pipeline.loader.BlockTintsLoader;
import dev.sbs.renderer.pipeline.loader.EntityModelLoader;
import dev.sbs.renderer.pipeline.loader.OverlayResolver;
import dev.sbs.renderer.tooling.ToolingColorMaps;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link RendererContext} backed by a single {@link AssetPipeline.Result}.
 * <p>
 * The factory method materialises every parsed {@link BlockModelData} and {@link ItemModelData}
 * entry into a corresponding {@link Block} / {@link Item} entity eagerly, keyed by the derived
 * namespaced id ({@code minecraft:block/grass_block} - &gt; {@code minecraft:grass_block}). Texture
 * pixels are loaded lazily from disk on first {@link #resolveTexture(String)} call and memoised
 * in a per-context cache so repeated lookups stay cheap.
 * <p>
 * Block face bindings are flattened eagerly: the first element's face map is walked, each face's
 * {@code #variable} reference is dereferenced against the model's texture variable map, and the
 * resolved texture id is stored under the vanilla direction key ({@code down}, {@code up},
 * {@code north}, {@code south}, {@code west}, {@code east}) on the block entity. The underlying
 * variable map is preserved under its original keys for models that rely on the
 * {@code all} / {@code side} / {@code particle} fallback chain.
 * <p>
 * Biome colormaps and the {@link Biome.TintTarget} of every known vanilla tinted block are wired
 * through to render time: {@link ToolingColorMaps.Parser} loads {@code grass.png}, {@code foliage.png},
 * and {@code dry_foliage.png} into {@link ColorMap} entities, and {@link BlockTintsLoader}
 * supplies the {@code minecraft:grass_block} - to - {@code GRASS} (etc.) mapping verified against
 * the bytecode of {@code BlockColors$createDefault} in the 26.1 client jar. Entity definitions
 * are still empty - {@link #findEntity(String)} returns {@link Optional#empty()} until a future
 * pipeline phase ships an entity loader.
 */
@RequiredArgsConstructor
public final class PipelineRendererContext implements RendererContext {

    private final @NotNull Path textureRoot;
    private final @NotNull ConcurrentList<TexturePack> packs;
    private final @NotNull ConcurrentMap<String, Block> blockIndex;
    private final @NotNull ConcurrentMap<String, Item> itemIndex;
    private final @NotNull ConcurrentMap<String, Entity> entityIndex;
    private final @NotNull ConcurrentMap<String, Texture> textureIndex;
    private final @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMapIndex;
    private final @NotNull ConcurrentMap<String, BlockTag> blockTagIndex;
    private final @NotNull ConcurrentMap<String, Integer> potionEffectColors;
    private final @NotNull ConcurrentMap<String, BannerPattern> bannerPatterns;
    /**
     * Block ids that were registered via the blockstate-only fallback path (Task 10) rather than
     * the primary block-model iteration. Exposed so downstream diagnostics can tag tiles by
     * registration source.
     */
    @Getter
    private final @NotNull Set<String> blockstateOnlyIds;
    /** Block entity metadata for multi-block centering and icon rotation at render time. */
    @Getter
    private final @NotNull ConcurrentMap<String, Block.Entity> blockEntityEntries;
    private final @NotNull ImageFactory imageFactory = new ImageFactory();
    private final @NotNull ConcurrentMap<String, PixelBuffer> textureCache = Concurrent.newMap();

    /**
     * Builds a context from a completed pipeline result.
     * <p>
     * Block and item entity materialisation happens eagerly so every {@code findBlock} /
     * {@code findItem} lookup is a pure map access. Textures stay on disk until
     * {@link #resolveTexture(String)} is called for them the first time.
     *
     * @param result the pipeline result to wrap
     * @return a new context scoped to the given result
     */
    public static @NotNull PipelineRendererContext of(@NotNull AssetPipeline.Result result) {
        ConcurrentList<TexturePack> packs = Concurrent.newList();
        packs.add(result.getVanillaPack());

        ConcurrentMap<String, Block.Tint> tints = result.getBlockTints();
        ConcurrentMap<String, String> itemDefs = result.getItemDefinitions();
        ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variantMap = result.getBlockStates();
        ConcurrentMap<String, Block.Multipart> multipartMap = result.getBlockMultiparts();
        ConcurrentMap<String, Block.Entity> blockEntityEntries = BlockEntityLoader.load();

        // Build reverse tag index (tag id → block ids becomes block id → tag names)
        ConcurrentMap<String, BlockTag> tagMap = result.getBlockTags();
        ConcurrentMap<String, ConcurrentList<String>> reverseTagIndex = Concurrent.newMap();
        for (Map.Entry<String, BlockTag> tagEntry : tagMap.entrySet()) {
            for (String blockId : tagEntry.getValue().getValues())
                reverseTagIndex.computeIfAbsent(blockId, k -> Concurrent.newList()).add(tagEntry.getKey());
        }

        ConcurrentMap<String, Block> blockIndex = Concurrent.newMap();
        for (Map.Entry<String, BlockModelData> blockEntry : result.getBlockModels().entrySet()) {
            String modelId = blockEntry.getKey();
            BlockModelData model = blockEntry.getValue();
            String blockId = stripPrefix(modelId, ":block/");
            String name = localName(modelId);

            // Use the item definition's model override when the inventory rendering model
            // differs from the blockstate model (e.g. piston -> piston_inventory).
            BlockModelData modelToUse = model;
            String itemModelRef = itemDefs.get(blockId);
            if (itemModelRef != null && !itemModelRef.equals(modelId)) {
                BlockModelData override = result.getBlockModels().get(itemModelRef);
                if (override != null)
                    modelToUse = override;
            }

            ConcurrentMap<String, String> textures = Concurrent.newMap();
            textures.putAll(modelToUse.getTextures());
            flattenElementFaces(modelToUse, textures);

            Block.Tint tint = tints.getOrDefault(blockId, new Block.Tint(Biome.TintTarget.NONE, Optional.empty()));
            ConcurrentMap<String, Block.Variant> variants = variantMap.getOrDefault(blockId, Concurrent.newMap());
            Optional<Block.Multipart> multipart = Optional.ofNullable(multipartMap.get(blockId));
            ConcurrentList<String> tags = reverseTagIndex.getOrDefault(blockId, Concurrent.newList());

            // Tile entities (beds, chests, banners, shulkers, signs, etc.) override the
            // vanilla {@code block.json} model - the template block.json is usually empty
            // (just a {@code particle} texture) and the real geometry is hardcoded in a
            // {@code BlockEntityRenderer}. {@link BlockEntityLoader} has extracted that
            // geometry into {@link Block.Entity#model()} for us; swap it in here and
            // rebind the texture map to the entity texture via the {@code "#entity"} face
            // reference. The {@link Block.Tint} goes to {@link Biome.TintTarget#NONE}
            // because per-entry tints are applied via {@link Block.Entity#tintArgb()} at
            // render time (see {@link BlockRenderer.Isometric3D}).
            // <p>
            // Additive entries (bell body) instead leave the primary block.json model in
            // place and only attach the entity for {@link BlockRenderer.Isometric3D} to
            // merge on top at render time.
            Block.Entity entity = blockEntityEntries.get(blockId);
            if (entity != null && !entity.additive()) {
                modelToUse = entity.model();
                textures = Concurrent.newMap();
                textures.put("#entity", entity.textureId());
                tint = new Block.Tint(Biome.TintTarget.NONE, Optional.empty());
            }

            blockIndex.put(blockId, new Block(blockId, "minecraft", name, modelToUse, textures, variants, multipart, tags, tint, Optional.ofNullable(entity)));
        }

        // Block-entity ids may not appear in the primary block-model loop when their
        // vanilla {@code block.json} is missing entirely (e.g. some skull variants that
        // have no template model file). Backstop: for any {@link Block.Entity} not yet
        // registered above, emit a fresh Block carrying the extracted geometry.
        // <p>
        // Additive entries are skipped here - they need a primary model from elsewhere
        // (the blockstate-only loop below for bells, the primary loop above for blocks
        // that already had a {@code block/<id>.json}).
        for (Map.Entry<String, Block.Entity> entry : blockEntityEntries.entrySet()) {
            String blockId = entry.getKey();
            if (blockIndex.containsKey(blockId)) continue;
            Block.Entity be = entry.getValue();
            if (be.additive()) continue;
            String shortName = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
            ConcurrentList<String> tags = reverseTagIndex.getOrDefault(blockId, Concurrent.newList());
            ConcurrentMap<String, Block.Variant> variants = variantMap.getOrDefault(blockId, Concurrent.newMap());
            Optional<Block.Multipart> multipart = Optional.ofNullable(multipartMap.get(blockId));
            ConcurrentMap<String, String> textures = Concurrent.newMap();
            textures.put("#entity", be.textureId());
            blockIndex.put(blockId, new Block(
                blockId, "minecraft", shortName,
                be.model(), textures,
                variants, multipart, tags,
                new Block.Tint(Biome.TintTarget.NONE, Optional.empty()),
                Optional.of(be)
            ));
        }

        // Task 10: register blockstate-only blocks - ids whose blockstate exists but whose model
        // file lives under a different name (fence/wall/door inventories, small_dripleaf, etc.).
        // These are invisible to the primary block-model loop because it keys on model files and
        // these ids have no matching {@code block/<id>.json}. Resolution precedence is item-def
        // override first (for correct inventory icons on fences/walls), then the first variant's
        // model id, then the first multipart part's apply model id. See {@link #resolveBlockStateModel}.
        Set<String> blockstateOnlyIds = new java.util.HashSet<>();
        java.util.Set<String> candidateBlockstateIds = new java.util.LinkedHashSet<>();
        candidateBlockstateIds.addAll(variantMap.keySet());
        candidateBlockstateIds.addAll(multipartMap.keySet());
        for (String blockId : candidateBlockstateIds) {
            if (blockIndex.containsKey(blockId)) continue;
            if (isParentOrTemplateBlockId(blockId)) continue;
            Optional<ResolvedBlockModel> resolved = resolveBlockStateModel(blockId, itemDefs, variantMap, result.getBlockModels());
            if (resolved.isEmpty()) continue;
            ResolvedBlockModel hit = resolved.get();

            String shortName = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
            BlockModelData modelToUse = hit.model();
            ConcurrentMap<String, String> textures = Concurrent.newMap();
            textures.putAll(modelToUse.getTextures());
            flattenElementFaces(modelToUse, textures);

            Block.Tint tint = tints.getOrDefault(blockId, new Block.Tint(Biome.TintTarget.NONE, Optional.empty()));
            ConcurrentMap<String, Block.Variant> variants = variantMap.getOrDefault(blockId, Concurrent.newMap());
            Optional<Block.Multipart> multipart = Optional.ofNullable(multipartMap.get(blockId));
            ConcurrentList<String> tags = reverseTagIndex.getOrDefault(blockId, Concurrent.newList());

            // Additive entity geometry attaches here so blockstate-driven blocks (bell)
            // pick up their entity overlay (BellModel bell-cup) at render time.
            Block.Entity additiveEntity = blockEntityEntries.get(blockId);
            Optional<Block.Entity> attachedEntity = additiveEntity != null && additiveEntity.additive()
                ? Optional.of(additiveEntity) : Optional.empty();
            blockIndex.put(blockId, new Block(blockId, "minecraft", shortName, modelToUse, textures, variants, multipart, tags, tint, attachedEntity));
            blockstateOnlyIds.add(blockId);
        }
        System.out.printf("Atlas blockstate-only registration: added %d blocks%n", blockstateOnlyIds.size());

        ConcurrentMap<String, Item> itemIndex = Concurrent.newMap();
        for (Map.Entry<String, ItemModelData> itemEntry : result.getItemModels().entrySet()) {
            String modelId = itemEntry.getKey();
            ItemModelData model = itemEntry.getValue();
            String itemId = stripPrefix(modelId, ":item/");
            String name = localName(modelId);
            // Skip items whose matching block carries a {@link Block.Entity} - beds, chests,
            // banners, shulkers, signs, skulls, conduit, decorated_pot, copper golem statues.
            // Their vanilla item models have neither elements nor a layer0 and would render as
            // blank 2D sprites; the {@link Block.Entity} geometry renders through the block
            // path as a {@code TILE_ENTITY} atlas tile. Filtering them out here lets us delete
            // the old {@code ItemRenderer.shouldRedirectToBlockRender} bridge entirely.
            if (blockEntityEntries.containsKey(itemId)) continue;
            ConcurrentMap<String, String> textures = Concurrent.newMap();
            textures.putAll(model.getTextures());
            Optional<Item.Overlay> overlay = OverlayResolver.resolve(itemId, model);
            itemIndex.put(itemId, new Item(itemId, "minecraft", name, model, textures, 0, 64, overlay));
        }

        // Task 1: drop parent/template ids and intentionally-invisible blocks. Every id removed
        // here was confirmed fully-transparent in missing.json; the filter never touches a tile
        // that was rendering. See plans/AssetRenderer-AtlasGaps.md -> Task 1 for the allow-list
        // rationale and {@link #isParentOrTemplateBlockId}/{@link #isParentOrTemplateItemId} for
        // the exact id predicate.
        int removedBlocks = 0;
        for (String blockId : new java.util.ArrayList<>(blockIndex.keySet())) {
            if (isParentOrTemplateBlockId(blockId)) {
                blockIndex.remove(blockId);
                removedBlocks++;
            }
        }
        int removedItems = 0;
        for (String itemId : new java.util.ArrayList<>(itemIndex.keySet())) {
            if (isParentOrTemplateItemId(itemId)) {
                itemIndex.remove(itemId);
                removedItems++;
            }
        }
        System.out.printf("Atlas parent/template filter: removed %d blocks, %d items%n", removedBlocks, removedItems);

        ConcurrentMap<String, Entity> entityIndex = Concurrent.newMap();
        for (Map.Entry<String, EntityModelLoader.EntityDefinition> entityEntry : EntityModelLoader.load().entrySet())
            entityIndex.put(entityEntry.getKey(), new Entity(entityEntry.getKey(), "minecraft", localName(entityEntry.getKey()), entityEntry.getValue().model(), entityEntry.getValue().textureId()));

        // Block entity models now render via the block model path (GeometryKit.buildFromElements),
        // not the entity model path. Only mob entities remain in the entity index.

        ConcurrentMap<String, Texture> textureIndex = Concurrent.newMap();
        for (Texture texture : result.getTextures())
            textureIndex.put(texture.getId(), texture);

        ConcurrentMap<ColorMap.Type, ColorMap> colorMapIndex = Concurrent.newMap();
        for (ColorMap colorMap : result.getColorMaps())
            colorMapIndex.put(colorMap.getType(), colorMap);

        Path textureRoot = result.getPackRoot()
            .resolve("assets")
            .resolve("minecraft")
            .resolve("textures");

        return new PipelineRendererContext(textureRoot, packs, blockIndex, itemIndex, entityIndex, textureIndex, colorMapIndex, tagMap, result.getPotionEffectColors(), result.getBannerPatterns(), java.util.Collections.unmodifiableSet(blockstateOnlyIds), blockEntityEntries);
    }

    @Override
    public @NotNull ConcurrentList<TexturePack> activePacks() {
        return this.packs;
    }

    @Override
    public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
        String normalized = textureId.contains(":") ? textureId : VanillaPaths.MINECRAFT_NAMESPACE + textureId;
        PixelBuffer cached = this.textureCache.get(normalized);
        if (cached != null) return Optional.of(cached);

        Texture texture = this.textureIndex.get(normalized);
        if (texture == null) return Optional.empty();

        Path file = this.textureRoot.resolve(texture.getRelativePath());
        PixelBuffer buffer = PixelBuffer.wrap(this.imageFactory.fromFile(file.toFile()).toBufferedImage());
        this.textureCache.put(normalized, buffer);
        return Optional.of(buffer);
    }

    @Override
    public @NotNull Optional<ColorMap> colorMap(@NotNull ColorMap.Type type) {
        return this.colorMapIndex.getOptional(type);
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
    public @NotNull Optional<AnimationData> animationFor(@NotNull String textureId) {
        String normalized = textureId.contains(":") ? textureId : VanillaPaths.MINECRAFT_NAMESPACE + textureId;
        Texture texture = this.textureIndex.get(normalized);
        return texture == null ? Optional.empty() : texture.getAnimation();
    }

    @Override
    public @NotNull ConcurrentList<String> knownBlockIds() {
        ConcurrentList<String> ids = Concurrent.newList();
        ids.addAll(this.blockIndex.keySet());
        ids.sort((a, b) -> {
            String groupA = primaryTag(a);
            String groupB = primaryTag(b);
            int cmp = String.CASE_INSENSITIVE_ORDER.compare(groupA, groupB);
            return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
        });
        return ids;
    }

    @Override
    public @NotNull ConcurrentList<String> knownItemIds() {
        ConcurrentList<String> ids = Concurrent.newList();
        ids.addAll(this.itemIndex.keySet());
        ids.sort((a, b) -> {
            int cmp = String.CASE_INSENSITIVE_ORDER.compare(idPrefix(a), idPrefix(b));
            return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
        });
        return ids;
    }

    @Override
    public @NotNull Optional<Integer> potionEffectColor(@NotNull String effectId) {
        return this.potionEffectColors.getOptional(effectId);
    }

    @Override
    public @NotNull Optional<BannerPattern> findBannerPattern(@NotNull String patternId) {
        return this.bannerPatterns.getOptional(patternId);
    }

    @Override
    public @NotNull ConcurrentList<BannerPattern> knownBannerPatterns() {
        ConcurrentList<BannerPattern> patterns = Concurrent.newList();
        patterns.addAll(this.bannerPatterns.values());
        return patterns;
    }

    @Override
    public @NotNull Optional<Block.Entity> findBlockEntityEntry(@NotNull String blockId) {
        return this.blockEntityEntries.getOptional(blockId);
    }

    /**
     * Registers an entity definition so it can be looked up by
     * {@link #findEntity(String)}. Callers supply the model data directly since vanilla
     * Minecraft does not ship entity model JSON files - entity geometry is hardcoded in the
     * client source and changes between versions.
     *
     * @param id the namespaced entity id (e.g. {@code "minecraft:zombie"})
     * @param model the bone/cube tree describing the entity's geometry
     * @param textureId the default texture reference, or empty to require an override at render time
     */
    public void registerEntity(
        @NotNull String id,
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureId
    ) {
        this.entityIndex.put(id, new Entity(id, "minecraft", localName(id), model, textureId));
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
                .filter(this.blockTagIndex::containsKey)
                .min(java.util.Comparator.comparingInt(tag -> this.blockTagIndex.get(tag).getValues().size()))
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
     * Removes the first occurrence of a {@code :prefix/} segment from a namespaced model id,
     * collapsing the result into a plain entity id. Returns the input untouched when the
     * segment is absent.
     */
    private static @NotNull String stripPrefix(@NotNull String modelId, @NotNull String segment) {
        int idx = modelId.indexOf(segment);
        if (idx < 0) return modelId;
        return modelId.substring(0, idx + 1) + modelId.substring(idx + segment.length());
    }

    /**
     * Returns the last path segment of a namespaced model id, used to populate the entity's
     * {@code name} column. Example: {@code minecraft:block/grass_block} - &gt; {@code grass_block}.
     */
    private static @NotNull String localName(@NotNull String modelId) {
        int slash = modelId.lastIndexOf('/');
        if (slash >= 0) return modelId.substring(slash + 1);
        int colon = modelId.lastIndexOf(':');
        return colon >= 0 ? modelId.substring(colon + 1) : modelId;
    }

    /**
     * Exact local-name matches for block-side template parents and multipart submodels that
     * should not appear as standalone atlas tiles. Every entry has been confirmed either
     * fully-transparent or sparse ({@code <2%} opaque) in the atlas diagnostic. Categories:
     * <ul>
     * <li>Parent templates ({@code stairs}, {@code slab}, {@code leaves}, {@code cross}, etc.) -
     *     concrete blocks inherit from these via the {@code parent:} chain.</li>
     * <li>Implicit templates ({@code flowerbed_*}, {@code stem_*}, {@code coral_fan}, etc.) -
     *     inherited by concrete blocks but not following the {@code template_*} naming convention.</li>
     * <li>Multipart submodels ({@code redstone_dust_*}, {@code brewing_stand_bottle2}, {@code tripwire_*},
     *     {@code glass_pane_post}, {@code *_bars_post_ends}, etc.) - only meaningful as part of a
     *     composite blockstate, never rendered standalone.</li>
     * <li>Early growth stages ({@code melon_stem_stage0..2}, {@code pumpkin_stem_stage0..2}) -
     *     sparse partial renders that add no atlas value.</li>
     * </ul>
     */
    private static final java.util.Set<String> TEMPLATE_BLOCK_NAMES = java.util.Set.of(
        // Parent templates - empty block.json files that concrete blocks inherit from. Kept
        // out of the atlas because they have no own geometry. {@code banner}, {@code bed},
        // {@code skull} stay in this list even after the Block.Entity refactor: they are still
        // template parents for their concrete variants, not tile-entity block ids themselves.
        // The real tile-entity ids ({@code red_bed}, {@code white_banner}, {@code skeleton_skull})
        // don't match this filter since they aren't in this set.
        "banner", "bed", "block", "button", "button_inventory", "button_pressed",
        "carpet", "crop", "cross", "cross_emissive",
        "door_bottom_left", "door_bottom_left_open", "door_bottom_right", "door_bottom_right_open",
        "door_top_left", "door_top_left_open", "door_top_right", "door_top_right_open",
        "fence_inventory", "fence_post", "fence_side",
        "inner_stairs", "leaves", "mossy_carpet_side", "outer_stairs",
        "piston_extended", "pressure_plate_down", "pressure_plate_up",
        "rail_curved", "rail_flat", "skull", "slab", "slab_top", "stairs",
        "thin_block", "tinted_cross", "wall_inventory",
        // Implicit templates (inherited by concrete blocks)
        "flowerbed_1", "flowerbed_2", "flowerbed_3", "flowerbed_4",
        "stem_fruit", "stem_growth0", "stem_growth1", "stem_growth2", "stem_growth3",
        "stem_growth4", "stem_growth5", "stem_growth6", "stem_growth7",
        "coral_fan", "coral_wall_fan",
        // Multipart submodels - redstone dust
        "redstone_dust_dot", "redstone_dust_side", "redstone_dust_side_alt", "redstone_dust_up",
        "redstone_dust_side0", "redstone_dust_side1", "redstone_dust_side_alt0", "redstone_dust_side_alt1",
        // Multipart submodels - brewing stand / pitcher crop
        "brewing_stand_bottle2", "brewing_stand_empty2",
        "pitcher_crop_top_stage_0", "pitcher_crop_top_stage_1", "pitcher_crop_top_stage_2",
        // Multipart submodels - tripwire
        "tripwire_n", "tripwire_ne", "tripwire_ns", "tripwire_nse", "tripwire_nsew",
        "tripwire_attached_n", "tripwire_attached_ne", "tripwire_attached_ns",
        "tripwire_attached_nse", "tripwire_attached_nsew",
        // Multipart submodels - pane / bar posts
        "glass_pane_post", "glass_pane_noside", "glass_pane_noside_alt",
        "black_stained_glass_pane_post", "blue_stained_glass_pane_post",
        "brown_stained_glass_pane_post", "cyan_stained_glass_pane_post",
        "gray_stained_glass_pane_post", "green_stained_glass_pane_post",
        "light_blue_stained_glass_pane_post", "light_gray_stained_glass_pane_post",
        "lime_stained_glass_pane_post", "magenta_stained_glass_pane_post",
        "orange_stained_glass_pane_post", "pink_stained_glass_pane_post",
        "purple_stained_glass_pane_post", "red_stained_glass_pane_post",
        "white_stained_glass_pane_post", "yellow_stained_glass_pane_post",
        "iron_bars_post_ends", "copper_bars_post_ends", "exposed_copper_bars_post_ends",
        "weathered_copper_bars_post_ends", "oxidized_copper_bars_post_ends",
        // Early growth stages (sparse renders, no atlas value)
        "melon_stem_stage0", "melon_stem_stage1", "melon_stem_stage2",
        "pumpkin_stem_stage0", "pumpkin_stem_stage1", "pumpkin_stem_stage2",
        // Sparse wildflower submodels
        "wildflowers_2", "wildflowers_4"
    );

    /** Blocks that are invisible by design in vanilla - renderer intentionally produces empty geometry for them, so they do not belong in the atlas. */
    private static final java.util.Set<String> INVISIBLE_BLOCK_NAMES = java.util.Set.of(
        "air", "barrier", "end_gateway", "moving_piston", "structure_void"
    );

    /**
     * Exact local-name matches for item-side templates / flat parents / held-pose predicate outputs.
     * {@code decorated_pot} is intentionally NOT in this set - it is a real item that renders blank
     * only because its block-entity mapping was missing before Task 3b, not because it is a
     * template. Held-pose predicate variants ({@code *_in_hand}, {@code *_throwing},
     * {@code shield_blocking}) ship as regular item models in {@code models/item/} but are not
     * real inventory items - they are the result of vanilla's held-pose predicate dispatch and
     * have no place in a GUI atlas.
     */
    private static final java.util.Set<String> TEMPLATE_ITEM_NAMES = java.util.Set.of(
        // Parent item templates - empty item.json files that concrete items inherit from.
        // Kept out of the atlas because they have no own content. Block-entity-item templates
        // ({@code template_bed}, {@code template_chest}, etc.) stay in this list: they are
        // template parents, not tile-entity item ids themselves. The concrete tile-entity
        // items ({@code red_bed}, {@code chest}, etc.) are filtered out of {@code itemIndex}
        // upstream so the item pass never sees them; they render via the block pass instead.
        "generated", "handheld", "handheld_mace", "handheld_rod",
        "template_bed", "template_bundle_open_back", "template_bundle_open_front",
        "template_chest", "template_copper_golem_statue", "template_music_disc",
        "template_shulker_box", "template_skull",
        "air",
        "amethyst_bud",
        "shield_blocking", "spear_in_hand", "spyglass_in_hand",
        "trident_in_hand", "trident_throwing"
    );

    /**
     * Returns {@code true} when a block id is a known parent/template model file or an
     * intentionally-invisible vanilla block. Matches the plan's Task 1 allow-list exactly:
     * {@code template_*}, {@code cube*}, {@code custom_fence_*}, {@code orientable*},
     * {@code light_NN}, plus the explicit {@link #TEMPLATE_BLOCK_NAMES} and
     * {@link #INVISIBLE_BLOCK_NAMES} sets.
     */
    private static boolean isParentOrTemplateBlockId(@NotNull String blockId) {
        String name = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        if (name.startsWith("template_")) return true;
        if (name.startsWith("cube")) return true;
        if (name.startsWith("custom_fence_")) return true;
        if (name.startsWith("orientable")) return true;
        if (name.length() == 8 && name.startsWith("light_") && Character.isDigit(name.charAt(6)) && Character.isDigit(name.charAt(7))) return true;
        return TEMPLATE_BLOCK_NAMES.contains(name) || INVISIBLE_BLOCK_NAMES.contains(name);
    }

    /**
     * Returns {@code true} when an item id is a known parent/template item file. Matches the
     * plan's Task 1 allow-list exactly: {@code template_*}, {@code handheld*},
     * {@code generated}, plus the explicit {@link #TEMPLATE_ITEM_NAMES} set (which includes
     * the item-side {@code decorated_pot} template, not the real block-item of the same id).
     */
    private static boolean isParentOrTemplateItemId(@NotNull String itemId) {
        String name = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        if (name.startsWith("template_")) return true;
        if (name.startsWith("handheld")) return true;
        return TEMPLATE_ITEM_NAMES.contains(name);
    }

    /**
     * Paired model id + concrete {@link BlockModelData} returned by the blockstate-only resolver.
     * The id is retained alongside the data so the caller can cross-check it against the
     * parent/template filter before registering the block.
     */
    private record ResolvedBlockModel(@NotNull String modelId, @NotNull BlockModelData model) {}

    /**
     * Resolves a blockstate-only id to a concrete block model, trying the item-def inventory
     * override first and the first variant's model id second. Returns empty when no candidate is
     * usable.
     * <p>
     * Multipart-only blocks (no item-def, no variants) deliberately resolve to empty: picking
     * the first multipart part of an inventory render produces a partial tile (only always-on
     * parts render, e.g. {@code glass_pane_post} alone) which is misleading. Inventory-rendering
     * for multipart blocks must come from an explicit {@code _inventory} item-def override.
     * <p>
     * A resolution candidate is usable only when its model is loaded, carries at least one
     * element (skips entity-rendered shells like wall signs whose model file is empty), and is
     * not itself a parent/template id ({@link #isParentOrTemplateBlockId}, e.g.
     * {@code block/skull} - whose elements reference unresolved {@code #var} face textures).
     *
     * @param blockId stripped blockstate id (e.g. {@code minecraft:acacia_fence})
     * @param itemDefs item-definition overrides keyed by stripped block id, valued by full model id
     * @param variantMap blockstate variant map keyed by stripped block id
     * @param blockModels loaded block model data keyed by full model id
     * @return the resolved model paired with the model id that produced it, or empty
     */
    private static @NotNull Optional<ResolvedBlockModel> resolveBlockStateModel(
        @NotNull String blockId,
        @NotNull ConcurrentMap<String, String> itemDefs,
        @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variantMap,
        @NotNull ConcurrentMap<String, BlockModelData> blockModels
    ) {
        String itemModelRef = itemDefs.get(blockId);
        if (itemModelRef != null) {
            BlockModelData model = blockModels.get(itemModelRef);
            if (isUsableResolvedModel(itemModelRef, model)) return Optional.of(new ResolvedBlockModel(itemModelRef, model));
        }

        ConcurrentMap<String, Block.Variant> variants = variantMap.get(blockId);
        if (variants != null && !variants.isEmpty()) {
            String variantModelId = variants.values().iterator().next().modelId();
            BlockModelData model = blockModels.get(variantModelId);
            if (isUsableResolvedModel(variantModelId, model)) return Optional.of(new ResolvedBlockModel(variantModelId, model));
        }

        return Optional.empty();
    }

    /**
     * Returns {@code true} when a candidate resolved model would produce a non-blank, non-template
     * atlas tile. Used by {@link #resolveBlockStateModel} to drop entity-only shells (empty
     * elements) and parent/template references that would otherwise sneak through the resolver
     * via an item-def or variant pointing at a template id.
     */
    private static boolean isUsableResolvedModel(@NotNull String modelId, BlockModelData model) {
        if (model == null) return false;
        if (model.getElements().isEmpty()) return false;
        return !isParentOrTemplateBlockId(stripPrefix(modelId, ":block/"));
    }

    /**
     * Walks the first element in a block model and writes the resolved direction-to-texture
     * mapping into the supplied textures map. Each face's {@code #variable} reference is
     * dereferenced against the model's texture variable map until it bottoms out at a concrete
     * namespaced id or fails; unresolved or absent faces leave the direction key alone so the
     * fallback chain can take over.
     * <p>
     * Only the first element is considered. Vanilla cube blocks always use a single element, and
     * multi-element models (chests, doors, pistons) would otherwise trample each other writing
     * contradictory bindings into the same direction key. A later pipeline phase will walk all
     * elements and pick a representative face per direction for multi-element blocks.
     */
    private static void flattenElementFaces(@NotNull BlockModelData model, @NotNull ConcurrentMap<String, String> textures) {
        if (model.getElements().isEmpty()) return;
        ModelElement element = model.getElements().getFirst();

        for (BlockFace blockFace : BlockFace.values()) {
            ModelFace face = element.getFaces().get(blockFace.direction());
            if (face == null) continue;
            String textureRef = face.getTexture();
            if (textureRef.isBlank()) continue;
            String resolved = TextureEngine.dereferenceVariable(textureRef, model.getTextures());
            if (resolved.startsWith("#")) continue;
            textures.put(blockFace.direction(), resolved);
        }
    }

}
