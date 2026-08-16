package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.client.VanillaSourcePaths;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.loader.BlockModelReader.BlockModelEntry;
import lib.minecraft.renderer.pipeline.util.BlockRendererOverrides;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Loads block-entity model geometry, orchestrating two pure reads and one assembler:
 * {@link BlockModelReader} decodes {@code block_models.json} into raw model entries,
 * {@link BlockGeometryReader} decodes {@code block_geometry.json} into bone trees, and
 * {@link BlockEntityAssembler} joins them by {@code geometry} coordinate and pivots the model-id-keyed
 * catalog into a block-id-keyed runtime map. Each entry carries the ASM-extracted geometry, y_axis
 * source convention, inventory transform, tinted flag, optional sub-model parts, and the list of block
 * variants + entity-texture paths that render as this entity model.
 *
 * <p>The output is a flat map of block id to {@link Block.Entity} carrying its geometry as a
 * parent-relative bone tree ({@link Block.Entity.BoneModel}, the same schema as
 * {@code entity_geometry.json}) plus the render presentation metadata and the entity texture
 * reference. These blocks render hierarchically through {@link BlockGeometryKit#buildFromBones} with
 * a presentation transform, rather than the plain-block {@link BlockGeometryKit#buildFromElements}
 * path.
 */
@UtilityClass
public class BlockModelLoader {

    /**
     * The result of loading {@code block_models.json}: the per-block-id primary geometry
     * ({@link #models}) plus any state-conditional geometry ({@link #variants}) a block-entity
     * model registers under a blockstate variant key. The pipeline context merges {@link #variants}
     * into each block's {@link Block#variants()} so the standard variant path selects them - the
     * ceiling hanging sign's straight-chain mesh is bound to {@code attached=true} this way.
     *
     * @param models block id to its primary (default-state) block-entity model
     * @param variants block id to its {@code variantKey -> geometry-bearing variant} map
     */
    public record LoadResult(
        @NotNull ConcurrentMap<String, Block.Entity> models,
        @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variants
    ) {

        /**
         * The block-entity-backed block ids - the primary model ids unioned with the state-conditional
         * variant ids - the set the shadow diagnostic and the block-index attach passes key off.
         *
         * @return the union of primary and variant block-entity-backed ids
         */
        public @NotNull Set<String> blockEntityBackedIds() {
            Set<String> ids = new HashSet<>(this.models.keySet());
            ids.addAll(this.variants.keySet());
            return ids;
        }
    }

    /**
     * Loads block-entity geometry from the classpath snapshot with the pack {@code renderer/*.json}
     * override channel applied, then reports any pack shipping a vanilla-form model / blockstate for a
     * code-rendered block entity. A vanilla-only stack ships no override, so the result is
     * byte-identical to {@link #load()} and the shadow report emits nothing.
     *
     * @param stack the resolved pack stack whose {@code renderer/*.json} override files are consulted
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if the resource is missing or cannot be parsed, or a pack override file
     *     fails format-2 envelope validation
     */
    public static @NotNull LoadResult load(@NotNull PackStack stack) {
        LoadResult result = load(BlockRendererOverrides.gather(stack));
        reportShadowedIds(stack, result.blockEntityBackedIds());
        return result;
    }

    /**
     * Warns when a non-vanilla pack ships a vanilla-form {@code models/block/<id>.json} or
     * {@code blockstates/<id>.json} for a block-entity-backed id (chests, beds, banners, signs, skulls,
     * shulkers, conduit, decorated_pot, copper_golem_statue, ...).
     *
     * <p>Those ids render through code-driven block-entity geometry - the non-additive
     * {@link Block.Entity} precedence in the block index builder hides any pack-supplied model, exactly
     * as the vanilla client ignores a stray {@code chest.json}. Rather than discarding the pack's file
     * in silence, this names the pack and points at the {@code renderer/*.json} override channel
     * ({@link BlockRendererOverrides}) that CAN deliberately replace block-entity geometry. It changes
     * no precedence and moves no output byte: a vanilla-only stack emits nothing.
     *
     * <p>Takes the id set rather than reading it back off a {@link LoadResult}, so the warning can be
     * exercised against a chosen id without loading the shipped catalog.
     *
     * @param stack the resolved pack stack
     * @param blockEntityBackedIds the namespaced ids that render through code-driven block-entity
     *     geometry (the block-entity primary models plus their state-conditional variants)
     */
    static void reportShadowedIds(@NotNull PackStack stack, @NotNull Set<String> blockEntityBackedIds) {
        for (ResourcePack pack : stack.ascending()) {
            if (pack.id().equals(PackId.VANILLA)) continue;
            for (String beId : blockEntityBackedIds) {
                ResourceId id = ResourceId.parse(beId);
                warnIfShipped(pack, beId, id, VanillaSourcePaths.MODELS_BLOCK_SUBDIR, "model");
                warnIfShipped(pack, beId, id, VanillaSourcePaths.BLOCKSTATES_SUBDIR, "blockstate");
            }
        }
    }

    /**
     * Warns once when the pack ships {@code assets/<ns>/<subdir>/<localName>.json} for a block-entity id,
     * across any of the pack's active roots. Container-agnostic ({@code exists} works for the
     * materialized directory and archive containers alike).
     */
    private static void warnIfShipped(@NotNull ResourcePack pack, @NotNull String beId, @NotNull ResourceId id,
                                      @NotNull String subdir, @NotNull String kind) {
        String relative = VanillaSourcePaths.assetSubdir(id.namespace(), subdir) + "/" + id.name() + ".json";
        boolean shipped = pack.roots().stream().anyMatch(root -> pack.container().exists(root.prefix() + relative));
        if (shipped)
            System.err.printf("Pack '%s' ships a %s for block-entity-backed id '%s', which is SHADOWED by "
                + "code-driven block-entity geometry (a resource pack cannot change its geometry this way, "
                + "as in the vanilla client). To deliberately replace it, use the renderer/*.json override "
                + "channel (renderer/block_models.json + renderer/block_geometry.json at the pack root).%n",
                pack.id(), kind, beId);
    }

    /**
     * Reads the block-entity model catalog natively from the bundled classpath snapshot, with no pack
     * override channel applied.
     *
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if a resource is missing, malformed, or a geometry coordinate dangles
     */
    public static @NotNull LoadResult load() {
        return load(BlockRendererOverrides.EMPTY);
    }

    /**
     * Reads the block-entity model catalog from the bundled classpath snapshot, then overlays the
     * pack-supplied {@code renderer/*.json} override channel per top-level entry: a pack {@code models}
     * entry replaces the classpath model of the same id, and a pack {@code geometries} entry replaces
     * the classpath bone tree at the same coordinate. An overridden id still renders through
     * {@link BlockGeometryKit#buildFromBones} - same kit, same lighting, same parity locks.
     *
     * @param overrides the gathered pack override channel; {@link BlockRendererOverrides#EMPTY} for a
     *     vanilla-only stack, which leaves the result byte-identical to the classpath snapshot
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if a resource is missing, malformed, or a geometry coordinate dangles
     */
    public static @NotNull LoadResult load(@NotNull BlockRendererOverrides overrides) {
        Map<String, BlockModelEntry> models = BlockModelReader.load(overrides);
        Map<String, EntityModelData> geometries = BlockGeometryReader.load(overrides);
        return BlockEntityAssembler.assemble(models, geometries);
    }

}
