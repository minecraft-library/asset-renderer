package lib.minecraft.renderer.pipeline;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.PortalRenderer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.binding.BannerPattern;
import lib.minecraft.renderer.asset.model.BlockModelData;
import lib.minecraft.renderer.asset.model.ItemModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.asset.pack.AnimationData;
import lib.minecraft.renderer.asset.pack.ColorMap;
import lib.minecraft.renderer.asset.pack.Texture;
import lib.minecraft.renderer.asset.pack.TexturePack;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.TextureEngine;
import lib.minecraft.renderer.geometry.Biome;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.pipeline.loader.BlockEntityLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pipeline.pack.CitMatcher;
import lib.minecraft.renderer.pipeline.pack.CitRule;
import lib.minecraft.renderer.pipeline.pack.CtmMatcher;
import lib.minecraft.renderer.pipeline.pack.CtmResolution;
import lib.minecraft.renderer.pipeline.pack.CtmRule;
import lib.minecraft.renderer.pipeline.pack.ItemContext;
import lib.minecraft.renderer.pipeline.resolver.OverlayResolver;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lib.minecraft.renderer.tooling.ToolingColorMaps;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The production {@link RendererContext} implementation, built once at bootstrap from a single
 * {@link Pipeline.Result}. Every {@code findX} / {@code resolveX} method is backed by an
 * eagerly-materialised index so warm-path lookups are pure map accesses; texture pixels stay on
 * disk until the first {@link #resolveTexture(String)} call and are memoised in a per-context cache.
 * <p>
 * Construction goes through {@link #of(Pipeline.Result)}, which materialises every parsed
 * {@link BlockModelData} and {@link ItemModelData} entry into a {@link Block} / {@link Item} DTO
 * eagerly, keyed by the derived namespaced id ({@code minecraft:block/grass_block} - &gt;
 * {@code minecraft:grass_block}). Each block carries a {@link Block.Source} tag identifying the
 * registration path that produced it ({@link Block.Source#PRIMARY},
 * {@link Block.Source#BLOCKSTATE_ONLY}, or {@link Block.Source#TILE_ENTITY}) so atlas tile
 * classification and similar consumers don't need to type-check the context implementation.
 * <p>
 * Block face bindings are flattened eagerly: the first element's face map is walked, each face's
 * {@code #variable} reference is dereferenced against the model's texture variable map, and the
 * resolved texture id is stored under the vanilla direction key ({@code down}, {@code up},
 * {@code north}, {@code south}, {@code west}, {@code east}) on the block entity. The underlying
 * variable map is preserved under its original keys for models that rely on the
 * {@code all} / {@code side} / {@code particle} fallback chain.
 * <p>
 * Biome colormaps and the {@link Biome.TintTarget} of every known vanilla tinted block are wired
 * through to render time: {@link ToolingColorMaps.Parser} loads {@code grass.png},
 * {@code foliage.png}, and {@code dry_foliage.png} into {@link ColorMap} entities, and
 * {@link BlockTintsLoader} supplies the {@code minecraft:grass_block} - to - {@code GRASS} (etc.)
 * mapping verified against the bytecode of {@code BlockColors$createDefault} in the 26.1 client
 * jar. Entities come from {@link EntityModelLoader#load()} keyed by namespaced id.
 * <p>
 * Every stored index is unmodifiable: indexes built locally inside {@link #of(Pipeline.Result)}
 * ({@code blockIndex}, {@code itemIndex}, {@code entityIndex}, {@code packs}) are wrapped via
 * {@link ConcurrentMap#toUnmodifiable()} at construction; indexes that came in already
 * keyed off the {@link Pipeline.Result} (or {@link BlockEntityLoader#load()}) are wrapped at
 * the loader exit so consumers between pipeline finish and context construction see the same
 * read-lock-free semantics. Read paths bypass the source map's read lock since the unmodifiable
 * wrapper is itself thread-safe by virtue of being immutable. The lazy {@code textureCache} is
 * the only mutable map on the context.
 */
@RequiredArgsConstructor
public final class PipelineRendererContext implements RendererContext {

    @Getter
    @Accessors(fluent = true)
    private final @NotNull ConcurrentList<TexturePack> activePacks;
    private final @NotNull ConcurrentMap<String, Block> blockIndex;
    private final @NotNull ConcurrentMap<String, Item> itemIndex;
    private final @NotNull ConcurrentMap<String, Entity> entityIndex;
    private final @NotNull ConcurrentMap<String, Texture> textureIndex;
    private final @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMapIndex;
    private final @NotNull ConcurrentMap<String, BlockTag> blockTagIndex;
    private final @NotNull ConcurrentMap<String, Integer> potionEffectColors;
    private final @NotNull ConcurrentMap<String, BannerPattern> bannerPatterns;
    private final @NotNull ConcurrentMap<String, Block.Entity> blockEntityEntries;
    private final @NotNull ConcurrentMap<String, Integer> colorOverrides;
    private final @NotNull ConcurrentList<CitRule> citRules;
    private final @NotNull ConcurrentList<CtmRule> ctmRules;

    private final @NotNull ImageFactory imageFactory = new ImageFactory();
    private final @NotNull ConcurrentMap<String, PixelBuffer> textureCache = Concurrent.newMap();

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
    private static final Set<String> TEMPLATE_BLOCK_NAMES = Set.of(
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

    /**
     * Blocks that are invisible by design in vanilla - renderer intentionally produces empty
     * geometry for them, so they do not belong in the atlas. {@code end_gateway} is not listed
     * because it now renders via {@link PortalRenderer} through {@link AtlasRenderer}'s
     * {@code PORTAL_BLOCK_IDS} intercept (no block-model file, no block-entity geometry).
     */
    private static final Set<String> INVISIBLE_BLOCK_NAMES = Set.of(
        "air", "barrier", "moving_piston", "structure_void"
    );

    /**
     * Exact local-name matches for item-side templates / flat parents / held-pose predicate outputs.
     * {@code decorated_pot} is intentionally NOT in this set - it is a real item that renders via
     * its block-entity mapping, not a template. Held-pose predicate variants
     * ({@code *_in_hand}, {@code *_throwing}, {@code shield_blocking}) ship as regular item
     * models in {@code models/item/} but are not real inventory items - they are the result of
     * vanilla's held-pose predicate dispatch and have no place in a GUI atlas.
     */
    private static final Set<String> TEMPLATE_ITEM_NAMES = Set.of(
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
     * Builds a context from a completed pipeline result.
     * <p>
     * Block and item entity materialization happens eagerly so every {@code findBlock} /
     * {@code findItem} lookup is a pure map access. Textures stay on disk until
     * {@link #resolveTexture(String)} is called for them the first time.
     *
     * @param result the pipeline result to wrap
     * @return a new context scoped to the given result
     */
    public static @NotNull PipelineRendererContext of(@NotNull Pipeline.Result result) {
        ConcurrentMap<String, Block.Entity> blockEntityEntries = BlockEntityLoader.load();
        ConcurrentMap<String, ConcurrentList<String>> reverseTagIndex = buildReverseTagIndex(result.getBlockTags());

        ConcurrentMap<String, Block> blockIndex = buildPrimaryBlockIndex(result, blockEntityEntries, reverseTagIndex);
        attachOrphanBlockEntities(blockIndex, blockEntityEntries, result, reverseTagIndex);
        Set<String> blockstateOnlyIds = attachBlockstateOnlyBlocks(blockIndex, blockEntityEntries, result, reverseTagIndex);
        System.out.printf("Atlas blockstate-only registration: added %d blocks%n", blockstateOnlyIds.size());

        ConcurrentMap<String, Item> itemIndex = buildItemIndex(result, blockEntityEntries);
        dropParentTemplates(blockIndex, itemIndex);

        ConcurrentMap<String, Entity> entityIndex = loadEntityIndex();

        return new PipelineRendererContext(
            result.getPacks(),
            blockIndex.toUnmodifiable(),
            itemIndex.toUnmodifiable(),
            entityIndex.toUnmodifiable(),
            result.getTextures(),
            result.getColorMaps(),
            result.getBlockTags(),
            result.getPotionEffectColors(),
            result.getBannerPatterns(),
            blockEntityEntries,
            result.getColorOverrides(),
            result.getCitRules(),
            result.getCtmRules()
        );
    }

    /**
     * Inverts the tag-to-blocks map into a block-to-tags map. The primary {@link Pipeline}
     * keys block tags by tag id with the member block ids as the value list; the renderer wants
     * the reverse so each block's {@code tags} field can be populated in a single hash lookup
     * during block index construction.
     *
     * @param tagMap tag id keyed to its membership descriptor
     * @return block id keyed to the tag names that include it
     */
    private static @NotNull ConcurrentMap<String, ConcurrentList<String>> buildReverseTagIndex(
        @NotNull ConcurrentMap<String, BlockTag> tagMap
    ) {
        // flatMap each tag's member block ids into (blockId, tagName) pairs, group by block id,
        // then adopt each per-block tag list and the outer map at finish. groupingBy collects
        // into plain ArrayLists, so the build phase pays no ConcurrentList write locks.
        return tagMap.stream()
            .flatMap(tagEntry -> tagEntry.getValue()
                .getValues()
                .stream()
                .map(blockId -> Map.entry(blockId, tagEntry.getKey()))
            )
            .collect(Collectors.groupingBy(
                Map.Entry::getKey,
                Collectors.mapping(
                    Map.Entry::getValue,
                    Collectors.toCollection(ArrayList::new)
                )
            ))
            .entrySet()
            .stream()
            .collect(Concurrent.toMap(
                Map.Entry::getKey,
                e -> Concurrent.adoptList(e.getValue()).toUnmodifiable())
            )
            .toUnmodifiable();
    }

    /**
     * Returns the bundled default-state key for a block id, or empty when the block has no
     * entry in {@code block_states.json} (an empty-property block).
     *
     * @param result the pipeline result supplying the loaded default-state-key table
     * @param blockId the namespaced block id
     * @return the default-state key, or empty
     */
    private static @NotNull String defaultKeyFor(@NotNull Pipeline.Result result, @NotNull String blockId) {
        return result.getBlockDefaultStateKeys().getOrDefault(blockId, "");
    }

    /**
     * Builds the primary block index by walking every parsed {@link BlockModelData} entry and
     * materialising a {@link Block} per id.
     * <p>
     * Three subtleties are folded in. <b>Item-def overrides</b>: when the inventory rendering
     * model differs from the blockstate model (e.g. {@code piston} -&gt; {@code piston_inventory}),
     * the item-def's model id wins so the atlas tile matches the inventory view. <b>Tile-entity
     * overrides</b>: non-additive {@link Block.Entity} mappings replace the vanilla block.json
     * model entirely (the template block.json is usually empty - just a {@code particle} texture
     * - and the real geometry is hardcoded in a {@code BlockEntityRenderer}). The texture map
     * rebinds to the entity texture under the {@code "#entity"} face reference, the
     * {@link Block.Tint} resets to {@link Biome.TintTarget#NONE} (per-entry tints are applied
     * via {@link Block.Entity#tintArgb()} at render time), and the block is tagged
     * {@link Block.Source#TILE_ENTITY}. <b>Additive entries</b> (e.g. the bell body) leave the
     * primary block.json model in place and only attach the entity for the renderer to merge
     * on top; these stay {@link Block.Source#PRIMARY}.
     *
     * @param result the pipeline result supplying block models, tints, item-defs, variants, and multiparts
     * @param blockEntityEntries the block-entity geometry table from {@link BlockEntityLoader}
     * @param reverseTagIndex block id -&gt; tag names, from {@link #buildReverseTagIndex}
     * @return a fresh map keyed by stripped block id
     */
    private static @NotNull ConcurrentMap<String, Block> buildPrimaryBlockIndex(
        @NotNull Pipeline.Result result,
        @NotNull ConcurrentMap<String, Block.Entity> blockEntityEntries,
        @NotNull ConcurrentMap<String, ConcurrentList<String>> reverseTagIndex
    ) {
        ConcurrentMap<String, Block.Tint> tints = result.getBlockTints();
        ConcurrentMap<String, String> itemDefs = result.getItemDefinitions();
        ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variantMap = result.getBlockVariants();
        ConcurrentMap<String, Block.Multipart> multipartMap = result.getBlockMultiparts();

        HashMap<String, Block> blockIndex = new HashMap<>();
        for (Map.Entry<String, BlockModelData> blockEntry : result.getBlockModels().entrySet()) {
            String modelId = blockEntry.getKey();
            BlockModelData model = blockEntry.getValue();
            String blockId = stripPrefix(modelId, ":block/");
            String name = localName(modelId);

            BlockModelData modelToUse = model;
            String itemModelRef = itemDefs.get(blockId);
            if (itemModelRef != null && !itemModelRef.equals(modelId)) {
                BlockModelData override = result.getBlockModels().get(itemModelRef);
                if (override != null)
                    modelToUse = override;
            }

            HashMap<String, String> textures = new HashMap<>(modelToUse.getTextures());
            flattenElementFaces(modelToUse, textures);

            Block.Tint tint = tints.getOrDefault(blockId, new Block.Tint(Biome.TintTarget.NONE, Optional.empty()));
            ConcurrentMap<String, Block.Variant> variants = variantMap.getOrDefault(blockId, Concurrent.newMap());
            Optional<Block.Multipart> multipart = Optional.ofNullable(multipartMap.get(blockId));
            ConcurrentList<String> tags = reverseTagIndex.getOrDefault(blockId, Concurrent.newList());

            Block.Entity entity = blockEntityEntries.get(blockId);
            Block.Source source = Block.Source.PRIMARY;
            if (entity != null && !entity.additive()) {
                modelToUse = entity.model();
                textures = new HashMap<>();
                textures.put("#entity", entity.textureId());
                tint = new Block.Tint(Biome.TintTarget.NONE, Optional.empty());
                source = Block.Source.TILE_ENTITY;
            }

            blockIndex.put(blockId, new Block(
                blockId,
                "minecraft",
                name,
                modelToUse,
                Concurrent.adoptMap(textures),
                variants,
                multipart,
                tags,
                tint,
                Optional.ofNullable(entity),
                source,
                defaultKeyFor(result, blockId)
            ));
        }

        return Concurrent.adoptMap(blockIndex);
    }

    /**
     * Backstops the primary block index with synthetic blocks for any non-additive
     * {@link Block.Entity} whose vanilla {@code block/<id>.json} is missing entirely (some skull
     * variants ship without a template model file). Each backstop block is tagged
     * {@link Block.Source#TILE_ENTITY}; additive entries are skipped here because they need a
     * primary model from elsewhere - either the primary loop (for blocks with a
     * {@code block/<id>.json}) or {@link #attachBlockstateOnlyBlocks} (for blockstate-only ids
     * like {@code bell}).
     *
     * @param blockIndex the primary index produced by {@link #buildPrimaryBlockIndex}; mutated in place
     * @param blockEntityEntries the block-entity geometry table from {@link BlockEntityLoader}
     * @param result the pipeline result supplying variants and multiparts
     * @param reverseTagIndex block id -&gt; tag names, from {@link #buildReverseTagIndex}
     */
    private static void attachOrphanBlockEntities(
        @NotNull ConcurrentMap<String, Block> blockIndex,
        @NotNull ConcurrentMap<String, Block.Entity> blockEntityEntries,
        @NotNull Pipeline.Result result,
        @NotNull ConcurrentMap<String, ConcurrentList<String>> reverseTagIndex
    ) {
        ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variantMap = result.getBlockVariants();
        ConcurrentMap<String, Block.Multipart> multipartMap = result.getBlockMultiparts();

        for (Map.Entry<String, Block.Entity> entry : blockEntityEntries.entrySet()) {
            String blockId = entry.getKey();
            if (blockIndex.containsKey(blockId)) continue;
            Block.Entity be = entry.getValue();
            if (be.additive()) continue;
            String shortName = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
            ConcurrentList<String> tags = reverseTagIndex.getOrDefault(blockId, Concurrent.newList());
            ConcurrentMap<String, Block.Variant> variants = variantMap.getOrDefault(blockId, Concurrent.newMap());
            Optional<Block.Multipart> multipart = Optional.ofNullable(multipartMap.get(blockId));
            HashMap<String, String> textures = new HashMap<>();
            textures.put("#entity", be.textureId());
            blockIndex.put(blockId, new Block(
                blockId,
                "minecraft", shortName,
                be.model(),
                Concurrent.adoptMap(textures),
                variants,
                multipart,
                tags,
                new Block.Tint(Biome.TintTarget.NONE, Optional.empty()),
                Optional.of(be),
                Block.Source.TILE_ENTITY,
                defaultKeyFor(result, blockId)
            ));
        }
    }

    /**
     * Registers the Task-10 blockstate-only fallbacks: ids whose blockstate exists but whose
     * {@code block/<id>.json} model is absent (fence/wall/door inventories, {@code small_dripleaf},
     * etc.). The primary block-model loop misses these because it keys on model files; this pass
     * walks {@code blockVariants} + {@code blockMultiparts} keys, skips parent/template ids, and
     * resolves each remaining id through {@link #resolveBlockStateModel} (item-def override first,
     * then the first variant's model id - multipart-only blocks deliberately resolve to empty).
     * <p>
     * If an id also carries an additive {@link Block.Entity} (e.g. {@code bell}'s bell-cup
     * overlay) the entity attaches to the resulting block and the source flips to
     * {@link Block.Source#TILE_ENTITY} so atlas classification matches the entity-bearing path
     * elsewhere; otherwise the block is tagged {@link Block.Source#BLOCKSTATE_ONLY}.
     *
     * @param blockIndex the index from {@link #buildPrimaryBlockIndex} +
     *     {@link #attachOrphanBlockEntities}; mutated in place
     * @param blockEntityEntries the block-entity geometry table from {@link BlockEntityLoader}
     * @param result the pipeline result supplying block models, tints, item-defs, variants, and multiparts
     * @param reverseTagIndex block id -&gt; tag names, from {@link #buildReverseTagIndex}
     * @return the set of ids registered through this fallback path (kept by the caller for the
     *     diagnostic count line)
     */
    private static @NotNull Set<String> attachBlockstateOnlyBlocks(
        @NotNull ConcurrentMap<String, Block> blockIndex,
        @NotNull ConcurrentMap<String, Block.Entity> blockEntityEntries,
        @NotNull Pipeline.Result result,
        @NotNull ConcurrentMap<String, ConcurrentList<String>> reverseTagIndex
    ) {
        ConcurrentMap<String, Block.Tint> tints = result.getBlockTints();
        ConcurrentMap<String, String> itemDefs = result.getItemDefinitions();
        ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variantMap = result.getBlockVariants();
        ConcurrentMap<String, Block.Multipart> multipartMap = result.getBlockMultiparts();

        Set<String> blockstateOnlyIds = new HashSet<>();
        Set<String> candidateBlockstateIds = new LinkedHashSet<>();
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
            HashMap<String, String> textures = new HashMap<>(modelToUse.getTextures());
            flattenElementFaces(modelToUse, textures);

            Block.Tint tint = tints.getOrDefault(blockId, new Block.Tint(Biome.TintTarget.NONE, Optional.empty()));
            ConcurrentMap<String, Block.Variant> variants = variantMap.getOrDefault(blockId, Concurrent.newMap());
            Optional<Block.Multipart> multipart = Optional.ofNullable(multipartMap.get(blockId));
            ConcurrentList<String> tags = reverseTagIndex.getOrDefault(blockId, Concurrent.newList());

            Block.Entity additiveEntity = blockEntityEntries.get(blockId);
            Optional<Block.Entity> attachedEntity = additiveEntity != null && additiveEntity.additive()
                ? Optional.of(additiveEntity) : Optional.empty();
            Block.Source source = attachedEntity.isPresent() ? Block.Source.TILE_ENTITY : Block.Source.BLOCKSTATE_ONLY;
            blockIndex.put(blockId, new Block(
                blockId,
                "minecraft",
                shortName,
                modelToUse,
                Concurrent.adoptMap(textures),
                variants,
                multipart,
                tags,
                tint,
                attachedEntity,
                source,
                defaultKeyFor(result, blockId)));
            blockstateOnlyIds.add(blockId);
        }
        return blockstateOnlyIds;
    }

    /**
     * Builds the item index by walking every parsed {@link ItemModelData} entry. Items whose
     * matching block carries a {@link Block.Entity} (beds, chests, banners, shulkers, signs,
     * skulls, conduit, decorated_pot, copper golem statues) are skipped because their vanilla
     * item models have neither elements nor a {@code layer0} and would render as blank 2D
     * sprites; those tiles render through the block path instead, as
     * {@link Block.Source#TILE_ENTITY}. Filtering them out here lets the renderer stay free of
     * a separate "redirect to block render" bridge.
     *
     * @param result the pipeline result supplying item models
     * @param blockEntityEntries the block-entity geometry table; ids in here are filtered out
     * @return the populated item index, keyed by stripped item id
     */
    private static @NotNull ConcurrentMap<String, Item> buildItemIndex(
        @NotNull Pipeline.Result result,
        @NotNull ConcurrentMap<String, Block.Entity> blockEntityEntries
    ) {
        HashMap<String, Item> itemIndex = new HashMap<>();
        for (Map.Entry<String, ItemModelData> itemEntry : result.getItemModels().entrySet()) {
            String modelId = itemEntry.getKey();
            ItemModelData model = itemEntry.getValue();
            String itemId = stripPrefix(modelId, ":item/");
            String name = localName(modelId);
            if (blockEntityEntries.containsKey(itemId)) continue;
            HashMap<String, String> textures = new HashMap<>(model.getTextures());
            Optional<Item.Overlay> overlay = OverlayResolver.resolve(itemId, model);
            itemIndex.put(itemId, new Item(itemId, "minecraft", name, model, Concurrent.adoptMap(textures), 0, 64, overlay));
        }
        return Concurrent.adoptMap(itemIndex);
    }

    /**
     * Drops parent / template ids and intentionally-invisible blocks from both indices. Every id
     * removed here was confirmed fully transparent in {@code missing.json}; the filter never
     * touches a tile that was rendering. The exact id predicate lives in
     * {@link #isParentOrTemplateBlockId} / {@link #isParentOrTemplateItemId} along with the
     * allow-list rationale.
     *
     * @param blockIndex the block index from the build / orphan / blockstate-only passes; mutated in place
     * @param itemIndex the item index from {@link #buildItemIndex}; mutated in place
     */
    private static void dropParentTemplates(
        @NotNull ConcurrentMap<String, Block> blockIndex,
        @NotNull ConcurrentMap<String, Item> itemIndex
    ) {
        int blocksBefore = blockIndex.size();
        blockIndex.keySet().removeIf(PipelineRendererContext::isParentOrTemplateBlockId);
        int itemsBefore = itemIndex.size();
        itemIndex.keySet().removeIf(PipelineRendererContext::isParentOrTemplateItemId);
        System.out.printf("Atlas parent/template filter: removed %d blocks, %d items%n",
            blocksBefore - blockIndex.size(), itemsBefore - itemIndex.size());
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
                    localName(entityId),
                    definition.model(),
                    definition.textureRef(),
                    definition.overlays()
                        .stream()
                        .map(o -> new Entity.Layer(o.model(), o.textureRef(), o.emissive()))
                        .collect(Concurrent.toList())
                        .toUnmodifiable()
                );
            }));
    }

    @Override
    public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
        String normalized = textureId.contains(":") ? textureId : VanillaSourcePaths.MINECRAFT_NAMESPACE + textureId;
        PixelBuffer cached = this.textureCache.get(normalized);
        if (cached != null) return Optional.of(cached);

        Texture texture = this.textureIndex.get(normalized);
        if (texture == null) return Optional.empty();

        TexturePack owner = null;
        for (TexturePack pack : this.activePacks) {
            if (pack.getId().equals(texture.getPackId())) {
                owner = pack;
                break;
            }
        }
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
        PixelBuffer buffer = PixelBuffer.wrap(this.imageFactory.fromFile(winning.toFile()).toBufferedImage());
        this.textureCache.put(normalized, buffer);
        return Optional.of(buffer);
    }

    @Override
    public @NotNull Optional<ColorMap> findColorMap(@NotNull ColorMap.Type type) {
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
    public @NotNull Optional<AnimationData> findAnimation(@NotNull String textureId) {
        String normalized = textureId.contains(":") ? textureId : VanillaSourcePaths.MINECRAFT_NAMESPACE + textureId;
        Texture texture = this.textureIndex.get(normalized);
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
        return this.blockEntityEntries.getOptional(blockId);
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
                .filter(this.blockTagIndex::containsKey)
                .min(Comparator.comparingInt(tag -> this.blockTagIndex.get(tag).getValues().size()))
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
     * Returns {@code true} when a block id is a known parent/template model file or an
     * intentionally-invisible vanilla block. Matches: {@code template_*}, {@code cube*},
     * {@code custom_fence_*}, {@code orientable*}, {@code light_NN}, plus the explicit
     * {@link #TEMPLATE_BLOCK_NAMES} and {@link #INVISIBLE_BLOCK_NAMES} sets.
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
     * Returns {@code true} when an item id is a known parent/template item file. Matches:
     * {@code template_*}, {@code handheld*}, {@code generated}, plus the explicit
     * {@link #TEMPLATE_ITEM_NAMES} set (which includes the item-side {@code decorated_pot}
     * template, not the real block-item of the same id).
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
    private static void flattenElementFaces(@NotNull BlockModelData model, @NotNull Map<String, String> textures) {
        if (model.getElements().isEmpty()) return;
        ModelElement element = model.getElements().getFirst();

        for (BlockFace blockFace : BlockFace.CACHED_VALUES) {
            ModelFace face = element.getFaces().get(blockFace.direction());
            if (face == null) continue;
            String textureRef = face.getTexture();
            if (textureRef.isBlank()) continue;
            String resolved = TextureEngine.resolveTextureReference(textureRef, model.getTextures());
            if (resolved.startsWith("#")) continue;
            textures.put(blockFace.direction(), resolved);
        }
    }

}
