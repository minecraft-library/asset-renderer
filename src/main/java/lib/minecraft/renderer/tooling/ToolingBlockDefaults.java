package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.tooling.defaults.BlockDefaultsWalk;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import dev.simplified.gson.node.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;

import java.nio.file.Path;

/**
 * Entry point of the {@code blockDefaults} Gradle task - walks every registered block's default
 * blockstate from a {@code registerDefaultState} bytewalk, plus the in-file {@code unresolved[]}
 * for class-resolution failures, so the file is reconstructible from itself and never conflates
 * an absent entry with an empty one.
 */
public final class ToolingBlockDefaults {

    /** The bundled resource directory. */
    private static final Path RESOURCE_DIR = Path.of("src", "main", "resources", "lib", "minecraft", "renderer");

    private ToolingBlockDefaults() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run (non-zero exit);
     * {@code -Dasset.tooling.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("blockDefaults", Diagnostics.Output.CONSOLE)) {
            BlockRegistryIndex index = BlockRegistryIndex.build(session);
            JsonTree root = session.envelope(
                "block ids sorted; properties sorted within each default object");
            BlockDefaultsWalk.run(session, index, root);
            Path out = RESOURCE_DIR.resolve("block_defaults.json");
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
            throw new ToolingException("blockDefaults flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
