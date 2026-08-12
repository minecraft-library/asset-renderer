package lib.minecraft.renderer.tooling;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.colormap.ColorMapWalk;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;

/**
 * Entry point of the {@code colorMaps} Gradle task - the biome-colormap flow: the
 * three vanilla colormap PNGs read straight from the jar (no pack extraction) as base64
 * big-endian ARGB pixels.
 */
public final class ToolingColorMaps {

    private ToolingColorMaps() {
    }

    /**
     * Runs the flow, writes its table, and applies the session's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("colorMaps", Diagnostics.Output.CONSOLE)) {
            JsonTree root = session.envelope("ColorMapPolicies declaration order");
            ColorMapWalk.run(session, root);
            session.write(root, "color_maps.json");
            session.failOnStrictGate();
        }
    }

}
