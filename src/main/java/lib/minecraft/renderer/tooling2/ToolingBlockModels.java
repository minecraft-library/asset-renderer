package lib.minecraft.renderer.tooling2;

import lib.minecraft.renderer.tooling2.blockentity.BlockEntityRegistryDiscovery;
import lib.minecraft.renderer.tooling2.blockentity.BlockEntityRegistryWalk;
import lib.minecraft.renderer.tooling2.blockentity.BlockEntitySubject;
import lib.minecraft.renderer.tooling2.geometry.GeometryFlow;
import lib.minecraft.renderer.tooling2.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.ToolingException;
import lib.minecraft.renderer.tooling2.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling2.kernel.ToolingSession;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point of the {@code blockModels2} Gradle task - the block-models flow, then the shared
 * geometry flow, ONE session (SPINE 3.3 / decision 12): discovery, registry walk,
 * {@code v2/block_models.json}, {@code v2/block_geometry.json}.
 *
 * <p>Pure jar to JSON: the legacy merge-with-previous [X10] and whitelist [X11] are gone -
 * every registered BER emits (incl. enchanting_table / lectern), the version is derived [D47].
 */
public final class ToolingBlockModels {

    /** The v2 resource directory (SPINE 4 registry). */
    private static final Path V2 = Path.of("src", "main", "resources", "lib", "minecraft", "renderer", "v2");

    private ToolingBlockModels() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run (doc-12 K3; non-zero exit);
     * {@code -Dasset.tooling2.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("blockModels", Diagnostics.Output.CONSOLE)) {
            List<BlockEntitySubject> subjects = BlockEntityRegistryDiscovery.discover(session);
            JsonNode root = JsonNode.envelope(session,
                "BlockEntityRenderers.<clinit> registration order x BlockFamilyPolicies split order");
            GeometryManifest manifest = new GeometryManifest();
            BlockEntityRegistryWalk.run(session, subjects, manifest, root);
            root.writeResource(V2.resolve("block_models.json"), session.diagnostics());
            GeometryFlow.emit(session, manifest, V2.resolve("block_geometry.json"));
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
            throw new ToolingException("blockModels flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
