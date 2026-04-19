package lib.minecraft.renderer.tooling;

import dev.simplified.image.ImageData;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.exception.AssetPipelineException;
import lib.minecraft.renderer.options.EntityOptions;
import lib.minecraft.renderer.pipeline.AssetPipeline;
import lib.minecraft.renderer.pipeline.AssetPipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Diagnostic task that renders every entity listed in the bundled
 * {@code /lib/minecraft/renderer/entity_models.json} snapshot through {@link EntityRenderer} in
 * its default 3D GUI-item pose and dumps each output to {@code cache/test-entity/} for visual
 * inspection. Per-entity failures are logged to stderr and the run continues, so a single broken
 * model never aborts the sweep.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:testEntity [-PrenderSize=512] [-PentityId=minecraft:zombie]}.
 * Passing {@code -PentityId} limits the run to a single entity; omitting it renders the full set.
 */
@UtilityClass
public final class TestEntityMain {

    /** Output directory for every entity render. */
    private static final Path OUTPUT_DIR = Path.of("cache/test-entity");

    /** Square edge length (pixels) for each render. */
    private static final int DEFAULT_SIZE = 512;

    /**
     * Runs the entity sweep.
     *
     * @param args {@code args[0]} is an optional render size (defaults to {@value #DEFAULT_SIZE});
     *     {@code args[1]} is an optional single entity id to restrict the sweep to
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_SIZE;
        Optional<String> singleEntityId = args.length > 1 ? Optional.of(args[1]) : Optional.empty();

        Files.createDirectories(OUTPUT_DIR);

        AssetPipeline.Result result;
        try {
            result = new AssetPipeline(new HttpFetcher()).run(AssetPipelineOptions.defaults());
        } catch (AssetPipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        EntityRenderer renderer = new EntityRenderer(context);

        List<String> entityIds = singleEntityId
            .map(List::of)
            .orElseGet(() -> List.copyOf(new TreeSet<>(EntityModelLoader.load().keySet())));

        System.out.printf("Rendering %d entit%s at %dx%d to %s%n",
            entityIds.size(),
            entityIds.size() == 1 ? "y" : "ies",
            size, size,
            OUTPUT_DIR.toAbsolutePath());

        int rendered = 0;
        int failed = 0;
        long t0 = System.nanoTime();

        for (String entityId : entityIds) {
            String safeName = entityId.replace(':', '_');
            EntityOptions options = EntityOptions.builder()
                .entityId(Optional.of(entityId))
                .outputSize(size)
                .antiAlias(true)
                .build();

            long perT0 = System.nanoTime();
            try {
                ImageData image = renderer.render(options);
                File out = OUTPUT_DIR.resolve(safeName + ".png").toFile();
                ImageIO.write(image.toBufferedImage(), "PNG", out);

                long elapsedMs = (System.nanoTime() - perT0) / 1_000_000L;
                System.out.printf("  %-40s -> %s.png (%d ms)%n", entityId, safeName, elapsedMs);
                rendered++;
            } catch (Exception ex) {
                System.err.printf("  %-40s FAILED: %s%n", entityId, ex.getMessage());
                failed++;
            }
        }

        long totalMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("Done. %d rendered, %d failed, %d ms total.%n",
            rendered, failed, totalMs);
    }

}
