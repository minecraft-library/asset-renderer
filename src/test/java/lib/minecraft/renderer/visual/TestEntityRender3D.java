package lib.minecraft.renderer.visual;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.options.EntityOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
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
 * Renders every entity from the Java-derived pipeline ({@code entity_models.json} /
 * {@code entity_geometry.json}) through {@link EntityRenderer}'s Y-down engine path. Output
 * lands in {@code cache/visual/entity-render-3d/} for visual inspection.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:entityRender3D [-PrenderSize=512] [-PentityId=minecraft:zombie]}.
 */
@UtilityClass
public final class TestEntityRender3D {

    private static final Path OUTPUT_DIR = Path.of("cache/visual/entity-render-3d");

    /** Square edge length (pixels) for each render. */
    private static final int DEFAULT_SIZE = 512;

    /**
     * Runs the Java-pipeline entity sweep.
     *
     * @param args {@code args[0]} is an optional render size; {@code args[1]} an optional single entity id
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_SIZE;
        Optional<String> singleEntityId = args.length > 1 ? Optional.of(args[1]) : Optional.empty();

        Files.createDirectories(OUTPUT_DIR);

        Pipeline.Result result;
        try {
            result = Pipeline.run(PipelineOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        ConcurrentMap<String, EntityModelLoader.EntityDefinition> javaEntities = EntityModelLoader.load();
        if (javaEntities.isEmpty()) {
            System.err.println("entity_models.json / entity_geometry.json not present on the classpath - run ./gradlew :asset-renderer:entityModelsJava first");
            return;
        }
        EntityRenderer renderer = new EntityRenderer(context, javaEntities);

        List<String> entityIds = singleEntityId
            .map(List::of)
            .orElseGet(() -> List.copyOf(new TreeSet<>(javaEntities.keySet())));

        System.out.printf("Rendering %d entit%s via Java pipeline at %dx%d to %s%n",
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
        System.out.printf("Done. %d rendered, %d failed, %d ms total.%n", rendered, failed, totalMs);
    }

}
