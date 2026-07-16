package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelBufferPool;
import lib.minecraft.renderer.engine.kit.GlintKit;
import lib.minecraft.renderer.engine.raster.GlintMask;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

/**
 * The single terminal pipeline for the rasterizing renderers: draw each frame, run the shared
 * supersample / FXAA / downscale tail, then either scroll an enchantment glint or bake an animation
 * strip, and wrap the result into {@link ImageData}. The differences between
 * renderers become a {@link FinalizeSpec} data value.
 * <p>
 * Glint and tick-driven animation compose by precedence rather than exclusion: the
 * <b>animation owns the frame axis</b> and glint is a per-frame post-stamp on the baked strip. A static
 * render ({@code frameCount <= 1}) with glint bakes one frame and applies the fps-paced glint scroll
 * ({@link #applyGlint}); an animated render ({@code frameCount > 1}) with glint bakes the strip and
 * stamps each frame with the glint whose wall-clock schedule is DERIVED from the tick clock
 * ({@code glintTime = tick * }{@link #MILLIS_PER_TICK}{@code  * }{@link GlintKit#MAX_ENCHANTMENT_GLINT_SPEED_MILLIS}),
 * so the foil stays phase-aligned with the live client. The animation never multiplies frames for the
 * glint; when the loop is short relative to glint's period the foil visibly jumps at the loop seam, the
 * same accepted truncation as the 2 s glint loop. Frame count = the animation's; delays = the animation's.
 */
@UtilityClass
public final class Finalize {

    /** Output frame rate for worn-armor glint, matching every vanilla armor render site. */
    private static final int ARMOR_GLINT_FPS = 30;

    /**
     * Milliseconds in one vanilla tick - the constant every texture-animated subject folds into its
     * per-frame playback delay ({@code ticksPerFrame * MILLIS_PER_TICK}) and the tick-to-wall-clock
     * bridge glint composition uses ({@code tick * MILLIS_PER_TICK}). Centralised so no call site
     * hand-writes {@code 50}.
     */
    public static final int MILLIS_PER_TICK = 50;

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

        /**
         * Whole-item glint whose texture an OptiFine {@code type=enchantment} CIT rule replaced - the
         * item preset with only the glint texture id swapped, everything else (scroll, scale, loop)
         * unchanged. Falls back to no glint at compose time when the replacement texture
         * resolves to nothing, exactly like the default preset.
         */
        public static @NotNull Glint itemReplaced(
            @NotNull TextureResolver resolver, boolean enchanted, boolean animate, int framesPerSecond, @NotNull String glintTextureId
        ) {
            return new Glint(resolver, enchanted, animate, GlintKit.GlintOptions.itemDefault(framesPerSecond).withTexture(glintTextureId));
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

        /**
         * An animation-strip spec: {@code frameCount} frames sampled from {@code startTick}. The
         * general low-level factory behind the three documented timing modes;
         * the {@code delayMs} the caller supplies is what distinguishes them:
         * <ul>
         * <li><b>(a) tick strip</b> - texture animation (fluid, and every block / item / entity
         *     subject): {@code delayMs = ticksPerFrame * }{@link Finalize#MILLIS_PER_TICK}, so the
         *     output clock resamples the texture clock at a fixed tick cadence. Built through
         *     {@link #tickStrip} so no call site hand-writes the constant.</li>
         * <li><b>(b) sim-tick + fixed delay</b> - portal parallax: {@code ticksPerFrame} advances a
         *     continuous shader sim while {@code delayMs} stays a fixed {@code 50}, deliberately
         *     decoupling sim speed from playback speed.</li>
         * <li><b>(c) fps-derived ms</b> - the enchantment-glint tail: frame {@code n} plays for
         *     {@code delayMs = max(1, round(1000 / fps))}; owned by {@link Glint}, not this factory.</li>
         * </ul>
         */
        public static @NotNull FinalizeSpec animated(
            int width, int height, int ssaa, boolean antiAlias,
            int frameCount, int startTick, int ticksPerFrame, int delayMs
        ) {
            return new FinalizeSpec(width, height, ssaa, antiAlias, false, frameCount, startTick, ticksPerFrame, delayMs, null);
        }

        /**
         * The mode-(a) tick-strip factory: binds an
         * {@link AnimationOptions} timeline to {@link #animated} with the mode-(a) delay
         * ({@code ticksPerFrame * }{@link Finalize#MILLIS_PER_TICK}) folded in once, so every
         * texture-animated subject (fluid, block, item, entity) shares one delay derivation and
         * cannot drift. A {@code frameCount = 1} timeline (the static default) produces the same
         * single-frame spec {@link #staticFrame} would, sampled at {@code startTick}.
         *
         * @param width the output canvas width
         * @param height the output canvas height
         * @param ssaa the supersampling factor; {@code 1} means no supersampling
         * @param antiAlias whether to apply FXAA before downscaling
         * @param animation the caller's animation timeline (start tick, frame count, ticks per frame)
         * @return the tick-strip spec
         */
        public static @NotNull FinalizeSpec tickStrip(
            int width, int height, int ssaa, boolean antiAlias, @NotNull AnimationOptions animation
        ) {
            return animated(width, height, ssaa, antiAlias, animation.getFrameCount(),
                animation.getStartTick(), animation.getTicksPerFrame(),
                animation.getTicksPerFrame() * MILLIS_PER_TICK);
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
        if (spec.frameCount() > 1)
            return renderStrip(spec, raster, UnaryOperator.identity());

        Finished finished = rasterizeAndPost(spec, raster, spec.startTick());
        Glint glint = spec.glint();
        if (glint == null)
            return FrameCompositor.staticFrame(finished.buffer());

        return applyGlint(glint, finished.buffer(), finished.mask());
    }

    /**
     * Bakes {@code spec.frameCount()} frames in parallel, applies {@code framePostProcess} to the full
     * baked list, composes a per-frame enchantment glint when the spec carries one, then wraps the
     * result into an animation strip at {@code spec.delayMs()}. The post-processor is the seam for
     * renderers whose frames are NOT independent: a seamless-loop crossfade that blends each frame
     * against a later one and trims the extra bridge frames, say. A plain strip passes
     * {@link UnaryOperator#identity()} - which is exactly what {@link #render} does for
     * {@code frameCount > 1}.
     * <p>
     * Glint × animation: the animation owns the frame axis; if the spec carries an
     * enchanted {@link Glint}, each frame is post-stamped with a scrolled foil whose glint time derives
     * from that frame's tick ({@code tick_f = startTick + f * ticksPerFrame}). Glint composition assumes
     * {@code framePostProcess} preserves frame count and order (the identity case, which every glint
     * caller uses); the portal's trimming post-processor never carries a glint.
     *
     * @param spec the terminal recipe; {@code frameCount} frames are baked from {@code startTick},
     *        {@code ticksPerFrame} apart
     * @param raster draws each frame at its tick
     * @param framePostProcess transforms the baked frame list before it is wrapped
     * @return the finished animation strip
     */
    public static @NotNull ImageData renderStrip(
        @NotNull FinalizeSpec spec,
        @NotNull FrameRasterizer raster,
        @NotNull UnaryOperator<ConcurrentList<PixelBuffer>> framePostProcess
    ) {
        // Frame-parallel bake keeping each frame's (optional) glint mask; mapToObj().toList() preserves
        // encounter order so the strip stays tick-ordered for GIF/WebP playback.
        List<Finished> baked = IntStream.range(0, spec.frameCount())
            .parallel()
            .mapToObj(f -> rasterizeAndPost(spec, raster, spec.startTick() + f * spec.ticksPerFrame()))
            .toList();

        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        for (Finished finished : baked) frames.add(finished.buffer());
        frames = framePostProcess.apply(frames);

        // Frame f's tick, or - for a non-scrolling glint (animateGlint=false) - startTick every frame.
        stampAnimatedGlint(spec, baked, frames,
            f -> spec.glint() != null && spec.glint().animate() ? spec.startTick() + f * spec.ticksPerFrame() : spec.startTick());

        return FrameCompositor.wrapFrames(frames, spec.delayMs());
    }

    /**
     * Composes the enchantment glint onto a baked strip in place (a no-op unless the spec carries an
     * enchanted glint whose texture resolves). The animation owns the frame axis; each frame is
     * post-stamped with a scrolled foil at the glint time derived from that frame's tick
     * ({@code tickForFrame}): {@code glintTime = tick * }{@link #MILLIS_PER_TICK}{@code  * }{@link
     * GlintKit#MAX_ENCHANTMENT_GLINT_SPEED_MILLIS} - the wall-clock instant the tick represents,
     * converted to a glint time exactly as {@link GlintKit#applyGlint} does for its fps-paced schedule
     * (which is why the {@code * 8.0} speed factor is applied HERE, not inside
     * {@link GlintKit#applyGlintAtTimes} which takes already-post-{@code glintSpeed} millis). The
     * per-frame {@link GlintMask} recorded during the bake confines the foil (armor), or is {@code null}
     * for a whole-icon glint (items).
     */
    private static void stampAnimatedGlint(
        @NotNull FinalizeSpec spec,
        @NotNull List<Finished> baked,
        @NotNull ConcurrentList<PixelBuffer> frames,
        @NotNull IntUnaryOperator tickForFrame
    ) {
        Glint glint = spec.glint();
        if (glint == null || !glint.enchanted()) return;

        Optional<PixelBuffer> glintTexture = glint.resolver().resolve(glint.preset().glintTextureId());
        if (glintTexture.isEmpty()) return;

        for (int f = 0; f < frames.size(); f++) {
            long glintTime = glintTimeForTick(tickForFrame.applyAsInt(f));
            GlintMask mask = f < baked.size() ? baked.get(f).mask() : null;
            PixelBuffer stamped = GlintKit.applyGlintAtTimes(
                frames.get(f), glintTexture.get(), new long[]{glintTime}, glint.preset(), null, mask).getFirst();
            frames.set(f, stamped);
        }
    }

    /**
     * Converts an animation {@code tick} to the glint time {@link GlintKit#applyGlintAtTimes} expects.
     * The tick's wall-clock instant is
     * {@code tick * }{@link #MILLIS_PER_TICK}{@code  ms}; the value {@code GlintKit} consumes is
     * "vanilla post-{@code glintSpeed} millis", i.e. the wall-clock instant times
     * {@link GlintKit#MAX_ENCHANTMENT_GLINT_SPEED_MILLIS} - EXACTLY how
     * {@link GlintKit#applyGlint} builds its own fps-paced schedule
     * ({@code frameIndex * millisPerFrame * MAX_ENCHANTMENT_GLINT_SPEED_MILLIS}). The {@code * 8.0}
     * speed factor therefore folds in HERE, not inside {@code applyGlintAtTimes}, so an animated glint
     * scrolls at the same rate as a static-frame glint and stays phase-aligned with the live client.
     * Both vanilla glint loop periods (110 000 / 30 000 ms) are multiples of this quantum, so a
     * tick-derived schedule sweeps the full glint cycle without phase drift.
     *
     * @param tick the animation tick a frame is drawn at
     * @return the glint time in vanilla post-{@code glintSpeed} milliseconds
     */
    static long glintTimeForTick(int tick) {
        return Math.round((double) tick * MILLIS_PER_TICK * GlintKit.MAX_ENCHANTMENT_GLINT_SPEED_MILLIS);
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
