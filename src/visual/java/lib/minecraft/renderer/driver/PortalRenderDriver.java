package lib.minecraft.renderer.driver;

import api.simplified.mojang.exception.MojangApiException;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.option.WebPWriteOptions;
import lib.minecraft.renderer.PortalRenderer;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.request.PortalOptions;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.ClientException;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Diagnostic task that renders {@link PortalRenderer}'s output to {@code cache/visual/portal-renderer/}
 * for visual inspection. Every file it writes is a member of the parity store's
 * {@code manifest.portal}, compared byte for byte against its promoted baseline. Static renders dump as
 * PNG, animated renders as lossless WebP - GIF's 256-colour palette dithers a continuous-tone parallax
 * field into visible diagonal streak artefacts, so WebP's full RGB output is the correct choice for the
 * end-portal shader output.
 * <p>
 * Each {@link PortalOptions.Portal} (end_portal / end_gateway) renders as a static
 * {@link PortalOptions.Type#ISOMETRIC_3D} PNG and as an animated strip of each {@link PortalOptions.Type},
 * sampled at {@link #SMOOTH_SUB_TICK_STEPS} steps a tick - 6 outputs. Two renders are not among them.
 * {@code pin.portal-crc} holds the code path the static flat 2D face goes through, at a canvas of its
 * own rather than at {@link #STATIC_SIZE}. A strip on whole ticks is the sub-stepped strip's every
 * third frame exactly - the seam's crossfade scales with the steps, so it lands on the same game time -
 * and the sub-stepped strip reaches both of the bake's arms, the whole tick and the instant between
 * two.
 * <p>
 * That whole-tick strip is still the render that tells a real in-between frame from a duplicate, so the
 * {@code lattice} argument writes it too, for each animated output, to {@link #LATTICE_DIR} - outside
 * the tree {@code manifest.portal} hashes, which it leaves as a run without it writes it.
 * <p>
 * Each output directory is cleared first, so it holds exactly what this run writes.
 * <p>
 * Usage: {@code ./gradlew portalRenderer [-PportalLattice]}. Sizes are fixed at {@link #STATIC_SIZE} /
 * {@link #ANIMATED_SIZE}.
 */
@UtilityClass
public final class PortalRenderDriver {

    /** Output directory for the portal renders {@code manifest.portal} hashes. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/portal-renderer");

    /** Output directory for the whole-tick strips the {@code lattice} argument asks for. */
    private static final Path LATTICE_DIR = Path.of("cache/visual/portal-lattice");

    /** The argument that asks for the whole-tick strips. */
    private static final String LATTICE_ARGUMENT = "lattice";

    /** Square edge length (pixels) used for static renders. */
    private static final int STATIC_SIZE = 512;

    /** Square edge length (pixels) used for animated renders. */
    private static final int ANIMATED_SIZE = 256;

    /**
     * Whole-tick frames each animated strip loops over. {@link PortalRenderer} plays a whole tick back
     * as 50 ms, so 40 is a 2.0 second loop, which {@link #SMOOTH_SUB_TICK_STEPS} samples as 120 frames.
     * At least 3, the shortest loop {@link PortalRenderer} bakes the seam's crossfade bridge for.
     */
    private static final int ANIMATED_FRAME_COUNT = 40;

    /**
     * Sub-tick steps the animated strips sample at - each tick sampled as this many frames, so the loop
     * plays at 60 rather than 20 frames a second over the same span of game time.
     */
    private static final int SMOOTH_SUB_TICK_STEPS = 3;

    /**
     * Runs the test matrix.
     *
     * @param args {@code lattice} to write the whole-tick strips beside it, or nothing
     * @throws IOException if an output directory cannot be cleared or created, or a render cannot be
     *     written
     */
    public static void main(String @NotNull [] args) throws IOException {
        boolean lattice = Arrays.asList(args).contains(LATTICE_ARGUMENT);
        clearOutputs(OUTPUT_DIR);
        if (lattice)
            clearOutputs(LATTICE_DIR);

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (ClientException | MojangApiException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        RendererContext context = RendererContext.load(result);
        PortalRenderer renderer = new PortalRenderer(context);

        // Matrix: 2 portals x (iso static + 2 types animated) = 6 outputs.
        for (PortalOptions.Portal portal : PortalOptions.Portal.values()) {
            String portalSlug = portal.name().toLowerCase(Locale.ROOT);

            render(renderer, OUTPUT_DIR, portalSlug + "_iso_static", PortalOptions.builder()
                .portal(portal)
                .type(PortalOptions.Type.ISOMETRIC_3D)
                .output(
                    OutputOptions.builder()
                        .canvasSize(STATIC_SIZE)
                        .build()
                )
                .build());

            for (PortalOptions.Type type : PortalOptions.Type.values()) {
                String typeSlug = type == PortalOptions.Type.ISOMETRIC_3D ? "iso" : "2d";

                // The loop sampled between ticks: the same span of game time at the same speed as a
                // frame per tick, which caps the loop at 20 a second and reads as a visible stutter on
                // drifting stars.
                PortalOptions strip = PortalOptions.builder()
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
                    .build();
                render(renderer, OUTPUT_DIR, portalSlug + "_" + typeSlug + "_animated_smooth",
                    strip.mutate().subTickSteps(SMOOTH_SUB_TICK_STEPS).build());

                // The same loop a frame per tick, whose frame n is the strip above's frame 3n.
                if (lattice)
                    render(renderer, LATTICE_DIR, portalSlug + "_" + typeSlug + "_animated", strip);
            }
        }

        System.out.println("Done. Outputs in " + OUTPUT_DIR.toAbsolutePath()
            + (lattice ? " and " + LATTICE_DIR.toAbsolutePath() : ""));
    }

    /**
     * Creates an output directory and deletes every file in it, so the directory holds exactly what
     * this run writes and a manifest taken over it hashes nothing another run left there.
     *
     * @param directory the output directory
     * @throws IOException if the directory cannot be created or a file in it cannot be deleted
     */
    private static void clearOutputs(@NotNull Path directory) throws IOException {
        Files.createDirectories(directory);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, Files::isRegularFile)) {
            for (Path file : files)
                Files.delete(file);
        }
    }

    /**
     * Renders one portal output, writing it to {@code <directory>/<slug>.{png|webp}} and
     * printing a timing + dimension summary line. Animated renders go to lossless WebP (GIF's
     * palette dithers the continuous-tone shader into visible streaks); static renders go to
     * PNG.
     *
     * @param renderer the portal renderer to drive
     * @param directory the output directory
     * @param slug the output file stem
     * @param options the render to perform
     * @throws IOException if the file cannot be written
     */
    private static void render(
        @NotNull PortalRenderer renderer,
        @NotNull Path directory,
        @NotNull String slug,
        @NotNull PortalOptions options
    ) throws IOException {
        long t0 = System.nanoTime();
        ImageData image = renderer.render(options).image();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        int width = image.getFrames().getFirst().pixels().width();
        int height = image.getFrames().getFirst().pixels().height();
        int frameCount = image.getFrames().size();
        boolean animated = image.isAnimated();

        String extension = animated ? "webp" : "png";
        File out = directory.resolve(slug + "." + extension).toFile();

        if (animated) {
            ImageFactory.toFile(
                image,
                ImageFormat.WEBP,
                out,
                WebPWriteOptions.builder()
                    .isLossless(true)
                    .isMultithreaded()
                    .build()
            );
        } else {
            ImageFactory.toFile(image, ImageFormat.PNG, out);
        }

        System.out.printf("  %-32s -> %s (%d ms, %dx%d, %d frame%s)%n",
            slug, out.getName(), elapsedMs, width, height, frameCount, frameCount == 1 ? "" : "s");
    }

}
