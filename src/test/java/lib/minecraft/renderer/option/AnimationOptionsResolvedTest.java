package lib.minecraft.renderer.option;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strip knobs' unnamed state and the one defaulting site that fills it.
 *
 * <p>An unnamed knob means the subject decides, and an explicit {@code 1} is a different request -
 * one frame OF the animation rather than a still one - so the two must stay tellable apart even
 * though the compatible getter answers them alike. {@code resolved} fills only what is unnamed, a
 * named knob always wins, and everything that is not a strip knob rides through untouched.
 */
@DisplayName("the animation strip knobs and their one defaulting site")
class AnimationOptionsResolvedTest {

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
    @DisplayName("resolved fills both unnamed knobs and leaves them named")
    void resolvedFillsBothUnnamedKnobs() {
        AnimationOptions filled = AnimationOptions.defaults().resolved(8, 3);
        assertEquals(8, filled.getFrameCount(), "the frame count takes the fallback");
        assertEquals(3, filled.getTicksPerFrame(), "and the tick step its own");
        assertTrue(filled.isFrameCountNamed(), "both are named afterwards");
        assertTrue(filled.isTicksPerFrameNamed(), "so a second resolve has nothing to fill");
    }

    @Test
    @DisplayName("resolved keeps a named knob and fills only the unnamed one")
    void resolvedKeepsANamedKnob() {
        AnimationOptions filled = AnimationOptions.builder().frameCount(4).build().resolved(8, 3);
        assertEquals(4, filled.getFrameCount(), "the named knob wins");
        assertEquals(3, filled.getTicksPerFrame(), "and the unnamed one is filled");
    }

    @Test
    @DisplayName("resolved on both-named options is the instance itself")
    void resolvedOnBothNamedIsTheInstanceItself() {
        AnimationOptions named = AnimationOptions.builder().frameCount(4).ticksPerFrame(2).build();
        assertSame(named, named.resolved(8, 3), "nothing to fill allocates nothing");
    }

    @Test
    @DisplayName("the seed tick and the schedule ride through resolved untouched")
    void theOtherKnobsSurviveResolved() {
        AnimationOptions filled = AnimationOptions.builder()
            .startTick(5)
            .schedule(AnimationOptions.Schedule.GAME_TIME)
            .build()
            .resolved(8, 3);
        assertEquals(5, filled.getStartTick(), "the seed tick is the caller's");
        assertSame(AnimationOptions.Schedule.GAME_TIME, filled.getSchedule(),
            "and so is the playback schedule");
    }

}
