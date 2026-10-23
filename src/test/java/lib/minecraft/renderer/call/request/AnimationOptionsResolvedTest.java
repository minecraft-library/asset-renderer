package lib.minecraft.renderer.call.request;

import lib.minecraft.renderer.engine.frame.StripFit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strip knobs' unnamed state and the one defaulting site that fills it from a derived strip.
 *
 * <p>An unnamed knob means the subject decides, and an explicit {@code 1} is a different request -
 * one frame OF the animation rather than a still one - so the two must stay tellable apart even
 * though the compatible getter answers them alike. {@code resolved} fills only what is unnamed, a
 * named knob always wins, and everything that is not a strip knob rides through untouched. A knob
 * named alone keeps the whole loop: a named count keeps the strip's own step, and a named step fills
 * as many frames as cover the strip's loop, however many that is - one for a still strip.
 */
@DisplayName("the animation strip knobs and their one defaulting site")
class AnimationOptionsResolvedTest {

    /** A derived strip of twenty-two frames at three ticks - a sixty-six-tick loop. */
    private static final @NotNull StripFit STRIP = new StripFit(3, 22, 0.5);

    /** The longest strip a derivation covers - {@link StripFit#MAX_TICKS} ticks, every frame the coarse cap allows. */
    private static final @NotNull StripFit CAPPED = new StripFit(3, StripFit.MAX_FRAMES, 0.5);

    /** A derived strip on the fine step - thirty-three frames at one tick, as a bee's wings take. */
    private static final @NotNull StripFit FINE = new StripFit(1, 33, 0.5);

    @Test
    @DisplayName("the defaults name neither knob and the compatible getters answer one")
    void theDefaultsAreUnnamed() {
        AnimationOptions defaults = AnimationOptions.defaults();
        assertFalse(defaults.isFrameCountNamed(), "no frame count is named");
        assertFalse(defaults.isTicksPerFrameNamed(), "and no tick step is");
        assertEquals(1, defaults.getFrameCount(), "the compatible getter answers one");
        assertEquals(1, defaults.getTicksPerFrame(), "for both knobs");
    }

    @Test
    @DisplayName("an explicit count of one is a named knob, distinct from an unnamed one")
    void anExplicitOneIsNamed() {
        AnimationOptions one = AnimationOptions.builder().frameCount(1).build();
        assertTrue(one.isFrameCountNamed(), "the caller named it");
        assertEquals(1, one.getFrameCount(), "and it reads the same as the unnamed default");
    }

    @Test
    @DisplayName("resolved fills both unnamed knobs from the strip and leaves them named")
    void resolvedFillsBothUnnamedKnobs() {
        AnimationOptions filled = AnimationOptions.defaults().resolved(STRIP);
        assertEquals(22, filled.getFrameCount(), "the frame count takes the strip's");
        assertEquals(3, filled.getTicksPerFrame(), "and the tick step its step");
        assertTrue(filled.isFrameCountNamed(), "both are named afterwards");
        assertTrue(filled.isTicksPerFrameNamed(), "so a second resolve has nothing to fill");
    }

    @Test
    @DisplayName("an unnamed step takes a fine strip's one tick rather than the coarse step")
    void resolvedTakesAFineStripsStep() {
        AnimationOptions filled = AnimationOptions.defaults().resolved(FINE);
        assertEquals(1, filled.getTicksPerFrame(), "the tick step is the strip's own single tick");
        assertEquals(33, filled.getFrameCount(), "and the frame count the strip's");
    }

    @Test
    @DisplayName("a named count keeps the strip's own step")
    void resolvedKeepsANamedKnob() {
        AnimationOptions filled = AnimationOptions.builder().frameCount(4).build().resolved(STRIP);
        assertEquals(4, filled.getFrameCount(), "the named knob wins");
        assertEquals(3, filled.getTicksPerFrame(), "and each frame keeps the row's step");
    }

    @Test
    @DisplayName("resolved on both-named options is the instance itself")
    void resolvedOnBothNamedIsTheInstanceItself() {
        AnimationOptions named = AnimationOptions.builder().frameCount(4).ticksPerFrame(2).build();
        assertSame(named, named.resolved(STRIP), "nothing to fill allocates nothing");
    }

    @Test
    @DisplayName("the seed tick and the schedule ride through resolved untouched")
    void theOtherKnobsSurviveResolved() {
        AnimationOptions filled = AnimationOptions.builder()
            .startTick(5)
            .schedule(AnimationOptions.Schedule.GAME_TIME)
            .build()
            .resolved(STRIP);
        assertEquals(5, filled.getStartTick(), "the seed tick is the caller's");
        assertSame(AnimationOptions.Schedule.GAME_TIME, filled.getSchedule(),
            "and so is the playback schedule");
    }

    @Test
    @DisplayName("a named step alone fills as many frames as cover the strip's whole loop")
    void aNamedStepCoversTheWholeLoop() {
        assertFills(66, 1, STRIP, "a one-tick step covers the 66-tick loop in 66 frames");
        assertFills(33, 2, STRIP, "a two-tick step in 33");
        assertFills(17, 4, STRIP, "and a four-tick step in 17, the last running past the loop's end");
    }

    @Test
    @DisplayName("a named step's fill is the caller's choice, so it is not held to the derived cap")
    void aNamedStepMayRunPastTheCap() {
        assertFills(198, 1, CAPPED, "a one-tick step over a capped three-tick strip draws every tick of it");
    }

    @Test
    @DisplayName("a named step on a still strip is one frame")
    void aNamedStepOnAStillStripIsOneFrame() {
        assertFills(1, 2, StripFit.STILL, "three identical frames would claim a motion that is not there");
    }

    @Test
    @DisplayName("an explicit count of one stays one")
    void anExplicitOneStaysOne() {
        AnimationOptions filled = AnimationOptions.builder().frameCount(1).build().resolved(STRIP);
        assertEquals(1, filled.getFrameCount(), "one frame of the animation, sampled at the start tick");
        assertEquals(3, filled.getTicksPerFrame(), "at the strip's step");
    }

    @Test
    @DisplayName("a named step below one tick is refused when the options are built")
    void aNamedStepBelowOneIsRefused() {
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
            () -> AnimationOptions.builder().ticksPerFrame(0).build());
        assertTrue(refusal.getMessage().contains("ticksPerFrame") && refusal.getMessage().contains("is 0"),
            refusal.getMessage());
        assertThrows(IllegalStateException.class, () -> AnimationOptions.defaults().mutate().ticksPerFrame(-3).build(),
            "and so is one a copy names");
        assertEquals(1, AnimationOptions.builder().ticksPerFrame(1).build().getTicksPerFrame(), "while one tick is a step");
    }

    // ------------------------------------------------------------------------------------

    /** Holds a step named alone to the frame count it fills over one strip. */
    private static void assertFills(int frames, int step, @NotNull StripFit strip, @NotNull String message) {
        AnimationOptions filled = AnimationOptions.builder().ticksPerFrame(step).build().resolved(strip);
        assertEquals(frames, filled.getFrameCount(), message);
        assertEquals(step, filled.getTicksPerFrame(), "the named step is kept");
    }

}
