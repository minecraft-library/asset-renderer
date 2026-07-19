package lib.minecraft.renderer.tooling;

import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.webp.WebPWriteOptions;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.option.AtlasOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.tooling.atlas.AtlasSidecar;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import dev.simplified.gson.exception.JsonException;
import dev.simplified.gson.node.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point of the {@code atlas} Gradle task - a render job over the texture pack, not a
 * client-jar extraction. A thin I/O shell around {@code ClientAcquisition.run} + {@link
 * AtlasRenderer#renderAtlas}: it writes the atlas image ({@code atlas.png}, or {@code atlas.webp}
 * for animated packs) plus the {@code atlas.json} sidecar to the output directory (scratch
 * {@code build/atlas/}, never a bundled resource).
 *
 * <p>The sidecar is verified through the typed {@link AtlasSidecar} on the read side only;
 * {@code AtlasRenderer} still emits the raw JSON natively.
 */
public final class ToolingAtlas {

    private ToolingAtlas() {
    }

    /**
     * Runs the atlas generator.
     *
     * @param args {@code args[0]} is the output directory; defaults to {@code build/atlas}
     * @throws IOException if the atlas image or sidecar cannot be written
     */
    public static void main(String[] args) throws IOException {
        Diagnostics diagnostics = Diagnostics.root("atlas", Diagnostics.Output.CONSOLE, null);
        Path outputDir = Path.of(args.length > 0 ? args[0] : "build/atlas");
        Files.createDirectories(outputDir);

        ClientAssets assets = ClientAcquisition.acquire(ClientOptions.defaults());
        PipelineRendererContext context = PipelineRendererContext.of(assets);
        diagnostics.info("pipeline ready: %d blocks, %d items at %s",
            context.knownBlockIds().size(), context.knownItemIds().size(), assets.vanillaRoot());
        AtlasRenderer.AtlasResult atlas = new AtlasRenderer(context).renderAtlas(AtlasOptions.defaults());

        boolean animated = atlas.image().isAnimated();
        File outputFile = outputDir.resolve("atlas." + (animated ? "webp" : "png")).toFile();
        ImageFactory imageFactory = new ImageFactory();
        if (animated)
            imageFactory.toFile(atlas.image(), ImageFormat.WEBP, outputFile,
                WebPWriteOptions.builder().isLossless().isMultithreaded().build());
        else
            imageFactory.toFile(atlas.image(), ImageFormat.PNG, outputFile);

        Path jsonFile = outputDir.resolve("atlas.json");
        Files.writeString(jsonFile, atlas.sidecarJson());

        AtlasSidecar sidecar;
        try {
            sidecar = AtlasSidecar.parse(JsonTree.parse(atlas.sidecarJson().getBytes(StandardCharsets.UTF_8)));
        } catch (JsonException ex) {
            throw new ToolingException(ex, "Failed to parse atlas sidecar JSON");
        }
        if (sidecar.count() != sidecar.tiles().size())
            throw new ToolingException("atlas sidecar count %d disagrees with %d tiles", sidecar.count(), sidecar.tiles().size());
        diagnostics.info("wrote atlas: %d tiles -> %s (%dx%d px)",
            sidecar.tiles().size(), outputFile.getAbsolutePath(), atlas.image().getWidth(), atlas.image().getHeight());
        diagnostics.info("wrote sidecar: %s", jsonFile.toAbsolutePath());
    }

}
