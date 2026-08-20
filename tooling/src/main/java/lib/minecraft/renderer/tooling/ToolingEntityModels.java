package lib.minecraft.renderer.tooling;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.animation.PoseFlow;
import lib.minecraft.renderer.tooling.entity.EntityRegistryDiscovery;
import lib.minecraft.renderer.tooling.entity.EntityRegistryWalk;
import lib.minecraft.renderer.tooling.entity.EntitySubject;
import lib.minecraft.renderer.tooling.geometry.GeometryFlow;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;

import java.util.List;

/**
 * Entry point of the {@code entityModels} Gradle task - runs the entity-models flow then the shared
 * geometry flow and the pose flow in one session: discovery, registry walk,
 * {@code entity_models.json}, {@code entity_geometry.json}, {@code entity_poses.json}.
 */
@UtilityClass
public final class ToolingEntityModels {

    /**
     * Runs the flow, writes its table, and applies the session's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("entityModels", Diagnostics.Output.CONSOLE)) {
            GeometryFlow.requireModelPackage(session);
            List<EntitySubject> subjects = EntityRegistryDiscovery.discover(session);
            JsonTree root = session.envelope(
                "EntityType.<clinit> registry order; members = EntityRendererResolver.resolve() chain");
            GeometryManifest manifest = new GeometryManifest();
            EntityRegistryWalk.run(session, subjects, manifest, root);
            session.write(root, "entity_models.json");
            GeometryFlow.emit(session, manifest, session.resolve("entity_geometry.json"));
            PoseFlow.emit(session, manifest, session.resolve("entity_poses.json"));
            session.failOnStrictGate();
        }
    }

}
