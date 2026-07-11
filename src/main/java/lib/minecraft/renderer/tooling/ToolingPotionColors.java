package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.JsonNode;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.snapshot.PotionColorWalk;

import java.nio.file.Path;

/**
 * Entry point of the {@code potionColors2} Gradle task - the potion-colour flow (SPINE 3.5):
 * every effect colour from a {@code MobEffects.<clinit>} walk, sorted by effect id and forced
 * fully opaque.
 */
public final class ToolingPotionColors {

    /** The v2 resource directory (SPINE 4 registry). */
    private static final Path V2 = Path.of("src", "main", "resources", "lib", "minecraft", "renderer");

    private ToolingPotionColors() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run (doc-12 K3);
     * {@code -Dasset.tooling.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("potionColors", Diagnostics.Output.CONSOLE)) {
            JsonNode root = JsonNode.envelope(session, "effect id sort order");
            PotionColorWalk.run(session, root);
            root.writeResource(V2.resolve("potion_colors.json"), session.diagnostics());
            failOnStrictGate(session);
        }
    }

    /** The doc-12 K3 strict gate: ERROR always fails; {@code strict=warn} adds WARN. */
    private static void failOnStrictGate(ToolingSession session) {
        Diagnostics diagnostics = session.diagnostics();
        boolean warnStrict = "warn".equalsIgnoreCase(System.getProperty("asset.tooling.strict", "").trim());
        int errors = diagnostics.count(Diagnostics.Severity.ERROR);
        int warns = diagnostics.count(Diagnostics.Severity.WARN);
        if (errors > 0 || (warnStrict && warns > 0))
            throw new ToolingException("potionColors flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
