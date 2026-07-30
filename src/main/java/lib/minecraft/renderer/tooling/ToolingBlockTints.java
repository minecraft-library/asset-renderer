package lib.minecraft.renderer.tooling;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.snapshot.TintWalk;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;

/**
 * Entry point of the {@code blockTints} Gradle task - the block-tints flow: every
 * default tint registration from a {@code BlockColors.createDefault()} walk, with colormap
 * targets derived from the source bodies and renderer-capability drops recorded in
 * {@code dropped[]}.
 */
public final class ToolingBlockTints {

    private ToolingBlockTints() {
    }

    /**
     * Runs the flow, writes its table, and applies the session's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("blockTints", Diagnostics.Output.CONSOLE)) {
            BlockRegistryIndex index = BlockRegistryIndex.build(session);
            JsonTree root = session.envelope("BlockColors.createDefault() walk order");
            TintWalk.run(session, index, root);
            session.write(root, "block_tints.json");
            session.failOnStrictGate();
        }
    }

}
