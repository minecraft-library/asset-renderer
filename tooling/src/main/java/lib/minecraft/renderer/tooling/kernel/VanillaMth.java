package lib.minecraft.renderer.tooling.kernel;

import dev.simplified.annotations.UtilityClass;

import java.util.function.IntPredicate;

/**
 * Bit-exact reproductions of the vanilla {@code net.minecraft.util.Mth} scalar helpers, alongside
 * the 65536-entry sine table its trigonometry is sampled from - this build's half of a pair.
 *
 * <p>Every body here is transcribed operand-for-operand from the shipped client bytecode rather
 * than rewritten into an algebraically equal form. Float arithmetic is neither associative nor
 * distributive, so {@code (a - b) * t + b} and {@code b + t * (a - b)} are different functions at
 * the bit level, and a mesh or a folded pose is composed of thousands of these.
 *
 * <p><b>Why it exists twice.</b> The renderer declares the same type and this build cannot see it,
 * the dependency running the other way. So each side carries its own copy and each pins it against
 * the same independently declared constants, which is what makes a drift fail on the side that
 * moved rather than surfacing later as a moved pixel.
 *
 * <p><b>Why the table exists.</b> {@link #mthSin} and {@link #mthCos} are a lookup into 65536
 * samples, not a libm call. The table-vs-libm gap reaches roughly {@code 1.8e-5}, which is tiny
 * until it is multiplied by a limb length and crosses a canvas-pixel rounding boundary. The two
 * are separate contracts and are never unified: a caller wanting libm asks {@link Math} by name,
 * and the {@code mth} prefix on exactly the two table-sampled methods is what keeps the choice
 * visible at the call site.
 *
 * @see Math#sin(double)
 */
@UtilityClass
public final class VanillaMth {

    /**
     * Vanilla's hardcoded {@code 65536 / (2 * PI)} - the index-per-radian scale. Held as the exact
     * {@code double} literal the client carries rather than recomputed, so the multiply feeding the
     * table index is bit-identical.
     */
    private static final double PI_RATIO = 10430.378350470453;

    /** The quarter-rotation index offset that turns the sine table into a cosine lookup. */
    private static final double QUARTER_TURN = 16384.0;

    /**
     * The 65536-entry sine table, element {@code i} holding
     * {@code (float) Math.sin(i / 10430.378350470453)}. Built eagerly, matching the client, so the
     * division that fills it is the one the client performs and not an equal-valued rearrangement.
     */
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++)
            SIN[i] = (float) Math.sin((double) i / PI_RATIO);
    }

    /**
     * Samples the sine table at an angle.
     *
     * @param radians the angle in radians
     * @return the tabulated sine, which is not the libm value
     */
    public static float mthSin(double radians) {
        return SIN[(int) (long) (radians * PI_RATIO) & 65535];
    }

    /**
     * Samples the sine table a quarter rotation ahead of an angle.
     *
     * @param radians the angle in radians
     * @return the tabulated cosine, which is not the libm value
     */
    public static float mthCos(double radians) {
        return SIN[(int) (long) (radians * PI_RATIO + QUARTER_TURN) & 65535];
    }

    /**
     * Computes a square root by widening to {@code double} and narrowing the result back.
     *
     * @param value the radicand
     * @return the square root, rounded once on the way out
     */
    public static float sqrt(float value) {
        return (float) Math.sqrt(value);
    }

    /**
     * Multiplies a value by itself.
     *
     * @param value the value to square
     * @return {@code value * value}
     */
    public static float square(float value) {
        return value * value;
    }

    /**
     * Multiplies a value by itself twice, left to right.
     *
     * @param value the value to cube
     * @return {@code value * value * value}
     */
    public static float cube(float value) {
        return value * value * value;
    }

    /**
     * Interpolates between two values.
     *
     * @param delta the interpolation fraction
     * @param start the value at {@code delta} zero
     * @param end the value at {@code delta} one
     * @return {@code start + delta * (end - start)}
     */
    public static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    /**
     * Interpolates between two double-precision values.
     *
     * @param delta the interpolation fraction
     * @param start the value at {@code delta} zero
     * @param end the value at {@code delta} one
     * @return {@code start + delta * (end - start)}
     */
    public static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    /**
     * Recovers the fraction a value sits at between two bounds.
     *
     * @param value the value to locate
     * @param start the value mapping to zero
     * @param end the value mapping to one
     * @return {@code (value - start) / (end - start)}
     */
    public static float inverseLerp(float value, float start, float end) {
        return (value - start) / (end - start);
    }

    /**
     * Confines a value to a range, taking the lower bound on a NaN comparison exactly as the client
     * does - the low test is {@code <} and the high one is {@link Math#min(float, float)}, which are
     * not symmetric and are not replaceable by a pair of comparisons.
     *
     * @param value the value to confine
     * @param min the lower bound
     * @param max the upper bound
     * @return the confined value
     */
    public static float clamp(float value, float min, float max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    /**
     * Folds an angle in degrees into {@code [-180, 180)}.
     *
     * @param degrees the angle to fold
     * @return the folded angle
     */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    /**
     * Interpolates between two angles in degrees along the shorter arc.
     *
     * @param delta the interpolation fraction
     * @param start the angle at {@code delta} zero
     * @param end the angle at {@code delta} one
     * @return the interpolated angle in degrees
     */
    public static float rotLerp(float delta, float start, float end) {
        return start + delta * wrapDegrees(end - start);
    }

    /**
     * Interpolates between two angles in radians along the shorter arc. The fold is a pair of
     * loops rather than a remainder, so an angle many turns away costs a turn per iteration and
     * lands on the same value the client's would.
     *
     * @param delta the interpolation fraction
     * @param start the angle at {@code delta} zero
     * @param end the angle at {@code delta} one
     * @return the interpolated angle in radians
     */
    public static float rotLerpRad(float delta, float start, float end) {
        float arc = end - start;
        while (arc < -3.1415927F) arc += 6.2831855F;
        while (arc >= 3.1415927F) arc -= 6.2831855F;
        return start + delta * arc;
    }

    /**
     * Maps a value onto a triangle wave normalized to {@code [-1, 1]}.
     *
     * @param value the position along the wave
     * @param period the wavelength
     * @return the wave height
     */
    public static float triangleWave(float value, float period) {
        return (Math.abs(value % period - period * 0.5F) - period * 0.25F) / (period * 0.25F);
    }

    /**
     * Evaluates a Catmull-Rom spline through four control points.
     *
     * @param delta the position between {@code p1} and {@code p2}
     * @param p0 the control point before the segment
     * @param p1 the control point the segment starts at
     * @param p2 the control point the segment ends at
     * @param p3 the control point after the segment
     * @return the point on the spline
     */
    public static float catmullrom(float delta, float p0, float p1, float p2, float p3) {
        return 0.5F * (2.0F * p1 + (p2 - p0) * delta
            + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * delta * delta
            + (3.0F * p1 - p0 - 3.0F * p2 + p3) * delta * delta * delta);
    }

    /**
     * Returns the absolute value of an integer.
     *
     * @param value the value to take the magnitude of
     * @return the magnitude
     */
    public static int abs(int value) {
        return Math.abs(value);
    }

    /**
     * Finds the lowest index in a half-open range that satisfies a predicate, assuming the
     * predicate is false below that index and true from it upward.
     *
     * @param min the inclusive low end of the range
     * @param max the exclusive high end of the range
     * @param isTargetBeyondMid the monotone predicate to locate the boundary of
     * @return the boundary index, or {@code max} when the predicate holds nowhere
     */
    public static int binarySearch(int min, int max, IntPredicate isTargetBeyondMid) {
        int remaining = max - min;
        while (remaining > 0) {
            int half = remaining / 2;
            int mid = min + half;
            if (isTargetBeyondMid.test(mid)) {
                remaining = half;
            } else {
                min = mid + 1;
                remaining -= half + 1;
            }
        }
        return min;
    }

}
