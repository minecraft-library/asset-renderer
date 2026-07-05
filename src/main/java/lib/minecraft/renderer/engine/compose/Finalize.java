package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelBufferPool;
import lib.minecraft.renderer.engine.kit.GlintKit;
import lib.minecraft.renderer.engine.raster.GlintMask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The single terminal pipeline for the rasterizing renderers: draw each frame, run the shared
 * supersample / FXAA / downscale tail, then either scroll an enchantment glint or bake an animation
 * strip, and wrap the result into {@link ImageData}. Replaces the ad-hoc, per-renderer nesting of the
 * former {@code FinalizeStage} + {@code AnimationStage} + {@code GlintStage} - the differences between
 * renderers become a {@link FinalizeSpec} data value.
 * <p>
 * Glint and tick-driven animation are mutually exclusive at every call site (a glinted render bakes one
 * frame; an animated render carries no glint), so the two frame-multiplying tails never compose.
 */
public final class Finalize {

    /** Output frame rate for worn-armor glint, matching every vanilla armor render site. */
    private static final int ARMOR_GLINT_FPS = 30;

    private Finalize() {
    }

    /**
     * Draws one frame into a target buffer at a given animation tick, optionally recording a glint
     * coverage mask. The tick is {@code startTick} for a static render; for an animated render it is
     * {@code startTick + f * ticksPerFrame} per frame, so per-tick state (engine, textures) must be
     * built inside the callback.
     */
    @FunctionalInterface
    public interface FrameRasterizer {

        /**
         * Draws the frame at {@code tick} into {@code target}.
         *
         * @param target the buffer to draw into (hi-res when supersampling, else the output buffer)
         * @param mask the glint mask to populate, or {@code null} when the spec records no mask
         * @param tick the absolute animation tick to sample
         */
        void rasterize(@NotNull PixelBuffer target, @Nullable GlintMask mask, int tick);
    }

    /**
     * Resolves a glint scroll texture by its namespaced id; typically {@code engine::tryResolveTexture}.
     */
    @FunctionalInterface
    public interface TextureResolver {

        /**
         * Resolves the glint texture for the given id.
         *
         * @param textureId the namespaced glint texture id
         * @return the resolved texture, or empty when the active pack stack provides none
         */
        @NotNull Optional<PixelBuffer> resolve(@NotNull String textureId);
    }

    /**
     * The enchantment-glint tail applied to a finished buffer.
     *
     * @param resolver the glint-texture resolver
     * @param enchanted whether the subject is enchanted and should show a glint
     * @param animate whether to emit the animated scroll; {@code false} keeps only the frame-0 glint
     * @param preset the glint preset (texture id + frame rate)
     */
    public record Glint(
        @NotNull TextureResolver resolver,
        boolean enchanted,
        boolean animate,
        @NotNull GlintKit.GlintOptions preset
    ) {

        /** Worn-armor glint: always animated, at the armor preset. */
        public static @NotNull Glint armor(@NotNull TextureResolver resolver, boolean enchanted) {
            return new Glint(resolver, enchanted, true, GlintKit.GlintOptions.armorDefault(ARMOR_GLINT_FPS));
        }

        /** Whole-item glint: animated per the caller's flag, at the item preset. */
        public static @NotNull Glint item(@NotNull TextureResolver resolver, boolean enchanted, boolean animate, int framesPerSecond) {
            return new Glint(resolver, enchanted, animate, GlintKit.GlintOptions.itemDefault(framesPerSecond));
        }
    }

    /**
     * The per-render terminal recipe: canvas size, supersample / FXAA, whether to record a glint mask,
     * the animation strip parameters ({@code frameCount <= 1} means a single static frame), and the
     * optional glint tail.
     *
     * @param width the output canvas width
     * @param height the output canvas height
     * @param ssaa the supersampling factor; {@code 1} means no supersampling
     * @param antiAlias whether to apply FXAA before downscaling
     * @param recordMask whether to record + downsample a glint coverage mask (independent of {@code glint})
     * @param frameCount number of animation frames; {@code <= 1} produces a static frame
     * @param startTick the absolute tick of frame 0
     * @param ticksPerFrame ticks between successive animation frames
     * @param delayMs per-frame playback delay in milliseconds (animated output only)
     * @param glint the glint tail, or {@code null} for no glint
     */
    public record FinalizeSpec(
        int width, int height, int ssaa, boolean antiAlias, boolean recordMask,
        int frameCount, int startTick, int ticksPerFrame, int delayMs, @Nullable Glint glint
    ) {

        /** A single-frame spec: no mask, no glint, no animation. */
        public static @NotNull FinalizeSpec staticFrame(int width, int height, int ssaa, boolean antiAlias) {
            return new FinalizeSpec(width, height, ssaa, antiAlias, false, 1, 0, 1, 0, null);
        }

        /** An animation-strip spec: {@code frameCount} frames sampled from {@code startTick}. */
        public static @NotNull FinalizeSpec animated(
            int width, int height, int ssaa, boolean antiAlias,
            int frameCount, int startTick, int ticksPerFrame, int delayMs
        ) {
            return new FinalizeSpec(width, height, ssaa, antiAlias, false, frameCount, startTick, ticksPerFrame, delayMs, null);
        }

        /** Returns a copy carrying the given glint tail and mask-recording flag. */
        public @NotNull FinalizeSpec withGlint(@NotNull Glint glint, boolean recordMask) {
            return new FinalizeSpec(width, height, ssaa, antiAlias, recordMask, frameCount, startTick, ticksPerFrame, delayMs, glint);
        }
    }

    /** A finished (downscaled) output buffer and its (downsampled) glint mask. */
    private record Finished(@NotNull PixelBuffer buffer, @Nullable GlintMask mask) {
    }

    /**
     * Runs the terminal pipeline for {@code spec}, drawing each frame through {@code raster}. Produces
     * an animation strip when {@code spec.frameCount() > 1}, otherwise a single frame with the optional
     * glint tail.
     *
     * @param spec the terminal recipe
     * @param raster draws each frame at its tick
     * @return the finished image
     */
    public static @NotNull ImageData render(@NotNull FinalizeSpec spec, @NotNull FrameRasterizer raster) {
        if (spec.frameCount() > 1) {
            // Frame-parallel bake: each frame is independent; mapToObj().toList() preserves encounter
            // order so the strip stays tick-ordered for GIF/WebP playback. Glint never applies here.
            ConcurrentList<PixelBuffer> frames = Concurrent.newList();
            frames.addAll(IntStream.range(0, spec.frameCount()).parallel()
                .mapToObj(f -> rasterizeAndPost(spec, raster, spec.startTick() + f * spec.ticksPerFrame()).buffer())
                .toList());
            return FrameCompositor.wrapFrames(frames, spec.delayMs());
        }

        Finished finished = rasterizeAndPost(spec, raster, spec.startTick());
        Glint glint = spec.glint();
        if (glint == null)
            return FrameCompositor.staticFrame(finished.buffer());

        return applyGlint(glint, finished.buffer(), finished.mask());
    }

    /**
     * Draws one frame at {@code tick} and runs the supersample / FXAA / downscale tail. When
     * {@code ssaa > 1} the frame is drawn into a pooled hi-res buffer, FXAA'd there, blit-scaled down,
     * and any mask downsampled; otherwise it is drawn straight into the output buffer.
     */
    private static @NotNull Finished rasterizeAndPost(@NotNull FinalizeSpec spec, @NotNull FrameRasterizer raster, int tick) {
        if (spec.ssaa() > 1) {
            int hiWidth = spec.width() * spec.ssaa();
            int hiHeight = spec.height() * spec.ssaa();
            try (PixelBufferPool.Lease lease = PixelBufferPool.acquire(hiWidth, hiHeight)) {
                PixelBuffer hiRes = lease.buffer();
                GlintMask hiMask = spec.recordMask() ? new GlintMask(hiWidth, hiHeight) : null;
                raster.rasterize(hiRes, hiMask, tick);
                if (spec.antiAlias()) hiRes.applyFxaa();
                PixelBuffer output = PixelBuffer.create(spec.width(), spec.height());
                output.blitScaled(hiRes, 0, 0, spec.width(), spec.height());
                GlintMask mask = hiMask == null ? null : hiMask.downsample(spec.width(), spec.height());
                return new Finished(output, mask);
            }
        }

        PixelBuffer buffer = PixelBuffer.create(spec.width(), spec.height());
        GlintMask mask = spec.recordMask() ? new GlintMask(spec.width(), spec.height()) : null;
        raster.rasterize(buffer, mask, tick);
        if (spec.antiAlias()) buffer.applyFxaa();
        return new Finished(buffer, mask);
    }

    /**
     * The glint core: returns the buffer as a static frame when nothing glints or no glint texture
     * resolves, else composes the scrolling foil via {@link GlintKit#applyGlint} - a single frame-0
     * frame when {@code animate} is false, otherwise the animated strip.
     */
    private static @NotNull ImageData applyGlint(@NotNull Glint glint, @NotNull PixelBuffer buffer, @Nullable GlintMask mask) {
        if (!glint.enchanted())
            return FrameCompositor.staticFrame(buffer);

        Optional<PixelBuffer> glintTexture = glint.resolver().resolve(glint.preset().glintTextureId());
        if (glintTexture.isEmpty())
            return FrameCompositor.staticFrame(buffer);

        ConcurrentList<PixelBuffer> frames = GlintKit.applyGlint(buffer, glintTexture.get(), glint.preset(), mask);
        if (!glint.animate())
            return FrameCompositor.staticFrame(frames.getFirst());

        int frameDelayMs = Math.max(1, Math.round(1000f / glint.preset().framesPerSecond()));
        return FrameCompositor.wrapFrames(frames, frameDelayMs);
    }
}
