package lib.minecraft.renderer.tooling2;

import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.ToolingException;
import lib.minecraft.renderer.tooling2.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling2.kernel.ToolingSession;
import lib.minecraft.renderer.tooling2.snapshot.GlintItemsWalk;

import java.nio.file.Path;

/**
 * Entry point of the {@code glintItems2} Gradle task - the always-glinted item flow (SPINE 3.5):
 * every item whose {@code Items} registration sets {@code ENCHANTMENT_GLINT_OVERRIDE = true},
 * from an {@code Items.<clinit>} walk, as sorted namespaced ids.
 */
public final class ToolingGlintItems {

    /** The v2 resource directory (SPINE 4 registry). */
    private static final Path V2 = Path.of("src", "main", "resources", "lib", "minecraft", "renderer", "v2");

    private ToolingGlintItems() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run (doc-12 K3);
     * {@code -Dasset.tooling2.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("glintItems", Diagnostics.Output.CONSOLE)) {
            JsonNode root = JsonNode.envelope(session, "item id sort order");
            GlintItemsWalk.run(session, root);
            root.writeResource(V2.resolve("glint_items.json"), session.diagnostics());
            failOnStrictGate(session);
        }
    }

    /** The doc-12 K3 strict gate: ERROR always fails; {@code strict=warn} adds WARN. */
    private static void failOnStrictGate(ToolingSession session) {
        Diagnostics diagnostics = session.diagnostics();
        boolean warnStrict = "warn".equalsIgnoreCase(System.getProperty("asset.tooling2.strict", "").trim());
        int errors = diagnostics.count(Diagnostics.Severity.ERROR);
        int warns = diagnostics.count(Diagnostics.Severity.WARN);
        if (errors > 0 || (warnStrict && warns > 0))
            throw new ToolingException("glintItems flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
