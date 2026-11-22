package lib.minecraft.renderer.call.request;

import lib.minecraft.renderer.engine.frame.RenderLimits;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.exception.RenderException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The schedule an {@link AnimationOptions} asks for - the caller-selected fork between the
 * authored-rate texture strip and the game-time simulation, and the two ways they agree.
 */
@DisplayName("AnimationOptions schedules")
class AnimationOptionsTimelineTest {

    @Test
    @DisplayName("schedule defaults to the authored-rate texture strip")
    void scheduleDefaultsToTextureStrip() {
        AnimationOptions anim = AnimationOptions.builder().frameCount(64).ticksPerFrame(375).build();
        assertThat(anim.timeline(), is(anim.tickStrip()));
        assertTickLoop(anim.timeline(), 0, 64, 375, 375 * Timeline.MILLIS_PER_TICK);
    }

    @Test
    @DisplayName("schedule GAME_TIME samples the same ticks but plays each back in one tick")
    void scheduleGameTimeCompressesPlayback() {
        // A whole day across the clock's 64 faces: the same 375-tick sampling cadence either way, but
        // the strip would hold each frame for the 18.75 s of world time it covers - a 20-minute loop -
        // where the game-time schedule plays the day out in 3.2 s.
        AnimationOptions anim = AnimationOptions.builder()
            .frameCount(64).ticksPerFrame(375).schedule(AnimationOptions.Schedule.GAME_TIME).build();
        assertTickLoop(anim.timeline(), 0, 64, 375, Timeline.MILLIS_PER_TICK);

        Timeline.TickTimeline strip = anim.tickStrip();
        Timeline.TickTimeline compressed = anim.timeline();
        for (int frame = 0; frame < 64; frame++)
            assertThat(compressed.tickAt(frame), is(strip.tickAt(frame)));
        assertThat(compressed.playbackMsAt(64), is(3_200L));
    }

    @Test
    @DisplayName("schedule GAME_TIME normalizes a single frame to a Static, like the strip does")
    void scheduleGameTimeSingleFrameIsStatic() {
        AnimationOptions anim = AnimationOptions.builder()
            .startTick(7).schedule(AnimationOptions.Schedule.GAME_TIME).build();
        Timeline.TickTimeline timeline = anim.timeline();
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
        Timeline.TickTimeline timeline = anim.timeline();
        assertThat(timeline.frames(), is(64));
        assertThat(timeline.tickAt(63), is(23_625));
        assertThat(timeline.tickAt(63), is(greaterThan(Timeline.MAX_LOOP_TICKS)));
    }

    @Test
    @DisplayName("a named frame count on the frame ceiling is answered, and one past it is refused where it is read")
    void aFrameCountPastTheCeilingIsRefusedWhereItIsRead() {
        assertThat(AnimationOptions.builder().frameCount(RenderLimits.MAX_FRAMES).build().timeline().frames(), is(RenderLimits.MAX_FRAMES));

        AnimationOptions oversize = AnimationOptions.builder().frameCount(RenderLimits.MAX_FRAMES + 1).build();
        assertThat(assertThrows(RenderException.class, oversize::getFrameCount).getMessage(), containsString("Frame count '4097'"));
        assertThrows(RenderException.class, oversize::timeline);
        assertThrows(RenderException.class, oversize::tickStrip);
        assertThat("the count is still one the caller named", oversize.isFrameCountNamed(), is(true));
    }

    /** Asserts a timeline is the expected {@link Timeline.TickLoop}. */
    private static void assertTickLoop(@NotNull Timeline.TickTimeline timeline,
                                       int startTick, int frameCount, int ticksPerFrame, int delayMs) {
        assertThat(timeline, is(instanceOf(Timeline.TickLoop.class)));
        Timeline.TickLoop loop = (Timeline.TickLoop) timeline;
        assertThat(loop.startTick(), is(startTick));
        assertThat(loop.frameCount(), is(frameCount));
        assertThat(loop.ticksPerFrame(), is(ticksPerFrame));
        assertThat(loop.delayMs(), is(delayMs));
    }

}
