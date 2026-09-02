package lib.minecraft.renderer.tooling;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.blockentity.BlockEntityRegistryDiscovery;
import lib.minecraft.renderer.tooling.blockentity.BlockEntityRegistryWalk;
import lib.minecraft.renderer.tooling.blockentity.BlockEntitySubject;
import lib.minecraft.renderer.tooling.geometry.GeometryFlow;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;

import java.util.List;

/**
 * Entry point of the {@code blockModels} Gradle task - runs the block-models flow, then the
 * shared geometry flow, in one session: discovery, registry walk, {@code block_models.json},
 * {@code block_geometry.json}.
 *
 * <p>Pure jar to JSON: every registered BER emits (incl. enchanting_table / lectern), and the
 * version is derived rather than merged with a previous run or filtered by a whitelist.
 */
@UtilityClass
public final class ToolingBlockModels {

    /**
     * Runs the flow, writes its table, and applies the session's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("blockModels", Diagnostics.Output.CONSOLE)) {
            GeometryFlow.requireModelPackage(session);
            List<BlockEntitySubject> subjects = BlockEntityRegistryDiscovery.discover(session);
            JsonTree root = session.envelope(
                "BlockEntityRenderers.<clinit> registration order x BlockFamilyPolicies split order");
            GeometryManifest manifest = new GeometryManifest(session.cache());
            BlockEntityRegistryWalk.run(session, subjects, manifest, root);
            session.write(root, "block_models.json");
            GeometryFlow.write(session, GeometryFlow.parse(session, manifest),
                session.resolve("block_geometry.json"));
            session.failOnStrictGate();
        }
    }

}
