package lib.minecraft.renderer.driver;

import api.simplified.mojang.exception.MojangApiException;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.call.request.BlockOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.exception.ClientException;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Diagnostic task that renders blocks to PNG files for visual inspection. For each block spec it
 * writes the isometric 3D render ({@link BlockOptions.Type#ISOMETRIC_3D}) plus flat 2D renders of its
 * faces ({@link BlockOptions.Type#BLOCK_FACE_2D}) so orientation and per-face UV issues can be compared
 * side by side. All output lands under {@code cache/visual/block-render-3d/}.
 * <p>
 * With no {@code -PblockId} the task renders {@link #DEFAULT_BLOCKS} (a mix of non-full-cube shapes)
 * at 512px with 2x supersampling; a block named by {@code -PblockId} draws all six faces.
 * <p>
 * Usage: {@code ./gradlew blockRender3D [-PblockId=minecraft:tnt] [-PrenderSize=512] [-Pssaa=2]}
 */
@UtilityClass
public final class BlockRenderDriver {

    /**
     * The blocks rendered when no {@code args[0]} is supplied, each with the faces drawn flat beside
     * it. Cake binds three textures across its faces, so it draws all six. Every face of each of the
     * others binds one texture, so one face stands for all six - the top, which on the brewing stand's
     * bottle is a face its model declares none of, so it resolves through the block's {@code all} /
     * {@code side} / {@code particle} fallback chain, the one present-block face no other subject
     * reaches.
     */
    private static final List<Subject> DEFAULT_BLOCKS = List.of(
        new Subject("minecraft:cake", List.of(Face.values())),
        new Subject("minecraft:oak_stairs", List.of(Face.UP)),
        new Subject("minecraft:torch", List.of(Face.UP)),
        new Subject("minecraft:lever", List.of(Face.UP)),
        new Subject("minecraft:brewing_stand_bottle1", List.of(Face.UP))
    );

    /**
     * One block to render.
     *
     * @param spec the block id, plus an optional {@code [state=foo,...]} suffix parsed as the render
     *     variant
     * @param faces the faces drawn flat beside the isometric render
     */
    private record Subject(@NotNull String spec, @NotNull List<Face> faces) {}

    /**
     * Loads vanilla's context and runs the block renders.
     *
     * @param args the arguments {@link #run} takes
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (ClientException | MojangApiException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        run(RendererContext.load(result), args);
    }

    /**
     * Runs the block renders - one isometric 3D PNG plus a 2D PNG per face drawn for each block.
     *
     * @param pipeline the loaded vanilla context
     * @param args {@code args[0]} is an optional semicolon-separated list of block specs (id
     *     plus optional {@code [state=foo,...]} suffix parsed as the render variant); {@code args[1]}
     *     is an optional render size (defaults to 512); {@code args[2]} is an optional supersample
     *     factor (defaults to 2); {@code args[3]} is an optional semicolon-separated list of texture
     *     ids to force absent, which is how a texture miss is made reachable on a vanilla-only stack
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void run(@NotNull RendererContext pipeline, String @NotNull [] args) throws IOException {
        List<Subject> subjects = args.length > 0
            ? Arrays.stream(args[0].split(";")).map(spec -> new Subject(spec, List.of(Face.values()))).toList()
            : DEFAULT_BLOCKS;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        int ssaa = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        String[] hidden = args.length > 3 && !args[3].isBlank() ? args[3].split(";") : new String[0];

        RendererContext context = hidden.length == 0
            ? pipeline
            : pipeline.hiding(hidden);
        BlockRenderer renderer = new BlockRenderer(context);
        Path outputDir = Path.of("cache/visual/block-render-3d");
        Files.createDirectories(outputDir);

        for (Subject subject : subjects) {
            String blockSpec = subject.spec().trim();
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
                ImageData image = renderer.render(options).image();
                File outputFile = outputDir.resolve(safeName + ".png").toFile();
                ImageFactory.toFile(image, ImageFormat.PNG, outputFile);
                System.out.println("Wrote " + outputFile.getAbsolutePath());
            } catch (Exception ex) {
                System.err.println("  FAILED: " + ex.getMessage());
                ex.printStackTrace(System.err);
            }

            // Render each 2D face for comparison
            for (Face face : subject.faces()) {
                try {
                    BlockOptions faceOpt = BlockOptions.builder()
                        .blockId(blockId)
                        .type(BlockOptions.Type.BLOCK_FACE_2D)
                        .face(face)
                        .output(OutputOptions.builder()
                            .canvasSize(128)
                            .build())
                        .build();
                    ImageData faceImage = renderer.render(faceOpt).image();
                    File faceFile = outputDir.resolve(safeName + "_" + face.direction() + ".png").toFile();
                    ImageFactory.toFile(faceImage, ImageFormat.PNG, faceFile);
                } catch (Exception ex) {
                    System.err.println("  Failed 2D " + face + ": " + ex.getMessage());
                }
            }
        }
    }

}
