package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelBufferPool;
import org.jetbrains.annotations.NotNull;

/**
 * The per-render raster configuration and callbacks a {@link Timeline} plays a schedule through:
 * canvas size, supersample / FXAA, mask recording, the per-frame draw, and the whole-strip finish
 * step. Knows how to raster exactly one frame; owns no time.
 *
 * @param width the output canvas width
 * @param height the output canvas height
 * @param ssaa the supersampling factor; {@code 1} means no supersampling
 * @param antiAlias whether to apply FXAA before downscaling
 * @param recordMask whether to record and downsample a coverage mask per frame
 * @param raster the per-frame draw callback, in its continuous form
 * @param finish the whole-strip post step; {@link Finish#NONE} when frames pass through untouched
 */
public record RasterPass(
    int width, int height, int ssaa, boolean antiAlias, boolean recordMask,
    @NotNull ContinuousRasterizer raster, @NotNull Finish finish
) {

    /**
     * Draws one frame into a target buffer at a given animation tick, optionally recording a
     * coverage mask. The tick is {@code startTick} for a static render; for an animated render it is
     * {@code startTick + f * ticksPerFrame} per frame, so per-tick state (engine, textures) must be
     * built inside the callback.
     */
    @FunctionalInterface
    public interface FrameRasterizer {

        /**
         * Draws the frame at {@code tick} into {@code target}.
         *
         * @param target the buffer to draw into (hi-res when supersampling, else the output buffer);
         *     enable a coverage mask on it via {@link PixelBuffer#enableMask()} to record glint coverage
         * @param tick the absolute animation tick to sample
         */
        void rasterize(@NotNull PixelBuffer target, int tick);

    }

    /**
     * Draws one frame at an instant that may fall between ticks - the draw for a subject whose
     * appearance is a continuous function of time rather than a lookup keyed by tick, and which
     * therefore has a defined value partway through one.
     * <p>
     * Most subjects want {@link FrameRasterizer} instead. A texture flipbook has no state between its
     * frames, so it reads the whole tick and renders identically whatever schedule plays it; only a
     * subject that would otherwise be sampled too coarsely needs the fraction.
     */
    @FunctionalInterface
    public interface ContinuousRasterizer {

        /**
         * Draws the frame at {@code ageInTicks} into {@code target}.
         *
         * @param target the buffer to draw into (hi-res when supersampling, else the output buffer)
         * @param tick the sample instant floored to a whole tick, for any discrete per-tick lookup
         * @param ageInTicks the exact sample instant in ticks, which may fall between two
         */
        void rasterize(@NotNull PixelBuffer target, int tick, float ageInTicks);

    }

    /**
     * The whole-strip post step a bake applies after rastering every frame and before wrapping:
     * receives the baked frames and may return different frames on a different playback schedule (a
     * seamless-loop crossfade that trims its bridge frames, or a post-stamp that expands one frame
     * into a loop of its own). A pass carries exactly one finish; a caller needing several effects
     * composes them into one before constructing the pass.
     */
    @FunctionalInterface
    public interface Finish {

        /** The identity finish - frames and playback schedule pass through unchanged. */
        Finish NONE = (frames, timeline) -> new Result(frames, timeline);

        /**
         * Transforms the baked strip.
         *
         * @param frames the baked frame buffers, in frame order; each carries its own coverage mask
         * @param timeline the schedule that baked the frames
         * @return the frames to wrap and the schedule whose delays wrap them
         */
        @NotNull Result finish(@NotNull ConcurrentList<PixelBuffer> frames, @NotNull Timeline timeline);

        /**
         * A finish step's output.
         *
         * @param frames the frames to wrap, in playback order
         * @param playback the schedule whose per-frame delays wrap the frames
         */
        record Result(@NotNull ConcurrentList<PixelBuffer> frames, @NotNull Timeline playback) {}

    }

    /**
     * Builds a pass with no mask recording and the identity finish.
     *
     * @param width the output canvas width
     * @param height the output canvas height
     * @param ssaa the supersampling factor; {@code 1} means no supersampling
     * @param antiAlias whether to apply FXAA before downscaling
     * @param raster the per-frame draw callback
     * @return the pass
     */
    public static @NotNull RasterPass of(int width, int height, int ssaa, boolean antiAlias,
                                         @NotNull FrameRasterizer raster) {
        return of(width, height, ssaa, antiAlias, (target, tick, ageInTicks) -> raster.rasterize(target, tick));
    }

    /**
     * Builds a pass whose draw reads the exact instant, with no mask recording and the identity finish.
     *
     * @param width the output canvas width
     * @param height the output canvas height
     * @param ssaa the supersampling factor; {@code 1} means no supersampling
     * @param antiAlias whether to apply FXAA before downscaling
     * @param raster the per-frame draw callback, reading an instant that may fall between ticks
     * @return the pass
     */
    public static @NotNull RasterPass of(int width, int height, int ssaa, boolean antiAlias,
                                         @NotNull ContinuousRasterizer raster) {
        return new RasterPass(width, height, ssaa, antiAlias, false, raster, Finish.NONE);
    }

    /**
     * Returns a copy with the given mask-recording flag.
     *
     * @param recordMask whether to record a coverage mask per frame
     * @return the copy
     */
    public @NotNull RasterPass withMask(boolean recordMask) {
        return new RasterPass(width, height, ssaa, antiAlias, recordMask, raster, finish);
    }

    /**
     * Returns a copy carrying the given finish step.
     *
     * @param finish the whole-strip post step
     * @return the copy
     */
    public @NotNull RasterPass finishing(@NotNull Finish finish) {
        return new RasterPass(width, height, ssaa, antiAlias, recordMask, raster, finish);
    }

    /**
     * Rasters one frame at the given tick through the supersample / FXAA / downscale tail. When
     * {@code ssaa > 1} the frame is drawn into a pooled hi-res buffer, FXAA'd there, blit-scaled
     * down, and any mask downsampled; otherwise it is drawn straight into the output buffer.
     *
     * @param tick the absolute animation tick to sample
     * @return the finished frame, carrying its downsampled coverage mask when the pass records one
     */
    public @NotNull PixelBuffer renderFrame(int tick) {
        return renderFrame(tick, tick);
    }

    /**
     * Rasters one frame at the given instant through the supersample / FXAA / downscale tail. When
     * {@code ssaa > 1} the frame is drawn into a pooled hi-res buffer, FXAA'd there, blit-scaled
     * down, and any mask downsampled; otherwise it is drawn straight into the output buffer.
     *
     * @param tick the sample instant floored to a whole tick, for discrete per-tick lookups
     * @param ageInTicks the exact sample instant, in ticks, which may fall between two
     * @return the finished frame, carrying its downsampled coverage mask when the pass records one
     */
    public @NotNull PixelBuffer renderFrame(int tick, float ageInTicks) {
        if (ssaa > 1) {
            int hiWidth = width * ssaa;
            int hiHeight = height * ssaa;

            try (PixelBufferPool.Lease lease = PixelBufferPool.acquire(hiWidth, hiHeight)) {
                PixelBuffer hiRes = lease.buffer();
                if (recordMask) hiRes.enableMask();
                raster.rasterize(hiRes, tick, ageInTicks);
                if (antiAlias) hiRes.applyFxaa();
                PixelBuffer output = PixelBuffer.create(width, height);
                output.blitScaled(hiRes, 0, 0, width, height);
                hiRes.mask().map(m -> m.downsample(width, height)).ifPresent(output::attachMask);
                return output;
            }
        }

        PixelBuffer buffer = PixelBuffer.create(width, height);
        if (recordMask) buffer.enableMask();
        raster.rasterize(buffer, tick, ageInTicks);
        if (antiAlias) buffer.applyFxaa();
        return buffer;
    }

}
