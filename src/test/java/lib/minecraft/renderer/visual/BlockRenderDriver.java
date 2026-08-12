package lib.minecraft.renderer.visual;

import dev.simplified.image.ImageData;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic task that renders blocks to PNG files for visual inspection. For each block spec it
 * writes the isometric 3D render ({@link BlockOptions.Type#ISOMETRIC_3D}) plus one flat 2D render
 * per {@link Face} ({@link BlockOptions.Type#BLOCK_FACE_2D}) so orientation and per-face UV
 * issues can be compared side by side. All output lands under {@code cache/visual/block-render-3d/}.
 * <p>
 * With no {@code -PblockId} the task renders {@link #DEFAULT_BLOCKS} (a mix of non-full-cube shapes)
 * at 512px with 2x supersampling.
 * <p>
 * Usage: {@code ./gradlew blockRender3D [-PblockId=minecraft:tnt] [-PrenderSize=512] [-Pssaa=2]}
 */
public final class BlockRenderDriver {

    private BlockRenderDriver() {}

    /** Default block id list when no {@code args[0]} is supplied (mix of non-full cube shapes). */
    private static final String[] DEFAULT_BLOCKS = {
        "minecraft:cake",
        "minecraft:oak_stairs",
        "minecraft:torch",
        "minecraft:lever",
        "minecraft:brewing_stand_bottle1"
    };

    /**
     * Runs the block renders - one isometric 3D PNG plus six per-face 2D PNGs per block.
     *
     * @param args {@code args[0]} is an optional semicolon-separated list of block specs (id
     *     plus optional {@code [state=foo,...]} suffix parsed as the render variant); {@code args[1]}
     *     is an optional render size (defaults to 512); {@code args[2]} is an optional supersample
     *     factor (defaults to 2)
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        String[] blockIds = args.length > 0
            ? args[0].split(";")
            : DEFAULT_BLOCKS;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        int ssaa = args.length > 2 ? Integer.parseInt(args[2]) : 2;

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        BlockRenderer renderer = new BlockRenderer(context);
        Path outputDir = Path.of("cache/visual/block-render-3d");
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
                .output(OutputOptions.builder()
                    .canvasSize(size)
                    .supersample(ssaa)
                    .antiAlias(true)
                    .build())
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
            for (Face face : Face.CACHED_VALUES) {
                try {
                    BlockOptions faceOpt = BlockOptions.builder()
                        .blockId(blockId)
                        .type(BlockOptions.Type.BLOCK_FACE_2D)
                        .face(face)
                        .output(OutputOptions.builder()
                            .canvasSize(128)
                            .build())
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
