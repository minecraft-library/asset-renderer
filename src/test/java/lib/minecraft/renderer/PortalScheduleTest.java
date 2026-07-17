package lib.minecraft.renderer;

import lib.minecraft.renderer.engine.compose.Timeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Pins the end-portal / end-gateway animation schedule as reproduced by {@link Timeline#gameTime}:
 * frame {@code f} samples the raw game tick {@code startTick + f * ticksPerFrame} and plays back for
 * a fixed real-time tick ({@code 50 ms}), a single-frame render samples one tick, and the raw ticks
 * the schedule emits feed the shader's day-fraction fold unchanged.
 * <p>
 * The day-fraction chain identity confirms that {@code bakeFace}'s
 * {@code floorMod(gameTick, 24000) / 24000f} over the schedule's raw ticks matches vanilla's
 * {@code ((gameTime % 24000) + partialTick) / 24000f} at {@code partialTick = 0}, including a case
 * that wraps past the 24 000-tick day period - so the fold can stay sampler-side while the timeline
 * emits only raw ticks.
 */
class PortalScheduleTest {

    /** The 24 000-tick vanilla day period the shader's GameTime uniform wraps over. */
    private static final int GAME_TIME_PERIOD_TICKS = 24_000;

    @Test
    @DisplayName("strip schedule: frame f samples startTick + f * tpf, every frame plays 50 ms")
    void stripSchedule() {
        assertStrip(0, 8, 120);
        assertStrip(13, 3, 40);
    }

    @Test
    @DisplayName("static schedule: one frame sampled at the start tick")
    void staticSchedule() {
        Timeline.TickTimeline timeline = Timeline.gameTime(5, 1, 8);
        assertThat(timeline, is(instanceOf(Timeline.Static.class)));
        assertThat(timeline.frames(), is(1));
        assertThat(timeline.tickAt(0), is(5));
    }

    @Test
    @DisplayName("day-fraction chain: floorMod(tick, 24000) / 24000f matches vanilla at partial 0, incl. wrap-around")
    void dayFractionChainIdentity() {
        // startTick + f * tpf crosses the 24 000-tick day period.
        Timeline.TickTimeline timeline = Timeline.gameTime(23_990, 20, 4);
        for (int f = 0; f < timeline.frames(); f++) {
            int tick = timeline.tickAt(f);
            float portalFold = Math.floorMod(tick, GAME_TIME_PERIOD_TICKS) / (float) GAME_TIME_PERIOD_TICKS;
            float vanillaAtPartialZero = ((tick % 24_000L) + 0f) / 24_000f;
            assertThat(portalFold, is(vanillaAtPartialZero));
        }
    }

    /** Asserts the game-time schedule emits {@code startTick + f * tpf} ticks at a fixed 50 ms delay. */
    private static void assertStrip(int startTick, int ticksPerFrame, int outputCount) {
        Timeline.TickTimeline timeline = Timeline.gameTime(startTick, outputCount, ticksPerFrame);
        assertThat(timeline, is(instanceOf(Timeline.TickLoop.class)));
        assertThat(timeline.frames(), is(outputCount));
        for (int f = 0; f < outputCount; f++) {
            assertThat(timeline.tickAt(f), is(startTick + f * ticksPerFrame));
            assertThat(timeline.delayMs(f), is(Timeline.MILLIS_PER_TICK));
        }
    }
}
