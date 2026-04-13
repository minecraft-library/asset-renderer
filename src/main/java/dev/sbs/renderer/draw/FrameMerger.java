package dev.sbs.renderer.draw;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.AnimatedImageData;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFrame;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.StaticImageData;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Composites multiple {@link ImageData} layers into a single output, transparently handling mixed
 * static and animated inputs.
 * <p>
 * Used by {@code LayoutRenderer} and {@code MenuRenderer} whenever children may be either static
 * PNGs or animated WebPs. When every layer is static the merger short-circuits to a single-frame
 * composite. When any layer is animated, the merger computes a merged loop period (LCM of the
 * animated layers' durations, capped at 10 seconds), then samples each layer at the correct time
 * offset for each output frame.
 */
@UtilityClass
public class FrameMerger {

    /** Upper bound on the merged loop duration to prevent runaway LCM math. */
    private static final long MAX_LOOP_MS = 10_000L;

    /**
     * A single layer in a composition.
     *
     * @param x the destination x origin on the merged canvas
     * @param y the destination y origin on the merged canvas
     * @param source the layer's image data, either static or animated
     */
    public record Layer(int x, int y, @NotNull ImageData source) {}

    /**
     * Composites the given layers onto a canvas of the specified size.
     * <p>
     * If every layer is static, returns a {@link StaticImageData} with a single-frame composite.
     * Otherwise returns an {@link AnimatedImageData} whose frame count is chosen so every animated
     * layer completes at least one full loop (or the cap, whichever is smaller).
     *
     * @param layers the layers to composite, in back-to-front order
     * @param canvasW the canvas width in pixels
     * @param canvasH the canvas height in pixels
     * @param framesPerSecond the output frame rate, used only when producing animated output
     * @param backgroundArgb the canvas background colour applied before blitting any layer
     * @return the composited image data
     */
    public static @NotNull ImageData merge(@NotNull ConcurrentList<Layer> layers, int canvasW, int canvasH, int framesPerSecond, int backgroundArgb) {
        boolean anyAnimated = layers.stream().anyMatch(layer -> layer.source() instanceof AnimatedImageData);

        if (!anyAnimated)
            return StaticImageData.of(renderFrame(layers, canvasW, canvasH, backgroundArgb, 0).toBufferedImage());

        int outputFrameDelayMs = Math.max(1, Math.round(1000f / framesPerSecond));
        long mergedLoopMs = computeMergedLoopMs(layers);
        int outputFrameCount = Math.max(1, (int) Math.ceil((double) mergedLoopMs / outputFrameDelayMs));

        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int frameIndex = 0; frameIndex < outputFrameCount; frameIndex++) {
            long timeMs = (long) frameIndex * outputFrameDelayMs;
            PixelBuffer frame = renderFrame(layers, canvasW, canvasH, backgroundArgb, timeMs);
            builder.withFrame(ImageFrame.of(frame, outputFrameDelayMs));
        }

        return builder.build();
    }

    private static @NotNull PixelBuffer renderFrame(@NotNull ConcurrentList<Layer> layers, int canvasW, int canvasH, int backgroundArgb, long timeMs) {
        PixelBuffer buffer = PixelBuffer.create(canvasW, canvasH);
        buffer.fill(backgroundArgb);

        for (Layer layer : layers) {
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

    private static long computeMergedLoopMs(@NotNull ConcurrentList<Layer> layers) {
        long merged = 0;

        for (Layer layer : layers) {
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
