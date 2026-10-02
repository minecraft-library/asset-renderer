package lib.minecraft.renderer.asset.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.frame.Timeline;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * The six {@code deriveTickStrip} fixtures over the real vanilla 26.1 {@code .mcmeta} shapes: a
 * bare frame list, a bare frametime, an interpolating source clamped to cadence 1, a loop capped at
 * 200 ticks, and two sources reconciled over an LCM loop and a GCD cadence.
 */
@DisplayName("Flipbook tick-strip derivation")
class FlipbookTickStripTest {

    @Test
    @DisplayName("derive: no sources -> Static at the start tick")
    void deriveEmptyIsStatic() {
        Timeline.TickTimeline timeline = Flipbook.deriveTickStrip(List.of(), 0);
        assertThat(timeline, is(instanceOf(Timeline.Static.class)));
        assertThat(timeline.tickAt(0), is(0));
    }

    @Test
    @DisplayName("derive fire: 32 bare frames -> 32 frames at cadence 1")
    void deriveFire() {
        assertTickLoop(Flipbook.deriveTickStrip(List.of(flipbook(32, fire())), 0),
            0, 32, 1, 50);
    }

    @Test
    @DisplayName("derive water_still: frametime 2 -> 32 frames at cadence 2")
    void deriveWater() {
        assertTickLoop(Flipbook.deriveTickStrip(List.of(flipbook(32, waterStill())), 0),
            0, 32, 2, 100);
    }

    @Test
    @DisplayName("derive magma: interpolate clamps cadence to 1 (loop 24 -> 24 frames)")
    void deriveMagma() {
        assertTickLoop(Flipbook.deriveTickStrip(List.of(flipbook(3, magma())), 0),
            0, 24, 1, 50);
    }

    @Test
    @DisplayName("derive prismarine: 6600-tick loop capped at 200 ticks (cadence 1 -> 200 frames)")
    void derivePrismarine() {
        assertTickLoop(Flipbook.deriveTickStrip(List.of(flipbook(4, prismarine())), 0),
            0, Timeline.MAX_LOOP_TICKS, 1, 50);
    }

    @Test
    @DisplayName("derive multi-texture: LCM loop over GCD cadence (lcm 24, gcd 2 -> 12 frames)")
    void deriveMultiTexture() {
        assertTickLoop(Flipbook.deriveTickStrip(List.of(
            flipbook(4, implicit(2)),
            flipbook(3, implicit(4))), 0), 0, 12, 2, 100);
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

    /**
     * Resolves an animation over a one-pixel-wide strip of the given frame count - the frame height
     * falls back to the strip's width, so a strip {@code frameCount} tall holds exactly that many.
     */
    private static @NotNull Flipbook flipbook(int frameCount, @NotNull MCMeta.Animation animation) {
        return Flipbook.of(PixelBuffer.create(1, frameCount), animation).orElseThrow();
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
