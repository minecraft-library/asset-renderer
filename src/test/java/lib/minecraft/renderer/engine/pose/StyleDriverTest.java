package lib.minecraft.renderer.engine.pose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
 *
 * <p>The stroke and the trapezoid are pinned at vanilla's own values: the squid's tentacle angle at
 * the rate entity id 9 draws, where vanilla's float accumulator and the closed-form phase agree to the
 * bit; the shulker's lid over its shortest peek, each tick within an ulp of vanilla's own steps; and
 * the warden's heartbeat, whose tenths are vanilla's {@code (10 - k) / 10f} to the bit.
 */
@DisplayName("a style driver's wave arithmetic")
class StyleDriverTest {

    /**
     * The shipped period, the ticks one excursion spans and so what a sweep or cycle wraps at -
     * twenty-four, whose quarters are whole ticks.
     */
    private static final int PERIOD_TICKS = 24;

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

    // ------------------------------------------------------------------------------------
    // the stroke and the trapezoid
    // ------------------------------------------------------------------------------------

    /** The radians of stroke phase a tick vanilla's squid draws for entity id 9. */
    private static final float SQUID_RATE = 0.11559614f;

    /** The shulker's idle peek - the lid's height at the top of vanilla's shortest peek. */
    private static final float PEEK = 0.29999998f;

    @Test
    @DisplayName("a stroke flares to its extent and back over half its phase, then hangs at rest")
    void strokeFlaresThenHangs() {
        StyleDriver stroke = new StyleDriver("tentacleAngle", StyleDriver.Wave.STROKE,
            0f, QUARTER_TURN, Optional.empty(), StyleDriver.Timing.stroke(SQUID_RATE));
        int[] ticks = {0, 1, 10, 19, 27, 28, 54, 55, 60, 82, 109};
        float[] angles = {0f, 0.0033131507f, 0.3240479f, 0.78490674f, 0.03214374f, 0f, 0f,
            0.0013553833f, 0.10607251f, 0f, 2.2589733E-4f};
        for (int index = 0; index < ticks.length; index++) {
            assertEquals(angles[index], stroke.at(ticks[index], PERIOD_TICKS),
                "vanilla's tentacle angle at tick " + ticks[index]);
        }
        for (int tick = 28; tick <= 54; tick++)
            assertEquals(0f, stroke.at(tick, PERIOD_TICKS), "the hang answers the rest at tick " + tick);
        for (int tick = 0; tick < 120; tick++) {
            assertEquals(stroke.at(tick, 7), stroke.at(tick, PERIOD_TICKS),
                "a stroke reads its own rate, never the window, at tick " + tick);
        }
    }

    @Test
    @DisplayName("a stroke runs from the rest it names, not from zero")
    void strokeRunsFromItsOwnRest() {
        StyleDriver stroke = new StyleDriver("tentacleAngle", StyleDriver.Wave.STROKE,
            0.1f, 0.5f, Optional.empty(), StyleDriver.Timing.stroke(SQUID_RATE));
        for (int tick = 28; tick <= 54; tick++)
            assertEquals(0.1f, stroke.at(tick, PERIOD_TICKS), "the hang answers the rest at tick " + tick);

        float f = ((19 * SQUID_RATE) % 6.2831855f) / 3.1415927f;
        assertEquals(0.1f + (0.5f - 0.1f) * VanillaMth.mthSin((double) (f * f * 3.1415927f)),
            stroke.at(19, PERIOD_TICKS), "the flare's peak is the rest plus the sine's share of the range");
    }

    @Test
    @DisplayName("a trapezoid rises, dwells at its extent, falls and rests, wrapping by floor-mod")
    void trapezoidRisesDwellsFallsAndRests() {
        StyleDriver peek = new StyleDriver("peekAmount", StyleDriver.Wave.TRAPEZOID,
            0f, PEEK, Optional.empty(), StyleDriver.Timing.trapezoid(6, 14, 6, 0));
        float[] rise = {0f, 0.049999997f, 0.099999994f, 0.14999999f, 0.19999999f, 0.24999999f, PEEK};
        for (int tick = 0; tick <= 6; tick++)
            assertEquals(rise[tick], peek.at(tick, PERIOD_TICKS), "the lid rises at tick " + tick);
        for (int tick = 6; tick <= 20; tick++)
            assertEquals(PEEK, peek.at(tick, PERIOD_TICKS), "the lid holds at the top at tick " + tick);
        float[] fall = {0.24999999f, 0.19999999f, 0.14999999f, 0.099999994f, 0.049999997f};
        for (int tick = 21; tick <= 25; tick++)
            assertEquals(fall[tick - 21], peek.at(tick, PERIOD_TICKS), "the lid lowers at tick " + tick);
        assertEquals(0f, peek.at(26, PERIOD_TICKS), "a whole cycle closes the lid");
        assertEquals(0.049999997f, peek.at(27, PERIOD_TICKS), "and the next cycle rises again");
        assertEquals(peek.at(0, PERIOD_TICKS), peek.at(26, PERIOD_TICKS), "one cycle later is the same tick");
        assertEquals(peek.at(25, PERIOD_TICKS), peek.at(-1, PERIOD_TICKS),
            "a tick before the start wraps by floor-mod rather than truncation");
        for (int tick = 0; tick < 60; tick++) {
            assertEquals(peek.at(tick, 7), peek.at(tick, PERIOD_TICKS),
                "a trapezoid reads its own spans, never the window, at tick " + tick);
        }
    }

    @Test
    @DisplayName("a trapezoid with no rise stands at its extent on its first tick and counts down by tenths")
    void aZeroRiseStandsAtTheExtentOnTheFirstTick() {
        StyleDriver heart = new StyleDriver("heartAnimation", StyleDriver.Wave.TRAPEZOID,
            0f, 1f, Optional.empty(), StyleDriver.Timing.trapezoid(0, 0, 10, 30));
        for (int tick = 0; tick <= 10; tick++)
            assertEquals((10 - tick) / 10f, heart.at(tick, PERIOD_TICKS), "vanilla's heartbeat at tick " + tick);
        for (int tick = 10; tick < 40; tick++)
            assertEquals(0f, heart.at(tick, PERIOD_TICKS), "the heart is dark at tick " + tick);
        assertEquals(1f, heart.at(40, PERIOD_TICKS), "and beats again forty ticks on");
    }

    @Test
    @DisplayName("a timing the wave does not read, or one it cannot run, is refused")
    void timingIsRefusedWhereTheWaveReadsNone() {
        IllegalArgumentException still = assertThrows(IllegalArgumentException.class, () ->
            new StyleDriver("tentacleAngle", StyleDriver.Wave.STROKE, 0f, QUARTER_TURN, Optional.empty(),
                StyleDriver.Timing.stroke(0f)));
        assertEquals("A stroke needs a positive rate, given 'Timing[rate=0.0, rise=0, dwell=0, fall=0, gap=0]'",
            still.getMessage());

        IllegalArgumentException spanned = assertThrows(IllegalArgumentException.class, () ->
            new StyleDriver("tentacleAngle", StyleDriver.Wave.STROKE, 0f, QUARTER_TURN, Optional.empty(),
                new StyleDriver.Timing(0.1f, 6, 14, 6, 0)));
        assertEquals("A stroke needs a positive rate, given 'Timing[rate=0.1, rise=6, dwell=14, fall=6, gap=0]'",
            spanned.getMessage(), "a stroke carrying a span is refused, however positive its rate");

        IllegalArgumentException unbounded = assertThrows(IllegalArgumentException.class, () ->
            StyleDriver.Timing.stroke(Float.NaN));
        assertEquals("Stroke rate 'NaN' is not a finite rate of at least zero", unbounded.getMessage());

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class, () ->
            new StyleDriver("heartAnimation", StyleDriver.Wave.TRAPEZOID, 0f, 1f, Optional.empty(),
                StyleDriver.Timing.trapezoid(0, 0, 0, 0)));
        assertEquals("A trapezoid needs a cycle of at least one tick, given 'Timing[rate=0.0, rise=0, dwell=0, fall=0, gap=0]'",
            empty.getMessage());

        IllegalArgumentException rated = assertThrows(IllegalArgumentException.class, () ->
            new StyleDriver("heartAnimation", StyleDriver.Wave.TRAPEZOID, 0f, 1f, Optional.empty(),
                new StyleDriver.Timing(0.1f, 0, 0, 10, 30)));
        assertEquals("A trapezoid needs a cycle of at least one tick, given 'Timing[rate=0.1, rise=0, dwell=0, fall=10, gap=30]'",
            rated.getMessage(), "a trapezoid carrying a rate is refused, however whole its cycle");

        IllegalArgumentException held = assertThrows(IllegalArgumentException.class, () ->
            new StyleDriver("croakAnimationState", StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty(),
                StyleDriver.Timing.stroke(0.1f)));
        assertEquals("Wave 'HOLD' reads no timing, given 'Timing[rate=0.1, rise=0, dwell=0, fall=0, gap=0]'",
            held.getMessage());

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class, () ->
            StyleDriver.Timing.trapezoid(-1, 0, 10, 30));
        assertEquals("Trapezoid spans '-1', '0', '10', '30' include a negative span", negative.getMessage());
    }

}
