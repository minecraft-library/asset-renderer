package lib.minecraft.renderer.visual;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.gif.GifWriteOptions;
import lib.minecraft.renderer.FluidRenderer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.engine.kit.FluidGeometryKit;
import lib.minecraft.renderer.engine.texture.Biome;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.FluidOptions;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Diagnostic task that exercises every {@link FluidRenderer} code path and dumps the output to
 * {@code cache/visual/fluid-renderer/} for visual inspection. This is a <b>functional / visual</b>
 * tool ("does it render") - there is no parity gate. Static renders dump as PNG, animated renders
 * as a transparent-palette GIF. Covers 12 outputs:
 * <ul>
 * <li>Source-block iso renders and 2D face blits for both water and lava
 * ({@link FluidOptions.Type#ISOMETRIC_3D} and {@link FluidOptions.Type#FLUID_FACE_2D}).</li>
 * <li>Animated renders that walk the {@code water_still} (32 frames, 2 ticks each) and
 * {@code lava_still} (20 frames, 3 ticks each) strips exactly once at native cadence.</li>
 * <li>A sloped corner-height + flow-direction render, whose non-coplanar top
 * {@link FluidGeometryKit#buildFluidCube} builds through its own four-corner fan.</li>
 * <li>Biome-tint variants (plains / swamp / cherry_grove / warm_ocean), each sampled through
 * {@link Block.TintTarget#WATER}, and the explicit ARGB override that bypasses the sample.</li>
 * </ul>
 * <p>
 * Usage: {@code ./gradlew fluidRenderer}. Takes no {@code -P} flags; sizes are
 * fixed at {@link #STATIC_SIZE} / {@link #ANIMATED_SIZE}.
 */
@UtilityClass
public final class FluidRenderDriver {

    /** Output directory for all fluid renders. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/fluid-renderer");

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

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        FluidRenderer renderer = new FluidRenderer(context);
        ImageFactory imageFactory = new ImageFactory();

        // Source blocks + 2D face.
        render(renderer, imageFactory, "water_source_iso", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .output(OutputOptions.builder()
                .canvasSize(STATIC_SIZE)
                .build())
            .build());
        render(renderer, imageFactory, "lava_source_iso", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.LAVA)
            .output(OutputOptions.builder()
                .canvasSize(STATIC_SIZE)
                .build())
            .build());
        render(renderer, imageFactory, "water_face_2d", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .type(FluidOptions.Type.FLUID_FACE_2D)
            .output(OutputOptions.builder()
                .canvasSize(STATIC_SIZE)
                .build())
            .build());
        render(renderer, imageFactory, "lava_face_2d", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.LAVA)
            .type(FluidOptions.Type.FLUID_FACE_2D)
            .output(OutputOptions.builder()
                .canvasSize(STATIC_SIZE)
                .build())
            .build());

        // Animation. water_still has 32 frames at frametime=2; lava_still has 20 frames at
        // frametime=3. Sample at native frametime so the strip is walked exactly once.
        render(renderer, imageFactory, "water_animated_iso", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .output(OutputOptions.builder()
                .canvasSize(ANIMATED_SIZE)
                .build())
            .animation(AnimationOptions.builder()
                .frameCount(32)
                .ticksPerFrame(2)
                .build())
            .build());
        render(renderer, imageFactory, "lava_animated_iso", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.LAVA)
            .output(OutputOptions.builder()
                .canvasSize(ANIMATED_SIZE)
                .build())
            .animation(AnimationOptions.builder()
                .frameCount(20)
                .ticksPerFrame(3)
                .build())
            .build());

        // Sloped top + flow direction. The four corner heights are not coplanar, so the top is the
        // one face the kit fans from its own centre rather than splitting on a diagonal.
        render(renderer, imageFactory, "water_level_4_flow_ne", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .output(OutputOptions.builder()
                .canvasSize(STATIC_SIZE)
                .build())
            .cornerHeights(new FluidOptions.CornerHeights(0.875f, 0.5f, 0.375f, 0.75f))
            .flowAngleRadians(Optional.of((float) Math.toRadians(45)))
            .build());

        // Biome tint. Each of these samples the biome's water colour through Block.TintTarget.WATER;
        // the override variant states an ARGB outright and never reaches the colormap.
        render(renderer, imageFactory, "water_plains", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .output(OutputOptions.builder()
                .canvasSize(STATIC_SIZE)
                .build())
            .biome(Biome.Vanilla.PLAINS)
            .build());
        render(renderer, imageFactory, "water_swamp", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .output(OutputOptions.builder()
                .canvasSize(STATIC_SIZE)
                .build())
            .biome(Biome.Vanilla.SWAMP)
            .build());
        render(renderer, imageFactory, "water_cherry_grove", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .output(OutputOptions.builder()
                .canvasSize(STATIC_SIZE)
                .build())
            .biome(Biome.Vanilla.CHERRY_GROVE)
            .build());
        render(renderer, imageFactory, "water_warm_ocean", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .output(OutputOptions.builder()
                .canvasSize(STATIC_SIZE)
                .build())
            .biome(Biome.Vanilla.WARM_OCEAN)
            .build());
        render(renderer, imageFactory, "water_override_magenta", FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .output(OutputOptions.builder()
                .canvasSize(STATIC_SIZE)
                .build())
            .waterTintArgbOverride(0xFFFF00FF)
            .build());

        System.out.println("Done. Outputs in " + OUTPUT_DIR.toAbsolutePath());
    }

    /**
     * Renders one fluid output, writing it to {@code <OUTPUT_DIR>/<slug>.{png|gif}} and printing
     * a timing + dimension summary line. Animated renders go to GIF with a transparent palette;
     * static renders go to PNG.
     *
     * @param renderer the fluid renderer to drive
     * @param imageFactory the encoder
     * @param slug the output file stem
     * @param options the render to perform
     * @throws IOException if the file cannot be written
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
