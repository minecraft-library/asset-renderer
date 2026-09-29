package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.bake.mesh.BlockGeometryKit;
import lib.minecraft.renderer.content.pack.BlockEntityShadows;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.read.BlockRendererOverrides;
import lib.minecraft.renderer.content.table.BlockGeometryReader;
import lib.minecraft.renderer.content.table.BlockModelReader.BlockModelEntry;
import lib.minecraft.renderer.content.table.BlockModelReader;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads block-entity model geometry, orchestrating two pure reads and one assembler:
 * {@link BlockModelReader} decodes {@code block_models.json} into raw model entries,
 * {@link BlockGeometryReader} decodes {@code block_geometry.json} into bone trees, and
 * {@link BlockEntityAssembler} joins them by {@code geometry} coordinate and pivots the model-id-keyed
 * catalog into a block-id-keyed runtime map. Each entry carries the ASM-extracted geometry, y_axis
 * source convention, inventory transform, tinted flag, optional sub-model parts, and the list of block
 * variants + entity-texture paths that render as this entity model.
 *
 * <p>The output is a flat map of block id to {@link Block.BlockEntity} carrying its geometry as a
 * parent-relative bone tree ({@link Block.BlockEntity.BoneModel}, the same schema as
 * {@code entity_geometry.json}) plus the render presentation metadata and the entity texture
 * reference. These blocks render hierarchically through {@link BlockGeometryKit#buildFromBones} with
 * a presentation transform, rather than the plain-block {@link BlockGeometryKit#buildFromElements}
 * path.
 */
@UtilityClass
@Parity(claim = "pack-resolution")
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
        @NotNull ConcurrentMap<String, Block.BlockEntity> models,
        @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variants
    ) {

        /**
         * The block-entity-backed block ids - the primary model ids unioned with the state-conditional
         * variant ids - the set the shadow diagnostic and the block-index attach passes key off.
         *
         * @return the union of primary and variant block-entity-backed ids
         */
        public @NotNull ConcurrentSet<String> blockEntityBackedIds() {
            return Stream.concat(this.models.keySet().stream(), this.variants.keySet().stream())
                .collect(Concurrent.toWideUnmodifiableSet());
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
     * @throws ContentException if a resource is missing or cannot be parsed, a pack override file
     *     fails format-2 envelope validation, a pack override model or geometry entry does not
     *     bind, or a model entry has no {@code geometry} coordinate or names one that dangles
     */
    public static @NotNull LoadResult load(@NotNull PackStack stack) {
        LoadResult result = load(BlockRendererOverrides.gather(stack.ascending()));
        BlockEntityShadows.report(stack, result.blockEntityBackedIds());
        return result;
    }

    /**
     * Reads the block-entity model catalog natively from the bundled classpath snapshot, with no pack
     * override channel applied.
     *
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws ContentException if a resource is missing or malformed, or a model entry has no
     *     {@code geometry} coordinate or names one that dangles
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
     * @throws ContentException if a resource is missing or malformed, a pack override model or
     *     geometry entry does not bind, or a model entry has no {@code geometry} coordinate or
     *     names one that dangles
     */
    public static @NotNull LoadResult load(@NotNull BlockRendererOverrides overrides) {
        Map<String, BlockModelEntry> models = BlockModelReader.load(overrides);
        Map<String, EntityMesh> geometries = BlockGeometryReader.load(overrides);
        return BlockEntityAssembler.assemble(models, geometries);
    }

}
