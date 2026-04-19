package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.exception.AssetPipelineException;
import lib.minecraft.renderer.options.AtlasOptions;
import lib.minecraft.renderer.pipeline.AssetPipeline;
import lib.minecraft.renderer.pipeline.AssetPipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.webp.WebPWriteOptions;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
@UtilityClass
public final class ToolingAtlas {

    /**
     * Runs the atlas generator.
     *
     * @param args {@code args[0]} is the output directory; defaults to {@code build/atlas} when omitted
     * @throws IOException if the atlas image or sidecar JSON cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        File outputDir = Path.of(args.length > 0 ? args[0] : "build/atlas").toFile();
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
        ImageFactory imageFactory = new ImageFactory();
        File outputFile = outputDir.toPath().resolve("atlas." + (atlas.image().isAnimated() ? "webp" : "png")).toFile();

        if (atlas.image().isAnimated()) {
            imageFactory.toFile(
                atlas.image(),
                ImageFormat.WEBP,
                outputFile,
                WebPWriteOptions.builder()
                    .isLossless()
                    .isMultithreaded()
                    .build()
            );
        } else {
            imageFactory.toFile(
                atlas.image(),
                ImageFormat.PNG,
                outputFile
            );
        }

        File jsonFile = outputDir.toPath().resolve("atlas.json").toFile();
        Files.writeString(jsonFile.toPath(), atlas.sidecarJson());

        System.out.printf(
            "Wrote atlas: %d tiles -> %s (%dx%d px)%n",
            atlas.tiles().size(),
            outputFile.getAbsolutePath(),
            atlas.image().getWidth(),
            atlas.image().getHeight()
        );
        System.out.println("Wrote sidecar: " + jsonFile.getAbsolutePath());
    }

}
