package dev.sbs.renderer.tooling;

import dev.sbs.renderer.PortalRenderer;
import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.options.PortalOptions;
import dev.sbs.renderer.pipeline.AssetPipeline;
import dev.sbs.renderer.pipeline.AssetPipelineOptions;
import dev.sbs.renderer.pipeline.PipelineRendererContext;
import dev.sbs.renderer.pipeline.client.HttpFetcher;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.webp.WebPWriteOptions;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Diagnostic task that exercises every {@link PortalRenderer} code path and dumps the output to
 * {@code cache/test-portal/} for visual inspection. Static renders dump as PNG, animated renders
 * as lossless WebP - GIF's 256-colour palette dithers a continuous-tone parallax field into
 * visible diagonal streak artefacts, so WebP's full RGB output is the correct choice for the
 * end-portal shader output. Covers the full matrix of {@link PortalOptions.Portal} (end_portal /
 * end_gateway) {@code ×} {@link PortalOptions.Type} (3D / 2D) {@code ×} (static / animated) -
 * 8 outputs.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:testPortal}.
 */
@UtilityClass
public final class TestPortalMain {

    private static final Path OUTPUT_DIR = Path.of("cache/test-portal");

    private static final int STATIC_SIZE = 512;
    private static final int ANIMATED_SIZE = 256;

    /**
     * Frame count for animated portal outputs. {@link PortalRenderer} plays back at a fixed
     * 20 FPS so this count directly controls wall-clock length: 32 frames = 1.6 seconds.
     */
    private static final int ANIMATED_FRAME_COUNT = 32;

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
        PortalRenderer renderer = new PortalRenderer(context);
        ImageFactory imageFactory = new ImageFactory();

        // Matrix: 2 portals x 2 types x 2 (static | animated) = 8 outputs.
        for (PortalOptions.Portal portal : PortalOptions.Portal.values()) {
            for (PortalOptions.Type type : PortalOptions.Type.values()) {
                String typeSlug = type == PortalOptions.Type.ISOMETRIC_3D ? "iso" : "2d";
                String portalSlug = portal.name().toLowerCase(Locale.ROOT);

                render(renderer, imageFactory, portalSlug + "_" + typeSlug + "_static", PortalOptions.builder()
                    .portal(portal)
                    .type(type)
                    .outputSize(STATIC_SIZE)
                    .build());

                render(renderer, imageFactory, portalSlug + "_" + typeSlug + "_animated", PortalOptions.builder()
                    .portal(portal)
                    .type(type)
                    .outputSize(ANIMATED_SIZE)
                    .frameCount(ANIMATED_FRAME_COUNT)
                    .build());
            }
        }

        System.out.println("Done. Outputs in " + OUTPUT_DIR.toAbsolutePath());
    }

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
