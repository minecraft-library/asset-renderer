package lib.minecraft.renderer.tooling;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.table.TableEnvelope;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.run.StrictGate;
import lib.minecraft.renderer.tooling.run.TableWriter;
import lib.minecraft.renderer.tooling.run.ToolingPipeline;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import lib.minecraft.renderer.tooling.block.BlockItemAliasWalk;
import lib.minecraft.renderer.tooling.index.BlockRegistryIndex;

/**
 * Entry point of the {@code blockItems} Gradle task - walks {@code Items.<clinit>} for the blocks
 * that share another block's item ({@code white_wall_banner} -> {@code white_banner},
 * {@code skeleton_wall_skull} -> {@code skeleton_skull}, filled cauldrons -> {@code cauldron}) and
 * writes the secondary-to-standing alias map read at runtime so an aliased block's inventory icon
 * poses through its standing block item's {@code display.gui}.
 */
@UtilityClass
public final class BlockItemsFlow {

    /**
     * Runs the flow, writes its table, and applies the run's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingRun run = ToolingPipeline.openSession("blockItems", Diagnostics.Output.CONSOLE)) {
            BlockRegistryIndex index = BlockRegistryIndex.build(run);
            JsonTree root = TableEnvelope.mint(run.diagnostics().path(),
                "secondary block ids sorted; each maps to its standing block's item id", run.options().getVersion());
            BlockItemAliasWalk.run(run, index, root);
            TableWriter.write(run, root, "block_items.json");
            StrictGate.apply(run);
        }
    }

}
