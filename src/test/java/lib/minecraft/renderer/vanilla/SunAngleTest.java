package lib.minecraft.renderer.vanilla;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * The {@link SunAngle} day curve - the noon phase anchor the item parity reference is captured at, the
 * eased (non-linear) shape of the day, and the neutrality the baked fast path depends on.
 */
@DisplayName("SunAngle day curve")
class SunAngleTest {

    /** Tolerance for a curve sample - far tighter than the {@code 1/128} that would move a clock face. */
    private static final double EPSILON = 1.0E-6;

    /** Faces in the vanilla clock's dispatch table, which is also the scale it dispatches at. */
    private static final int CLOCK_FACES = 64;

    /**
     * Selects the clock face vanilla's {@code range_dispatch} table answers at a day-time tick - the
     * highest threshold at or below the scaled angle, over entries {@code 0.0, 0.5, 1.5, ... 63.5}. The
     * final entry wraps back onto the first face, so the result is taken modulo the face count.
     *
     * @param dayTime the day-time tick to sample the curve at
     * @return the index of the selected clock face
     */
    private static int face(long dayTime) {
        float scaled = SunAngle.at(dayTime) * CLOCK_FACES;
        int selected = 0;
        for (int entry = 1; entry <= CLOCK_FACES; entry++)
            if (entry - 0.5f <= scaled) selected = entry;
        return selected % CLOCK_FACES;
    }

    @Nested
    @DisplayName("phase anchor")
    class PhaseAnchor {

        @Test
        @DisplayName("noon is exactly positive zero")
        void noonIsPositiveZero() {
            // Not merely == 0f: a -0.0f would compare equal here but unequal under record equality,
            // silently costing every item its baked fast path.
            assertThat(Float.floatToRawIntBits(SunAngle.at(SunAngle.NOON_TICK)), is(0));
        }

        @Test
        @DisplayName("noon selects the first clock face, the instant the parity reference is captured at")
        void noonSelectsFirstFace() {
            assertThat(face(SunAngle.NOON_TICK), is(0));
        }

        @Test
        @DisplayName("midnight is exactly half a turn, on the opposite clock face")
        void midnightIsHalfTurn() {
            assertThat(SunAngle.at(18_000), is(0.5f));
            assertThat(face(18_000), is(CLOCK_FACES / 2));
        }

    }

    @Nested
    @DisplayName("curve shape")
    class CurveShape {

        @Test
        @DisplayName("samples the eased day track, not a linear ramp")
        void samplesEasedTrack() {
            assertThat((double) SunAngle.at(0), closeTo(0.784364223, EPSILON));
            assertThat((double) SunAngle.at(3_000), closeTo(0.903884947, EPSILON));
            assertThat((double) SunAngle.at(6_000), closeTo(0.0, EPSILON));
            assertThat((double) SunAngle.at(9_000), closeTo(0.096115045, EPSILON));
            assertThat((double) SunAngle.at(12_000), closeTo(0.215635806, EPSILON));
            assertThat((double) SunAngle.at(15_000), closeTo(0.352968216, EPSILON));
            assertThat((double) SunAngle.at(18_000), closeTo(0.500000000, EPSILON));
            assertThat((double) SunAngle.at(21_000), closeTo(0.647031784, EPSILON));
        }

        @Test
        @DisplayName("departs from a linear ramp by more than a clock face")
        void departsFromLinearRamp() {
            // Sunrise sits three quarters of the way round on a linear ramp; the easing pushes it far
            // enough past that to select a different face, which is what makes the curve load-bearing.
            assertThat((double) SunAngle.at(0) - 0.75, greaterThan(1.0 / CLOCK_FACES));
        }

        @Test
        @DisplayName("selects the day's clock faces")
        void selectsDayFaces() {
            assertThat(face(0), is(50));
            assertThat(face(6_000), is(0));
            assertThat(face(12_000), is(14));
            assertThat(face(18_000), is(32));
        }

        @Test
        @DisplayName("rises monotonically from noon and stays within one turn")
        void risesMonotonically() {
            float previous = SunAngle.at(SunAngle.NOON_TICK);
            for (int tick = 1; tick < SunAngle.TICKS_PER_DAY; tick++) {
                float angle = SunAngle.at(SunAngle.NOON_TICK + tick);
                assertThat((double) angle, both(greaterThan((double) previous)).and(lessThan(1.0)));
                previous = angle;
            }
        }

    }

    @Nested
    @DisplayName("day wrapping")
    class DayWrapping {

        @Test
        @DisplayName("wraps whole days onto the same angle")
        void wrapsWholeDays() {
            assertThat(SunAngle.at(7_321), is(SunAngle.at(7_321 + SunAngle.TICKS_PER_DAY)));
            assertThat(SunAngle.at(7_321), is(SunAngle.at(7_321 + 10L * SunAngle.TICKS_PER_DAY)));
        }

        @Test
        @DisplayName("wraps negative ticks into the day rather than off the curve")
        void wrapsNegativeTicks() {
            assertThat(SunAngle.at(-SunAngle.TICKS_PER_DAY + 6_000), is(0f));
            assertThat(SunAngle.at(-1), is(SunAngle.at(SunAngle.TICKS_PER_DAY - 1)));
        }

        @Test
        @DisplayName("stays inside the dispatch table across the whole day")
        void staysInsideDispatchTable() {
            for (int tick = 0; tick < SunAngle.TICKS_PER_DAY; tick++) {
                float angle = SunAngle.at(tick);
                assertThat((double) angle, both(greaterThan(-EPSILON)).and(lessThan(1.0)));
                assertThat(face(tick), lessThanOrEqualTo(CLOCK_FACES - 1));
            }
        }

    }

}
