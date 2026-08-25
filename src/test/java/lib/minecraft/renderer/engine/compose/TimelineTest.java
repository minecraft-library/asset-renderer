package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.AnimationOptions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The frame-generation contract of every {@link Timeline} permit: the per-frame
 * {@code (tick, millis, delay, playbackMs)} tuples, the tick/millisecond identities, the
 * {@code FpsLoop} double-op sample instant pinned against its literal formula, the
 * {@code deriveTickStrip} fixtures over the real vanilla 26.1
 * {@code .mcmeta} shapes, and the {@code bake} / {@code wrap} terminals including the trimming and
 * playback-swapping {@code Finish} seams.
 */
@DisplayName("Timeline permits, factories and bake/wrap terminals")
class TimelineTest {

    // ---- constants + conversions ---------------------------------------------------------------

    @Test
    @DisplayName("the 200-tick / 10 000-ms loop cap is one identity")
    void loopCapIdentity() {
        assertThat(Timeline.MAX_LOOP_TICKS * (long) Timeline.MILLIS_PER_TICK, is(Timeline.MAX_LOOP_MS));
        assertThat(Timeline.MAX_LOOP_MS, is(10_000L));
    }

    @Test
    @DisplayName("ticks per second is the reciprocal of the 50 ms tick")
    void ticksPerSecondIsTheTickReciprocal() {
        assertThat(Timeline.TICKS_PER_SECOND, is(20));
    }

    @Test
    @DisplayName("delayForFps rounds, including the non-divisor 30 -> 33 case")
    void delayForFpsTable() {
        assertThat(Timeline.delayForFps(20), is(50));
        assertThat(Timeline.delayForFps(24), is(42));
        assertThat(Timeline.delayForFps(30), is(33));
        assertThat(Timeline.delayForFps(60), is(17));
    }

    @Test
    @DisplayName("lcm divides before multiplying and propagates a zero operand")
    void lcmDividesBeforeMultiplying() {
        assertThat(Timeline.lcm(8, 12), is(24L));
        assertThat(Timeline.lcm(0, 5), is(0L));
        assertThat(Timeline.lcm(5, 0), is(0L));
    }

    // ---- Static --------------------------------------------------------------------------------

    @Test
    @DisplayName("Static: one frame at its tick, no delay")
    void staticTuple() {
        Timeline.Static timeline = new Timeline.Static(7);
        assertThat(timeline.frames(), is(1));
        assertThat(timeline.tickAt(0), is(7));
        assertThat(timeline.millisAt(0), is(350.0));
        assertThat(timeline.delayMs(0), is(0));
        assertThat(timeline.playbackMsAt(0), is(0L));
    }

    @Test
    @DisplayName("Static.ZERO samples tick 0 (tickAt(0) == startTick)")
    void staticZero() {
        assertThat(Timeline.Static.ZERO.startTick(), is(0));
        assertThat(Timeline.Static.ZERO.tickAt(0), is(0));
    }

    // ---- TickLoop ------------------------------------------------------------------------------

    @Test
    @DisplayName("TickLoop: uniform tick cadence, uniform delay, accumulating playback offset")
    void tickLoopTuple() {
        Timeline.TickLoop timeline = new Timeline.TickLoop(5, 4, 3, 100);
        assertThat(timeline.frames(), is(4));
        assertThat(timeline.tickAt(0), is(5));   // tickAt(0) == startTick
        int[] expectedTicks = {5, 8, 11, 14};
        double[] expectedMillis = {250.0, 400.0, 550.0, 700.0};
        long[] expectedPlayback = {0, 100, 200, 300};
        for (int f = 0; f < 4; f++) {
            assertThat(timeline.tickAt(f), is(expectedTicks[f]));
            assertThat(timeline.millisAt(f), is(expectedMillis[f]));
            assertThat(timeline.delayMs(f), is(100));
            assertThat(timeline.playbackMsAt(f), is(expectedPlayback[f]));
        }
    }

    @Test
    @DisplayName("tick permits sit on the 50 ms lattice: millisAt(f) == tickAt(f) * 50.0")
    void tickLatticeIdentity() {
        Timeline.TickLoop timeline = new Timeline.TickLoop(3, 6, 2, 100);
        for (int f = 0; f < timeline.frames(); f++)
            assertThat(timeline.millisAt(f), is(timeline.tickAt(f) * 50.0));
    }

    @Test
    @DisplayName("the game-time factory loops over ticks, and stills at a single frame")
    void gameTimeForksOnFrameCount() {
        assertThat(Timeline.gameTime(0, 8, 120), is(instanceOf(Timeline.TickLoop.class)));
        assertThat(Timeline.gameTime(5, 1, 8), is(instanceOf(Timeline.Static.class)));
    }

    // ---- ageInTicks (the off-lattice view) ------------------------------------------------------

    @Test
    @DisplayName("ageInTicks on a tick permit is exactly its tickAt, for every permit")
    void ageInTicksIsExactOnTickPermits() {
        // The lattice identity above makes this exact rather than approximate: tick * 50.0 is an exact
        // integer product well inside a double's mantissa, and dividing it back by 50 recovers the tick
        // with no rounding. Asserted as float equality on purpose - a tolerance here would hide the
        // narrowing this view would otherwise be free to introduce.
        List<Timeline.TickTimeline> permits = List.of(
            new Timeline.Static(7),
            new Timeline.TickLoop(3, 6, 2, 100),
            new Timeline.TickLoop(0, 64, 375, Timeline.MILLIS_PER_TICK));

        for (Timeline.TickTimeline timeline : permits)
            for (int f = 0; f < timeline.frames(); f++)
                assertThat(timeline.ageInTicks(f), is((float) timeline.tickAt(f)));
    }

    @Test
    @DisplayName("ageInTicks falls between ticks on a wall-rate loop, which has no tickAt")
    void ageInTicksIsFractionalOnFpsLoop() {
        Timeline.FpsLoop timeline = new Timeline.FpsLoop(30, 6);
        for (int f = 0; f < timeline.frames(); f++)
            assertThat(timeline.ageInTicks(f), is((float) (f * (1000.0 / 30) / Timeline.MILLIS_PER_TICK)));
        // The value the whole view exists for: a frame sitting off the tick lattice.
        float age = timeline.ageInTicks(1);
        assertThat(age, is(0.6666667f));
        assertThat(age == Math.rint(age), is(false));
    }

    @Test
    @DisplayName("ageInTicks reads the millisecond axis, so it tracks millisAt on every schedule")
    void ageInTicksTracksMillisAt() {
        List<Timeline> schedules = List.of(
            new Timeline.Static(7),
            new Timeline.TickLoop(3, 6, 2, 100),
            new Timeline.FpsLoop(24, 5));
        for (Timeline timeline : schedules)
            for (int f = 0; f < timeline.frames(); f++)
                assertThat(timeline.ageInTicks(f), is((float) (timeline.millisAt(f) / Timeline.MILLIS_PER_TICK)));
    }

    // ---- SubTickLoop (sampling between ticks) ---------------------------------------------------

    @Test
    @DisplayName("one sub-tick step is the whole-tick schedule itself, not a copy of it")
    void subTickStepOneIsTheWholeTickSchedule() {
        // Identity, not equivalence: the default path must reach the exact same object shape, so a
        // caller who never asks for subdivision cannot be moved by its arithmetic.
        assertThat(Timeline.gameTime(5, 8, 3, 1), is(Timeline.gameTime(5, 8, 3)));
        assertThat(Timeline.gameTime(5, 8, 3, 0), is(Timeline.gameTime(5, 8, 3)));
        assertThat(Timeline.gameTime(5, 8, 3, -2), is(Timeline.gameTime(5, 8, 3)));
        assertThat(Timeline.gameTime(5, 8, 3, 4), is(instanceOf(Timeline.SubTickLoop.class)));
    }

    @Test
    @DisplayName("a still has no motion to sample finely, so it stays a Static however subdivided")
    void subTickLeavesAStillAlone() {
        assertThat(Timeline.gameTime(7, 1, 4, 8), is(instanceOf(Timeline.Static.class)));
        assertThat(Timeline.gameTime(7, 1, 4, 8).frames(), is(1));
    }

    @Test
    @DisplayName("subdividing samples between ticks over the same span at the same speed")
    void subTickSubdividesTheSameSpan() {
        Timeline whole = Timeline.gameTime(0, 4, 1);
        Timeline fine = Timeline.gameTime(0, 4, 1, 4);

        assertThat(fine.frames(), is(16));
        // Quarter-tick steps: the whole-tick instants still appear, with three new ones between each.
        for (int f = 0; f < fine.frames(); f++)
            assertThat(fine.ageInTicks(f), is(f * 0.25f));
        for (int f = 0; f < whole.frames(); f++)
            assertThat(fine.ageInTicks(f * 4), is(whole.ageInTicks(f)));

        // Same wall-clock span, so the loop plays at the same speed - only more finely sampled. 50 ms
        // does not divide into 4 evenly, so the leftover 2 ms go to the first frames of each tick.
        assertThat(fine.delayMs(0), is(13));
        assertThat(fine.delayMs(1), is(13));
        assertThat(fine.delayMs(2), is(12));
        assertThat(fine.delayMs(3), is(12));
        assertThat(fine.playbackMsAt(4), is((long) Timeline.MILLIS_PER_TICK));
        assertThat(fine.playbackMsAt(fine.frames()), is(whole.playbackMsAt(whole.frames())));
    }

    @Test
    @DisplayName("a step count that does not divide the tick evenly rounds the delay, and says so")
    void subTickRoundsAnUnevenDelay() {
        // 50 / 3 is 16.67 ms, which no whole-millisecond delay can carry. Spreading the leftover keeps
        // each tick exactly 50 ms - 17 / 17 / 16 - where a rounded 17 / 17 / 17 would stretch the loop.
        Timeline fine = Timeline.gameTime(0, 2, 1, 3);
        assertThat(fine.frames(), is(6));
        assertThat(fine.delayMs(0), is(17));
        assertThat(fine.delayMs(1), is(17));
        assertThat(fine.delayMs(2), is(16));
        assertThat(fine.playbackMsAt(3), is((long) Timeline.MILLIS_PER_TICK));
        assertThat(fine.playbackMsAt(6), is(2L * Timeline.MILLIS_PER_TICK));
        assertThat(fine.ageInTicks(1), is(1f / 3f));
    }

    @Test
    @DisplayName("SubTickLoop is not a tick timeline, having no honest tick to report")
    void subTickLoopIsNotTickNative() {
        Timeline fine = Timeline.gameTime(0, 4, 1, 4);
        assertThat(fine instanceof Timeline.TickTimeline, is(false));
        // It still sits on the millisecond axis every schedule shares.
        assertThat(fine.millisAt(1), is(0.25 * Timeline.MILLIS_PER_TICK));
    }

    // ---- FpsLoop -------------------------------------------------------------------------------

    @Test
    @DisplayName("FpsLoop: exact double sample instant, rounded playback delay, no tick view")
    void fpsTuple() {
        Timeline.FpsLoop timeline = new Timeline.FpsLoop(30, 6);
        assertThat(timeline.frames(), is(6));
        for (int f = 0; f < 6; f++) {
            assertThat(timeline.millisAt(f), is(f * (1000.0 / 30)));
            assertThat(timeline.delayMs(f), is(33));
            assertThat(timeline.playbackMsAt(f), is((long) f * 33));
        }
        // A wall-rate loop has no tick view: it is never a TickTimeline (the seal proves it).
        Timeline asTimeline = timeline;
        assertThat(asTimeline instanceof Timeline.TickTimeline, is(false));
    }

    @Test
    @DisplayName("FpsLoop.millisAt equals the literal f * (1000.0 / fps) bit-for-bit")
    void fpsLoopDoubleOpPin() {
        for (int fps : new int[]{20, 24, 30, 60}) {
            Timeline.FpsLoop timeline = new Timeline.FpsLoop(fps, fps * 2);
            for (int f = 0; f <= fps * 2; f++)
                assertThat(timeline.millisAt(f), is(f * (1000.0 / fps)));
        }
    }

    // ---- schedule (the caller-selected playback fork) -------------------------------------------

    @Test
    @DisplayName("schedule defaults to the authored-rate texture strip")
    void scheduleDefaultsToTextureStrip() {
        AnimationOptions anim = AnimationOptions.builder().frameCount(64).ticksPerFrame(375).build();
        assertThat(Timeline.schedule(anim), is(Timeline.tickStrip(anim)));
        assertTickLoop(Timeline.schedule(anim), 0, 64, 375, 375 * Timeline.MILLIS_PER_TICK);
    }

    @Test
    @DisplayName("schedule GAME_TIME samples the same ticks but plays each back in one tick")
    void scheduleGameTimeCompressesPlayback() {
        // A whole day across the clock's 64 faces: the same 375-tick sampling cadence either way, but
        // the strip would hold each frame for the 18.75 s of world time it covers - a 20-minute loop -
        // where the game-time schedule plays the day out in 3.2 s.
        AnimationOptions anim = AnimationOptions.builder()
            .frameCount(64).ticksPerFrame(375).schedule(AnimationOptions.Schedule.GAME_TIME).build();
        assertTickLoop(Timeline.schedule(anim), 0, 64, 375, Timeline.MILLIS_PER_TICK);

        Timeline.TickTimeline strip = Timeline.tickStrip(anim);
        Timeline.TickTimeline compressed = Timeline.schedule(anim);
        for (int frame = 0; frame < 64; frame++)
            assertThat(compressed.tickAt(frame), is(strip.tickAt(frame)));
        assertThat(compressed.playbackMsAt(64), is(3_200L));
    }

    @Test
    @DisplayName("schedule GAME_TIME normalizes a single frame to a Static, like the strip does")
    void scheduleGameTimeSingleFrameIsStatic() {
        AnimationOptions anim = AnimationOptions.builder()
            .startTick(7).schedule(AnimationOptions.Schedule.GAME_TIME).build();
        Timeline.TickTimeline timeline = Timeline.schedule(anim);
        assertThat(timeline, is(instanceOf(Timeline.Static.class)));
        assertThat(timeline.tickAt(0), is(7));
    }

    @Test
    @DisplayName("the 200-tick derive cap does not clip an explicit day-long strip")
    void scheduleIsNotClippedByDeriveCap() {
        // MAX_LOOP_TICKS bounds derivation from .mcmeta sidecars, not a schedule a caller states
        // outright - a 64 x 375 day spans 24 000 ticks, two orders past the cap.
        AnimationOptions anim = AnimationOptions.builder()
            .frameCount(64).ticksPerFrame(375).schedule(AnimationOptions.Schedule.GAME_TIME).build();
        Timeline.TickTimeline timeline = Timeline.schedule(anim);
        assertThat(timeline.frames(), is(64));
        assertThat(timeline.tickAt(63), is(23_625));
        assertThat(timeline.tickAt(63), is(greaterThan(Timeline.MAX_LOOP_TICKS)));
    }

    // ---- deriveTickStrip (the six AUTO fixtures) -----------------------------------------------

    @Test
    @DisplayName("derive: no sources -> Static at the start tick")
    void deriveEmptyIsStatic() {
        Timeline.TickTimeline timeline = Timeline.deriveTickStrip(List.of(), 0);
        assertThat(timeline, is(instanceOf(Timeline.Static.class)));
        assertThat(timeline.tickAt(0), is(0));
    }

    @Test
    @DisplayName("derive fire: 32 bare frames -> 32 frames at cadence 1")
    void deriveFire() {
        assertTickLoop(Timeline.deriveTickStrip(List.of(new Timeline.Source(32, fire())), 0),
            0, 32, 1, 50);
    }

    @Test
    @DisplayName("derive water_still: frametime 2 -> 32 frames at cadence 2")
    void deriveWater() {
        assertTickLoop(Timeline.deriveTickStrip(List.of(new Timeline.Source(32, waterStill())), 0),
            0, 32, 2, 100);
    }

    @Test
    @DisplayName("derive magma: interpolate clamps cadence to 1 (loop 24 -> 24 frames)")
    void deriveMagma() {
        assertTickLoop(Timeline.deriveTickStrip(List.of(new Timeline.Source(3, magma())), 0),
            0, 24, 1, 50);
    }

    @Test
    @DisplayName("derive prismarine: 6600-tick loop capped at 200 ticks (cadence 1 -> 200 frames)")
    void derivePrismarine() {
        assertTickLoop(Timeline.deriveTickStrip(List.of(new Timeline.Source(4, prismarine())), 0),
            0, Timeline.MAX_LOOP_TICKS, 1, 50);
    }

    @Test
    @DisplayName("derive multi-texture: LCM loop over GCD cadence (lcm 24, gcd 2 -> 12 frames)")
    void deriveMultiTexture() {
        assertTickLoop(Timeline.deriveTickStrip(List.of(
            new Timeline.Source(4, implicit(2)),
            new Timeline.Source(3, implicit(4))), 0), 0, 12, 2, 100);
    }

    // ---- terminals -----------------------------------------------------------------------------

    @Test
    @DisplayName("wrap invokes frameAt serially in frame-index order")
    void wrapSerialIndexOrder() {
        List<Integer> indices = new ArrayList<>();
        ImageData result = new Timeline.TickLoop(0, 4, 1, 50).wrap(f -> {
            indices.add(f);
            return PixelBuffer.create(1, 1);
        });
        assertThat(indices, is(List.of(0, 1, 2, 3)));
        assertThat(result, is(instanceOf(AnimatedImageData.class)));
        assertThat(((AnimatedImageData) result).getFrames().size(), is(4));
    }

    @Test
    @DisplayName("wrap of one frame collapses to a StaticImageData")
    void wrapSingleFrameStatic() {
        ImageData result = new Timeline.Static(7).wrap(f -> PixelBuffer.create(2, 2));
        assertThat(result, is(instanceOf(StaticImageData.class)));
    }

    @Test
    @DisplayName("wrap of an empty schedule throws")
    void wrapEmptyThrows() {
        assertThrows(RenderException.class,
            () -> new Timeline.TickLoop(0, 0, 1, 50).wrap(f -> PixelBuffer.create(1, 1)));
    }

    @Test
    @DisplayName("bake rasters one frame per sample tick, in tickAt order")
    void bakeTickSequence() {
        assertThat(recordBakedTicks(new Timeline.TickLoop(5, 4, 3, 100)),
            is(List.of(5, 8, 11, 14)));
    }

    @Test
    @DisplayName("bake of one frame collapses to a StaticImageData")
    void bakeSingleFrameStatic() {
        ImageData result = new Timeline.Static(9).bake(
            RasterPass.of(1, 1, 1, false, (target, tick) -> target.setPixel(0, 0, 0xFFFFFFFF)));
        assertThat(result, is(instanceOf(StaticImageData.class)));
    }

    @Test
    @DisplayName("a trimming Finish keeps only the requested frames on the same timeline")
    void trimmingFinish() {
        int[] receivedBaked = {-1};
        RasterPass pass = RasterPass.of(1, 1, 1, false,
                (target, tick) -> target.setPixel(0, 0, 0xFFFFFFFF))
            .finishing((frames, timeline) -> {
                receivedBaked[0] = frames.size();
                ConcurrentList<PixelBuffer> trimmed = Concurrent.newList();
                for (int i = 0; i < 3; i++) trimmed.add(frames.get(i));
                return new RasterPass.Finish.Result(trimmed, timeline);
            });

        ImageData result = new Timeline.TickLoop(0, 5, 1, 50).bake(pass);
        assertThat(receivedBaked[0], is(5));
        assertThat(result, is(instanceOf(AnimatedImageData.class)));
        assertThat(((AnimatedImageData) result).getFrames().size(), is(3));
    }

    @Test
    @DisplayName("a playback-swapping Finish wraps at the swapped timeline's delays")
    void playbackSwappingFinish() {
        int expanded = 8;
        int fps = 30;
        RasterPass pass = RasterPass.of(1, 1, 1, false,
                (target, tick) -> target.setPixel(0, 0, 0xFFFFFFFF))
            .finishing((frames, timeline) -> {
                ConcurrentList<PixelBuffer> looped = Concurrent.newList();
                for (int i = 0; i < expanded; i++) looped.add(frames.getFirst().copy());
                return new RasterPass.Finish.Result(looped, new Timeline.FpsLoop(fps, expanded));
            });

        ImageData result = Timeline.Static.ZERO.bake(pass);
        assertThat(result, is(instanceOf(AnimatedImageData.class)));
        AnimatedImageData animated = (AnimatedImageData) result;
        assertThat(animated.getFrames().size(), is(expanded));
        // The wrap read its delays from the swapped FpsLoop (delayForFps(30) == 33), not the baking
        // Static timeline (whose delay is 0).
        for (ImageFrame frame : animated.getFrames())
            assertThat(frame.delayMs(), is(Timeline.delayForFps(fps)));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Asserts a derived timeline is the expected {@link Timeline.TickLoop}. */
    private static void assertTickLoop(@NotNull Timeline.TickTimeline timeline,
                                       int startTick, int frameCount, int ticksPerFrame, int delayMs) {
        assertThat(timeline, is(instanceOf(Timeline.TickLoop.class)));
        Timeline.TickLoop loop = (Timeline.TickLoop) timeline;
        assertThat(loop.startTick(), is(startTick));
        assertThat(loop.frameCount(), is(frameCount));
        assertThat(loop.ticksPerFrame(), is(ticksPerFrame));
        assertThat(loop.delayMs(), is(delayMs));
    }

    /** Bakes a stub pass that encodes each frame's tick into pixel (0, 0) and reads them back in order. */
    private static @NotNull List<Integer> recordBakedTicks(@NotNull Timeline.TickTimeline timeline) {
        List<Integer> ticks = new ArrayList<>();
        RasterPass pass = RasterPass.of(1, 1, 1, false,
                (target, tick) -> target.setPixel(0, 0, 0xFF000000 | (tick & 0xFFFF)))
            .finishing((frames, tl) -> {
                for (PixelBuffer frame : frames)
                    ticks.add(frame.getPixel(0, 0) & 0xFFFF);
                return new RasterPass.Finish.Result(frames, tl);
            });
        timeline.bake(pass);
        return ticks;
    }

    /** fire_0.png.mcmeta: 32 bare frame indices [16..31, 0..15], default frametime (1 tick each). */
    private static @NotNull MCMeta.Animation fire() {
        ConcurrentList<MCMeta.Frame> frames = Concurrent.newList();
        for (int i = 16; i <= 31; i++) frames.add(new MCMeta.Frame(i, -1));
        for (int i = 0; i <= 15; i++) frames.add(new MCMeta.Frame(i, -1));
        return new MCMeta.Animation(1, false, -1, -1, frames);
    }

    /** water_still.png.mcmeta: bare {@code frametime: 2}, no frames list. */
    private static @NotNull MCMeta.Animation waterStill() {
        return new MCMeta.Animation(2, false, -1, -1, Concurrent.newList());
    }

    /** magma.png.mcmeta: {@code frametime: 8}, interpolate, frames [0, 1, 2]. */
    private static @NotNull MCMeta.Animation magma() {
        ConcurrentList<MCMeta.Frame> frames = Concurrent.newList();
        frames.add(new MCMeta.Frame(0, -1));
        frames.add(new MCMeta.Frame(1, -1));
        frames.add(new MCMeta.Frame(2, -1));
        return new MCMeta.Animation(8, true, -1, -1, frames);
    }

    /** prismarine.png.mcmeta: {@code frametime: 300}, interpolate, 22 frames -> loop far over the cap. */
    private static @NotNull MCMeta.Animation prismarine() {
        ConcurrentList<MCMeta.Frame> frames = Concurrent.newList();
        int[] seq = {0, 1, 0, 2, 0, 3, 0, 1, 2, 1, 3, 1, 0, 2, 1, 2, 3, 2, 0, 3, 1, 3};
        for (int index : seq) frames.add(new MCMeta.Frame(index, -1));
        return new MCMeta.Animation(300, true, -1, -1, frames);
    }

    /** A frametime-only source with the given tick length and implicit frame count. */
    private static @NotNull MCMeta.Animation implicit(int frametime) {
        return new MCMeta.Animation(frametime, false, -1, -1, Concurrent.newList());
    }
}
