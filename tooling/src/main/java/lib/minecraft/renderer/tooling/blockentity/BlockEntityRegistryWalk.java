package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import lib.minecraft.renderer.tooling.index.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.index.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The block-models registry walk - the ONLY stage that touches the output tree. Builds the
 * run-wide indexes once (shared with the entity flow), loops the subjects in registry order,
 * and fans each subject into its family splits via {@link BlockGeometrySourceResolver}, appending
 * one {@code models} entry per split id.
 *
 * <p>The block side has no {@code group_of} analogue - no post-pass.
 */
@UtilityClass
public final class BlockEntityRegistryWalk {

    /**
     * Runs the per-split resolver chain over every subject, appending to {@code root.models}.
     *
     * @param run the live run
     * @param subjects the discovered subjects in registry order
     * @param manifest the geometry-request registry the resolvers populate
     * @param root the envelope root owning the {@code models} node
     */
    public static void run(
        @NotNull ToolingRun run,
        @NotNull List<BlockEntitySubject> subjects,
        @NotNull GeometryManifest manifest,
        @NotNull JsonTree root
    ) {
        LayerDefinitionIndex layerDefinitions = LayerDefinitionIndex.build(run);
        BlockRegistryIndex blockRegistry = BlockRegistryIndex.build(run);
        BlockTintFlagResolver tint = new BlockTintFlagResolver(run);
        BlockGuiResolver gui = new BlockGuiResolver(run.cache());
        InventoryTransformResolver transform = new InventoryTransformResolver(run);

        JsonTree models = root.child("models");
        for (BlockEntitySubject subject : subjects) {
            BlockGeometrySourceResolver geometry =
                new BlockGeometrySourceResolver(run, subject, layerDefinitions, manifest);
            List<BlockGeometrySourceResolver.Split> splits = geometry.resolveSplits();

            List<String> splitIds = splits
                .stream()
                .map(BlockGeometrySourceResolver.Split::splitId)
                .collect(Collectors.toList());
            BlockCatalogResolver catalog =
                new BlockCatalogResolver(run, blockRegistry, layerDefinitions, subject, splitIds);

            for (BlockGeometrySourceResolver.Split split : splits)
                models.put(split.splitId(), new BlockEntityRendererResolver(subject, split, tint, gui, catalog, transform).resolve());
        }
    }

}
