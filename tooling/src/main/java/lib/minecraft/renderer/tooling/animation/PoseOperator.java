package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.tooling.kernel.VanillaEase;
import lib.minecraft.renderer.tooling.kernel.VanillaMth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Every operation a pose expression can name, with the token it is spelled with, how many operands
 * it takes and the width it computes at.
 *
 * <p><b>Width rides the token.</b> A {@code setupAnim} body is not float-only: a value crosses into
 * {@code double}, stays there across a divide and a multiply, and narrows once at a point vanilla
 * chose. Reproducing that needs the width of every operation to be part of what is recorded, so the
 * arithmetic comes in a float family and a double family and the two conversions between them are
 * operations in their own right rather than an attribute a reader has to infer. An expression that
 * folded a double divide at float width would be wrong by less than an ULP and wrong every frame.
 *
 * <p><b>The two trigonometry contracts are two operators.</b> {@link #MTH_SIN} and {@link #MTH_COS}
 * sample vanilla's 65536-entry table; {@link #LIBM_SIN} and {@link #LIBM_COS} call the JDK. One
 * corpus reaches both, sometimes in the same method, and the gap between them is roughly
 * {@code 1.8e-5} - invisible until a limb length multiplies it across a pixel boundary. They are
 * never canonicalised into one another.
 *
 * <p>The roster is what the reachable corpus proves it calls, not what vanilla declares. An
 * operation outside it has to arrive as a failed walk naming the method it met, because the one
 * forbidden outcome is a pose that is quietly missing an operand.
 */
public enum PoseOperator {

    // Float arithmetic - the default family.

    /** Adds two floats. */
    ADD("add", 2, Width.FLOAT),

    /** Subtracts the second float from the first. */
    SUB("sub", 2, Width.FLOAT),

    /** Multiplies two floats. */
    MUL("mul", 2, Width.FLOAT),

    /** Divides the first float by the second. */
    DIV("div", 2, Width.FLOAT),

    /** Takes the float remainder, which carries the sign of the dividend. */
    REM("rem", 2, Width.FLOAT),

    /** Negates a float. */
    NEG("neg", 1, Width.FLOAT),

    // Double arithmetic - emitted only where the bytecode stayed wide.

    /** Adds two doubles. */
    DADD("dadd", 2, Width.DOUBLE),

    /** Subtracts the second double from the first. */
    DSUB("dsub", 2, Width.DOUBLE),

    /** Multiplies two doubles. */
    DMUL("dmul", 2, Width.DOUBLE),

    /** Divides the first double by the second. */
    DDIV("ddiv", 2, Width.DOUBLE),

    /** Negates a double. */
    DNEG("dneg", 1, Width.DOUBLE),

    // Integer arithmetic - what survives after a loop counter has been unrolled away.

    /** Adds two ints. */
    IADD("iadd", 2, Width.INT),

    /** Subtracts the second int from the first. */
    ISUB("isub", 2, Width.INT),

    /** Multiplies two ints. */
    IMUL("imul", 2, Width.INT),

    /** Divides the first int by the second, truncating - the step is the look, not a rounding. */
    IDIV("idiv", 2, Width.INT),

    /** Takes the int remainder, which carries the sign of the dividend. */
    IREM("irem", 2, Width.INT),

    /** Negates an int. */
    INEG("ineg", 1, Width.INT),

    // Width conversions - the narrowing point is the value, so it is a node.

    /** Widens a float to a double, exactly. */
    F2D("f2d", 1, Width.DOUBLE),

    /** Narrows a double to a float, rounding once. */
    D2F("d2f", 1, Width.FLOAT),

    /** Widens an int to a float. */
    I2F("i2f", 1, Width.FLOAT),

    /** Widens an int to a double, exactly. */
    I2D("i2d", 1, Width.DOUBLE),

    /** Truncates a float to an int, toward zero. */
    F2I("f2i", 1, Width.INT),

    // Vanilla Mth - reproduced bit-for-bit rather than called.

    /** Samples the vanilla sine table, which is not the libm sine. */
    MTH_SIN("mth_sin", 1, Width.FLOAT),

    /** Samples the vanilla sine table a quarter turn ahead, which is not the libm cosine. */
    MTH_COS("mth_cos", 1, Width.FLOAT),

    /** Takes a square root by widening and narrowing once. */
    SQRT("sqrt", 1, Width.FLOAT),

    /** Clamps a value between a low and a high bound. */
    CLAMP("clamp", 3, Width.FLOAT),

    /** Interpolates from the second operand to the third by the first. */
    LERP("lerp", 3, Width.FLOAT),

    /** Answers where a value sits between two bounds. */
    INVERSE_LERP("inverse_lerp", 3, Width.FLOAT),

    /** Interpolates two angles in degrees along the shorter arc. */
    ROT_LERP("rot_lerp", 3, Width.FLOAT),

    /** Interpolates two angles in radians along the shorter arc. */
    ROT_LERP_RAD("rot_lerp_rad", 3, Width.FLOAT),

    /** Wraps an angle in degrees into the half-open range about zero. */
    WRAP_DEGREES("wrap_degrees", 1, Width.FLOAT),

    /** Answers a triangle wave of a given period. */
    TRIANGLE_WAVE("triangle_wave", 2, Width.FLOAT),

    /** Answers the smaller of two floats. */
    MIN("min", 2, Width.FLOAT),

    /** Answers the larger of two floats. */
    MAX("max", 2, Width.FLOAT),

    /** Answers the magnitude of a float. */
    ABS("abs", 1, Width.FLOAT),

    /** Answers the magnitude of an int. */
    IABS("iabs", 1, Width.INT),

    // The JDK's own double math, which vanilla reaches directly at a handful of sites.

    /** Calls the JDK sine, which is not the sampled table. */
    LIBM_SIN("libm_sin", 1, Width.DOUBLE),

    /** Calls the JDK cosine, which is not the sampled table. */
    LIBM_COS("libm_cos", 1, Width.DOUBLE),

    /** Answers the magnitude of a double. */
    LIBM_ABS("libm_abs", 1, Width.DOUBLE),

    /** Answers the sign of a double as one of minus one, zero or one. */
    LIBM_SIGNUM("libm_signum", 1, Width.DOUBLE),

    /** Calls the JDK square root, which is exact where the sampled one is not. */
    LIBM_SQRT("libm_sqrt", 1, Width.DOUBLE),

    /** Answers the larger of two doubles. */
    LIBM_MAX("libm_max", 2, Width.DOUBLE),

    // The eight reachable easings.

    /** Eases in along a quarter circle. */
    EASE_IN_CIRC("ease_in_circ", 1, Width.FLOAT),

    /** Eases in quadratically. */
    EASE_IN_QUAD("ease_in_quad", 1, Width.FLOAT),

    /** Eases out along a quarter circle. */
    EASE_OUT_CIRC("ease_out_circ", 1, Width.FLOAT),

    /** Eases out cubically. */
    EASE_OUT_CUBIC("ease_out_cubic", 1, Width.FLOAT),

    /** Eases out quartically. */
    EASE_OUT_QUART("ease_out_quart", 1, Width.FLOAT),

    /** Eases in and out along a half cosine sampled from the vanilla table. */
    EASE_IN_OUT_SINE("ease_in_out_sine", 1, Width.FLOAT),

    /** Eases in and out exponentially. */
    EASE_IN_OUT_EXPO("ease_in_out_expo", 1, Width.FLOAT),

    /** Eases in and out elastically. */
    EASE_IN_OUT_ELASTIC("ease_in_out_elastic", 1, Width.FLOAT);

    /** The numeric width an operation computes at, which decides where a value rounds. */
    public enum Width {

        /** Single precision - the width a bone channel is finally stored at. */
        FLOAT,

        /** Double precision - what a value crosses into between an {@code f2d} and a {@code d2f}. */
        DOUBLE,

        /** Integral - a loop counter, a phase index, or vanilla's deliberate truncating divide. */
        INT

    }

    private final @NotNull String token;
    private final int arity;
    private final @NotNull Width width;

    PoseOperator(@NotNull String token, int arity, @NotNull Width width) {
        this.token = token;
        this.arity = arity;
        this.width = width;
    }

    /**
     * The token this operation is spelled with in the shipped table.
     *
     * @return the snake-case token
     */
    public @NotNull String token() {
        return this.token;
    }

    /**
     * How many operands this operation takes.
     *
     * @return the operand count
     */
    public int arity() {
        return this.arity;
    }

    /**
     * The width this operation computes at, and therefore the width of its result.
     *
     * @return the numeric width
     */
    public @NotNull Width width() {
        return this.width;
    }

    /**
     * Applies this operation to concrete operands.
     *
     * <p>Every arm is the same call the renderer will make on the same values, so an expression
     * folded here and the same expression evaluated there answer the same bits. Operands arrive as
     * {@code double} because that is the only carrier wide enough to hold all three widths without
     * loss; each arm narrows its own way back out, so a float operation rounds exactly once and an
     * integral one truncates rather than rounding at all.
     *
     * @param operands the operand values, in declaration order
     * @return the result, at this operation's own width
     * @throws IllegalArgumentException if the operand count is not this operation's arity
     */
    public double apply(double @NotNull ... operands) {
        if (operands.length != this.arity)
            throw new IllegalArgumentException(
                "'" + this.token + "' takes " + this.arity + " operand(s), got " + operands.length);
        return switch (this) {
            case ADD -> (float) operands[0] + (float) operands[1];
            case SUB -> (float) operands[0] - (float) operands[1];
            case MUL -> (float) operands[0] * (float) operands[1];
            case DIV -> (float) operands[0] / (float) operands[1];
            case REM -> (float) operands[0] % (float) operands[1];
            case NEG -> -(float) operands[0];
            case DADD -> operands[0] + operands[1];
            case DSUB -> operands[0] - operands[1];
            case DMUL -> operands[0] * operands[1];
            case DDIV -> operands[0] / operands[1];
            case DNEG -> -operands[0];
            case IADD -> (int) operands[0] + (int) operands[1];
            case ISUB -> (int) operands[0] - (int) operands[1];
            case IMUL -> (int) operands[0] * (int) operands[1];
            case IDIV -> (int) operands[0] / (int) operands[1];
            case IREM -> (int) operands[0] % (int) operands[1];
            case INEG -> -(int) operands[0];
            case F2D -> operands[0];
            case D2F -> (float) operands[0];
            case I2F -> (float) (int) operands[0];
            case I2D -> (double) (int) operands[0];
            case F2I -> (int) (float) operands[0];
            case MTH_SIN -> VanillaMth.mthSin(operands[0]);
            case MTH_COS -> VanillaMth.mthCos(operands[0]);
            case SQRT -> VanillaMth.sqrt((float) operands[0]);
            case CLAMP -> VanillaMth.clamp((float) operands[0], (float) operands[1], (float) operands[2]);
            case LERP -> VanillaMth.lerp((float) operands[0], (float) operands[1], (float) operands[2]);
            case INVERSE_LERP -> VanillaMth.inverseLerp((float) operands[0], (float) operands[1], (float) operands[2]);
            case ROT_LERP -> VanillaMth.rotLerp((float) operands[0], (float) operands[1], (float) operands[2]);
            case ROT_LERP_RAD -> VanillaMth.rotLerpRad((float) operands[0], (float) operands[1], (float) operands[2]);
            case WRAP_DEGREES -> VanillaMth.wrapDegrees((float) operands[0]);
            case TRIANGLE_WAVE -> VanillaMth.triangleWave((float) operands[0], (float) operands[1]);
            case MIN -> Math.min((float) operands[0], (float) operands[1]);
            case MAX -> Math.max((float) operands[0], (float) operands[1]);
            case ABS -> Math.abs((float) operands[0]);
            case IABS -> Math.abs((int) operands[0]);
            case LIBM_SIN -> Math.sin(operands[0]);
            case LIBM_COS -> Math.cos(operands[0]);
            case LIBM_ABS -> Math.abs(operands[0]);
            case LIBM_SIGNUM -> Math.signum(operands[0]);
            case LIBM_SQRT -> Math.sqrt(operands[0]);
            case LIBM_MAX -> Math.max(operands[0], operands[1]);
            case EASE_IN_CIRC -> VanillaEase.inCirc((float) operands[0]);
            case EASE_IN_QUAD -> VanillaEase.inQuad((float) operands[0]);
            case EASE_OUT_CIRC -> VanillaEase.outCirc((float) operands[0]);
            case EASE_OUT_CUBIC -> VanillaEase.outCubic((float) operands[0]);
            case EASE_OUT_QUART -> VanillaEase.outQuart((float) operands[0]);
            case EASE_IN_OUT_SINE -> VanillaEase.inOutSine((float) operands[0]);
            case EASE_IN_OUT_EXPO -> VanillaEase.inOutExpo((float) operands[0]);
            case EASE_IN_OUT_ELASTIC -> VanillaEase.inOutElastic((float) operands[0]);
        };
    }

    /**
     * Resolves the operation a token names.
     *
     * @param token the token to resolve
     * @return the operation, or {@code null} when no operation is spelled that way
     */
    public static @Nullable PoseOperator ofToken(@NotNull String token) {
        for (PoseOperator operator : values())
            if (operator.token.equals(token)) return operator;
        return null;
    }

}
