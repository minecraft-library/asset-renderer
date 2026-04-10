package dev.sbs.renderer.gradle;

import dev.sbs.renderer.AtlasRenderer;
import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.options.AtlasOptions;
import dev.sbs.renderer.pipeline.AssetPipeline;
import dev.sbs.renderer.pipeline.AssetPipelineOptions;
import dev.sbs.renderer.pipeline.HttpFetcher;
import dev.sbs.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Entry point invoked by the {@code generateAtlas} Gradle JavaExec task.
 * <p>
 * The main is a thin I/O wrapper: it bootstraps the asset pipeline (downloading the Minecraft
 * client jar if it is not already cached), wraps the result in a {@link PipelineRendererContext},
 * delegates the actual rendering to {@link AtlasRenderer#renderAtlas(AtlasOptions)}, and writes
 * the produced PNG and sidecar JSON to the output directory passed as the first program
 * argument. All rendering, tile iteration, and progress reporting lives on the renderer; this
 * class only handles file I/O and command-line argument parsing.
 */
public final class AtlasGeneratorMain {

    private AtlasGeneratorMain() {
    }

    /**
     * Runs the atlas generator.
     *
     * @param args {@code args[0]} is the output directory; defaults to {@code build/atlas} when omitted
     * @throws IOException if the atlas image or sidecar JSON cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        File outputDir = new File(args.length > 0 ? args[0] : "build/atlas");
        Files.createDirectories(outputDir.toPath());

        AssetPipeline.Result result;
        try {
            result = new AssetPipeline(new HttpFetcher()).run(AssetPipelineOptions.defaults());
        } catch (AssetPipelineException ex) {
            System.err.println("Atlas generation failed during pipeline bootstrap: " + ex.getMessage());
            throw ex;
        }

        System.out.printf(
            "Pipeline ready: %d block models, %d item models, %d textures cached at %s%n",
            result.getBlockModels().size(),
            result.getItemModels().size(),
            result.getTextures().size(),
            result.getPackRoot()
        );

        PipelineRendererContext context = PipelineRendererContext.of(result);
        AtlasRenderer atlasRenderer = new AtlasRenderer(context);
        AtlasRenderer.AtlasResult atlas = atlasRenderer.renderAtlas(AtlasOptions.defaults());

        File pngFile = new File(outputDir, "atlas.png");
        ImageIO.write(atlas.image().toBufferedImage(), "PNG", pngFile);

        File jsonFile = new File(outputDir, "atlas.json");
        Files.writeString(jsonFile.toPath(), atlas.sidecarJson());

        System.out.printf(
            "Wrote atlas: %d tiles -> %s (%dx%d px)%n",
            atlas.tiles().size(),
            pngFile.getAbsolutePath(),
            atlas.image().getWidth(),
            atlas.image().getHeight()
        );
        System.out.println("Wrote sidecar: " + jsonFile.getAbsolutePath());
    }

}
