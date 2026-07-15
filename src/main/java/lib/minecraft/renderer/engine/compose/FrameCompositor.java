package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.exception.RenderException;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Composites {@link FramePlacement} layers into a single output, transparently handling mixed static
 * and animated inputs - the "frame tier" of the layer model, where {@code ImageLayer} mutates one
 * buffer and {@code GeometryLayer} feeds a shared depth pass.
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
     * Composites the given placements onto a canvas of the specified size.
     * <p>
     * If every placement is static, returns a {@link StaticImageData} with a single-frame composite.
     * Otherwise returns an {@link AnimatedImageData} whose duration is the LCM of the animated layers'
     * loop periods (capped at {@link #MAX_LOOP_MS}), so every animated placement completes a whole
     * number of loops, sampled at {@code framesPerSecond}.
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

    /**
     * Wraps a list of rendered frames as an {@link ImageData} instance. A single-frame list becomes a
     * {@link StaticImageData}; multi-frame lists become an {@link AnimatedImageData} where every frame
     * shares the same delay. The terminal output step shared by every renderer and compose stage.
     *
     * @param frames the ordered frame list
     * @param frameDelayMs the per-frame display duration in milliseconds
     * @return the wrapped image data
     * @throws RenderException if {@code frames} is empty
     */
    public static @NotNull ImageData wrapFrames(@NotNull ConcurrentList<PixelBuffer> frames, int frameDelayMs) {
        if (frames.isEmpty())
            throw new RenderException("Frame list must contain at least one frame");

        if (frames.size() == 1)
            return StaticImageData.of(frames.getFirst().toBufferedImage());

        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (PixelBuffer frame : frames)
            builder.withFrame(ImageFrame.of(frame, frameDelayMs));

        return builder.build();
    }

    /**
     * Wraps a list of rendered frames as an {@link ImageData} instance where each frame carries its OWN
     * playback delay - the variable-cadence counterpart of
     * {@link #wrapFrames(ConcurrentList, int)}. A single-frame list becomes a {@link StaticImageData};
     * multi-frame lists become an {@link AnimatedImageData} stamping {@code frameDelaysMs[i]} on frame
     * {@code i}. Lets a subject export one output frame per distinct texture state (fire's authored,
     * reordered {@code frames} list) without resampling to a uniform tick cadence.
     *
     * @param frames the ordered frame list
     * @param frameDelaysMs the per-frame display durations in milliseconds, parallel to {@code frames}
     * @return the wrapped image data
     * @throws RenderException if {@code frames} is empty
     * @throws IllegalArgumentException if {@code frameDelaysMs} is not the same length as {@code frames}
     */
    public static @NotNull ImageData wrapFrames(@NotNull ConcurrentList<PixelBuffer> frames, int @NotNull [] frameDelaysMs) {
        if (frames.isEmpty())
            throw new RenderException("Frame list must contain at least one frame");
        if (frameDelaysMs.length != frames.size())
            throw new IllegalArgumentException("frameDelaysMs (%d) must match frame count (%d)"
                .formatted(frameDelaysMs.length, frames.size()));

        if (frames.size() == 1)
            return StaticImageData.of(frames.getFirst().toBufferedImage());

        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int i = 0; i < frames.size(); i++)
            builder.withFrame(ImageFrame.of(frames.get(i), frameDelaysMs[i]));

        return builder.build();
    }

    /**
     * Wraps a pixel buffer as a single-frame static {@link ImageData}. Shared convenience for every
     * renderer that needs to emit exactly one frame without glint or animation.
     *
     * @param buffer the pixel buffer that becomes the static frame
     * @return the wrapped image data
     */
    public static @NotNull ImageData staticFrame(@NotNull PixelBuffer buffer) {
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(buffer);
        return wrapFrames(frames, 0);
    }

    /**
     * Returns a minimal 1x1 transparent static frame - the canonical "nothing to render" result for
     * renderers short-circuiting on missing or empty input.
     *
     * @return a 1x1 transparent static image
     */
    public static @NotNull ImageData emptyFrame() {
        return staticFrame(PixelBuffer.create(1, 1));
    }

    /**
     * Renders one output frame at {@code timeMs}: fills the background, then blits each placement
     * (sampled at that playback time) at its destination origin, in back-to-front list order.
     *
     * @param layers the placements to composite, in back-to-front order
     * @param canvasW the canvas width in pixels
     * @param canvasH the canvas height in pixels
     * @param background the canvas background fill applied before blitting any layer
     * @param timeMs the playback offset each animated layer is sampled at
     * @return the composited frame
     */
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
     * Returns the pixel buffer of the layer at the specified playback time. Static layers (and empty
     * animated ones) always return their single frame; non-empty animated layers delegate to the
     * built-in {@link AnimatedImageData#getFrameAtTime(long, boolean) frame-at-time resolver}.
     *
     * @param source the layer's image data, static or animated
     * @param timeMs the playback offset to sample at
     * @return the layer's pixel buffer at {@code timeMs}
     */
    private static @NotNull PixelBuffer sampleLayerAtTime(@NotNull ImageData source, long timeMs) {
        if (source instanceof AnimatedImageData animated && !animated.getFrames().isEmpty())
            return animated.getFrameAtTime(timeMs, false).frame().pixels();

        return source.toPixelBuffer();
    }

    /**
     * Computes the merged loop duration as the LCM of every animated layer's total duration, so
     * every animated layer completes a whole number of loops within it. Static layers and layers
     * with a non-positive duration are skipped. Clamped to {@link #MAX_LOOP_MS} to bound the frame
     * count; returns {@code 1} when no layer is animated.
     *
     * @param layers the placements to inspect
     * @return the merged loop duration in milliseconds, capped at {@link #MAX_LOOP_MS}
     */
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

    /**
     * Least common multiple of {@code a} and {@code b}, computed as {@code |a / gcd(a, b) * b|} to
     * divide before multiplying and limit overflow. Returns {@code 0} when either input is {@code 0}.
     *
     * @param a the first value
     * @param b the second value
     * @return the least common multiple
     */
    private static long lcm(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return Math.abs(a / gcd(a, b) * b);
    }

    /**
     * Greatest common divisor of {@code a} and {@code b} by the iterative Euclidean algorithm.
     *
     * @param a the first value
     * @param b the second value
     * @return the greatest common divisor
     */
    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }

        return a;
    }
}
