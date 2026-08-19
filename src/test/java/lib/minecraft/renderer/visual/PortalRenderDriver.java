package lib.minecraft.renderer.visual;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.webp.WebPWriteOptions;
import lib.minecraft.renderer.PortalRenderer;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.option.PortalOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Diagnostic task that exercises every {@link PortalRenderer} code path and dumps the output to
 * {@code cache/visual/portal-renderer/} for visual inspection. This is a <b>functional / visual</b>
 * tool ("does it render") - there is no parity gate. Static renders dump as PNG, animated renders
 * as lossless WebP - GIF's 256-colour palette dithers a continuous-tone parallax field into visible
 * diagonal streak artefacts, so WebP's full RGB output is the correct choice for the end-portal
 * shader output. Covers the full matrix of {@link PortalOptions.Portal} (end_portal / end_gateway)
 * {@code x} {@link PortalOptions.Type} ({@link PortalOptions.Type#ISOMETRIC_3D} / 2D) {@code x}
 * (static / animated / animated-smooth) - 12 outputs.
 * <p>
 * Usage: {@code ./gradlew portalRenderer}. Takes no {@code -P} flags; sizes are
 * fixed at {@link #STATIC_SIZE} / {@link #ANIMATED_SIZE}.
 */
@UtilityClass
public final class PortalRenderDriver {

    /** Output directory for all portal renders. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/portal-renderer");

    /** Square edge length (pixels) used for static renders. */
    private static final int STATIC_SIZE = 512;

    /** Square edge length (pixels) used for animated renders. */
    private static final int ANIMATED_SIZE = 256;

    /**
     * Frame count for animated portal outputs. {@link PortalRenderer} plays back at a fixed
     * 20 FPS so this count directly controls wall-clock length: 120 frames = 6.0 seconds.
     */
    private static final int ANIMATED_FRAME_COUNT = 120;

    /**
     * Sub-tick steps used for the smooth animated variants - each tick sampled as this many frames, so
     * the loop plays at 60 rather than 20 frames a second over the same span of game time.
     */
    private static final int SMOOTH_SUB_TICK_STEPS = 3;

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
        PortalRenderer renderer = new PortalRenderer(context);
        ImageFactory imageFactory = new ImageFactory();

        // Matrix: 2 portals x 2 types x 3 variants (static | animated | animated-smooth) = 12 outputs.
        for (PortalOptions.Portal portal : PortalOptions.Portal.values()) {
            for (PortalOptions.Type type : PortalOptions.Type.values()) {
                String typeSlug = type == PortalOptions.Type.ISOMETRIC_3D ? "iso" : "2d";
                String portalSlug = portal.name().toLowerCase(Locale.ROOT);

                render(renderer, imageFactory, portalSlug + "_" + typeSlug + "_static", PortalOptions.builder()
                    .portal(portal)
                    .type(type)
                    .output(
                        OutputOptions.builder()
                            .canvasSize(STATIC_SIZE)
                            .build()
                    )
                    .build());

                render(renderer, imageFactory, portalSlug + "_" + typeSlug + "_animated", PortalOptions.builder()
                    .portal(portal)
                    .type(type)
                    .output(
                        OutputOptions.builder()
                            .canvasSize(ANIMATED_SIZE)
                            .supersample(2)
                            .build()
                    )
                    .animation(
                        PortalOptions.DEFAULT_ANIMATION.mutate()
                            .frameCount(ANIMATED_FRAME_COUNT)
                            .build()
                    )
                    .build());

                // The same loop sampled between ticks. Covers the same span of game time at the same
                // speed, so the two play identically apart from smoothness - a frame per tick caps the
                // plain loop at 20 a second, which reads as a visible stutter on drifting stars.
                render(renderer, imageFactory, portalSlug + "_" + typeSlug + "_animated_smooth", PortalOptions.builder()
                    .portal(portal)
                    .type(type)
                    .output(
                        OutputOptions.builder()
                            .canvasSize(ANIMATED_SIZE)
                            .supersample(2)
                            .build()
                    )
                    .animation(
                        PortalOptions.DEFAULT_ANIMATION.mutate()
                            .frameCount(ANIMATED_FRAME_COUNT)
                            .subTickSteps(SMOOTH_SUB_TICK_STEPS)
                            .build()
                    )
                    .build());
            }
        }

        System.out.println("Done. Outputs in " + OUTPUT_DIR.toAbsolutePath());
    }

    /**
     * Renders one portal output, writing it to {@code <OUTPUT_DIR>/<slug>.{png|webp}} and
     * printing a timing + dimension summary line. Animated renders go to lossless WebP (GIF's
     * palette dithers the continuous-tone shader into visible streaks); static renders go to
     * PNG.
     *
     * @param renderer the portal renderer to drive
     * @param imageFactory the encoder
     * @param slug the output file stem
     * @param options the render to perform
     * @throws IOException if the file cannot be written
     */
    private static void render(
        @NotNull PortalRenderer renderer,
        @NotNull ImageFactory imageFactory,
        @NotNull String slug,
        @NotNull PortalOptions options
    ) throws IOException {
        long t0 = System.nanoTime();
        ImageData image = renderer.render(options);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        int width = image.getFrames().getFirst().pixels().width();
        int height = image.getFrames().getFirst().pixels().height();
        int frameCount = image.getFrames().size();
        boolean animated = image.isAnimated();

        String extension = animated ? "webp" : "png";
        File out = OUTPUT_DIR.resolve(slug + "." + extension).toFile();

        if (animated) {
            imageFactory.toFile(
                image,
                ImageFormat.WEBP,
                out,
                WebPWriteOptions.builder()
                    .isLossless(true)
                    .isMultithreaded()
                    .build()
            );
        } else {
            imageFactory.toFile(image, ImageFormat.PNG, out);
        }

        System.out.printf("  %-32s -> %s (%d ms, %dx%d, %d frame%s)%n",
            slug, out.getName(), elapsedMs, width, height, frameCount, frameCount == 1 ? "" : "s");
    }

}
