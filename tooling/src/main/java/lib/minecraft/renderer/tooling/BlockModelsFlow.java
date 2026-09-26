package lib.minecraft.renderer.tooling;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.table.TableEnvelope;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.blockentity.BlockEntityRegistryDiscovery;
import lib.minecraft.renderer.tooling.blockentity.BlockEntityRegistryWalk;
import lib.minecraft.renderer.tooling.blockentity.BlockEntitySubject;
import lib.minecraft.renderer.tooling.geometry.GeometryFlow;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.run.OutputRoot;
import lib.minecraft.renderer.tooling.run.StrictGate;
import lib.minecraft.renderer.tooling.run.TableWriter;
import lib.minecraft.renderer.tooling.run.ToolingPipeline;
import lib.minecraft.renderer.tooling.run.ToolingRun;

import java.util.List;

/**
 * Entry point of the {@code blockModels} Gradle task - runs the block-models flow, then the
 * shared geometry flow, in one run: discovery, registry walk, {@code block_models.json},
 * {@code block_geometry.json}.
 *
 * <p>Pure jar to JSON: every registered BER emits (incl. enchanting_table / lectern), and the
 * version is derived rather than merged with a previous run or filtered by a whitelist.
 */
@UtilityClass
public final class BlockModelsFlow {

    /**
     * Runs the flow, writes its table, and applies the run's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingRun run = ToolingPipeline.openSession("blockModels", Diagnostics.Output.CONSOLE)) {
            GeometryFlow.requireModelPackage(run);
            List<BlockEntitySubject> subjects = BlockEntityRegistryDiscovery.discover(run);
            JsonTree root = TableEnvelope.mint(run.diagnostics().path(),
                "BlockEntityRenderers.<clinit> registration order x BlockFamilyPolicies split order",
                run.options().getVersion());
            GeometryManifest manifest = new GeometryManifest(run.cache());
            BlockEntityRegistryWalk.run(run, subjects, manifest, root);
            TableWriter.write(run, root, "block_models.json");
            GeometryFlow.write(run, GeometryFlow.parse(run, manifest),
                OutputRoot.resolve("block_geometry.json"));
            StrictGate.apply(run);
        }
    }

}
