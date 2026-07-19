package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.snapshot.TintWalk;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;

import java.nio.file.Path;

/**
 * Entry point of the {@code blockTints} Gradle task - the block-tints flow: every
 * default tint registration from a {@code BlockColors.createDefault()} walk, with colormap
 * targets derived from the source bodies and renderer-capability drops recorded in
 * {@code dropped[]}.
 */
public final class ToolingBlockTints {

    /** The bundled resource directory. */
    private static final Path RESOURCE_DIR = Path.of("src", "main", "resources", "lib", "minecraft", "renderer");

    private ToolingBlockTints() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run;
     * {@code -Dasset.tooling.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("blockTints", Diagnostics.Output.CONSOLE)) {
            BlockRegistryIndex index = BlockRegistryIndex.build(session);
            JsonTree root = session.envelope("BlockColors.createDefault() walk order");
            TintWalk.run(session, index, root);
            Path out = RESOURCE_DIR.resolve("block_tints.json");
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
            throw new ToolingException("blockTints flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
