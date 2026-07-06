package lib.minecraft.renderer.tooling.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

/**
 * Pins {@link GeometryParser.FastTrig} as a bit-identical reproduction of vanilla's {@code Mth.sin}
 * / {@code Mth.cos} 65536-entry table lookup.
 *
 * <p>Two layers of assertion:
 *
 * <ul>
 *   <li><b>Shape</b> - {@link #knownAngles} checks the table stays within {@value #TABLE_TOLERANCE} of
 *       libm at cardinal angles, confirming the port is functionally trigonometric and not merely
 *       self-consistent.
 *   <li><b>Byte identity</b> - {@link #sinBitIdentical} / {@link #cosBitIdentical} re-derive vanilla's
 *       exact index math ({@link #tableSin} / {@link #tableCos}) independently and demand
 *       {@code equalTo} on the raw {@code float}, so any drift in the double-to-long narrowing, the
 *       {@code 0xFFFF} mask, or the quarter-rotation cos offset would fail. This is what lets tooling
 *       pre-bake vanilla {@code Mth.sin}/{@code Mth.cos} bytecode calls to the same bit pattern the
 *       runtime would produce (see {@link GeometryParser.FastTrig} doc for the WitherBoss tail-pivot
 *       drift that motivates it).
 * </ul>
 */
@DisplayName("GeometryParser.FastTrig vanilla Mth.sin/cos table reproduction")
class FastTrigTest {

    /** Vanilla's {@code 65536 / (2 * PI)} index-per-radian scale factor, held as the exact literal. */
    private static final double RATIO = 10430.378350470453;

    /** Table sampling + float rounding + index quantization gap vs libm; allow a small margin. */
    private static final double TABLE_TOLERANCE = 2e-4;

    @Test
    @DisplayName("matches libm sin/cos at known angles within table tolerance")
    void knownAngles() {
        assertThat((double) GeometryParser.FastTrig.sin(0.0), closeTo(0.0, TABLE_TOLERANCE));
        assertThat((double) GeometryParser.FastTrig.cos(0.0), closeTo(1.0, TABLE_TOLERANCE));
        assertThat((double) GeometryParser.FastTrig.sin(Math.PI / 2), closeTo(1.0, TABLE_TOLERANCE));
        assertThat((double) GeometryParser.FastTrig.cos(Math.PI / 2), closeTo(0.0, TABLE_TOLERANCE));
        assertThat((double) GeometryParser.FastTrig.sin(Math.PI), closeTo(0.0, TABLE_TOLERANCE));
        assertThat((double) GeometryParser.FastTrig.cos(Math.PI), closeTo(-1.0, TABLE_TOLERANCE));
        assertThat((double) GeometryParser.FastTrig.sin(-Math.PI / 2), closeTo(-1.0, TABLE_TOLERANCE));
    }

    @Test
    @DisplayName("sin is bit-identical to vanilla's index math + table sample")
    void sinBitIdentical() {
        for (double d : new double[]{ 1.0, 2.5, -0.7, 4.2 })
            assertThat(GeometryParser.FastTrig.sin(d), equalTo(tableSin(d)));
    }

    @Test
    @DisplayName("cos is bit-identical to vanilla's quarter-rotation-offset table sample")
    void cosBitIdentical() {
        for (double d : new double[]{ 1.0, 2.5, -0.7, 4.2 })
            assertThat(GeometryParser.FastTrig.cos(d), equalTo(tableCos(d)));
    }

    /**
     * Re-derives vanilla's {@code Mth.sin} index math independently of {@link GeometryParser.FastTrig}:
     * scale by {@link #RATIO}, narrow {@code double -> long -> int}, mask to the low 16 bits, then
     * sample {@code Math.sin} at the quantized angle. Sampling {@code Math.sin} directly (rather than
     * indexing a table) is equivalent because {@code FastTrig.SIN[i] == (float) Math.sin(i / RATIO)}.
     *
     * @param d the angle in radians
     * @return the table-quantized {@code sin(d)} as vanilla would compute it
     */
    private static float tableSin(double d) {
        int index = (int) (long) (d * RATIO) & 65535;
        return (float) Math.sin(index / RATIO);
    }

    /**
     * Cos counterpart of {@link #tableSin} - adds the {@code 16384.0} quarter-rotation offset
     * ({@code 65536 / 4}) before masking, which is how vanilla phase-shifts the sine table into a
     * cosine.
     *
     * @param d the angle in radians
     * @return the table-quantized {@code cos(d)} as vanilla would compute it
     */
    private static float tableCos(double d) {
        int index = (int) (long) (d * RATIO + 16384.0) & 65535;
        return (float) Math.sin(index / RATIO);
    }

}
