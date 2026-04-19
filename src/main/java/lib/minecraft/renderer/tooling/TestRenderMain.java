package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.exception.AssetPipelineException;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.options.BlockOptions;
import lib.minecraft.renderer.pipeline.AssetPipeline;
import lib.minecraft.renderer.pipeline.AssetPipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import dev.simplified.image.ImageData;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic task that renders a single block to a PNG file for visual inspection. Defaults to
 * {@code minecraft:tnt} at 512px with 2x supersampling - the TNT block has text and distinct
 * top/side/bottom faces that make orientation issues immediately obvious.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:testRender [-PblockId=minecraft:tnt] [-PrenderSize=512]}
 */
@UtilityClass
public final class TestRenderMain {

    /** Alternate default block id list (standard full cubes + faced furnace + piston). */
    private static final String[] BLOCK_TEST_1 = {
        "minecraft:tnt",
        "minecraft:crafting_table",
        "minecraft:furnace[facing=east,lit=false]",
        "minecraft:bookshelf",
        "minecraft:piston"
    };

    /** Default block id list when no {@code args[0]} is supplied (mix of non-full cube shapes). */
    private static final String[] BLOCK_TEST_2 = {
        "minecraft:cake",
        "minecraft:oak_stairs",
        "minecraft:torch",
        "minecraft:lever",
        "minecraft:brewing_stand_bottle1"
    };

    /**
     * Runs the block renders.
     *
     * @param args {@code args[0]} is an optional semicolon-separated list of block specs (id
     *     plus optional {@code [variant=foo]} suffix); {@code args[1]} is an optional render
     *     size (defaults to 512); {@code args[2]} is an optional supersample factor (defaults to 2)
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        String[] blockIds = args.length > 0
            ? args[0].split(";")
            : BLOCK_TEST_2;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        int ssaa = args.length > 2 ? Integer.parseInt(args[2]) : 2;

        AssetPipeline.Result result;
        try {
            result = new AssetPipeline(new HttpFetcher()).run(AssetPipelineOptions.defaults());
        } catch (AssetPipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        BlockRenderer renderer = new BlockRenderer(context);
        Path outputDir = Path.of("cache/test-render");
        Files.createDirectories(outputDir);

        for (String blockSpec : blockIds) {
            blockSpec = blockSpec.trim();
            String blockId;
            String variant = "";
            int bracket = blockSpec.indexOf('[');
            if (bracket >= 0) {
                blockId = blockSpec.substring(0, bracket);
                variant = blockSpec.substring(bracket + 1, blockSpec.length() - 1);
            } else {
                blockId = blockSpec;
            }
            String safeName = blockId.replace(":", "_");

            BlockOptions options = BlockOptions.builder()
                .blockId(blockId)
                .variant(variant)
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .outputSize(size)
                .supersample(ssaa)
                .antiAlias(true)
                .build();

            System.out.printf("Rendering %s%s at %dx%d (ssaa=%d)...%n", blockId,
                variant.isEmpty() ? "" : "[" + variant + "]", size, size, ssaa);
            try {
                ImageData image = renderer.render(options);
                File outputFile = outputDir.resolve(safeName + ".png").toFile();
                ImageIO.write(image.toBufferedImage(), "PNG", outputFile);
                System.out.println("Wrote " + outputFile.getAbsolutePath());
            } catch (Exception ex) {
                System.err.println("  FAILED: " + ex.getMessage());
                ex.printStackTrace(System.err);
            }

            // Render each 2D face for comparison
            for (BlockFace face : BlockFace.values()) {
                try {
                    BlockOptions faceOpt = BlockOptions.builder()
                        .blockId(blockId)
                        .type(BlockOptions.Type.BLOCK_FACE_2D)
                        .face(face)
                        .outputSize(128)
                        .build();
                    ImageData faceImage = renderer.render(faceOpt);
                    File faceFile = outputDir.resolve(safeName + "_" + face.direction() + ".png").toFile();
                    ImageIO.write(faceImage.toBufferedImage(), "PNG", faceFile);
                } catch (Exception ex) {
                    System.err.println("  Failed 2D " + face + ": " + ex.getMessage());
                }
            }
        }
    }

}
