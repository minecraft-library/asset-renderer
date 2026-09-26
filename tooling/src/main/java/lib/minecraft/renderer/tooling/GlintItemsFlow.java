package lib.minecraft.renderer.tooling;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.table.TableEnvelope;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.item.GlintItemsWalk;
import lib.minecraft.renderer.tooling.run.StrictGate;
import lib.minecraft.renderer.tooling.run.TableWriter;
import lib.minecraft.renderer.tooling.run.ToolingPipeline;
import lib.minecraft.renderer.tooling.run.ToolingRun;

/**
 * Entry point of the {@code glintItems} Gradle task - the always-glinted item flow:
 * every item whose {@code Items} registration sets {@code ENCHANTMENT_GLINT_OVERRIDE = true},
 * from an {@code Items.<clinit>} walk, as sorted namespaced ids.
 */
@UtilityClass
public final class GlintItemsFlow {

    /**
     * Runs the flow, writes its table, and applies the run's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingRun run = ToolingPipeline.openSession("glintItems", Diagnostics.Output.CONSOLE)) {
            JsonTree root = TableEnvelope.mint(run.diagnostics().path(),
                "item id sort order", run.options().getVersion());
            GlintItemsWalk.run(run, root);
            TableWriter.write(run, root, "glint_items.json");
            StrictGate.apply(run);
        }
    }

}
