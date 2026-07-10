package lib.minecraft.renderer.tooling2;

import lib.minecraft.renderer.tooling2.colormap.ColorMapWalk;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.ToolingException;
import lib.minecraft.renderer.tooling2.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling2.kernel.ToolingSession;

import java.nio.file.Path;

/**
 * Entry point of the {@code colorMaps2} Gradle task - the biome-colormap flow (SPINE 3.6): the
 * three vanilla colormap PNGs read straight from the jar (no pack extraction) as base64
 * big-endian ARGB pixels.
 */
public final class ToolingColorMaps {

    /** The v2 resource directory (SPINE 4 registry). */
    private static final Path V2 = Path.of("src", "main", "resources", "lib", "minecraft", "renderer", "v2");

    private ToolingColorMaps() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run (doc-12 K3);
     * {@code -Dasset.tooling2.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("colorMaps", Diagnostics.Output.CONSOLE)) {
            JsonNode root = JsonNode.envelope(session, "ColorMapPolicies declaration order");
            ColorMapWalk.run(session, root);
            root.writeResource(V2.resolve("color_maps.json"), session.diagnostics());
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
            throw new ToolingException("colorMaps flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
