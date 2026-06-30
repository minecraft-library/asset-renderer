package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelBufferPool;
import lib.minecraft.renderer.engine.GlintMask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared terminal stage for 3D renders: supersample, optional FXAA, downscale, and finalisation.
 * <p>
 * The entity, player, block, and fluid renderers each duplicated a near-identical tail that
 * rasterizes into a pooled hi-res buffer (when supersampling), applies FXAA, downscales, and then
 * finalises - sometimes as an animated glint, sometimes as a static frame, sometimes as a raw buffer.
 * {@link #run} holds that one control flow; the per-renderer differences are supplied as two
 * callbacks so they stay explicit:
 * <ul>
 * <li><b>{@link Rasterizer}</b> - how to draw the geometry into a target buffer (which engine call,
 * which perspective, and whether a glint mask is recorded).</li>
 * <li><b>{@link Finalizer}</b> - what to produce from the finished buffer (masked glint image, static
 * frame, or the raw buffer for an animation loop).</li>
 * </ul>
 * The control flow is a verbatim extraction of the previous per-renderer tails, so output is
 * byte-identical.
 */
public final class FinalizeStage {

    /**
     * Draws the geometry into {@code target}, optionally recording a glint mask.
     */
    @FunctionalInterface
    public interface Rasterizer {

        /**
         * Rasterizes into the given target buffer.
         *
         * @param target the buffer to draw into (hi-res when supersampling, else the output buffer)
         * @param mask the glint mask to record, or {@code null} when no glint is needed
         */
        void rasterize(@NotNull PixelBuffer target, @Nullable GlintMask mask);
    }

    /**
     * Produces the render result from the finished (downsampled) buffer and its glint mask.
     *
     * @param <R> the produced result type ({@code ImageData} or {@code PixelBuffer})
     */
    @FunctionalInterface
    public interface Finalizer<R> {

        /**
         * Finalises the rendered buffer.
         *
         * @param buffer the finished output buffer at the requested canvas size
         * @param mask the (downsampled) glint mask, or {@code null} when no glint was recorded
         * @return the render result
         */
        @NotNull R finish(@NotNull PixelBuffer buffer, @Nullable GlintMask mask);
    }

    private FinalizeStage() {
    }

    /**
     * Runs the supersample / FXAA / downscale / finalise tail.
     * <p>
     * When {@code ssaa > 1} the geometry is drawn into a pooled buffer at {@code width*ssaa} by
     * {@code height*ssaa}, FXAA is applied there (if {@code antiAlias}), the buffer is blit-scaled
     * down to {@code width} by {@code height}, and any glint mask is downsampled to match. Otherwise
     * the geometry is drawn straight into a {@code width} by {@code height} buffer.
     *
     * @param width the output canvas width
     * @param height the output canvas height
     * @param ssaa the supersampling factor; {@code 1} means no supersampling
     * @param antiAlias whether to apply FXAA before downscaling
     * @param withMask whether a glint mask is recorded and downsampled
     * @param rasterizer draws the geometry into the target buffer
     * @param finalizer produces the result from the finished buffer
     * @param <R> the produced result type
     * @return the render result
     */
    public static <R> @NotNull R run(
        int width, int height, int ssaa, boolean antiAlias, boolean withMask,
        @NotNull Rasterizer rasterizer, @NotNull Finalizer<R> finalizer
    ) {
        if (ssaa > 1) {
            int hiWidth = width * ssaa;
            int hiHeight = height * ssaa;
            try (PixelBufferPool.Lease lease = PixelBufferPool.acquire(hiWidth, hiHeight)) {
                PixelBuffer hiRes = lease.buffer();
                GlintMask hiMask = withMask ? new GlintMask(hiWidth, hiHeight) : null;
                rasterizer.rasterize(hiRes, hiMask);
                if (antiAlias) hiRes.applyFxaa();
                PixelBuffer output = PixelBuffer.create(width, height);
                output.blitScaled(hiRes, 0, 0, width, height);
                GlintMask mask = hiMask == null ? null : hiMask.downsample(width, height);
                return finalizer.finish(output, mask);
            }
        }

        PixelBuffer buffer = PixelBuffer.create(width, height);
        GlintMask mask = withMask ? new GlintMask(width, height) : null;
        rasterizer.rasterize(buffer, mask);
        if (antiAlias) buffer.applyFxaa();
        return finalizer.finish(buffer, mask);
    }
}
