package lib.minecraft.renderer.bake.texture;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.AnimatedImageData;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelMask;
import lib.minecraft.renderer.call.request.AnimationOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.RasterPass;
import lib.minecraft.renderer.engine.frame.Timeline;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link GlintKit}'s schedule bridge - the exact sample instant of a frame, converted to the glint
 * time {@link GlintKit#applyGlintAtTimes} consumes via {@link GlintKit#glintTimeAt}.
 * <p>
 * The load-bearing invariant is the {@code * 8.0} speed factor. {@code applyGlintAtTimes} takes
 * "vanilla post-{@code glintSpeed} millis" - a tick's wall-clock instant {@code tick * 50} must be
 * fed {@code tick * 50 * 8.0}, and an fps loop's instant {@code f * (1000.0 / fps)} must be fed that
 * times 8.0 - both through the one {@code glintTimeAt} fold, byte-identical to the literal formulas.
 * The {@link GlintKit.Foil} finish then consumes a whole {@link Timeline}: an animated subject stamps
 * per frame, a static subject swaps to the glint's own {@link Timeline.FpsLoop}.
 * <p>
 * The stamp each frame takes is held byte for byte to a frozen copy of its per-pixel loop, which wraps
 * the sampler's four texels with {@code Math.floorMod} and rounds the fade inline. The shipped stamp
 * masks a power-of-two axis and reads its fade from a table, so the copy is compared over glint
 * textures of power-of-two, non-power-of-two and mixed sizes, at coordinates that wrap negative and
 * at a scale whose texel coordinate saturates. The copy also computes the rotated coordinate whole at
 * each pixel, where the shipped stamp tables its column products per call and its row products per
 * row, so it is compared over canvases whose width and height differ and are not powers of two, under
 * scales and a sprite rect whose spans are not powers of two either: there a reciprocal in place of
 * the divide, a scale folded into the divisor or a product regrouped lands on another float.
 */
@DisplayName("GlintKit schedule bridge")
class GlintKitScheduleTest {

    /** Vanilla's default Glint Strength, as the frozen stamp fades by it. */
    private static final float FROZEN_GLINT_ALPHA = 0.75f;

    // ---- the one fold, both axes ---------------------------------------------------------------

    @Test
    @DisplayName("tick 0 maps to glint time 0")
    void zeroTick() {
        assertThat(GlintKit.glintTimeAt(0 * (double) Timeline.MILLIS_PER_TICK), is(0L));
    }

    @Test
    @DisplayName("glint time = tick * 50 ms * 8.0 speed factor (the * 8 is folded in glintTimeAt)")
    void includesSpeedFactor() {
        // tick 1 -> 50 ms wall-clock -> 400 glint-millis (50 * 8). NOT 50.
        assertThat(GlintKit.glintTimeAt(1 * (double) Timeline.MILLIS_PER_TICK), is(400L));
        assertThat(GlintKit.glintTimeAt(10 * (double) Timeline.MILLIS_PER_TICK), is(4000L));
        assertThat(GlintKit.glintTimeAt(200 * (double) Timeline.MILLIS_PER_TICK), is(80_000L));
    }

    @Test
    @DisplayName("tick axis: glintTimeAt(tick * 50.0) matches round(wallMs * 8.0) exactly")
    void tickAxisConvention() {
        for (int tick : new int[]{0, 1, 3, 40, 137, 200}) {
            long wallMs = (long) tick * Timeline.MILLIS_PER_TICK;
            long expected = Math.round(wallMs * GlintKit.MAX_ENCHANTMENT_GLINT_SPEED_MILLIS);
            assertThat(GlintKit.glintTimeAt(tick * (double) Timeline.MILLIS_PER_TICK), is(expected));
        }
    }

    @Test
    @DisplayName("fps axis: glintTimeAt(FpsLoop.millisAt(f)) == round(f * (1000.0 / fps) * 8.0)")
    void fpsAxisConvention() {
        for (int fps : new int[]{20, 24, 30, 60}) {
            Timeline.FpsLoop loop = new Timeline.FpsLoop(fps, fps * 2);
            for (int f = 0; f <= fps; f++) {
                long expected = Math.round(f * (1000.0 / fps) * GlintKit.MAX_ENCHANTMENT_GLINT_SPEED_MILLIS);
                assertThat(GlintKit.glintTimeAt(loop.millisAt(f)), is(expected));
            }
        }
    }

    @Test
    @DisplayName("both glint loop periods are multiples of the tick wall quantum -> clean sweep")
    void loopPeriodsSweepCleanly() {
        assertThat(GlintKit.VANILLA_U_LOOP_MILLIS % Timeline.MILLIS_PER_TICK, is(0L));
        assertThat(GlintKit.VANILLA_V_LOOP_MILLIS % Timeline.MILLIS_PER_TICK, is(0L));
    }

    // ---- Foil consumes a Timeline --------------------------------------------------------------

    @Test
    @DisplayName("static subject (1-frame timeline): Foil swaps playback to its own FpsLoop")
    void scrollingSwapsPlaybackToItsOwnFpsLoop() {
        GlintKit.Foil foil = GlintKit.Foil.item(context(), true, true, 30);
        ConcurrentList<PixelBuffer> frames = frames(base());
        RasterPass.Finish.Result result = foil.finish(frames, new Timeline.Static(0));

        assertThat(result.playback(), is(instanceOf(Timeline.FpsLoop.class)));
        Timeline.FpsLoop playback = (Timeline.FpsLoop) result.playback();
        assertThat(playback.framesPerSecond(), is(30));
        assertThat(playback.frameCount(), is(60));   // fps * GLINT_LOOP_SECONDS
        assertThat(result.frames().size(), is(60));
    }

    @Test
    @DisplayName("static subject, animate=false: Foil keeps only the frame-0 glint")
    void scrollNonAnimateKeepsOneFrame() {
        GlintKit.Foil foil = GlintKit.Foil.item(context(), true, false, 30);
        ConcurrentList<PixelBuffer> frames = frames(base());
        RasterPass.Finish.Result result = foil.finish(frames, new Timeline.Static(0));

        assertThat(result.frames().size(), is(1));
        assertThat(result.playback(), is(instanceOf(Timeline.FpsLoop.class)));
    }

    @Test
    @DisplayName("animated subject (N-frame timeline): Foil stamps in place on the baking timeline")
    void stampsInPlace() {
        GlintKit.Foil foil = GlintKit.Foil.item(context(), true, true, 30);
        ConcurrentList<PixelBuffer> frames = frames(base(), base(), base());
        Timeline.TickLoop timeline = new Timeline.TickLoop(0, 3, 50, 100);

        RasterPass.Finish.Result result = foil.finish(frames, timeline);
        assertThat(result.frames(), is(sameInstance(frames)));
        assertThat(result.playback(), is(sameInstance(timeline)));
        assertThat(result.frames().size(), is(3));
    }

    @Test
    @DisplayName("unenchanted subject: Foil is the identity finish")
    void unenchantedIsIdentity() {
        GlintKit.Foil foil = GlintKit.Foil.item(context(), false, true, 30);
        ConcurrentList<PixelBuffer> frames = frames(base(), base());
        Timeline.TickLoop timeline = new Timeline.TickLoop(0, 2, 50, 100);

        RasterPass.Finish.Result result = foil.finish(frames, timeline);
        assertThat(result.frames(), is(sameInstance(frames)));
        assertThat(result.playback(), is(sameInstance(timeline)));
    }

    @Test
    @DisplayName("animate=false freezes the stamp at millisAt(0); animate=true scrolls per frame")
    void animateFalseFreezesAtFrameZero() {
        // Two identical dark bases and a wide tick cadence so an animated foil visibly diverges.
        Timeline.TickLoop timeline = new Timeline.TickLoop(0, 2, 40, 100);

        ConcurrentList<PixelBuffer> scrolling = frames(base(), base());
        GlintKit.Foil.item(context(), true, true, 30).finish(scrolling, timeline);
        assertThat(differingPixels(scrolling.getFirst(), scrolling.getLast()), greaterThan(0));

        ConcurrentList<PixelBuffer> frozen = frames(base(), base());
        GlintKit.Foil.item(context(), true, false, 30).finish(frozen, timeline);
        assertThat(differingPixels(frozen.getFirst(), frozen.getLast()), is(0));

        // The same scrolling foil driven through a real bake, to pin that the finish reaches playback:
        // an animated strip, one image frame per animation frame, not a collapsed still.
        AnimationOptions animation = AnimationOptions.builder().frameCount(4).ticksPerFrame(50).build();
        ImageData baked = animation.tickStrip().bake(RasterPass.of(24, 24, 1, false,
            (target, tick) -> fillDark(target)).finishing(GlintKit.Foil.item(context(), true, true, 30)));

        assertThat(baked, is(instanceOf(AnimatedImageData.class)));
        assertThat(((AnimatedImageData) baked).getFrames().size(), is(4));
    }

    @Test
    @DisplayName("animate=false stamps the baked frame itself, as a stamped copy reads, and leaves it unmasked")
    void nonAnimatingFoilStampsTheBakedFrame() {
        for (boolean masked : new boolean[]{ false, true }) {
            PixelBuffer frame = halfMasked(base(), masked);
            PixelBuffer copied = GlintKit.applyGlintAtTimes(halfMasked(base(), masked), glintTexture(),
                new long[]{ 0L }, GlintKit.GlintOptions.itemDefault(30), null).getFirst();

            RasterPass.Finish.Result result = GlintKit.Foil.item(context(), true, false, 30)
                .finish(frames(frame), new Timeline.Static(0));

            assertThat(result.frames().getFirst(), is(sameInstance(frame)));
            assertThat("masked " + masked, frame.data(), is(copied.data()));
            assertThat(frame.mask().isPresent(), is(false));
        }
    }

    @Test
    @DisplayName("an animated subject's frames are stamped themselves, each as a stamped copy reads")
    void animatedSubjectFramesAreStampedThemselves() {
        Timeline.TickLoop timeline = new Timeline.TickLoop(0, 2, 40, 100);
        PixelBuffer first = halfMasked(base(), true);
        PixelBuffer second = base();
        ConcurrentList<PixelBuffer> frames = frames(first, second);

        GlintKit.Foil.item(context(), true, true, 30).finish(frames, timeline);

        assertThat(frames.getFirst(), is(sameInstance(first)));
        assertThat(frames.getLast(), is(sameInstance(second)));
        for (int f = 0; f < 2; f++) {
            PixelBuffer copied = GlintKit.applyGlintAtTimes(f == 0 ? halfMasked(base(), true) : base(), glintTexture(),
                new long[]{ GlintKit.glintTimeAt(timeline.millisAt(f)) }, GlintKit.GlintOptions.itemDefault(30), null).getFirst();
            assertThat("frame " + f, frames.get(f).data(), is(copied.data()));
            assertThat(frames.get(f).mask().isPresent(), is(false));
        }
    }

    // ---- the stamp against a frozen copy of its per-pixel loop ---------------------------------

    @Test
    @DisplayName("the fade table holds Math.round(c * 0.75f) for every channel 0-255")
    void fadeTableHoldsTheRoundedProduct() {
        for (int c = 0; c <= 255; c++)
            assertThat("channel " + c, GlintKit.fade(c), is(Math.round(c * FROZEN_GLINT_ALPHA)));
    }

    @Test
    @DisplayName("every stamped frame matches the frozen per-pixel loop, at every glint and canvas size")
    void stampMatchesTheFrozenLoop() {
        int[][] glintSizes = { { 128, 128 }, { 96, 48 }, { 12, 20 }, { 128, 48 }, { 12, 16 } };
        int[][] baseSizes = { { 24, 20 }, { 24, 40 }, { 100, 60 } };
        GlintKit.GlintOptions[] presets = {
            GlintKit.GlintOptions.itemDefault(30),
            GlintKit.GlintOptions.armorDefault(30),
            // A negative scale sends both axes negative, across several wraps of the texture.
            new GlintKit.GlintOptions(30, 60, GlintKit.ITEM_GLINT_TEXTURE_ID, -2.75f, false,
                GlintKit.VANILLA_U_LOOP_MILLIS, GlintKit.VANILLA_V_LOOP_MILLIS, ColorMath.WHITE),
            // A scale this large saturates the (int) floor, so x0 + 1 overflows.
            new GlintKit.GlintOptions(30, 60, GlintKit.ITEM_GLINT_TEXTURE_ID, 3.0e9f, false,
                GlintKit.VANILLA_U_LOOP_MILLIS, GlintKit.VANILLA_V_LOOP_MILLIS, ColorMath.WHITE)
        };
        GlintKit.SpriteUv[] rects = {
            null,
            new GlintKit.SpriteUv(0.25f, 0.5f, 0.265625f, 0.53125f),
            // Spans that are not powers of two, so the lerp across the rect rounds.
            new GlintKit.SpriteUv(0.1f, 0.2f, 0.37f, 0.61f)
        };
        long[] times = { 0L, 1L, 1_234L, 29_999L, 37_000L, 109_999L, -1L, -1_234L, -250_001L };

        for (int[] baseSize : baseSizes) {
            for (int[] size : glintSizes) {
                PixelBuffer glint = noisyGlint(size[0], size[1]);
                for (GlintKit.GlintOptions options : presets) {
                    for (GlintKit.SpriteUv rect : rects) {
                        for (boolean masked : new boolean[]{ false, true }) {
                            PixelBuffer base = halfMasked(noisyBase(baseSize[0], baseSize[1]), masked);
                            ConcurrentList<PixelBuffer> frames = GlintKit.applyGlintAtTimes(base, glint, times, options, rect);
                            assertThat(frames.size(), is(times.length));

                            for (int f = 0; f < times.length; f++) {
                                PixelBuffer expected = base.copy();
                                frozenStamp(expected, glint, times[f], options, rect, base.mask().orElse(null));
                                long time = times[f];
                                assertArrayEquals(expected.data(), frames.get(f).data(), () -> "canvas %dx%d, glint %dx%d, scale %s, rect %s, masked %s, time %d"
                                    .formatted(baseSize[0], baseSize[1], size[0], size[1], options.textureScale(), rect, masked, time));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("an empty glint texture fails in floorMod rather than masking as a power of two")
    void emptyGlintIsNoPowerOfTwo() {
        PixelBuffer empty = PixelBuffer.ofStraight(new int[0], 0, 4);
        assertThrows(ArithmeticException.class, () -> GlintKit.applyGlintAtTimes(noisyBase(), empty,
            new long[]{ 0L }, GlintKit.GlintOptions.itemDefault(30), null));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Stamps a target through the per-pixel loop as it read with the rotated coordinate computed whole
     * at each pixel, a {@code Math.floorMod} on each of the sampler's four wraps and the fade rounded
     * inline, kept verbatim so the shipped stamp is held to it byte for byte.
     *
     * @param target the buffer stamped in place
     * @param glint the glint texture
     * @param glintTimeMillis the glint clock the scroll is read at
     * @param options the glint's scale, loops and atlas fold
     * @param rect the sprite's atlas sub-rect, or {@code null} for the offline icon coordinate
     * @param glintMask the pixels the foil is restricted to, or {@code null} for every opaque pixel
     */
    private static void frozenStamp(@NotNull PixelBuffer target, @NotNull PixelBuffer glint, long glintTimeMillis,
                                    @NotNull GlintKit.GlintOptions options, @Nullable GlintKit.SpriteUv rect,
                                    @Nullable PixelMask glintMask) {
        int w = target.width();
        int h = target.height();
        int glintW = glint.width();
        int glintH = glint.height();

        float u = (float) Math.floorMod(glintTimeMillis, options.uLoopMillis()) / options.uLoopMillis();
        float v = (float) Math.floorMod(glintTimeMillis, options.vLoopMillis()) / options.vLoopMillis();
        boolean itemAtlas = rect == null && options.atlasSampled();
        float scaleX = itemAtlas ? options.textureScale() * (16f / 1024f) : options.textureScale();
        float scaleY = itemAtlas ? options.textureScale() * (16f / 512f) : options.textureScale();
        float cos = (float) Math.cos(0.17453292f);
        float sin = (float) Math.sin(0.17453292f);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dst = target.getPixel(x, y);
                if (ColorMath.alpha(dst) == 0) continue;
                if (glintMask != null && !glintMask.marked(x, y)) continue;

                float s = (x + 0.5f) / w;
                float t = (y + 0.5f) / h;
                float uv0x = rect == null ? s : rect.u0() + s * (rect.u1() - rect.u0());
                float uv0y = rect == null ? t : rect.v0() + t * (rect.v1() - rect.v0());

                float sx = uv0x * scaleX;
                float sy = uv0y * scaleY;
                float rx = cos * sx - sin * sy;
                float ry = sin * sx + cos * sy;
                float tcx = rx - u;
                float tcy = ry + v;

                int glintPixel = frozenSample(glint, tcx * glintW, tcy * glintH);
                if (ColorMath.alpha(glintPixel) < 26) continue;

                int faded = ColorMath.pack(
                    ColorMath.alpha(glintPixel),
                    Math.round(ColorMath.red(glintPixel) * FROZEN_GLINT_ALPHA),
                    Math.round(ColorMath.green(glintPixel) * FROZEN_GLINT_ALPHA),
                    Math.round(ColorMath.blue(glintPixel) * FROZEN_GLINT_ALPHA));
                target.setPixel(x, y, ColorMath.blend(faded, dst, BlendMode.QUADRATIC_ADD));
            }
        }
    }

    /**
     * Samples the glint bilinearly with {@code GL_REPEAT} wrapping, all four texels wrapped by
     * {@code Math.floorMod}, as the frozen stamp reads it.
     *
     * @param texture the glint texture
     * @param texelX the sample X in texel units
     * @param texelY the sample Y in texel units
     * @return the interpolated ARGB sample
     */
    private static int frozenSample(@NotNull PixelBuffer texture, float texelX, float texelY) {
        int w = texture.width();
        int h = texture.height();
        float fx = texelX - 0.5f;
        float fy = texelY - 0.5f;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float dx = fx - x0;
        float dy = fy - y0;
        int xa = Math.floorMod(x0, w);
        int xb = Math.floorMod(x0 + 1, w);
        int ya = Math.floorMod(y0, h);
        int yb = Math.floorMod(y0 + 1, h);

        int p00 = texture.getPixel(xa, ya);
        int p10 = texture.getPixel(xb, ya);
        int p01 = texture.getPixel(xa, yb);
        int p11 = texture.getPixel(xb, yb);
        return ColorMath.pack(
            frozenBilerp(ColorMath.alpha(p00), ColorMath.alpha(p10), ColorMath.alpha(p01), ColorMath.alpha(p11), dx, dy),
            frozenBilerp(ColorMath.red(p00), ColorMath.red(p10), ColorMath.red(p01), ColorMath.red(p11), dx, dy),
            frozenBilerp(ColorMath.green(p00), ColorMath.green(p10), ColorMath.green(p01), ColorMath.green(p11), dx, dy),
            frozenBilerp(ColorMath.blue(p00), ColorMath.blue(p10), ColorMath.blue(p01), ColorMath.blue(p11), dx, dy)
        );
    }

    /**
     * Interpolates one channel across the four corner texels, as the frozen sampler does.
     *
     * @param c00 the channel at the top-left texel
     * @param c10 the channel at the top-right texel
     * @param c01 the channel at the bottom-left texel
     * @param c11 the channel at the bottom-right texel
     * @param dx the fraction across
     * @param dy the fraction down
     * @return the interpolated channel, clamped to 0-255
     */
    private static int frozenBilerp(int c00, int c10, int c01, int c11, float dx, float dy) {
        float top = c00 + (c10 - c00) * dx;
        float bottom = c01 + (c11 - c01) * dx;
        return Math.clamp(Math.round(top + (bottom - top) * dy), 0, 255);
    }

    /**
     * Builds a glint texture of seeded noise on every channel, so a texel read from the wrong column or
     * row shows; about one texel in eight is near-transparent, so the discard below alpha 26 fires too.
     *
     * @param width the texture width
     * @param height the texture height
     * @return the texture
     */
    private static @NotNull PixelBuffer noisyGlint(int width, int height) {
        Random random = new Random(31L * width + height);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int alpha = random.nextInt(8) == 0 ? random.nextInt(48) : 255;
            pixels[i] = ColorMath.pack(alpha, random.nextInt(256), random.nextInt(256), random.nextInt(256));
        }
        return PixelBuffer.ofStraight(pixels, width, height);
    }

    /**
     * Builds a 24x20 base of seeded noise, as {@link #noisyBase(int, int)} does.
     *
     * @return the base
     */
    private static @NotNull PixelBuffer noisyBase() {
        return noisyBase(24, 20);
    }

    /**
     * Builds a base of seeded noise: about one pixel in six transparent with colour left in it, the rest
     * of varied alpha and colour, some bright enough that the additive blend clamps.
     *
     * @param width the base width
     * @param height the base height
     * @return the base
     */
    private static @NotNull PixelBuffer noisyBase(int width, int height) {
        Random random = new Random(0x6C1A7L);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int alpha = random.nextInt(6) == 0 ? 0 : 1 + random.nextInt(255);
            pixels[i] = ColorMath.pack(alpha, random.nextInt(256), random.nextInt(256), random.nextInt(256));
        }
        return PixelBuffer.ofStraight(pixels, width, height);
    }

    /** Marks the left half of a buffer's coverage mask, so a foil restricted to it stamps only there. */
    private static @NotNull PixelBuffer halfMasked(@NotNull PixelBuffer buffer, boolean masked) {
        if (!masked) return buffer;

        buffer.enableMask();
        for (int y = 0; y < buffer.height(); y++)
            for (int x = 0; x < buffer.width() / 2; x++)
                buffer.mask().orElseThrow().mark(x, y);
        return buffer;
    }

    /** A context serving a small non-uniform texture as the item glint. */
    private static @NotNull RendererContext context() {
        return RendererContext.builder()
            .textures(Map.of(GlintKit.ITEM_GLINT_TEXTURE_ID, glintTexture()))
            .build();
    }

    /** A small non-uniform glint texture so a scrolled sample visibly changes across phases. */
    private static @NotNull PixelBuffer glintTexture() {
        int size = 16;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                pixels[y * size + x] = ColorMath.pack(255, (x * 16) & 0xFF, (y * 16) & 0xFF, ((x + y) * 8) & 0xFF);
        return PixelBuffer.ofStraight(pixels, size, size);
    }

    /** A flat dark opaque glintable base - dark so the additive glint blend does not clamp to white. */
    private static @NotNull PixelBuffer base() {
        PixelBuffer buffer = PixelBuffer.canvas(8, 8);
        fillDark(buffer);
        return buffer;
    }

    /** Fills the whole buffer with the flat dark opaque colour {@link #base()} carries. */
    private static void fillDark(@NotNull PixelBuffer target) {
        int dark = ColorMath.pack(255, 32, 32, 40);
        for (int y = 0; y < target.height(); y++)
            for (int x = 0; x < target.width(); x++)
                target.setPixel(x, y, dark);
    }

    /** Wraps the given buffers as a mutable frame list. */
    private static @NotNull ConcurrentList<PixelBuffer> frames(@NotNull PixelBuffer... buffers) {
        ConcurrentList<PixelBuffer> list = Concurrent.newList();
        for (PixelBuffer buffer : buffers) list.add(buffer);
        return list;
    }

    /** Counts pixels that differ between two equal-size buffers. */
    private static int differingPixels(@NotNull PixelBuffer a, @NotNull PixelBuffer b) {
        int diff = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) diff++;
        return diff;
    }
}
