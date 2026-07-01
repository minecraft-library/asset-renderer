package lib.minecraft.renderer.tooling.util;

import lombok.experimental.UtilityClass;

/**
 * Bit-identical port of vanilla Minecraft's {@code net.minecraft.util.Mth.sin / Mth.cos} table
 * lookup. Tooling uses this when emulating a vanilla bytecode {@code INVOKESTATIC Mth.sin (D)F}
 * / {@code Mth.cos (D)F} call so the resulting pre-baked float lands at the same bit pattern
 * vanilla's runtime would produce.
 * <p>
 * Vanilla's implementation:
 * <pre>{@code
 *   private static final float[] SIN = new float[65536];
 *   static {
 *       for (int i = 0; i < 65536; i++)
 *           SIN[i] = (float) Math.sin((double) i / 10430.378350470453);  // i / (65536 / 2pi)
 *   }
 *   public static float sin(double d) {
 *       return SIN[(int) (long) (d * 10430.378350470453) & 65535];
 *   }
 *   public static float cos(double d) {
 *       return SIN[(int) (long) (d * 10430.378350470453 + 16384.0) & 65535];
 *   }
 * }</pre>
 * Every operation matches the bytecode: index math in {@code double}, conversion to
 * {@code long} via Java's narrowing convention, mask with {@code 0xFFFF} (65535), narrow to
 * {@code int}, array load. The {@code cos} offset {@code 16384.0} is a quarter rotation
 * ({@code 65536 / 4}), the table's phase shift from sine to cosine.
 * <p>
 * Why this matters: {@code Math.cos / Math.sin} are libm calls accurate to roughly machine
 * epsilon. The 65536-entry table samples sin at multiples of {@code 2pi/65536 ~= 9.587e-5 rad}
 * and rounds intermediate values to single-precision float when populating the array. The
 * table-vs-libm gap is up to ~1.8e-5 in absolute value - tiny, but multiplied by the
 * {@code * 10.0F} in WitherBossModel's tail-pivot computation it surfaces as a 0.0002-unit
 * float drift on the tail pivot Y, which is enough to shift the entity's projected screen
 * bounds across the canvas-pixel rounding boundary.
 */
@UtilityClass
public class FastTrig {

    /**
     * Vanilla's {@code 65536 / (2 * PI)} constant - the index-per-radian scale factor. Held as
     * the exact {@code double} literal vanilla hardcodes (not recomputed) so the multiply that
     * feeds the table index is bit-identical.
     */
    private static final double MTH_PI_RATIO = 10430.378350470453;

    /**
     * The 65536-entry sin lookup table. Element {@code i} holds
     * {@code (float) Math.sin(i / 10430.378350470453)}. Initialised eagerly so the first
     * call site does not pay table-population cost.
     */
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < 65536; i++)
            SIN[i] = (float) Math.sin((double) i / MTH_PI_RATIO);
    }

    /**
     * Bit-identical reproduction of vanilla {@code Mth.sin(double)}.
     *
     * @param d the angle in radians
     * @return {@code sin(d)} sampled from the 65536-entry table
     */
    public static float sin(double d) {
        return SIN[(int) (long) (d * MTH_PI_RATIO) & 65535];
    }

    /**
     * Bit-identical reproduction of vanilla {@code Mth.cos(double)}.
     *
     * @param d the angle in radians
     * @return {@code cos(d)} sampled from the 65536-entry table (offset by a quarter rotation)
     */
    public static float cos(double d) {
        return SIN[(int) (long) (d * MTH_PI_RATIO + 16384.0) & 65535];
    }

}
