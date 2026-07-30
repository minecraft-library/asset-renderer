package lib.minecraft.renderer.tooling;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.BlockItemAliasWalk;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;

/**
 * Entry point of the {@code blockItems} Gradle task - walks {@code Items.<clinit>} for the blocks
 * that share another block's item ({@code white_wall_banner} -> {@code white_banner},
 * {@code skeleton_wall_skull} -> {@code skeleton_skull}, filled cauldrons -> {@code cauldron}) and
 * writes the secondary-to-standing alias map read at runtime so an aliased block's inventory icon
 * poses through its standing block item's {@code display.gui}.
 */
public final class ToolingBlockItems {

    private ToolingBlockItems() {
    }

    /**
     * Runs the flow, writes its table, and applies the session's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("blockItems", Diagnostics.Output.CONSOLE)) {
            BlockRegistryIndex index = BlockRegistryIndex.build(session);
            JsonTree root = session.envelope("secondary block ids sorted; each maps to its standing block's item id");
            BlockItemAliasWalk.run(session, index, root);
            session.write(root, "block_items.json");
            session.failOnStrictGate();
        }
    }

}
