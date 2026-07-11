package lib.minecraft.renderer.tooling2;

import lib.minecraft.renderer.tooling2.entity.EntityRegistryDiscovery;
import lib.minecraft.renderer.tooling2.entity.EntityRegistryWalk;
import lib.minecraft.renderer.tooling2.entity.EntitySubject;
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
 * Entry point of the {@code entityModels2} Gradle task - the entity-models flow, then the
 * shared geometry flow, ONE session (SPINE 3.1 / decision 12): discovery, registry walk,
 * {@code v2/entity_models.json}, {@code v2/entity_geometry.json}.
 */
public final class ToolingEntityModels {

    /** The v2 resource directory (SPINE 4 registry). */
    private static final Path V2 = Path.of("src", "main", "resources", "lib", "minecraft", "renderer");

    private ToolingEntityModels() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run (doc-12 K3; non-zero exit);
     * {@code -Dasset.tooling2.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("entityModels", Diagnostics.Output.CONSOLE)) {
            List<EntitySubject> subjects = EntityRegistryDiscovery.discover(session);
            JsonNode root = JsonNode.envelope(session,
                "EntityType.<clinit> registry order; members = EntityRendererResolver.resolve() chain");
            GeometryManifest manifest = new GeometryManifest();
            EntityRegistryWalk.run(session, subjects, manifest, root);
            root.writeResource(V2.resolve("entity_models.json"), session.diagnostics());
            GeometryFlow.emit(session, manifest, V2.resolve("entity_geometry.json"));
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
            throw new ToolingException("entityModels flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
