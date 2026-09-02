package lib.minecraft.renderer.asset.pose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The wave arithmetic a style driver answers a field with.
 *
 * <p>The swept and cycling arithmetic is the excursion arithmetic the reference set is rendered
 * against - a floor-mod phase over the period, a triangle or wrapping travel, and the rest plus the
 * travel's share of the range, every narrowing a {@code float} - so its answers are pinned at exact
 * values, an ulp of drift here being a mover on every subject the driver reaches. The shipped
 * rest and extent decimals are held to the harness contract by {@code StyleCatalogMirrorTest}; what
 * is pinned here is what the arithmetic does with them. The held and ramped waves are pinned
 * against their literal definitions.
 */
@DisplayName("a style driver's wave arithmetic")
class StyleDriverTest {

    /** The ticks one shipped excursion spans, which is what a sweep or cycle wraps at. */
    private static final int PERIOD_TICKS = StyleCatalog.BIND_ONLY.periodTicks();

    /** The squid's shipped tentacle extent - a quarter turn, whose halves and quarters are exact. */
    private static final float QUARTER_TURN = 0.7853982f;

    @Test
    @DisplayName("a swept driver travels out to its extent and back, starting from rest")
    void sweepTravelsOutAndBack() {
        StyleDriver sweep = new StyleDriver("tentacleAngle", StyleDriver.Wave.SWEEP,
            0f, QUARTER_TURN, Optional.empty());
        assertEquals(0f, sweep.at(0, PERIOD_TICKS), "tick zero is rest, so frame 0 is free");
        assertEquals(QUARTER_TURN * 0.5f, sweep.at(6, PERIOD_TICKS), "a quarter period is half way out");
        assertEquals(QUARTER_TURN, sweep.at(12, PERIOD_TICKS), "half the period is the extent");
        assertEquals(QUARTER_TURN * 0.5f, sweep.at(18, PERIOD_TICKS), "and the way back mirrors the way out");
        assertEquals(0f, sweep.at(24, PERIOD_TICKS), "one whole period returns to rest");
        assertEquals(sweep.at(18, PERIOD_TICKS), sweep.at(-6, PERIOD_TICKS),
            "a tick before the start wraps by floor-mod rather than truncation");
    }

    @Test
    @DisplayName("a swept driver runs from the rest it names, not from zero")
    void sweepRunsFromItsOwnRest() {
        StyleDriver sweep = new StyleDriver("peekAmount", StyleDriver.Wave.SWEEP,
            0.1f, 0.5f, Optional.empty());
        assertEquals(0.1f, sweep.at(0, PERIOD_TICKS), "rest is the near end of the travel");
        assertEquals(0.3f, sweep.at(6, PERIOD_TICKS), "half way out is half the range past rest");
        assertEquals(0.5f, sweep.at(12, PERIOD_TICKS), "and the far end is the extent itself");
    }

    @Test
    @DisplayName("a cycling driver is a phase that wraps at its period")
    void cycleWrapsAtThePeriod() {
        StyleDriver cycle = new StyleDriver("flapTime", StyleDriver.Wave.CYCLE,
            0f, QUARTER_TURN, Optional.empty());
        assertEquals(0f, cycle.at(0, PERIOD_TICKS), "tick zero is rest");
        assertEquals(QUARTER_TURN * 0.25f, cycle.at(6, PERIOD_TICKS), "a quarter period is a quarter turn of phase");
        assertEquals(QUARTER_TURN * 0.75f, cycle.at(18, PERIOD_TICKS), "climbing straight through the period");
        assertEquals(0f, cycle.at(24, PERIOD_TICKS), "and wrapping back to rest where a sweep turns around");
        assertEquals(cycle.at(6, PERIOD_TICKS), cycle.at(30, PERIOD_TICKS),
            "one period later is the same phase");
    }

    @Test
    @DisplayName("a held driver answers its extent at every tick")
    void holdAnswersTheExtent() {
        StyleDriver held = new StyleDriver("croakAnimationState", StyleDriver.Wave.HOLD,
            0f, 1f, Optional.of("action"));
        for (int tick = 0; tick < PERIOD_TICKS; tick++)
            assertEquals(1f, held.at(tick, PERIOD_TICKS), "held at tick " + tick);
    }

    @Test
    @DisplayName("a ramped driver answers the tick times its extent")
    void rampAnswersTheTickTimesTheExtent() {
        StyleDriver age = new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP,
            0f, 1f, Optional.empty());
        assertEquals(0f, age.at(0, PERIOD_TICKS), "a ramp starts at nothing");
        assertEquals(7f, age.at(7, PERIOD_TICKS), "and climbs with the tick");
        assertEquals(41f, age.at(41, PERIOD_TICKS), "past the period, unwrapped");

        StyleDriver half = new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP,
            0f, 0.5f, Optional.empty());
        assertEquals(3.5f, half.at(7, PERIOD_TICKS), "at the slope the extent names");
    }

}
