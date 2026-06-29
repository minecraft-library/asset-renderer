package lib.minecraft.renderer.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Composites {@link FramePlacement} layers into a single output, transparently handling mixed static
 * and animated inputs - the "frame tier" of the layer model, where {@link ImageLayer} mutates one
 * buffer and {@link GeometryLayer} feeds a shared depth pass.
 * <p>
 * Used by the compositor renderers ({@code GridRenderer}, {@code LayoutRenderer},
 * {@code MenuRenderer}) whenever children may be either static PNGs or animated WebPs. When every
 * layer is static the merger short-circuits to a single-frame composite. When any layer is animated,
 * it computes a merged loop period (LCM of the animated layers' durations, capped at 10 seconds),
 * then samples each layer at the correct time offset for each output frame.
 */
@UtilityClass
public class FrameCompositor {

    /**
     * Upper bound on the merged loop duration to prevent runaway LCM math.
     */
    private static final long MAX_LOOP_MS = 10_000L;

    /**
     * Runs each frame layer's contribution into a fresh sink, then merges the collected placements.
     *
     * @param layers the frame layers to contribute and composite, in back-to-front order
     * @param canvasW the canvas width in pixels
     * @param canvasH the canvas height in pixels
     * @param framesPerSecond the output frame rate, used only when producing animated output
     * @param background the canvas background fill applied before blitting any layer
     * @return the composited image data
     */
    public static @NotNull ImageData composite(@NotNull List<? extends FrameLayer> layers, int canvasW, int canvasH, int framesPerSecond, @NotNull Background background) {
        ConcurrentList<FramePlacement> placements = Concurrent.newList();
        for (FrameLayer layer : layers) layer.contribute(placements);
        return merge(placements, canvasW, canvasH, framesPerSecond, background);
    }

    /**
     * Composites the given placements onto a canvas of the specified size.
     * <p>
     * If every placement is static, returns a {@link StaticImageData} with a single-frame composite.
     * Otherwise returns an {@link AnimatedImageData} whose frame count is chosen so every animated
     * placement completes at least one full loop (or the cap, whichever is smaller).
     *
     * @param layers the placements to composite, in back-to-front order
     * @param canvasW the canvas width in pixels
     * @param canvasH the canvas height in pixels
     * @param framesPerSecond the output frame rate, used only when producing animated output
     * @param background the canvas background fill applied before blitting any layer
     * @return the composited image data
     */
    public static @NotNull ImageData merge(@NotNull ConcurrentList<FramePlacement> layers, int canvasW, int canvasH, int framesPerSecond, @NotNull Background background) {
        boolean anyAnimated = layers.stream().anyMatch(layer -> layer.source() instanceof AnimatedImageData);

        if (!anyAnimated)
            return StaticImageData.of(renderFrame(layers, canvasW, canvasH, background, 0).toBufferedImage());

        int outputFrameDelayMs = Math.max(1, Math.round(1000f / framesPerSecond));
        long mergedLoopMs = computeMergedLoopMs(layers);
        int outputFrameCount = Math.max(1, (int) Math.ceil((double) mergedLoopMs / outputFrameDelayMs));

        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int frameIndex = 0; frameIndex < outputFrameCount; frameIndex++) {
            long timeMs = (long) frameIndex * outputFrameDelayMs;
            PixelBuffer frame = renderFrame(layers, canvasW, canvasH, background, timeMs);
            builder.withFrame(ImageFrame.of(frame, outputFrameDelayMs));
        }

        return builder.build();
    }

    private static @NotNull PixelBuffer renderFrame(@NotNull ConcurrentList<FramePlacement> layers, int canvasW, int canvasH, @NotNull Background background, long timeMs) {
        PixelBuffer buffer = PixelBuffer.create(canvasW, canvasH);
        background.fill(buffer);

        for (FramePlacement layer : layers) {
            PixelBuffer frame = sampleLayerAtTime(layer.source(), timeMs);
            buffer.blit(frame, layer.x(), layer.y());
        }

        return buffer;
    }

    /**
     * Returns the pixel buffer of the layer at the specified playback time. Static layers always
     * return their single frame; animated layers delegate to the built-in
     * {@link AnimatedImageData#getFrameAtTime(long, boolean) frame-at-time resolver}.
     */
    private static @NotNull PixelBuffer sampleLayerAtTime(@NotNull ImageData source, long timeMs) {
        if (source instanceof AnimatedImageData animated && !animated.getFrames().isEmpty())
            return animated.getFrameAtTime(timeMs, false).frame().pixels();

        return source.toPixelBuffer();
    }

    private static long computeMergedLoopMs(@NotNull ConcurrentList<FramePlacement> layers) {
        long merged = 0;

        for (FramePlacement layer : layers) {
            if (!(layer.source() instanceof AnimatedImageData animated)) continue;
            long layerMs = animated.getTotalDurationMs();
            if (layerMs <= 0) continue;
            merged = merged == 0 ? layerMs : lcm(merged, layerMs);
            if (merged >= MAX_LOOP_MS) return MAX_LOOP_MS;
        }

        return merged == 0 ? 1 : merged;
    }

    private static long lcm(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return Math.abs(a / gcd(a, b) * b);
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }

        return a;
    }
}
