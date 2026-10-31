package lib.minecraft.renderer.engine.frame;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.AnimatedImageData;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.StaticImageData;
import dev.simplified.image.frame.FrameBlend;
import dev.simplified.image.frame.FrameDisposal;
import dev.simplified.image.frame.ImageFrame;
import dev.simplified.image.pixel.Background;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import org.jetbrains.annotations.NotNull;

import java.util.function.LongFunction;

/**
 * Composites {@link FramePlacement} layers into a single output, transparently handling mixed static
 * and animated inputs - the "frame tier" of the layer model, where {@code ImageLayer} mutates one
 * buffer and {@code GeometryLayer} feeds a shared depth pass.
 * <p>
 * Used by the compositor renderers ({@code GridRenderer}, {@code LayoutRenderer},
 * {@code MenuRenderer}) whenever children may be either static or animated images. When every layer
 * is static the merger short-circuits to a single-frame composite. When any layer is animated, it
 * computes a merged loop period (LCM of the animated layers' durations, capped at 10 seconds), then
 * samples each layer at the correct time offset for each output frame.
 * <p>
 * An animated layer is sampled as a viewer shows it. Where every frame of the image covers its canvas
 * from the origin, the picture at a playback time is the frame showing then. Where some frame does
 * not, the picture is the image's frames up to the one showing, drawn in order onto its canvas at
 * their offsets and each disposed of before the next is drawn, so a decoded GIF, whose later frames
 * hold only the part of the picture that changed, shows the picture it builds up.
 * <p>
 * Each sampled picture is drawn at its placement's origin: rescaled into the placement's
 * {@link FramePlacement#extent() extent} by {@link PixelBuffer#blitScaled} when it carries one, and
 * blitted at the picture's own size when it does not. Either way it is laid source-over onto a
 * {@link PixelBuffer#canvas canvas} in the convention it holds: a render, itself a canvas, by the
 * premultiplied formula, so a translucent pixel over a clear one stays as it was drawn, and a straight
 * picture - one read from a file, or built up from a straight animation's partial frames - by the
 * formula a GPU lays a straight texel with. An {@linkplain FramePlacement#additive() additive}
 * placement's picture is added to the canvas with {@link BlendMode#ADD} instead, as the client's
 * additive GUI pipeline adds a text field's highlight band to what lies under it.
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
     * <p>
     * Every frame is a canvas holding the fill, with each placement laid over it in the convention its
     * picture holds, or added to it where the placement is additive, so either image holds
     * premultiplied colour.
     *
     * @param layers the placements to composite, in back-to-front order
     * @param canvasW the canvas width in pixels
     * @param canvasH the canvas height in pixels
     * @param framesPerSecond the output frame rate, used only when producing animated output
     * @param background the canvas background fill applied before blitting any layer
     * @return the composited image data, held premultiplied
     */
    public static @NotNull ImageData merge(@NotNull ConcurrentList<FramePlacement> layers, int canvasW, int canvasH, int framesPerSecond, @NotNull Background background) {
        boolean anyAnimated = layers.stream().anyMatch(layer -> layer.source() instanceof AnimatedImageData);
        ConcurrentList<LongFunction<PixelBuffer>> samplers = layers.stream()
            .map(layer -> sampler(layer.source()))
            .collect(Concurrent.toList());

        if (!anyAnimated)
            return StaticImageData.of(renderFrame(layers, samplers, canvasW, canvasH, background, 0));

        long mergedLoopMs = computeMergedLoopMs(layers);
        int outputFrameDelayMs = Timeline.delayForFps(framesPerSecond);
        int outputFrameCount = Math.max(1, (int) Math.ceil((double) mergedLoopMs / outputFrameDelayMs));
        Timeline.FpsLoop playback = new Timeline.FpsLoop(framesPerSecond, outputFrameCount);

        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int frameIndex = 0; frameIndex < outputFrameCount; frameIndex++) {
            PixelBuffer frame = renderFrame(layers, samplers, canvasW, canvasH, background, playback.playbackMsAt(frameIndex));

            // renderFrame fills the background and blits every placement from scratch, so this frame
            // is the whole canvas and not a delta against the last one, and it replaces the frame
            // before it, as RESTORE_TO_BACKGROUND and SOURCE say; a frame left standing would show
            // through wherever this one is clear.
            builder.withFrame(ImageFrame.of(frame, playback.delayMs(frameIndex), 0, 0,
                FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE));
        }

        return builder.build();
    }

    /**
     * Renders one output frame at {@code timeMs}: fills a canvas with the background, then draws each
     * placement (sampled at that playback time) at its destination origin, in back-to-front list order.
     *
     * @param layers the placements to composite, in back-to-front order
     * @param samplers each placement's {@link #sampler sampler}, parallel to {@code layers}
     * @param canvasW the canvas width in pixels
     * @param canvasH the canvas height in pixels
     * @param background the canvas background fill applied before blitting any layer
     * @param timeMs the playback offset each animated layer is sampled at
     * @return the composited frame
     */
    private static @NotNull PixelBuffer renderFrame(@NotNull ConcurrentList<FramePlacement> layers, @NotNull ConcurrentList<LongFunction<PixelBuffer>> samplers, int canvasW, int canvasH, @NotNull Background background, long timeMs) {
        PixelBuffer buffer = PixelBuffer.canvas(canvasW, canvasH);
        background.fill(buffer);

        for (int index = 0; index < layers.size(); index++)
            draw(buffer, layers.get(index), samplers.get(index).apply(timeMs));

        return buffer;
    }

    /**
     * Lays one sampled picture of a placement onto the canvas at the placement's origin - rescaled
     * into the placement's extent by {@link PixelBuffer#blitScaled} when it has one, and blitted at the
     * picture's own size when it does not - source-over, by the formula the canvas and the convention
     * the picture holds give, or added to the canvas with {@link BlendMode#ADD} where the placement is
     * additive.
     *
     * @param canvas the output frame being composited
     * @param layer the placement the picture was sampled from
     * @param picture the placement's picture at the output frame's playback time
     */
    private static void draw(@NotNull PixelBuffer canvas, @NotNull FramePlacement layer, @NotNull PixelBuffer picture) {
        BlendMode mode = layer.additive() ? BlendMode.ADD : BlendMode.NORMAL;
        layer.extent().ifPresentOrElse(
            extent -> canvas.blitScaled(picture, layer.x(), layer.y(), extent.width(), extent.height(), mode),
            () -> canvas.blit(picture, layer.x(), layer.y(), mode));
    }

    /**
     * Returns how a layer's source is sampled at a playback time.
     * <p>
     * A static source, or an animated one with no frames, shows its single frame throughout. An
     * animated source whose every frame {@linkplain Composition#covers covers} its canvas from the
     * origin shows, at each time, the frame the
     * {@link AnimatedImageData#getFrameAtTime(long, boolean) frame-at-time resolver} picks, exactly as
     * it is. Any other animated source is played as a {@link Composition}.
     *
     * @param source the layer's image data, static or animated
     * @return the picture the source shows at a playback time
     */
    private static @NotNull LongFunction<PixelBuffer> sampler(@NotNull ImageData source) {
        if (!(source instanceof AnimatedImageData animated) || animated.getFrames().isEmpty())
            return timeMs -> source.toPixelBuffer();

        boolean wholeFrames = animated.getFrames().stream()
            .allMatch(frame -> Composition.covers(frame, animated.getWidth(), animated.getHeight()));

        if (wholeFrames)
            return timeMs -> animated.getFrameAtTime(timeMs, false).frame().pixels();

        return new Composition(animated)::pictureAt;
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

    /**
     * An animation whose frames are not all whole pictures, played as a viewer plays it - each frame
     * drawn in order onto a canvas the image's size ({@link ImageData#getWidth()} by
     * {@link ImageData#getHeight()}), and the canvas after each draw kept as the picture the image
     * shows while that frame does.
     * <p>
     * The canvas starts transparent, in the convention the animation holds. A frame that
     * {@linkplain #covers covers} the canvas from its origin is the whole picture while it shows, and
     * replaces what the canvas held. Any other frame is drawn at its offset and clipped to the canvas,
     * by the image library's source-over composite, in the convention the animation holds: a fully
     * transparent texel leaves the canvas beneath it, an opaque one replaces it, a part-transparent one
     * blends with it, and a frame whose pixels carry no alpha is copied as it is. Once a frame has
     * shown, its {@link FrameDisposal} decides what the next frame is drawn onto -
     * {@link FrameDisposal#NONE NONE} and {@link FrameDisposal#DO_NOT_DISPOSE DO_NOT_DISPOSE} leave the
     * frame standing, {@link FrameDisposal#RESTORE_TO_BACKGROUND RESTORE_TO_BACKGROUND} clears its
     * rectangle to transparent, and {@link FrameDisposal#RESTORE_TO_PREVIOUS RESTORE_TO_PREVIOUS} puts
     * the canvas back as it stood before the frame was drawn.
     * <p>
     * A frame's {@link FrameBlend} is not read: a frame that covers the canvas replaces it and any
     * other is drawn source-over, whatever blend it carries. The image library's GIF reader marks every
     * frame {@link FrameBlend#OVER OVER}, and its WebP reader answers each frame already composed into
     * the whole picture, marked {@link FrameBlend#SOURCE SOURCE}.
     * <p>
     * A picture is composed when playback first reaches its frame and kept for every later sample, so
     * the frames are drawn once each however many output frames, and loops, sample the animation.
     */
    private static final class Composition {

        /** The animation being played. */
        private final @NotNull AnimatedImageData animated;

        /** The picture the image shows while each frame does, filled in as playback first reaches it. */
        private final PixelBuffer @NotNull [] pictures;

        /** The canvas the next frame is drawn onto. */
        private @NotNull PixelBuffer canvas;

        /** The number of frames drawn so far, which is the index of the next one to draw. */
        private int drawn;

        /**
         * Constructs a new {@code Composition} of an animation, on a transparent canvas the image's size
         * made in the convention the animation holds.
         *
         * @param animated the animation to play
         */
        Composition(@NotNull AnimatedImageData animated) {
            this.animated = animated;
            this.pictures = new PixelBuffer[animated.getFrames().size()];
            this.canvas = switch (animated.getAlphaMode()) {
                case STRAIGHT -> PixelBuffer.straight(animated.getWidth(), animated.getHeight());
                case PREMULTIPLIED -> PixelBuffer.canvas(animated.getWidth(), animated.getHeight());
            };
        }

        /**
         * Returns the picture the image shows at a playback time, first drawing every frame up to the
         * one showing then that has not been drawn yet.
         *
         * @param timeMs the playback offset to sample at
         * @return the picture at {@code timeMs}
         */
        @NotNull PixelBuffer pictureAt(long timeMs) {
            int index = this.frameIndexAt(timeMs);

            while (this.drawn <= index)
                this.drawNext();

            return this.pictures[index];
        }

        /**
         * Returns the index of the frame showing at a playback time, by the walk
         * {@link AnimatedImageData#getFrameAtTime(long, boolean)} makes without interpolation: time
         * wraps at the total duration, a frame held for no time is never the one showing, and an
         * animation with no duration shows its first frame throughout.
         *
         * @param timeMs the playback offset to sample at
         * @return the index of the frame showing at {@code timeMs}
         */
        private int frameIndexAt(long timeMs) {
            ConcurrentList<ImageFrame> frames = this.animated.getFrames();
            int totalMs = this.animated.getTotalDurationMs();
            if (totalMs <= 0) return 0;

            int normalized = (int) (timeMs % totalMs);
            int accumulated = 0;

            for (int index = 0; index < frames.size(); index++) {
                int delayMs = frames.get(index).delayMs();
                if (delayMs <= 0) continue;
                accumulated += delayMs;
                if (normalized < accumulated) return index;
            }

            return frames.size() - 1;
        }

        /**
         * Draws the next frame onto the canvas, keeps the picture that makes, then disposes of the frame.
         */
        private void drawNext() {
            ImageFrame frame = this.animated.getFrames().get(this.drawn);
            int width = this.canvas.width();
            int height = this.canvas.height();
            PixelBuffer before = frame.disposal() == FrameDisposal.RESTORE_TO_PREVIOUS ? this.canvas.copy() : this.canvas;

            if (covers(frame, width, height)) {
                this.pictures[this.drawn] = frame.pixels();
                this.canvas = frame.pixels().crop(0, 0, width, height);
            } else {
                this.canvas.blit(frame.pixels(), frame.offsetX(), frame.offsetY());
                this.pictures[this.drawn] = this.canvas.copy();
            }

            switch (frame.disposal()) {
                case NONE, DO_NOT_DISPOSE -> { }
                case RESTORE_TO_BACKGROUND -> this.canvas.fillRect(frame.offsetX(), frame.offsetY(), frame.width(), frame.height(), ColorMath.TRANSPARENT);
                case RESTORE_TO_PREVIOUS -> this.canvas = before;
            }

            this.drawn++;
        }

        /**
         * Tests whether a frame covers a canvas from its origin - seated at the origin and at least as
         * wide and as tall as the canvas - and so is a whole picture rather than part of one.
         *
         * @param frame the frame to test
         * @param width the canvas width
         * @param height the canvas height
         * @return whether the frame covers the canvas
         */
        static boolean covers(@NotNull ImageFrame frame, int width, int height) {
            return frame.offsetX() == 0 && frame.offsetY() == 0 && frame.width() >= width && frame.height() >= height;
        }

    }

}
