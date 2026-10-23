package lib.minecraft.renderer.engine.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
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
 * nothing but its tick, the driver's rest and extent, the wave's own components, the period a sweep
 * or a cycle wraps at and, for a stroke, vanilla's own sine table, so its answers are bit-stable.
 *
 * @param field the render-state name a shipped pose reads - {@code tentacleAngle},
 *     {@code croakAnimationState}, {@code walkAnimationPos}
 * @param wave how the value travels across one period, with whatever that travel reads beside the
 *     rest and the extent
 * @param rest the near end of the travel, which a sweep, a cycle and a stroke hold at tick zero
 * @param extent the far end of the travel - the whole of a held one-hot, and the per-tick slope of
 *     a ramp
 * @param group the exclusion group this driver's field belongs to, under which one row's driver
 *     stands in for another's; empty for an ungrouped field
 */
@Parity(claim = "asset-layer")
public record StyleDriver(
    @NotNull String field,
    @NotNull Wave wave,
    float rest,
    float extent,
    @NotNull Optional<String> group
) {

    /** One whole turn of a stroke's phase, the float literal its phase wraps at. */
    private static final float TWO_PI = 6.2831855f;

    /** Half a turn of a stroke's phase, where its flare ends and its hang begins. */
    private static final float PI = 3.1415927f;

    /**
     * How a driven value travels between what it rests at and what it reaches - one of the four
     * waves that read nothing else, a stroke reading its phase rate, or a trapezoid reading its four
     * spans.
     */
    public sealed interface Wave permits Wave.Plain, Wave.Stroke, Wave.Trapezoid {

        /**
         * The token a shipped table spells this wave by, which both the table's reader and its
         * writer read here.
         *
         * @return the lower-case wave token
         */
        @NotNull String token();

        /** A wave that reads nothing beside the driver's rest and extent. */
        @EnumLookup
        @Getter(style = NamingStyle.FLUENT)
        @RequiredArgsConstructor
        enum Plain implements Wave {

            /** Held at the extent - a selected one-hot, or a level a clock settles at for a gait. */
            HOLD("hold"),

            /** The tick times the extent - elapsed age, and the stride phase. */
            RAMP("ramp"),

            /** Out and back, so one period begins and ends at rest. */
            SWEEP("sweep"),

            /** A phase, wrapping at its period. */
            CYCLE("cycle");

            /** The lower-case token this wave is spelled with in the shipped table. */
            @KeyField
            private final @NotNull String token;

        }

        /**
         * A flare and an equal hang: over the first half of a stroke phase advancing at the rate,
         * the value rises from rest to the extent and back, and it rests for the second half.
         *
         * @param rate the radians of stroke phase a tick
         */
        record Stroke(float rate) implements Wave {

            /** The token a shipped table spells a stroke by. */
            public static final @NotNull String TOKEN = "stroke";

            /**
             * Constructs a new {@code Stroke}, refusing a phase that does not advance.
             *
             * @throws IllegalArgumentException if the rate is not a finite rate above zero
             */
            public Stroke {
                if (!(rate > 0f) || !Float.isFinite(rate)) {
                    throw new IllegalArgumentException(
                        String.format("Stroke rate '%s' is not a finite rate above zero", rate));
                }
            }

            /** {@inheritDoc} */
            @Override
            public @NotNull String token() {
                return TOKEN;
            }

        }

        /**
         * A rise, a dwell, a fall and a gap in whole ticks from tick zero: the value climbs from rest
         * to the extent, holds there, returns, and rests until the cycle repeats.
         *
         * @param rise the ticks it climbs from its rest to its extent
         * @param dwell the ticks it holds at its extent
         * @param fall the ticks it returns to its rest
         * @param gap the ticks it holds at its rest before the next rise
         */
        record Trapezoid(int rise, int dwell, int fall, int gap) implements Wave {

            /** The token a shipped table spells a trapezoid by. */
            public static final @NotNull String TOKEN = "trapezoid";

            /**
             * Constructs a new {@code Trapezoid}, refusing a cycle no tick can run.
             *
             * @throws IllegalArgumentException if a span is negative or the four span no tick
             */
            public Trapezoid {
                if (rise < 0 || dwell < 0 || fall < 0 || gap < 0 || rise + dwell + fall + gap < 1) {
                    throw new IllegalArgumentException(String.format(
                        "Trapezoid spans '%s + %s + %s + %s' ticks, and a trapezoid spans at least one and none negative",
                        rise, dwell, fall, gap));
                }
            }

            /**
             * Sums the four spans - the ticks one cycle spans.
             *
             * @return {@code rise + dwell + fall + gap}
             */
            public int cycleTicks() {
                return this.rise + this.dwell + this.fall + this.gap;
            }

            /** {@inheritDoc} */
            @Override
            public @NotNull String token() {
                return TOKEN;
            }

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
            case Wave.Plain plain -> switch (plain) {
                case HOLD -> this.extent;
                case RAMP -> tick * this.extent;
                case SWEEP, CYCLE -> {
                    float phase = Math.floorMod(tick, periodTicks) / (float) periodTicks;
                    float travel = plain == Wave.Plain.CYCLE ? phase : 1f - Math.abs(2f * phase - 1f);
                    yield this.rest + (this.extent - this.rest) * travel;
                }
            };
            case Wave.Stroke stroke -> {
                float phase = (tick * stroke.rate()) % TWO_PI;
                if (phase < 0f) phase += TWO_PI;
                if (!(phase < PI)) yield this.rest;
                float f = phase / PI;
                yield this.rest + (this.extent - this.rest) * VanillaMth.mthSin((double) (f * f * PI));
            }
            case Wave.Trapezoid trapezoid -> {
                int cycle = Math.floorMod(tick, trapezoid.cycleTicks());
                float range = this.extent - this.rest;
                if (cycle < trapezoid.rise()) yield this.rest + range * (cycle / (float) trapezoid.rise());
                cycle -= trapezoid.rise();
                if (cycle < trapezoid.dwell()) yield this.extent;
                cycle -= trapezoid.dwell();
                if (cycle < trapezoid.fall()) {
                    int left = trapezoid.fall() - cycle;
                    yield this.rest + range * (left / (float) trapezoid.fall());
                }
                yield this.rest;
            }
        };
    }

}
