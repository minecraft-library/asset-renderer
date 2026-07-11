package lib.minecraft.renderer.tooling2;

import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.ToolingException;
import lib.minecraft.renderer.tooling2.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling2.kernel.ToolingSession;
import lib.minecraft.renderer.tooling2.snapshot.TintWalk;
import lib.minecraft.renderer.tooling2.vanilla.BlockRegistryIndex;

import java.nio.file.Path;

/**
 * Entry point of the {@code blockTints2} Gradle task - the block-tints flow (SPINE 3.5): every
 * default tint registration from a {@code BlockColors.createDefault()} walk, with colormap
 * targets derived from the source bodies and renderer-capability drops recorded in
 * {@code dropped[]} (decision 24).
 */
public final class ToolingBlockTints {

    /** The v2 resource directory (SPINE 4 registry). */
    private static final Path V2 = Path.of("src", "main", "resources", "lib", "minecraft", "renderer");

    private ToolingBlockTints() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run (doc-12 K3);
     * {@code -Dasset.tooling2.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("blockTints", Diagnostics.Output.CONSOLE)) {
            BlockRegistryIndex index = BlockRegistryIndex.build(session);
            JsonNode root = JsonNode.envelope(session, "BlockColors.createDefault() walk order");
            TintWalk.run(session, index, root);
            root.writeResource(V2.resolve("block_tints.json"), session.diagnostics());
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
            throw new ToolingException("blockTints flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
