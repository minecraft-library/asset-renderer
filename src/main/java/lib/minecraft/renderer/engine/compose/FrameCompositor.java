package lib.minecraft.renderer.engine.compose;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
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
     * Composites the given placements onto a canvas of the specified size.
     * <p>
     * If every placement is static, returns a {@link StaticImageData} with a single-frame composite.
     * Otherwise returns an {@link AnimatedImageData} whose duration is the LCM of the animated layers'
     * loop periods (capped at {@link Timeline#MAX_LOOP_MS}), so every animated placement completes a
     * whole number of loops, sampled at {@code framesPerSecond}.
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

        long mergedLoopMs = computeMergedLoopMs(layers);
        int outputFrameDelayMs = Timeline.delayForFps(framesPerSecond);
        int outputFrameCount = Math.max(1, (int) Math.ceil((double) mergedLoopMs / outputFrameDelayMs));
        Timeline.FpsLoop playback = new Timeline.FpsLoop(framesPerSecond, outputFrameCount);

        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int frameIndex = 0; frameIndex < outputFrameCount; frameIndex++) {
            PixelBuffer frame = renderFrame(layers, canvasW, canvasH, background, playback.playbackMsAt(frameIndex));
            builder.withFrame(ImageFrame.of(frame, playback.delayMs(frameIndex)));
        }

        return builder.build();
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
     * with a non-positive duration are skipped. Clamped to {@link Timeline#MAX_LOOP_MS} to bound the
     * frame count; returns {@code 1} when no layer is animated.
     *
     * @param layers the placements to inspect
     * @return the merged loop duration in milliseconds, capped at {@link Timeline#MAX_LOOP_MS}
     */
    private static long computeMergedLoopMs(@NotNull ConcurrentList<FramePlacement> layers) {
        long merged = 0;

        for (FramePlacement layer : layers) {
            if (!(layer.source() instanceof AnimatedImageData animated)) continue;
            long layerMs = animated.getTotalDurationMs();
            if (layerMs <= 0) continue;
            merged = merged == 0 ? layerMs : Timeline.lcm(merged, layerMs);
            if (merged >= Timeline.MAX_LOOP_MS) return Timeline.MAX_LOOP_MS;
        }

        return merged == 0 ? 1 : merged;
    }
}
