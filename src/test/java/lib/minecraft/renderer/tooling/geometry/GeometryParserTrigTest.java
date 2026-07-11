package lib.minecraft.renderer.tooling.geometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the tooling parser's nested {@code FastTrig} to vanilla {@code Mth.sin / Mth.cos}
 * bit-parity (doc-12 K10) - the table-vs-libm gap (~1.8e-5) crosses pixel rounding for
 * WitherBoss-class entities, so the pin converts a silent drift into a loud failure.
 * Mirrors the legacy {@code FastTrigTest}'s two assertions with NO shared code
 * (decision-28 precedent): the expected values re-derive the table build and the index
 * math independently.
 */
@DisplayName("tooling FastTrig bit-parity vs an independent vanilla Mth re-derivation")
class GeometryParserTrigTest {

    /** Vanilla's hardcoded {@code 65536 / (2 * PI)} - re-declared, never shared. */
    private static final double RATIO = 10430.378350470453;

    @Test
    @DisplayName("sin matches the table build bit-for-bit across a sweep + the wither angle")
    void sinBitParity() {
        for (double angle : sweep()) {
            int index = (int) (long) (angle * RATIO) & 65535;
            float expected = (float) Math.sin((double) index / RATIO);
            assertEquals(Float.floatToIntBits(expected),
                Float.floatToIntBits(GeometryParser.FastTrig.sin(angle)),
                "sin(" + angle + ")");
        }
    }

    @Test
    @DisplayName("cos matches the quarter-rotation-offset table lookup bit-for-bit")
    void cosBitParity() {
        for (double angle : sweep()) {
            int index = (int) (long) (angle * RATIO + 16384.0) & 65535;
            float expected = (float) Math.sin((double) index / RATIO);
            assertEquals(Float.floatToIntBits(expected),
                Float.floatToIntBits(GeometryParser.FastTrig.cos(angle)),
                "cos(" + angle + ")");
        }
    }

    private static double[] sweep() {
        double[] angles = new double[1003];
        for (int i = 0; i < 1000; i++)
            angles[i] = -8.0 + i * 0.016;                       // dense sweep across +-8 rad
        angles[1000] = 0.20420352f;                             // the WitherBoss ribcage angle
        angles[1001] = -0.20420352f;
        angles[1002] = 2.0 * Math.PI;
        return angles;
    }

}
