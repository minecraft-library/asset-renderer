package lib.minecraft.renderer.tooling;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.table.TableEnvelope;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.block.BlockDefaultsWalk;
import lib.minecraft.renderer.tooling.run.StrictGate;
import lib.minecraft.renderer.tooling.run.TableWriter;
import lib.minecraft.renderer.tooling.run.ToolingPipeline;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import lib.minecraft.renderer.tooling.index.BlockRegistryIndex;

/**
 * Entry point of the {@code blockDefaults} Gradle task - walks every registered block's default
 * blockstate from a {@code registerDefaultState} bytewalk, plus the in-file {@code unresolved[]}
 * for class-resolution failures, so the file is reconstructible from itself and never conflates
 * an absent entry with an empty one.
 */
@UtilityClass
public final class BlockDefaultsFlow {

    /**
     * Runs the flow, writes its table, and applies the run's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingRun run = ToolingPipeline.openSession("blockDefaults", Diagnostics.Output.CONSOLE)) {
            BlockRegistryIndex index = BlockRegistryIndex.build(run);
            JsonTree root = TableEnvelope.mint(run.diagnostics().path(),
                "block ids sorted; properties sorted within each default object", run.options().getVersion());
            BlockDefaultsWalk.run(run, index, root);
            TableWriter.write(run, root, "block_defaults.json");
            StrictGate.apply(run);
        }
    }

}
