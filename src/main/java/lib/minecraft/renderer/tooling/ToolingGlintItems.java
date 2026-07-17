package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.snapshot.GlintItemsWalk;

import java.nio.file.Path;

/**
 * Entry point of the {@code glintItems} Gradle task - the always-glinted item flow:
 * every item whose {@code Items} registration sets {@code ENCHANTMENT_GLINT_OVERRIDE = true},
 * from an {@code Items.<clinit>} walk, as sorted namespaced ids.
 */
public final class ToolingGlintItems {

    /** The bundled resource directory. */
    private static final Path RESOURCE_DIR = Path.of("src", "main", "resources", "lib", "minecraft", "renderer");

    private ToolingGlintItems() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run;
     * {@code -Dasset.tooling.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("glintItems", Diagnostics.Output.CONSOLE)) {
            JsonNode root = session.envelope("item id sort order");
            GlintItemsWalk.run(session, root);
            Path out = RESOURCE_DIR.resolve("glint_items.json");
            root.write(out);
            session.diagnostics().info("wrote %s", out.toAbsolutePath());
            failOnStrictGate(session);
        }
    }

    /** The strict gate: ERROR always fails; {@code strict=warn} adds WARN. */
    private static void failOnStrictGate(ToolingSession session) {
        Diagnostics diagnostics = session.diagnostics();
        boolean warnStrict = "warn".equalsIgnoreCase(System.getProperty("asset.tooling.strict", "").trim());
        int errors = diagnostics.count(Diagnostics.Severity.ERROR);
        int warns = diagnostics.count(Diagnostics.Severity.WARN);
        if (errors > 0 || (warnStrict && warns > 0))
            throw new ToolingException("glintItems flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
