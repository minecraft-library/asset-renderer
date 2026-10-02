package lib.minecraft.renderer.tooling;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.table.TableEnvelope;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.block.TintWalk;
import lib.minecraft.renderer.tooling.index.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.run.StrictGate;
import lib.minecraft.renderer.tooling.run.TableWriter;
import lib.minecraft.renderer.tooling.run.ToolingPipeline;
import lib.minecraft.renderer.tooling.run.ToolingRun;

/**
 * Entry point of the {@code blockTints} Gradle task - the block-tints flow: every
 * default tint registration from a {@code BlockColors.createDefault()} walk, with colormap
 * targets derived from the source bodies and renderer-capability drops recorded in
 * {@code dropped[]}.
 */
@UtilityClass
public final class BlockTintsFlow {

    /**
     * Runs the flow, writes its table, and applies the run's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingRun run = ToolingPipeline.openSession("blockTints", Diagnostics.Output.CONSOLE)) {
            BlockRegistryIndex index = BlockRegistryIndex.build(run);
            JsonTree root = TableEnvelope.mint(run.diagnostics().path(),
                "BlockColors.createDefault() walk order", run.options().getVersion());
            TintWalk.run(run, index, root);
            TableWriter.write(run, root, "block_tints.json");
            StrictGate.apply(run);
        }
    }

}
