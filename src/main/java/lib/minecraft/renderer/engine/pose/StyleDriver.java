package lib.minecraft.renderer.engine.pose;

import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * One render-state field a style drives - the wave its value travels, and the range it travels
 * between.
 *
 * <p>A driver is the whole of how a style answers one field: a swept scalar rises from what it
 * rests at to what it reaches and returns, a cycling phase wraps at its period, a held one-hot
 * stands at its extent, a ramp climbs with the tick itself, a stroke flares and hangs, and a
 * trapezoid rises, dwells, falls and rests. Whatever a style does not drive rests, so an undriven
 * field is answered with zero rather than by a driver of its own.
 *
 * <p><b>Every wave's arithmetic is fixed</b>: each arm narrows to {@code float} as it goes and reads
 * nothing but its tick, its own components, the period a sweep or a cycle wraps at and, for a
 * stroke, vanilla's own sine table, so its answers are bit-stable.
 *
 * @param field the render-state name a shipped pose reads - {@code tentacleAngle},
 *     {@code croakAnimationState}, {@code walkAnimationPos}
 * @param wave how the value travels across one period
 * @param rest the near end of the travel, which a sweep, a cycle and a stroke hold at tick zero
 * @param extent the far end of the travel - the whole of a held one-hot, and the per-tick slope of
 *     a ramp
 * @param group the exclusion group this driver's field belongs to, under which one row's driver
 *     stands in for another's; empty for an ungrouped field
 * @param timing what a stroke or a trapezoid reads beside its rest and extent - {@link Timing#NONE}
 *     for every other wave
 */
@Parity(claim = "asset-layer")
public record StyleDriver(
    @NotNull String field,
    @NotNull Wave wave,
    float rest,
    float extent,
    @NotNull Optional<String> group,
    @NotNull Timing timing
) {

    /** One whole turn of a stroke's phase, the float literal its phase wraps at. */
    private static final float TWO_PI = 6.2831855f;

    /** Half a turn of a stroke's phase, where its flare ends and its hang begins. */
    private static final float PI = 3.1415927f;

    /**
     * Constructs a new {@code StyleDriver}, refusing a wave and a timing that do not belong together.
     *
     * @throws IllegalArgumentException if a stroke has no positive rate or carries a span, a
     *     trapezoid carries a rate or spans no tick, or any other wave carries a timing
     */
    public StyleDriver {
        switch (wave) {
            case STROKE -> {
                if (!(timing.rate() > 0f) || timing.cycleTicks() != 0) {
                    throw new IllegalArgumentException(
                        String.format("A stroke needs a positive rate, given '%s'", timing));
                }
            }
            case TRAPEZOID -> {
                if (timing.rate() != 0f || timing.cycleTicks() < 1) {
                    throw new IllegalArgumentException(
                        String.format("A trapezoid needs a cycle of at least one tick, given '%s'", timing));
                }
            }
            default -> {
                if (!timing.equals(Timing.NONE)) {
                    throw new IllegalArgumentException(
                        String.format("Wave '%s' reads no timing, given '%s'", wave, timing));
                }
            }
        }
    }

    /**
     * Constructs a new {@code StyleDriver} reading no timing - every wave but a stroke and a
     * trapezoid.
     *
     * @param field the render-state name a shipped pose reads
     * @param wave how the value travels across one period
     * @param rest the near end of the travel, which a sweep and a cycle hold at tick zero
     * @param extent the far end of the travel
     * @param group the exclusion group this driver's field belongs to, empty for an ungrouped field
     * @throws IllegalArgumentException if the wave is a stroke or a trapezoid, which read a timing
     */
    public StyleDriver(
        @NotNull String field, @NotNull Wave wave, float rest, float extent, @NotNull Optional<String> group) {

        this(field, wave, rest, extent, group, Timing.NONE);
    }

    /** How a driven value travels between what it rests at and what it reaches. */
    public enum Wave {

        /** Held at the extent - a selected one-hot, or a level a clock settles at for a gait. */
        HOLD,

        /** The tick times the extent - elapsed age, and the stride phase. */
        RAMP,

        /** Out and back, so one period begins and ends at rest. */
        SWEEP,

        /** A phase, wrapping at its period. */
        CYCLE,

        /**
         * A flare and an equal hang: over the first half of a stroke phase advancing at the timing's
         * rate, the value rises from rest to the extent and back, and it rests for the second half.
         */
        STROKE,

        /**
         * A rise, a dwell, a fall and a gap in whole ticks from tick zero: the value climbs from rest
         * to the extent, holds there, returns, and rests until the cycle repeats.
         */
        TRAPEZOID
    }

    /**
     * What a stroke or a trapezoid reads besides its rest and extent - the stroke's phase rate, or the
     * trapezoid's four spans; zero throughout on every other wave.
     *
     * @param rate the radians of stroke phase a tick
     * @param rise the ticks a trapezoid climbs from its rest to its extent
     * @param dwell the ticks a trapezoid holds at its extent
     * @param fall the ticks a trapezoid returns to its rest
     * @param gap the ticks a trapezoid holds at its rest before the next rise
     */
    public record Timing(float rate, int rise, int dwell, int fall, int gap) {

        /** The timing of every wave that reads none. */
        public static final @NotNull Timing NONE = new Timing(0f, 0, 0, 0, 0);

        /**
         * Constructs a new {@code Timing}, refusing a rate or a span no wave can run.
         *
         * @throws IllegalArgumentException if the rate is negative or not finite, or a span is
         *     negative
         */
        public Timing {
            if (!Float.isFinite(rate) || rate < 0f) {
                throw new IllegalArgumentException(
                    String.format("Stroke rate '%s' is not a finite rate of at least zero", rate));
            }
            if (rise < 0 || dwell < 0 || fall < 0 || gap < 0) {
                throw new IllegalArgumentException(String.format(
                    "Trapezoid spans '%s', '%s', '%s', '%s' include a negative span", rise, dwell, fall, gap));
            }
        }

        /**
         * Creates the timing of a stroke.
         *
         * @param rate the radians of stroke phase a tick
         * @return a timing reading the rate and no span
         * @throws IllegalArgumentException if the rate is negative or not finite
         */
        public static @NotNull Timing stroke(float rate) {
            return new Timing(rate, 0, 0, 0, 0);
        }

        /**
         * Creates the timing of a trapezoid.
         *
         * @param rise the ticks it climbs from its rest to its extent
         * @param dwell the ticks it holds at its extent
         * @param fall the ticks it returns to its rest
         * @param gap the ticks it holds at its rest before the next rise
         * @return a timing reading the four spans and no rate
         * @throws IllegalArgumentException if a span is negative
         */
        public static @NotNull Timing trapezoid(int rise, int dwell, int fall, int gap) {
            return new Timing(0f, rise, dwell, fall, gap);
        }

        /**
         * Sums the four spans - the ticks one trapezoid cycle spans.
         *
         * @return {@code rise + dwell + fall + gap}
         */
        public int cycleTicks() {
            return this.rise + this.dwell + this.fall + this.gap;
        }

    }

    /**
     * What this driver's field holds at one tick.
     *
     * @param tick the tick being posed
     * @param periodTicks the window a sweep or cycle wraps at, which every other wave ignores
     * @return its value at that tick
     */
    public float at(int tick, int periodTicks) {
        return switch (this.wave) {
            case HOLD -> this.extent;
            case RAMP -> tick * this.extent;
            case SWEEP, CYCLE -> {
                float phase = Math.floorMod(tick, periodTicks) / (float) periodTicks;
                float travel = this.wave == Wave.CYCLE ? phase : 1f - Math.abs(2f * phase - 1f);
                yield this.rest + (this.extent - this.rest) * travel;
            }
            case STROKE -> {
                float phase = (tick * this.timing.rate()) % TWO_PI;
                if (phase < 0f) phase += TWO_PI;
                if (!(phase < PI)) yield this.rest;
                float f = phase / PI;
                yield this.rest + (this.extent - this.rest) * VanillaMth.mthSin((double) (f * f * PI));
            }
            case TRAPEZOID -> {
                int cycle = Math.floorMod(tick, this.timing.cycleTicks());
                float range = this.extent - this.rest;
                if (cycle < this.timing.rise()) yield this.rest + range * (cycle / (float) this.timing.rise());
                cycle -= this.timing.rise();
                if (cycle < this.timing.dwell()) yield this.extent;
                cycle -= this.timing.dwell();
                if (cycle < this.timing.fall()) {
                    int left = this.timing.fall() - cycle;
                    yield this.rest + range * (left / (float) this.timing.fall());
                }
                yield this.rest;
            }
        };
    }

}
