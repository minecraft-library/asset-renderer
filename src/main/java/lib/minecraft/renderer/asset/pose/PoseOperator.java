package lib.minecraft.renderer.asset.pose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Every operation a shipped pose expression names, with the token it is spelled with, how many
 * operands it takes and the width it computes at.
 *
 * <p><b>Width is part of the operation, not an attribute of its operands.</b> A pose body crosses
 * into {@code double} for a divide and a multiply and narrows once at a point vanilla chose, so the
 * float family, the double family and the two conversions between them are separate operations. An
 * expression evaluated at the wrong width is wrong by less than an ULP and wrong every frame.
 *
 * <p><b>The two trigonometry contracts are two operations.</b> {@link #MTH_SIN} and {@link #MTH_COS}
 * name vanilla's sampled table where {@link #LIBM_SIN} and {@link #LIBM_COS} name the JDK's own, and
 * one model reaches both. They are never canonicalised into one another.
 *
 * <p>This is the reader's half of a vocabulary the generator writes. The two sides share no code -
 * the tables travel as tokens rather than as types - so the roster here is what the shipped bytes
 * can say, and a token outside it is a table this renderer is too old to read.
 */
public enum PoseOperator {

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

    /** Adds two ints. */
    IADD("iadd", 2, Width.INT),

    /** Subtracts the second int from the first. */
    ISUB("isub", 2, Width.INT),

    /** Multiplies two ints. */
    IMUL("imul", 2, Width.INT),

    /** Divides the first int by the second, truncating. */
    IDIV("idiv", 2, Width.INT),

    /** Takes the int remainder, which carries the sign of the dividend. */
    IREM("irem", 2, Width.INT),

    /** Negates an int. */
    INEG("ineg", 1, Width.INT),

    /** Widens a float to a double, exactly. */
    F2D("f2d", 1, Width.DOUBLE),

    /** Narrows a double to a float, rounding once. */
    D2F("d2f", 1, Width.FLOAT),

    /** Widens an int to a float. */
    I2F("i2f", 1, Width.FLOAT),

    /** Truncates a float to an int, toward zero. */
    F2I("f2i", 1, Width.INT),

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

    /** The numeric width an operation computes at, and therefore where its value rounds. */
    public enum Width {

        /** Single precision - the width a bone channel is finally stored at. */
        FLOAT,

        /** Double precision - what a value crosses into between an {@code f2d} and a {@code d2f}. */
        DOUBLE,

        /** Integral - an unrolled loop's index, a switch case, or a deliberate truncating divide. */
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
