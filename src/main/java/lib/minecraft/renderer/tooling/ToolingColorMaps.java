package lib.minecraft.renderer.tooling;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.colormap.ColorMapWalk;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;

import java.nio.file.Path;

/**
 * Entry point of the {@code colorMaps} Gradle task - the biome-colormap flow: the
 * three vanilla colormap PNGs read straight from the jar (no pack extraction) as base64
 * big-endian ARGB pixels.
 */
public final class ToolingColorMaps {

    /** The bundled resource directory. */
    private static final Path RESOURCE_DIR = Path.of("src", "main", "resources", "lib", "minecraft", "renderer");

    private ToolingColorMaps() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run;
     * {@code -Dasset.tooling.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("colorMaps", Diagnostics.Output.CONSOLE)) {
            JsonTree root = session.envelope("ColorMapPolicies declaration order");
            ColorMapWalk.run(session, root);
            Path out = RESOURCE_DIR.resolve("color_maps.json");
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
            throw new ToolingException("colorMaps flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
