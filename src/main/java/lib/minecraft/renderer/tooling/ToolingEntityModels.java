package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.tooling.entity.EntityRegistryDiscovery;
import lib.minecraft.renderer.tooling.entity.EntityRegistryWalk;
import lib.minecraft.renderer.tooling.entity.EntitySubject;
import lib.minecraft.renderer.tooling.geometry.GeometryFlow;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point of the {@code entityModels} Gradle task - runs the entity-models flow, then the
 * shared geometry flow, in one session: discovery, registry walk, {@code entity_models.json},
 * {@code entity_geometry.json}.
 */
public final class ToolingEntityModels {

    /** The bundled resource directory. */
    private static final Path RESOURCE_DIR = Path.of("src", "main", "resources", "lib", "minecraft", "renderer");

    private ToolingEntityModels() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run (non-zero exit);
     * {@code -Dasset.tooling.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("entityModels", Diagnostics.Output.CONSOLE)) {
            List<EntitySubject> subjects = EntityRegistryDiscovery.discover(session);
            JsonNode root = session.envelope(
                "EntityType.<clinit> registry order; members = EntityRendererResolver.resolve() chain");
            GeometryManifest manifest = new GeometryManifest();
            EntityRegistryWalk.run(session, subjects, manifest, root);
            Path out = RESOURCE_DIR.resolve("entity_models.json");
            root.write(out);
            session.diagnostics().info("wrote %s", out.toAbsolutePath());
            GeometryFlow.emit(session, manifest, RESOURCE_DIR.resolve("entity_geometry.json"));
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
            throw new ToolingException("entityModels flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
