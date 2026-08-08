package lib.minecraft.renderer.tooling;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.defaults.BlockDefaultsWalk;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;

/**
 * Entry point of the {@code blockDefaults} Gradle task - walks every registered block's default
 * blockstate from a {@code registerDefaultState} bytewalk, plus the in-file {@code unresolved[]}
 * for class-resolution failures, so the file is reconstructible from itself and never conflates
 * an absent entry with an empty one.
 */
public final class ToolingBlockDefaults {

    private ToolingBlockDefaults() {
    }

    /**
     * Runs the flow, writes its table, and applies the session's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("blockDefaults", Diagnostics.Output.CONSOLE)) {
            BlockRegistryIndex index = BlockRegistryIndex.build(session);
            JsonTree root = session.envelope(
                "block ids sorted; properties sorted within each default object");
            BlockDefaultsWalk.run(session, index, root);
            session.write(root, "block_defaults.json");
            session.failOnStrictGate();
        }
    }

}
