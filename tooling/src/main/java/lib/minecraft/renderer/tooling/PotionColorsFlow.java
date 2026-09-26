package lib.minecraft.renderer.tooling;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.table.TableEnvelope;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.run.StrictGate;
import lib.minecraft.renderer.tooling.run.TableWriter;
import lib.minecraft.renderer.tooling.run.ToolingPipeline;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import lib.minecraft.renderer.tooling.item.PotionColorWalk;

/**
 * Entry point of the {@code potionColors} Gradle task - the potion-colour flow: every effect
 * colour from a {@code MobEffects.<clinit>} walk, sorted by effect id and forced fully opaque.
 */
@UtilityClass
public final class PotionColorsFlow {

    /**
     * Runs the flow, writes its table, and applies the run's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingRun run = ToolingPipeline.openSession("potionColors", Diagnostics.Output.CONSOLE)) {
            JsonTree root = TableEnvelope.mint(run.diagnostics().path(),
                "effect id sort order", run.options().getVersion());
            PotionColorWalk.run(run, root);
            TableWriter.write(run, root, "potion_colors.json");
            StrictGate.apply(run);
        }
    }

}
