package lib.minecraft.renderer.engine.frame;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.FrameBlend;
import dev.simplified.image.data.FrameDisposal;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
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
 * blitted at the picture's own size when it does not.
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
        ConcurrentList<LongFunction<PixelBuffer>> samplers = layers.stream()
            .map(layer -> sampler(layer.source()))
            .collect(Concurrent.toList());

        if (!anyAnimated)
            return StaticImageData.of(renderFrame(layers, samplers, canvasW, canvasH, background, 0).toBufferedImage());

        long mergedLoopMs = computeMergedLoopMs(layers);
        int outputFrameDelayMs = Timeline.delayForFps(framesPerSecond);
        int outputFrameCount = Math.max(1, (int) Math.ceil((double) mergedLoopMs / outputFrameDelayMs));
        Timeline.FpsLoop playback = new Timeline.FpsLoop(framesPerSecond, outputFrameCount);

        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int frameIndex = 0; frameIndex < outputFrameCount; frameIndex++) {
            PixelBuffer frame = renderFrame(layers, samplers, canvasW, canvasH, background, playback.playbackMsAt(frameIndex));

            // renderFrame fills the background and blits every placement from scratch, so this frame
            // is the whole canvas and not a delta against the last one. Say so: the two-argument
            // ImageFrame.of defaults to FrameDisposal.NONE, which leaves the previous frame standing
            // underneath, and a transparent canvas then shows it through wherever this one is clear.
            builder.withFrame(ImageFrame.of(frame, playback.delayMs(frameIndex), 0, 0,
                FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE));
        }

        return builder.build();
    }

    /**
     * Renders one output frame at {@code timeMs}: fills the background, then draws each placement
     * (sampled at that playback time) at its destination origin, in back-to-front list order.
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
        PixelBuffer buffer = PixelBuffer.create(canvasW, canvasH);
        background.fill(buffer);

        for (int index = 0; index < layers.size(); index++)
            draw(buffer, layers.get(index), samplers.get(index).apply(timeMs));

        return buffer;
    }

    /**
     * Draws one sampled picture of a placement onto the canvas at the placement's origin - rescaled
     * into the placement's extent by {@link PixelBuffer#blitScaled} when it has one, and blitted at the
     * picture's own size when it does not.
     *
     * @param canvas the output frame being composited
     * @param layer the placement the picture was sampled from
     * @param picture the placement's picture at the output frame's playback time
     */
    private static void draw(@NotNull PixelBuffer canvas, @NotNull FramePlacement layer, @NotNull PixelBuffer picture) {
        layer.extent().ifPresentOrElse(
            extent -> canvas.blitScaled(picture, layer.x(), layer.y(), extent.width(), extent.height()),
            () -> canvas.blit(picture, layer.x(), layer.y()));
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
     * The canvas starts transparent. A frame that {@linkplain #covers covers} the canvas from its
     * origin is the whole picture while it shows, and replaces what the canvas held. Any other frame is
     * drawn source-over at its offset and clipped to the canvas: a fully transparent texel leaves the
     * canvas beneath it, an opaque one replaces it, and a part-transparent one blends with it as the
     * image library's WebP reader blends a frame onto its canvas. Once a frame has shown, its
     * {@link FrameDisposal} decides what the next frame is drawn onto - {@link FrameDisposal#NONE NONE}
     * and {@link FrameDisposal#DO_NOT_DISPOSE DO_NOT_DISPOSE} leave the frame standing,
     * {@link FrameDisposal#RESTORE_TO_BACKGROUND RESTORE_TO_BACKGROUND} clears its rectangle to
     * transparent, and {@link FrameDisposal#RESTORE_TO_PREVIOUS RESTORE_TO_PREVIOUS} puts the canvas
     * back as it stood before the frame was drawn.
     * <p>
     * A frame's {@link FrameBlend} is not read. The image library's GIF reader marks every frame
     * {@link FrameBlend#SOURCE SOURCE}, though a GIF frame's transparent texels leave the picture
     * beneath them, and its WebP reader keeps each frame's blend on a frame it has already composed
     * into the whole picture.
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
         * Constructs a new {@code Composition} of an animation, on a transparent canvas the image's size.
         *
         * @param animated the animation to play
         */
        Composition(@NotNull AnimatedImageData animated) {
            this.animated = animated;
            this.pictures = new PixelBuffer[animated.getFrames().size()];
            this.canvas = PixelBuffer.create(animated.getWidth(), animated.getHeight());
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
                drawOver(this.canvas, frame);
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

        /**
         * Draws a frame source-over onto a canvas at the frame's offset, clipped to the canvas. A frame
         * whose pixels carry no alpha is copied as it is, as {@link PixelBuffer#blit} copies one.
         *
         * @param canvas the canvas to draw onto
         * @param frame the frame to draw
         */
        private static void drawOver(@NotNull PixelBuffer canvas, @NotNull ImageFrame frame) {
            PixelBuffer pixels = frame.pixels();
            int[] source = pixels.data();
            int[] target = canvas.data();
            int x0 = Math.max(0, frame.offsetX());
            int y0 = Math.max(0, frame.offsetY());
            int x1 = Math.min(canvas.width(), frame.offsetX() + pixels.width());
            int y1 = Math.min(canvas.height(), frame.offsetY() + pixels.height());

            for (int y = y0; y < y1; y++) {
                int sourceRow = (y - frame.offsetY()) * pixels.width() - frame.offsetX();
                int targetRow = y * canvas.width();

                for (int x = x0; x < x1; x++)
                    target[targetRow + x] = pixels.hasAlpha()
                        ? over(source[sourceRow + x], target[targetRow + x])
                        : source[sourceRow + x];
            }
        }

        /**
         * Composites one texel source-over another, by the integer arithmetic the image library's WebP
         * reader blends a frame onto its canvas with. An opaque texel replaces the one beneath it, a
         * fully transparent one leaves it, and any texel over a fully transparent one is kept exactly.
         *
         * @param source the frame's texel
         * @param destination the canvas texel beneath it
         * @return the composited texel
         */
        private static int over(int source, int destination) {
            int sourceAlpha = ColorMath.alpha(source);
            if (sourceAlpha == 0xFF) return source;
            if (sourceAlpha == 0) return destination;

            int destinationAlpha = ColorMath.alpha(destination);
            if (destinationAlpha == 0) return source;

            int alpha = sourceAlpha + destinationAlpha * (255 - sourceAlpha) / 255;
            int red = (ColorMath.red(source) * sourceAlpha + ColorMath.red(destination) * destinationAlpha * (255 - sourceAlpha) / 255) / alpha;
            int green = (ColorMath.green(source) * sourceAlpha + ColorMath.green(destination) * destinationAlpha * (255 - sourceAlpha) / 255) / alpha;
            int blue = (ColorMath.blue(source) * sourceAlpha + ColorMath.blue(destination) * destinationAlpha * (255 - sourceAlpha) / 255) / alpha;
            return ColorMath.pack(alpha, red, green, blue);
        }

    }

}
