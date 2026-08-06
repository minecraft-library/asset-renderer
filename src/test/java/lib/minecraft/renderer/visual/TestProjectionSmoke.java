package lib.minecraft.renderer.visual;

import dev.simplified.image.ImageData;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.tensor.EulerRotation;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostic task that renders one block under every {@link Projection} (at its base pose) plus a
 * handful of sample camera rotations on {@link Projection#ISOMETRIC}, to
 * {@code cache/visual/projection-smoke/} for visual inspection. The caller's rotation now composes
 * onto the projection's base pose - posing the camera and its lighting together, with no separate
 * facing enum - so the sample renders exercise that rotation-into-pose path. Defaults to
 * {@code minecraft:tnt} - a complete cube with distinct top/side/bottom faces that make orientation
 * and clipping issues obvious.
 * <p>
 * Usage: {@code ./gradlew projectionSmoke [-PblockId=minecraft:tnt] [-PrenderSize=512]}
 */
@UtilityClass
public final class TestProjectionSmoke {

    /**
     * Named sample camera rotations applied to {@link Projection#ISOMETRIC}, exercising the
     * rotation-into-pose path now that facing is just a supplied {@link EulerRotation}.
     */
    private static final @NotNull Map<String, EulerRotation> SAMPLE_ROTATIONS = sampleRotations();

    private static @NotNull Map<String, EulerRotation> sampleRotations() {
        Map<String, EulerRotation> map = new LinkedHashMap<>();
        map.put("base", EulerRotation.NONE);
        map.put("yaw_plus_90", new EulerRotation(0f, 90f, 0f));
        map.put("yaw_minus_90", new EulerRotation(0f, -90f, 0f));
        map.put("pitch_plus_45", new EulerRotation(45f, 0f, 0f));
        map.put("roll_plus_30", new EulerRotation(0f, 0f, 30f));
        return map;
    }

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

        PipelineRendererContext context = PipelineRendererContext.of(ClientAcquisition.acquire(ClientOptions.defaults()));
        BlockRenderer renderer = new BlockRenderer(context);
        Path outputDir = Path.of("cache/visual/projection-smoke");
        Files.createDirectories(outputDir);
        String safe = blockId.replace(":", "_");

        for (Projection projection : Projection.values())
            render(renderer, blockId, size, projection, EulerRotation.NONE,
                outputDir, safe + "__" + projection + "__base");

        for (Map.Entry<String, EulerRotation> sample : SAMPLE_ROTATIONS.entrySet())
            render(renderer, blockId, size, Projection.ISOMETRIC, sample.getValue(),
                outputDir, safe + "__ISOMETRIC__" + sample.getKey());
    }

    private static void render(@NotNull BlockRenderer renderer, @NotNull String blockId, int size,
                               @NotNull Projection projection, @NotNull EulerRotation rotation,
                               @NotNull Path outputDir, @NotNull String name) {
        try {
            ImageData image = renderer.render(BlockOptions.builder()
                .blockId(blockId)
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .output(OutputOptions.builder()
                    .canvasSize(size)
                    .supersample(2)
                    .antiAlias(true)
                    .projection(projection)
                    .rotation(rotation)
                    .build())
                .build());
            File outputFile = outputDir.resolve(name + ".png").toFile();
            ImageIO.write(image.toBufferedImage(), "PNG", outputFile);
            System.out.println("Wrote " + outputFile.getName());
        } catch (Exception ex) {
            System.err.println("FAILED " + name + ": " + ex.getMessage());
        }
    }

}
