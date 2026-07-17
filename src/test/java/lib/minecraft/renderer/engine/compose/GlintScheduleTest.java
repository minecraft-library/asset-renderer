package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.kit.GlintKit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Pins the glint schedule bridge: the exact sample instant of a frame, converted to the glint time
 * {@link GlintKit#applyGlintAtTimes} consumes via {@link GlintKit#glintTimeAt}.
 * <p>
 * The load-bearing invariant is the {@code * 8.0} speed factor. {@code applyGlintAtTimes} takes
 * "vanilla post-{@code glintSpeed} millis" - a tick's wall-clock instant {@code tick * 50} must be
 * fed {@code tick * 50 * 8.0}, and an fps loop's instant {@code f * (1000.0 / fps)} must be fed that
 * times 8.0 - both through the one {@code glintTimeAt} fold, byte-identical to the literal formulas.
 * The {@link GlintKit.Foil} finish then consumes a whole {@link Timeline}: an animated subject stamps
 * per frame, a static subject swaps to the glint's own {@link Timeline.FpsLoop}.
 */
class GlintScheduleTest {

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
        assertThat(GlintKit.MAX_ENCHANTMENT_GLINT_SPEED_MILLIS, is(8.0));
    }

    // ---- Foil consumes a Timeline --------------------------------------------------------------

    @Test
    @DisplayName("static subject (1-frame timeline): Foil swaps playback to its own FpsLoop")
    void scrollDirectionSwapsToFpsLoop() {
        GlintKit.Foil foil = GlintKit.Foil.item(resolver(), true, true, 30);
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
        GlintKit.Foil foil = GlintKit.Foil.item(resolver(), true, false, 30);
        ConcurrentList<PixelBuffer> frames = frames(base());
        RasterPass.Finish.Result result = foil.finish(frames, new Timeline.Static(0));

        assertThat(result.frames().size(), is(1));
        assertThat(result.playback(), is(instanceOf(Timeline.FpsLoop.class)));
    }

    @Test
    @DisplayName("animated subject (N-frame timeline): Foil stamps in place on the baking timeline")
    void stampDirectionInPlace() {
        GlintKit.Foil foil = GlintKit.Foil.item(resolver(), true, true, 30);
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
        GlintKit.Foil foil = GlintKit.Foil.item(resolver(), false, true, 30);
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
        GlintKit.Foil.item(resolver(), true, true, 30).finish(scrolling, timeline);
        assertThat(differingPixels(scrolling.get(0), scrolling.get(1)), greaterThan(0));

        ConcurrentList<PixelBuffer> frozen = frames(base(), base());
        GlintKit.Foil.item(resolver(), true, false, 30).finish(frozen, timeline);
        assertThat(differingPixels(frozen.get(0), frozen.get(1)), is(0));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A resolver that always returns a small non-uniform glint texture. */
    private static GlintKit.TextureResolver resolver() {
        PixelBuffer glint = glintTexture();
        return id -> Optional.of(glint);
    }

    /** A small non-uniform glint texture so a scrolled sample visibly changes across phases. */
    private static @NotNull PixelBuffer glintTexture() {
        int size = 16;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                pixels[y * size + x] = ColorMath.pack(255, (x * 16) & 0xFF, (y * 16) & 0xFF, ((x + y) * 8) & 0xFF);
        return PixelBuffer.of(pixels, size, size);
    }

    /** A flat dark opaque glintable base - dark so the additive glint blend does not clamp to white. */
    private static @NotNull PixelBuffer base() {
        PixelBuffer buffer = PixelBuffer.create(8, 8);
        int dark = ColorMath.pack(255, 32, 32, 40);
        for (int y = 0; y < buffer.height(); y++)
            for (int x = 0; x < buffer.width(); x++)
                buffer.setPixel(x, y, dark);
        return buffer;
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
