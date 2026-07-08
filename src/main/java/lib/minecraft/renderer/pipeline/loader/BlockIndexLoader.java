package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.util.Models;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Materialises the renderer's block index from a {@link Pipeline.Result} and the block-entity
 * geometry table, returning the finished {@code blockId -> }{@link Block} map the renderer
 * context wraps directly.
 * <p>
 * The index is assembled in three passes - {@link #buildPrimaryBlockIndex} (one block per parsed
 * {@code models/block/*.json}), {@link #attachOrphanBlockEntities} (block-entity blocks whose
 * template model file is absent), and {@link #attachBlockstateOnlyBlocks} (ids whose blockstate
 * exists but whose model file is missing) - then filtered to drop models that render nothing.
 * <p>
 * Because the primary pass keys off model FILES, the parent templates ({@code block/stairs},
 * {@code block/cross}, {@code block/slab}) leak in as fake {@code minecraft:<name>} entries. They
 * are dropped structurally rather than by name: a template carries only unresolved
 * {@code #variable} texture references, so it would render blank ({@link Models#rendersNothing}).
 * Concrete variant models that DO render ({@code block/acacia_slab_top}, {@code block/redstone_dust_side},
 * door halves, growth stages) are kept. {@link #INVISIBLE_BLOCK_NAMES} is the one explicit
 * exception: those ids carry a real texture but vanilla renders them invisible, so they are dropped too.
 */
@UtilityClass
public class BlockIndexLoader {

    /**
     * Blocks that are invisible by design in vanilla - they carry a real texture binding (so the
     * structural empty-model filter would keep them) but the renderer intentionally produces no
     * visible geometry, so they do not belong in the atlas. A small explicit set, distinct from the
     * parent/template models which drop out structurally with no name list.
     */
    private static final Set<String> INVISIBLE_BLOCK_NAMES = Set.of(
        "air", "barrier", "moving_piston", "structure_void"
    );

    /**
     * Builds and filters the renderer's block index.
     *
     * @param result the pipeline result supplying block models, tints, item-defs, variants, multiparts, and tags
     * @param beEntries the block-entity geometry table from {@link BlockModelLoader}
     * @param beVariants the block-entity state-conditional variants from {@link BlockModelLoader}
     * @return the finished block index, keyed by stripped block id, unmodifiable
     */
    public static @NotNull ConcurrentMap<String, Block> load(
        @NotNull Pipeline.Result result,
        @NotNull ConcurrentMap<String, Block.Entity> beEntries,
        @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> beVariants
    ) {
        ConcurrentMap<String, Block> blockIndex = buildUnfiltered(result, beEntries, beVariants);

        int before = blockIndex.size();
        blockIndex.entrySet().removeIf(entry -> {
            Block block = entry.getValue();
            // Block entities carry their mesh as a relative bone tree (block.getModel() is an empty
            // element sentinel that would trip rendersNothing); they render via
            // BlockGeometryKit#buildFromBones, so keep them regardless.
            if (block.getEntity().isPresent())
                return false;
            ModelData model = block.getModel();
            return isInvisible(entry.getKey())
                || Models.rendersNothing(model.getElements(), model.getTextures(), false);
        });
        System.out.printf("Atlas empty-model filter: removed %d template/invisible blocks%n", before - blockIndex.size());

        return blockIndex.toUnmodifiable();
    }

    /**
     * Runs the three build passes and returns the assembled index <b>before</b> the empty-model /
     * invisible filter. Exposed package-privately so the parity test can inspect the full built set.
     *
     * @param result the pipeline result
     * @param beEntries the block-entity geometry table
     * @param beVariants the block-entity state-conditional variants
     * @return the unfiltered block index, mutable
     */
    static @NotNull ConcurrentMap<String, Block> buildUnfiltered(
        @NotNull Pipeline.Result result,
        @NotNull ConcurrentMap<String, Block.Entity> beEntries,
        @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> beVariants
    ) {
        ConcurrentMap<String, ConcurrentList<String>> reverseTagIndex = buildReverseTagIndex(result.getBlockTags());
        ConcurrentMap<String, Block> blockIndex = buildPrimaryBlockIndex(result, beEntries, beVariants, reverseTagIndex);
        attachOrphanBlockEntities(blockIndex, beEntries, beVariants, result, reverseTagIndex);
        Set<String> blockstateOnlyIds = attachBlockstateOnlyBlocks(blockIndex, beEntries, result, reverseTagIndex);
        System.out.printf("Atlas blockstate-only registration: added %d blocks%n", blockstateOnlyIds.size());
        return blockIndex;
    }

    /**
     * Returns {@code true} when an id is an intentionally-invisible vanilla block. Compares the
     * id's local name (namespace stripped) against {@link #INVISIBLE_BLOCK_NAMES}.
     *
     * @param blockId the namespaced or stripped block id
     * @return whether the block is one of the intentionally-invisible vanilla ids
     */
    private static boolean isInvisible(@NotNull String blockId) {
        return INVISIBLE_BLOCK_NAMES.contains(ResourceId.localName(blockId));
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
     * entry in {@code block_defaults.json} (an empty-property block).
     *
     * @param result the pipeline result supplying the loaded default-state-key table
     * @param blockId the namespaced block id
     * @return the default-state key, or empty
     */
    private static @NotNull String defaultKeyFor(@NotNull Pipeline.Result result, @NotNull String blockId) {
        return result.getBlockDefaultStateKeys().getOrDefault(blockId, "");
    }

    /**
     * Builds the primary block index by walking every parsed {@link ModelData} entry and
     * materialising a {@link Block} per id.
     * <p>
     * Three subtleties are folded in. <b>Item-def overrides</b>: when the inventory rendering
     * model differs from the blockstate model (e.g. {@code piston} -&gt; {@code piston_inventory}),
     * the item-def's model id wins so the atlas tile matches the inventory view. <b>Tile-entity
     * overrides</b>: non-additive {@link Block.Entity} mappings replace the vanilla block.json
     * model entirely (the template block.json is usually empty - just a {@code particle} texture
     * - and the real geometry is hardcoded in a {@code BlockEntityRenderer}). The texture map
     * rebinds to the entity texture under the {@code "#entity"} face reference, the
     * {@link Block.Tint} resets to {@link Block.TintTarget#NONE} (per-entry tints are applied
     * via {@link Block.Entity#tintArgb()} at render time), and the block is tagged
     * {@link Block.Source#TILE_ENTITY}. <b>Additive entries</b> (e.g. the bell body) leave the
     * primary block.json model in place and only attach the entity for the renderer to merge
     * on top; these stay {@link Block.Source#PRIMARY}.
     *
     * @param result the pipeline result supplying block models, tints, item-defs, variants, and multiparts
     * @param blockEntityEntries the block-entity geometry table from {@link BlockModelLoader}
     * @param beVariants the block-entity state-conditional variants from {@link BlockModelLoader}
     * @param reverseTagIndex block id -&gt; tag names, from {@link #buildReverseTagIndex}
     * @return a fresh map keyed by stripped block id
     */
    private static @NotNull ConcurrentMap<String, Block> buildPrimaryBlockIndex(
        @NotNull Pipeline.Result result,
        @NotNull ConcurrentMap<String, Block.Entity> blockEntityEntries,
        @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> beVariants,
        @NotNull ConcurrentMap<String, ConcurrentList<String>> reverseTagIndex
    ) {
        ConcurrentMap<String, Block.Tint> tints = result.getBlockTints();
        ConcurrentMap<String, String> itemDefs = result.getItemDefinitions();
        ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variantMap = result.getBlockVariants();
        ConcurrentMap<String, Block.Multipart> multipartMap = result.getBlockMultiparts();

        HashMap<String, Block> blockIndex = new HashMap<>();
        for (Map.Entry<String, ModelData> blockEntry : result.getBlockModels().entrySet()) {
            String modelId = blockEntry.getKey();
            ModelData model = blockEntry.getValue();
            ResourceId blockResource = ResourceId.ofModelId(modelId);
            String blockId = blockResource.id();

            ModelData modelToUse = model;
            String itemModelRef = itemDefs.get(blockId);
            if (itemModelRef != null && !itemModelRef.equals(modelId)) {
                ModelData override = result.getBlockModels().get(itemModelRef);
                if (override != null)
                    modelToUse = override;
            }

            HashMap<String, String> textures = new HashMap<>(modelToUse.getTextures());
            flattenElementFaces(modelToUse, textures);

            Block.Tint tint = tints.getOrDefault(blockId, new Block.Tint(Block.TintTarget.NONE, Optional.empty()));
            ConcurrentMap<String, Block.Variant> variants = mergeBlockEntityVariants(variantMap.getOrDefault(blockId, Concurrent.newMap()), beVariants.get(blockId));
            Optional<Block.Multipart> multipart = Optional.ofNullable(multipartMap.get(blockId));
            ConcurrentList<String> tags = reverseTagIndex.getOrDefault(blockId, Concurrent.newList());

            Block.Entity entity = blockEntityEntries.get(blockId);
            Block.Source source = Block.Source.PRIMARY;
            if (entity != null && !entity.additive()) {
                // A block entity's geometry is its bone tree (entity.boneModel()); Block.model is an
                // empty element sentinel - never rendered for a BE (the bone dispatch precedes it) and
                // exempted from the empty-model filter.
                modelToUse = new ModelData();
                textures = new HashMap<>();
                textures.put("#entity", entity.textureId());
                tint = new Block.Tint(Block.TintTarget.NONE, Optional.empty());
                source = Block.Source.TILE_ENTITY;
            }

            blockIndex.put(blockId, new Block(
                blockResource,
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
     * @param blockEntityEntries the block-entity geometry table from {@link BlockModelLoader}
     * @param beVariants the block-entity state-conditional variants from {@link BlockModelLoader}
     * @param result the pipeline result supplying variants and multiparts
     * @param reverseTagIndex block id -&gt; tag names, from {@link #buildReverseTagIndex}
     */
    private static void attachOrphanBlockEntities(
        @NotNull ConcurrentMap<String, Block> blockIndex,
        @NotNull ConcurrentMap<String, Block.Entity> blockEntityEntries,
        @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> beVariants,
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
            ConcurrentList<String> tags = reverseTagIndex.getOrDefault(blockId, Concurrent.newList());
            ConcurrentMap<String, Block.Variant> variants = mergeBlockEntityVariants(variantMap.getOrDefault(blockId, Concurrent.newMap()), beVariants.get(blockId));
            Optional<Block.Multipart> multipart = Optional.ofNullable(multipartMap.get(blockId));
            HashMap<String, String> textures = new HashMap<>();
            textures.put("#entity", be.textureId());
            blockIndex.put(blockId, new Block(
                ResourceId.parse(blockId),
                new ModelData(),
                Concurrent.adoptMap(textures),
                variants,
                multipart,
                tags,
                new Block.Tint(Block.TintTarget.NONE, Optional.empty()),
                Optional.of(be),
                Block.Source.TILE_ENTITY,
                defaultKeyFor(result, blockId)
            ));
        }
    }

    /**
     * Merges a block's pack-derived blockstate variants with any geometry-bearing variants a
     * block-entity model registers for the same id ({@link BlockModelLoader.LoadResult#variants()}).
     * The block-entity variants win on key collision. Returns {@code packVariants} unchanged when the
     * block has no block-entity variants, so the common case allocates nothing.
     *
     * @param packVariants the variants parsed from the resource pack's blockstate JSON
     * @param beVariants the block-entity state-conditional variants for this id, or {@code null}
     * @return the merged variant map
     */
    private static @NotNull ConcurrentMap<String, Block.Variant> mergeBlockEntityVariants(
        @NotNull ConcurrentMap<String, Block.Variant> packVariants,
        @Nullable ConcurrentMap<String, Block.Variant> beVariants
    ) {
        if (beVariants == null || beVariants.isEmpty()) return packVariants;
        HashMap<String, Block.Variant> merged = new HashMap<>();
        merged.putAll(packVariants);
        merged.putAll(beVariants);
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /**
     * Registers the blockstate-only fallbacks: ids whose blockstate exists but whose
     * {@code block/<id>.json} model is absent (fence/wall/door inventories, {@code small_dripleaf},
     * etc.). The primary block-model loop misses these because it keys on model files; this pass
     * walks {@code blockVariants} + {@code blockMultiparts} keys, skips intentionally-invisible ids,
     * and resolves each remaining id through {@link #resolveBlockStateModel} (item-def override
     * first, then the first variant's model id - multipart-only blocks deliberately resolve to empty).
     * <p>
     * If an id also carries an additive {@link Block.Entity} (e.g. {@code bell}'s bell-cup
     * overlay) the entity attaches to the resulting block and the source flips to
     * {@link Block.Source#TILE_ENTITY} so atlas classification matches the entity-bearing path
     * elsewhere; otherwise the block is tagged {@link Block.Source#BLOCKSTATE_ONLY}.
     *
     * @param blockIndex the index from {@link #buildPrimaryBlockIndex} +
     *     {@link #attachOrphanBlockEntities}; mutated in place
     * @param blockEntityEntries the block-entity geometry table from {@link BlockModelLoader}
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
            if (isInvisible(blockId)) continue;
            Optional<ResolvedBlockModel> resolved = resolveBlockStateModel(blockId, itemDefs, variantMap, result.getBlockModels());
            if (resolved.isEmpty()) continue;
            ResolvedBlockModel hit = resolved.get();

            ModelData modelToUse = hit.model();
            HashMap<String, String> textures = new HashMap<>(modelToUse.getTextures());
            flattenElementFaces(modelToUse, textures);

            Block.Tint tint = tints.getOrDefault(blockId, new Block.Tint(Block.TintTarget.NONE, Optional.empty()));
            ConcurrentMap<String, Block.Variant> variants = variantMap.getOrDefault(blockId, Concurrent.newMap());
            Optional<Block.Multipart> multipart = Optional.ofNullable(multipartMap.get(blockId));
            ConcurrentList<String> tags = reverseTagIndex.getOrDefault(blockId, Concurrent.newList());

            Block.Entity additiveEntity = blockEntityEntries.get(blockId);
            Optional<Block.Entity> attachedEntity = additiveEntity != null && additiveEntity.additive()
                ? Optional.of(additiveEntity) : Optional.empty();
            Block.Source source = attachedEntity.isPresent() ? Block.Source.TILE_ENTITY : Block.Source.BLOCKSTATE_ONLY;
            blockIndex.put(blockId, new Block(
                ResourceId.parse(blockId),
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
     * Paired model id + concrete {@link ModelData} returned by the blockstate-only resolver.
     * The id is retained alongside the data so the caller can stamp the block's model reference.
     */
    private record ResolvedBlockModel(@NotNull String modelId, @NotNull ModelData model) {}

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
     * A resolution candidate is usable only when its model is loaded and would render non-blank
     * ({@link #isUsableResolvedModel}): it skips entity-rendered shells whose model file is empty,
     * and template parents (e.g. {@code block/skull}) whose elements reference unresolved
     * {@code #var} face textures.
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
        @NotNull ConcurrentMap<String, ModelData> blockModels
    ) {
        String itemModelRef = itemDefs.get(blockId);
        if (itemModelRef != null) {
            ModelData model = blockModels.get(itemModelRef);
            if (isUsableResolvedModel(model))
                return Optional.of(new ResolvedBlockModel(itemModelRef, model));
        }

        ConcurrentMap<String, Block.Variant> variants = variantMap.get(blockId);
        if (variants != null && !variants.isEmpty()) {
            String variantModelId = variants.values().iterator().next().modelId();
            ModelData model = blockModels.get(variantModelId);
            if (isUsableResolvedModel(model))
                return Optional.of(new ResolvedBlockModel(variantModelId, model));
        }

        return Optional.empty();
    }

    /**
     * Returns {@code true} when a candidate resolved model would produce a non-blank atlas tile:
     * it is loaded, carries at least one element, and at least one of that element's faces resolves
     * to a concrete (non-{@code #variable}) texture. Template parents like {@code block/skull} /
     * {@code block/cross} have elements but reference unresolved {@code #var} faces, so they flatten
     * to nothing and are rejected here - the same structural signal the renderer uses to keep them
     * out of the atlas, with no hardcoded id list.
     *
     * @param model the candidate resolved model, or {@code null} when the model id did not load
     * @return whether the model would produce a non-blank atlas tile
     */
    private static boolean isUsableResolvedModel(@Nullable ModelData model) {
        if (model == null) return false;
        if (model.getElements().isEmpty()) return false;
        HashMap<String, String> faces = new HashMap<>();
        flattenElementFaces(model, faces);
        return !faces.isEmpty();
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
     *
     * @param model the block model whose first element's faces are flattened
     * @param textures the direction-to-texture map to write resolved bindings into, mutated in place
     */
    private static void flattenElementFaces(@NotNull ModelData model, @NotNull Map<String, String> textures) {
        if (model.getElements().isEmpty()) return;
        ModelElement element = model.getElements().getFirst();

        for (BlockFace blockFace : BlockFace.CACHED_VALUES) {
            ModelFace face = element.getFaces().get(blockFace.direction());
            if (face == null) continue;
            String textureRef = face.getTexture();
            if (textureRef.isBlank()) continue;
            String resolved = Textures.resolveTextureReference(textureRef, model.getTextures());
            if (resolved.startsWith("#")) continue;
            textures.put(blockFace.direction(), resolved);
        }
    }

}
