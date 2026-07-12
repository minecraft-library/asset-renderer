package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.load.block.BlockModelReader;
import lib.minecraft.renderer.pipeline.load.block.BlockRendererOverrides;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Loads block-entity model geometry from {@code /lib/minecraft/renderer/block_models.json},
 * the tooling-generated catalog keyed by block-entity-model id. Each entry carries the
 * ASM-extracted geometry, y_axis source convention, inventory transform, tinted flag,
 * optional sub-model parts, and the list of block variants + entity-texture paths that
 * render as this entity model. The pattern-derived per-block fields
 * ({@code iconRotation} on beds, {@code additive} on bells, {@code tint} on banners) are
 * emitted directly into the block entries by the tooling's id-pattern walker.
 *
 * <p>The output is a flat map of block id to {@link Block.Entity} carrying its geometry as a
 * parent-relative bone tree ({@link Block.Entity.BoneModel}, the same schema as
 * {@code entity_geometry.json}) plus the render presentation metadata and the entity texture
 * reference. These blocks render hierarchically through
 * {@link BlockGeometryKit#buildFromBones} with a presentation transform, rather than the
 * plain-block {@link BlockGeometryKit#buildFromElements} path.
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
    ) {}

    /**
     * Loads block-entity geometry and wiring from {@code block_models.json}.
     * <p>
     * The catalog is keyed by block-entity-model id; entries live under a {@code "models"}
     * envelope object when present, otherwise the root object is the model map directly. Each model
     * entry supplies the shared {@code "model"} geometry, optional sub-model {@code "parts"}, and a
     * {@code "blocks"} array binding that geometry to concrete block ids with their entity textures.
     * The {@code "//"} comment key and non-object / model-less entries are skipped. Blocks flagged
     * with a {@code "variant"} route to {@link LoadResult#variants()} instead of the primary map.
     *
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if the resource is missing or cannot be parsed
     */
    public static @NotNull LoadResult load() {
        return BlockModelReader.load(Diagnostics.root("blockModels", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Loads block-entity geometry from the classpath snapshot with the pack {@code renderer/*.json}
     * override channel applied (05-models.md §4.3). Gathers each pack's block-entity geometry overrides
     * and overlays them per block-entity model id / geometry coordinate; a vanilla-only stack ships
     * none, so the result is byte-identical to {@link #load()}.
     *
     * @param stack the resolved pack stack whose {@code renderer/*.json} override files are consulted
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if the resource is missing or cannot be parsed, or a pack override file
     *     fails format-2 envelope validation
     */
    public static @NotNull LoadResult load(@NotNull PackStack stack) {
        Diagnostics diagnostics = Diagnostics.root("blockModels", Diagnostics.Output.CONSOLE, null);
        return BlockModelReader.load(diagnostics, BlockRendererOverrides.gather(stack, diagnostics));
    }

}
