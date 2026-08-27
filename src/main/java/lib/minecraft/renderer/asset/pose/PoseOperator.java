package lib.minecraft.renderer.asset.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.tensor.VanillaEase;
import lib.minecraft.renderer.tensor.VanillaMth;
import org.jetbrains.annotations.NotNull;

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
 *
 * <p><b>{@link #apply} is what makes folding at extraction safe.</b> The generator folds every
 * sub-expression whose operands it already knows, so most of what a table ships was computed on the
 * other side of that divide; the arithmetic here has to answer the same bits for the rest, or a
 * folded term and an evaluated one are two different poses in one expression. The two copies are
 * held to each other by test rather than by a shared type.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
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

    /** Widens an int to a double, exactly. */
    I2D("i2d", 1, Width.DOUBLE),

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

    /** Answers the larger of two doubles. */
    LIBM_MAX("libm_max", 2, Width.DOUBLE),

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

    /** The snake-case token this operation is spelled with in the shipped table. */
    @KeyField
    private final @NotNull String token;

    /** How many operands this operation takes. */
    private final int arity;

    /** The width this operation computes at, and therefore the width of its result. */
    private final @NotNull Width width;

    /**
     * Applies this operation to concrete operands.
     *
     * <p>Every arm is the same call the generator made when it folded, so an expression folded there
     * and the same expression evaluated here answer the same bits. Operands arrive as {@code double}
     * because that is the only carrier wide enough to hold all three widths without loss; each arm
     * narrows its own way back out, so a float operation rounds exactly once and an integral one
     * truncates rather than rounding at all.
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

}
