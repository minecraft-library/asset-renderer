package lib.minecraft.renderer.tooling.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("FastTrig vanilla Mth.sin/cos table reproduction")
class FastTrigTest {

    private static final double RATIO = 10430.378350470453;
    /** Table sampling + float rounding + index quantization gap vs libm; allow a small margin. */
    private static final double TABLE_TOLERANCE = 2e-4;

    @Test
    @DisplayName("matches libm sin/cos at known angles within table tolerance")
    void knownAngles() {
        assertThat((double) FastTrig.sin(0.0), closeTo(0.0, TABLE_TOLERANCE));
        assertThat((double) FastTrig.cos(0.0), closeTo(1.0, TABLE_TOLERANCE));
        assertThat((double) FastTrig.sin(Math.PI / 2), closeTo(1.0, TABLE_TOLERANCE));
        assertThat((double) FastTrig.cos(Math.PI / 2), closeTo(0.0, TABLE_TOLERANCE));
        assertThat((double) FastTrig.sin(Math.PI), closeTo(0.0, TABLE_TOLERANCE));
        assertThat((double) FastTrig.cos(Math.PI), closeTo(-1.0, TABLE_TOLERANCE));
        assertThat((double) FastTrig.sin(-Math.PI / 2), closeTo(-1.0, TABLE_TOLERANCE));
    }

    @Test
    @DisplayName("sin is bit-identical to vanilla's index math + table sample")
    void sinBitIdentical() {
        for (double d : new double[]{ 1.0, 2.5, -0.7, 4.2 })
            assertThat(FastTrig.sin(d), equalTo(tableSin(d)));
    }

    @Test
    @DisplayName("cos is bit-identical to vanilla's quarter-rotation-offset table sample")
    void cosBitIdentical() {
        for (double d : new double[]{ 1.0, 2.5, -0.7, 4.2 })
            assertThat(FastTrig.cos(d), equalTo(tableCos(d)));
    }

    /** Re-derives vanilla's {@code Mth.sin} index math independently of the implementation. */
    private static float tableSin(double d) {
        int index = (int) (long) (d * RATIO) & 65535;
        return (float) Math.sin(index / RATIO);
    }

    private static float tableCos(double d) {
        int index = (int) (long) (d * RATIO + 16384.0) & 65535;
        return (float) Math.sin(index / RATIO);
    }

}
