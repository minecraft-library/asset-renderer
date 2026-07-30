package lib.minecraft.renderer.tooling;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.snapshot.GlintItemsWalk;

/**
 * Entry point of the {@code glintItems} Gradle task - the always-glinted item flow:
 * every item whose {@code Items} registration sets {@code ENCHANTMENT_GLINT_OVERRIDE = true},
 * from an {@code Items.<clinit>} walk, as sorted namespaced ids.
 */
public final class ToolingGlintItems {

    private ToolingGlintItems() {
    }

    /**
     * Runs the flow, writes its table, and applies the session's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("glintItems", Diagnostics.Output.CONSOLE)) {
            JsonTree root = session.envelope("item id sort order");
            GlintItemsWalk.run(session, root);
            session.write(root, "glint_items.json");
            session.failOnStrictGate();
        }
    }

}
