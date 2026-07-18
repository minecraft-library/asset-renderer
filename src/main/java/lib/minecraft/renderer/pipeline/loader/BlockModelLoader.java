package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.loader.BlockModelReader.BlockModelEntry;
import lib.minecraft.renderer.pipeline.util.BlockRendererOverrides;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
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
     * Loads block-entity geometry from the classpath snapshot with no pack override channel applied.
     *
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if the resource is missing or cannot be parsed
     */
    public static @NotNull LoadResult load() {
        return load(Diagnostics.root("blockModels", Diagnostics.Output.CONSOLE, null));
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
        Diagnostics diagnostics = Diagnostics.root("blockModels", Diagnostics.Output.CONSOLE, null);
        LoadResult result = load(diagnostics, BlockRendererOverrides.gather(stack, diagnostics));
        BlockEntityShadowDiagnostics.report(stack, result.blockEntityBackedIds());
        return result;
    }

    /**
     * Reads the block-entity model catalog natively from the bundled classpath snapshot, with no pack
     * override channel applied.
     *
     * @param diagnostics the scope envelope and read warnings are recorded to
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if a resource is missing, malformed, or a geometry coordinate dangles
     */
    public static @NotNull LoadResult load(@NotNull Diagnostics diagnostics) {
        return load(diagnostics, BlockRendererOverrides.EMPTY);
    }

    /**
     * Reads the block-entity model catalog from the bundled classpath snapshot, then overlays the
     * pack-supplied {@code renderer/*.json} override channel per top-level entry: a pack {@code models}
     * entry replaces the classpath model of the same id, and a pack {@code geometries} entry replaces
     * the classpath bone tree at the same coordinate. An overridden id still renders through
     * {@link BlockGeometryKit#buildFromBones} - same kit, same lighting, same parity locks.
     *
     * @param diagnostics the scope envelope and read warnings are recorded to
     * @param overrides the gathered pack override channel; {@link BlockRendererOverrides#EMPTY} for a
     *     vanilla-only stack, which leaves the result byte-identical to the classpath snapshot
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if a resource is missing, malformed, or a geometry coordinate dangles
     */
    public static @NotNull LoadResult load(@NotNull Diagnostics diagnostics, @NotNull BlockRendererOverrides overrides) {
        Map<String, BlockModelEntry> models = BlockModelReader.load(diagnostics, overrides);
        Map<String, EntityModelData> geometries = BlockGeometryReader.load(diagnostics, overrides);
        return BlockEntityAssembler.assemble(models, geometries, diagnostics);
    }

}
