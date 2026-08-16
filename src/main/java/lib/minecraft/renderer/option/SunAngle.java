package lib.minecraft.renderer.option;

import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * The vanilla sun-angle curve - the normalized angle a world day-time tick puts the sun at, which is
 * the numeric input the {@code minecraft:time} item-dispatch property reads (a clock face is picked
 * by dispatching on it).
 *
 * <p>The angle is not a linear ramp over the day. Vanilla's day timeline declares a single
 * {@code 360 -> 0} degree keyframe pair anchored at noon and eases it with a symmetric cubic Bezier,
 * so the sun sweeps fastest around dawn and dusk and slowest around noon and midnight. Sampling the
 * day at a uniform tick cadence therefore lingers on some clock faces and skips others - that
 * unevenness is the curve itself, not a rounding artefact.
 *
 * <p>Noon is the phase anchor: {@link #NOON_TICK} maps to exactly {@code 0}, midnight to {@code 0.5},
 * and the angle grows monotonically through dusk, midnight and dawn back around to a full turn.
 */
@UtilityClass
public class SunAngle {

    /** Ticks in one full day-night cycle. */
    public static final int TICKS_PER_DAY = 24_000;

    /** The day-time tick the sun stands at its zenith - the phase anchor where the angle is zero. */
    public static final int NOON_TICK = 6_000;

    /** A full turn in degrees - the units the keyframe pair interpolates in. */
    private static final float FULL_TURN_DEGREES = 360f;

    /** Newton-Raphson iterations spent inverting the easing's horizontal curve. */
    private static final int SOLVER_STEPS = 4;

    /** Gradient below which the solver stops stepping, guarding the division that follows. */
    private static final float MIN_GRADIENT = 1.0E-5f;

    /** The easing's horizontal curve, inverted per sample to recover the Bezier parameter. */
    private static final @NotNull Curve EASE_X = Curve.through(0.362f, 0.638f);

    /** The easing's vertical curve, evaluated at the recovered Bezier parameter to give the eased fraction. */
    private static final @NotNull Curve EASE_Y = Curve.through(0.241f, 0.759f);

    /**
     * Returns the normalized sun angle at a world day-time tick - the value {@code minecraft:time}
     * dispatches on, in {@code [0, 1)}, growing from {@code 0} at noon around to a full turn.
     *
     * @param dayTime the world day-time tick, wrapped into the current day
     * @return the normalized sun angle
     */
    public static float at(long dayTime) {
        long looped = Math.floorMod(dayTime, (long) TICKS_PER_DAY);
        // Both of the day track's keyframes sit at noon, so the sampler short-circuits to the track's
        // start value there. Reproduced explicitly because noon must yield a literal +0.0f - anything
        // else (a -0.0f, or a curve evaluation that lands one ULP off) would make an otherwise neutral
        // render context compare unequal to the neutral default and lose the baked fast path.
        if (looped == NOON_TICK) return 0f;

        long sinceNoon = looped < NOON_TICK ? looped + (TICKS_PER_DAY - NOON_TICK) : looped - NOON_TICK;
        float eased = ease(sinceNoon / (float) TICKS_PER_DAY);
        // Vanilla interpolates the keyframe pair in degrees and the dispatch property then divides by a
        // full turn. Both steps are kept rather than cancelled: the pair is not a float-exact round trip
        // (it moves the last ULP on roughly a sixth of the day's ticks), so cancelling them would drift
        // off vanilla's arithmetic even though no clock face changes hands over the current 64-face table.
        return eased * FULL_TURN_DEGREES / FULL_TURN_DEGREES;
    }

    /**
     * Eases a linear day fraction through the sun track's symmetric cubic Bezier: inverts the
     * horizontal curve by a fixed Newton-Raphson budget, then reads the vertical curve at the
     * recovered parameter.
     */
    private static float ease(float fraction) {
        float t = fraction;
        for (int step = 0; step < SOLVER_STEPS; step++) {
            float gradient = EASE_X.gradient(t);
            if (gradient < MIN_GRADIENT) break;
            t -= (EASE_X.sample(t) - fraction) / gradient;
        }
        return EASE_Y.sample(t);
    }

    /**
     * One coordinate curve of the easing Bezier - the cubic {@code ((a*t + b)*t + c)*t} through a unit
     * cubic Bezier whose outer control points are pinned at {@code 0} and {@code 1}.
     *
     * @param a the cubic coefficient
     * @param b the quadratic coefficient
     * @param c the linear coefficient
     */
    private record Curve(float a, float b, float c) {

        /**
         * Builds the curve through the two interior control points of a unit cubic Bezier.
         *
         * @param first the first interior control point
         * @param second the second interior control point
         * @return the coordinate curve
         */
        static @NotNull Curve through(float first, float second) {
            return new Curve(3f * first - 3f * second + 1f, -6f * first + 3f * second, 3f * first);
        }

        /**
         * Returns this curve's value at a Bezier parameter.
         *
         * @param t the Bezier parameter
         * @return the curve value
         */
        float sample(float t) {
            return ((this.a * t + this.b) * t + this.c) * t;
        }

        /**
         * Returns this curve's first derivative at a Bezier parameter.
         *
         * @param t the Bezier parameter
         * @return the curve gradient
         */
        float gradient(float t) {
            return (3f * this.a * t + 2f * this.b) * t + this.c;
        }

    }

}
