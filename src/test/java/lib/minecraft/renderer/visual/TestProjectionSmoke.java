package lib.minecraft.renderer.visual;

import dev.simplified.image.ImageData;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.options.BlockOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Diagnostic task that renders one block under every {@link Projection} (at the default
 * {@code RIGHT}/{@code DOWN} facing) plus the four facing combinations of {@link
 * Projection#ISOMETRIC}, to {@code cache/visual/projection-smoke/} for visual inspection.
 * Defaults to {@code minecraft:tnt} - a complete cube with distinct top/side/bottom faces that make
 * orientation and clipping issues obvious.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:projectionSmoke [-PblockId=minecraft:tnt] [-PrenderSize=512]}
 */
@UtilityClass
public final class TestProjectionSmoke {

    /**
     * Runs the projection smoke renders.
     *
     * @param args {@code args[0]} optional block id (default {@code minecraft:tnt}); {@code args[1]}
     *     optional render size (default 512)
     * @throws IOException if the output directory cannot be created
     */
    public static void main(String @NotNull [] args) throws IOException {
        String blockId = args.length > 0 ? args[0] : "minecraft:tnt";
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 512;

        PipelineRendererContext context = PipelineRendererContext.of(Pipeline.run(PipelineOptions.defaults()));
        BlockRenderer renderer = new BlockRenderer(context);
        Path outputDir = Path.of("cache/visual/projection-smoke");
        Files.createDirectories(outputDir);
        String safe = blockId.replace(":", "_");

        for (Projection projection : Projection.values())
            render(renderer, blockId, size, projection, Projection.Horizontal.RIGHT, Projection.Vertical.DOWN,
                outputDir, safe + "__" + projection + "__RIGHT_DOWN");

        for (Projection.Horizontal horizontal : Projection.Horizontal.values())
            for (Projection.Vertical vertical : Projection.Vertical.values())
                render(renderer, blockId, size, Projection.ISOMETRIC, horizontal, vertical,
                       outputDir, safe + "__ISOMETRIC__" + horizontal + "_" + vertical);
    }

    private static void render(@NotNull BlockRenderer renderer, @NotNull String blockId, int size,
                               @NotNull Projection projection, @NotNull Projection.Horizontal horizontal,
                               @NotNull Projection.Vertical vertical, @NotNull Path outputDir, @NotNull String name) {
        try {
            ImageData image = renderer.render(BlockOptions.builder()
                .blockId(blockId)
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .outputSize(size)
                .supersample(2)
                .antiAlias(true)
                .projection(projection)
                .horizontalFacing(horizontal)
                .verticalFacing(vertical)
                .build());
            File outputFile = outputDir.resolve(name + ".png").toFile();
            ImageIO.write(image.toBufferedImage(), "PNG", outputFile);
            System.out.println("Wrote " + outputFile.getName());
        } catch (Exception ex) {
            System.err.println("FAILED " + name + ": " + ex.getMessage());
        }
    }

}
