package lib.minecraft.renderer.tooling;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.table.TableEnvelope;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.colormap.ColorMapReader;
import lib.minecraft.renderer.tooling.run.StrictGate;
import lib.minecraft.renderer.tooling.run.TableWriter;
import lib.minecraft.renderer.tooling.run.ToolingPipeline;
import lib.minecraft.renderer.tooling.run.ToolingRun;

/**
 * Entry point of the {@code colorMaps} Gradle task - the biome-colormap flow: the
 * three vanilla colormap PNGs read straight from the jar (no pack extraction) as base64
 * big-endian ARGB pixels.
 */
@UtilityClass
public final class ColorMapsFlow {

    /**
     * Runs the flow, writes its table, and applies the run's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingRun run = ToolingPipeline.openSession("colorMaps", Diagnostics.Output.CONSOLE)) {
            JsonTree root = TableEnvelope.mint(run.diagnostics().path(),
                "ColorMapPolicies declaration order", run.options().getVersion());
            ColorMapReader.run(run, root);
            TableWriter.write(run, root, "color_maps.json");
            StrictGate.apply(run);
        }
    }

}
