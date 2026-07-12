package lib.minecraft.renderer.engine.compose;

import lib.minecraft.renderer.engine.kit.GlintKit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Pins the glint × animation schedule bridge (04-animation §5): the wall-clock instant of an
 * animation tick, converted to the glint time {@link GlintKit#applyGlintAtTimes} consumes.
 * <p>
 * The load-bearing invariant is the {@code * 8.0} speed factor. {@code applyGlintAtTimes} takes
 * "vanilla post-{@code glintSpeed} millis" - {@link GlintKit#applyGlint} itself builds them as
 * {@code frameIndex * millisPerFrame * MAX_ENCHANTMENT_GLINT_SPEED_MILLIS}. So an animation frame at
 * tick {@code t} (wall-clock {@code t * 50} ms) must be fed {@code t * 50 * 8.0}, NOT {@code t * 50}.
 * A regression to the bare wall-clock would scroll the animated foil at 1/8 speed, out of phase with
 * the live client - this test would catch it.
 */
class FinalizeGlintScheduleTest {

    @Test
    @DisplayName("tick 0 maps to glint time 0")
    void zeroTick() {
        assertThat(Finalize.glintTimeForTick(0), is(0L));
    }

    @Test
    @DisplayName("glint time = tick * 50 ms * 8.0 speed factor (the * 8 is folded in HERE)")
    void includesSpeedFactor() {
        // tick 1 -> 50 ms wall-clock -> 400 glint-millis (50 * 8). NOT 50.
        assertThat(Finalize.glintTimeForTick(1), is(400L));
        assertThat(Finalize.glintTimeForTick(10), is(4000L));
        assertThat(Finalize.glintTimeForTick(200), is(80_000L));
    }

    @Test
    @DisplayName("matches GlintKit.applyGlint's wall-clock -> glint-time convention exactly")
    void matchesApplyGlintConvention() {
        for (int tick : new int[]{0, 1, 3, 40, 137, 200}) {
            long wallMs = (long) tick * Finalize.MILLIS_PER_TICK;
            long expected = Math.round(wallMs * GlintKit.MAX_ENCHANTMENT_GLINT_SPEED_MILLIS);
            assertThat(Finalize.glintTimeForTick(tick), is(expected));
        }
    }

    @Test
    @DisplayName("both glint loop periods are multiples of the tick wall quantum -> clean sweep")
    void loopPeriodsSweepCleanly() {
        // The design's phase-alignment claim: 110000 / 30000 ms loops are multiples of 50 ms/tick, so
        // tick-derived glint times visit distinct phases and complete the loop without drift.
        assertThat(GlintKit.VANILLA_U_LOOP_MILLIS % Finalize.MILLIS_PER_TICK, is(0L));
        assertThat(GlintKit.VANILLA_V_LOOP_MILLIS % Finalize.MILLIS_PER_TICK, is(0L));
        assertThat(GlintKit.MAX_ENCHANTMENT_GLINT_SPEED_MILLIS, is(8.0));
    }
}
