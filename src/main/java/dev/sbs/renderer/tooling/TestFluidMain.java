package dev.sbs.renderer.tooling;

import dev.sbs.renderer.FluidRenderer;
import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.geometry.Biome;
import dev.sbs.renderer.options.FluidOptions;
import dev.sbs.renderer.pipeline.AssetPipeline;
import dev.sbs.renderer.pipeline.AssetPipelineOptions;
import dev.sbs.renderer.pipeline.PipelineRendererContext;
import dev.sbs.renderer.pipeline.client.HttpFetcher;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.gif.GifWriteOptions;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Diagnostic task that exercises every {@link FluidRenderer} code path and dumps the output to
 * {@code cache/test-fluid/} for visual inspection. Static renders dump as PNG, animated renders
 * as GIF. Covers:
 * <ul>
 * <li>Source-block renders for both water and lava in both render types (phase 1).</li>
 * <li>Animated renders that sample the {@code water_still} / {@code lava_still} strips across
 * their full frame cycle (phase 2).</li>
 * <li>Sloped corner-height and flow-direction renders (phase 3 - will look flat-topped until
 * {@link dev.sbs.renderer.kit.FluidGeometryKit#buildFluidCube} is expanded).</li>
 * <li>Biome-tint variants and the explicit ARGB override (phase 4 biome variants will all look
 * identical until {@link Biome.TintTarget#WATER} is wired; the override path works today).</li>
 * </ul>
 * Usage: {@code ./gradlew :asset-renderer:testFluid}.
 */
@UtilityClass
public final class TestFluidMain {

    /** Output directory for all fluid renders. */
    private static final Path OUTPUT_DIR = Path.of("cache/test-fluid");

    /** Square edge length (pixels) used for static renders. */
    private static final int STATIC_SIZE = 512;

    /** Square edge length (pixels) used for animated renders; smaller to keep GIF file sizes sane. */
    private static final int ANIMATED_SIZE = 256;

    /**
     * Runs the test matrix.
     *
     * @param args ignored
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        AssetPipeline.Result result;
        try {
            result = new AssetPipeline(new HttpFetcher()).run(AssetPipelineOptions.defaults());
        } catch (AssetPipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        FluidRenderer renderer = new FluidRenderer(context);
        ImageFactory imageFactory = new ImageFactory();

        // Phase 1 - source blocks + 2D face.
        render(renderer, imageFactory, "water_source_iso", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .outputSize(STATIC_SIZE)
            .build());
        render(renderer, imageFactory, "lava_source_iso", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.LAVA)
            .outputSize(STATIC_SIZE)
            .build());
        render(renderer, imageFactory, "water_face_2d", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .type(FluidOptions.Type.FLUID_FACE_2D)
            .outputSize(STATIC_SIZE)
            .build());
        render(renderer, imageFactory, "lava_face_2d", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.LAVA)
            .type(FluidOptions.Type.FLUID_FACE_2D)
            .outputSize(STATIC_SIZE)
            .build());

        // Phase 2 - animation. water_still has 32 frames at frametime=2; lava_still has 20 frames
        // at frametime=3. Sample at native frametime so the strip is walked exactly once.
        render(renderer, imageFactory, "water_animated_iso", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .outputSize(ANIMATED_SIZE)
            .frameCount(32)
            .ticksPerFrame(2)
            .build());
        render(renderer, imageFactory, "lava_animated_iso", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.LAVA)
            .outputSize(ANIMATED_SIZE)
            .frameCount(20)
            .ticksPerFrame(3)
            .build());

        // Phase 3 - sloped top + flow direction. Will look flat-topped until FluidGeometryKit is
        // expanded; kept in the matrix so the fix lights these up without any driver changes.
        render(renderer, imageFactory, "water_level_4_flow_ne", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .outputSize(STATIC_SIZE)
            .cornerHeights(new FluidOptions.CornerHeights(0.875f, 0.5f, 0.375f, 0.75f))
            .flowAngleRadians(Optional.of((float) Math.toRadians(45)))
            .build());

        // Phase 4 - biome tint. Until Biome.TintTarget.WATER ships these will all render at the
        // vanilla default water colour; only the override variant differs.
        render(renderer, imageFactory, "water_plains", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .outputSize(STATIC_SIZE)
            .biome(Biome.Vanilla.PLAINS)
            .build());
        render(renderer, imageFactory, "water_swamp", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .outputSize(STATIC_SIZE)
            .biome(Biome.Vanilla.SWAMP)
            .build());
        render(renderer, imageFactory, "water_cherry_grove", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .outputSize(STATIC_SIZE)
            .biome(Biome.Vanilla.CHERRY_GROVE)
            .build());
        render(renderer, imageFactory, "water_warm_ocean", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .outputSize(STATIC_SIZE)
            .biome(Biome.Vanilla.WARM_OCEAN)
            .build());
        render(renderer, imageFactory, "water_override_magenta", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .outputSize(STATIC_SIZE)
            .waterTintArgbOverride(0xFFFF00FF)
            .build());

        System.out.println("Done. Outputs in " + OUTPUT_DIR.toAbsolutePath());
    }

    /**
     * Renders one fluid output, writing it to {@code <OUTPUT_DIR>/<slug>.{png|gif}} and printing
     * a timing + dimension summary line. Animated renders go to GIF with a transparent palette;
     * static renders go to PNG.
     */
    private static void render(
        @NotNull FluidRenderer renderer,
        @NotNull ImageFactory imageFactory,
        @NotNull String slug,
        @NotNull FluidOptions options
    ) throws IOException {
        long t0 = System.nanoTime();
        ImageData image = renderer.render(options);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        int width = image.getFrames().getFirst().pixels().width();
        int height = image.getFrames().getFirst().pixels().height();
        int frameCount = image.getFrames().size();
        boolean animated = frameCount > 1;

        String extension = animated ? "gif" : "png";
        File out = OUTPUT_DIR.resolve(slug + "." + extension).toFile();

        if (animated) {
            imageFactory.toFile(
                image,
                ImageFormat.GIF,
                out,
                GifWriteOptions.builder()
                    .isTransparent()
                    .build()
            );
        } else {
            imageFactory.toFile(image, ImageFormat.PNG, out);
        }

        System.out.printf("  %-32s -> %s (%d ms, %dx%d, %d frame%s)%n",
            slug, out.getName(), elapsedMs, width, height, frameCount, frameCount == 1 ? "" : "s");
    }

}
