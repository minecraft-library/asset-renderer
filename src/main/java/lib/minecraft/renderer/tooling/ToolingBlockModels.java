package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.tooling.blockentity.BlockEntityRegistryDiscovery;
import lib.minecraft.renderer.tooling.blockentity.BlockEntityRegistryWalk;
import lib.minecraft.renderer.tooling.blockentity.BlockEntitySubject;
import lib.minecraft.renderer.tooling.geometry.GeometryFlow;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import dev.simplified.gson.node.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point of the {@code blockModels} Gradle task - runs the block-models flow, then the
 * shared geometry flow, in one session: discovery, registry walk, {@code block_models.json},
 * {@code block_geometry.json}.
 *
 * <p>Pure jar to JSON: every registered BER emits (incl. enchanting_table / lectern), and the
 * version is derived rather than merged with a previous run or filtered by a whitelist.
 */
public final class ToolingBlockModels {

    /** The bundled resource directory. */
    private static final Path RESOURCE_DIR = Path.of("src", "main", "resources", "lib", "minecraft", "renderer");

    private ToolingBlockModels() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run (non-zero exit);
     * {@code -Dasset.tooling.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("blockModels", Diagnostics.Output.CONSOLE)) {
            List<BlockEntitySubject> subjects = BlockEntityRegistryDiscovery.discover(session);
            JsonTree root = session.envelope(
                "BlockEntityRenderers.<clinit> registration order x BlockFamilyPolicies split order");
            GeometryManifest manifest = new GeometryManifest();
            BlockEntityRegistryWalk.run(session, subjects, manifest, root);
            Path out = RESOURCE_DIR.resolve("block_models.json");
            root.write(out);
            session.diagnostics().info("wrote %s", out.toAbsolutePath());
            GeometryFlow.emit(session, manifest, RESOURCE_DIR.resolve("block_geometry.json"));
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
            throw new ToolingException("blockModels flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
