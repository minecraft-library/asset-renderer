package lib.minecraft.renderer.tooling;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.snapshot.PotionColorWalk;

/**
 * Entry point of the {@code potionColors} Gradle task - the potion-colour flow: every effect
 * colour from a {@code MobEffects.<clinit>} walk, sorted by effect id and forced fully opaque.
 */
public final class ToolingPotionColors {

    private ToolingPotionColors() {
    }

    /**
     * Runs the flow, writes its table, and applies the session's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("potionColors", Diagnostics.Output.CONSOLE)) {
            JsonTree root = session.envelope("effect id sort order");
            PotionColorWalk.run(session, root);
            session.write(root, "potion_colors.json");
            session.failOnStrictGate();
        }
    }

}
