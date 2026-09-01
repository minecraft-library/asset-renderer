package lib.minecraft.renderer.asset.pose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The wave arithmetic a style driver answers a field with.
 *
 * <p>The load-bearing half is bit equality against the figure roster: a swept or cycling driver
 * carries the same excursion arithmetic the reference set is rendered against, so its answers must
 * be the figure's float for float - an ulp of drift here is a mover on every subject the figure
 * reaches. The held and ramped waves are pinned against their literal definitions.
 */
@DisplayName("a style driver's wave arithmetic")
class StyleDriverTest {

    @Test
    @DisplayName("a swept or cycling driver answers the figure roster bit for bit")
    void sweepAndCycleMatchTheFigureArithmetic() {
        for (IdleFigure figure : IdleFigure.values()) {
            StyleDriver driver = new StyleDriver(figure.field(),
                figure.shape() == IdleFigure.Shape.CYCLE
                    ? StyleDriver.Wave.CYCLE : StyleDriver.Wave.SWEEP,
                figure.rest(), figure.extent(), Optional.empty());
            for (int tick = 0; tick < IdleFigure.PERIOD_TICKS; tick++)
                assertEquals(Float.floatToIntBits(figure.at(tick)),
                    Float.floatToIntBits(driver.at(tick, IdleFigure.PERIOD_TICKS)),
                    figure + " at tick " + tick);
        }
    }

    @Test
    @DisplayName("a held driver answers its extent at every tick")
    void holdAnswersTheExtent() {
        StyleDriver held = new StyleDriver("croakAnimationState", StyleDriver.Wave.HOLD,
            0f, 1f, Optional.of("action"));
        for (int tick = 0; tick < IdleFigure.PERIOD_TICKS; tick++)
            assertEquals(1f, held.at(tick, IdleFigure.PERIOD_TICKS), "held at tick " + tick);
    }

    @Test
    @DisplayName("a ramped driver answers the tick times its extent")
    void rampAnswersTheTickTimesTheExtent() {
        StyleDriver age = new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP,
            0f, 1f, Optional.empty());
        assertEquals(0f, age.at(0, IdleFigure.PERIOD_TICKS), "a ramp starts at nothing");
        assertEquals(7f, age.at(7, IdleFigure.PERIOD_TICKS), "and climbs with the tick");
        assertEquals(41f, age.at(41, IdleFigure.PERIOD_TICKS), "past the period, unwrapped");

        StyleDriver half = new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP,
            0f, 0.5f, Optional.empty());
        assertEquals(3.5f, half.at(7, IdleFigure.PERIOD_TICKS), "at the slope the extent names");
    }

}
