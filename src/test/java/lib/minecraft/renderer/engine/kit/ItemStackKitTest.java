package lib.minecraft.renderer.engine.kit;

import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Pins {@link ItemStackKit#drawDamageBar} pixel output on a 16x16 GUI buffer: the no-op guards
 * (zero damage, non-damageable item) and the two-row bar at 50% damage - an opaque-black
 * background row plus a partial coloured foreground row from the HSV durability sweep.
 */
class ItemStackKitTest {

    @Test
    @DisplayName("drawDamageBar is a no-op when damage is zero")
    void noBarForUndamagedItem() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, 0, 100);

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++)
                assertThat("x=" + x + " y=" + y, buffer.getPixel(x, y), equalTo(0x00000000));
        }
    }

    @Test
    @DisplayName("drawDamageBar is a no-op when max durability is zero")
    void noBarForNonDamageableItem() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, 5, 0);

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++)
                assertThat(buffer.getPixel(x, y), equalTo(0x00000000));
        }
    }

    @Test
    @DisplayName("drawDamageBar at 50% damage fills background and partial foreground")
    void halfDamagePaintsExpectedPixels() {
        // 16-wide buffer -> GUI scale 1, so logical bar rows 13/14 land on pixel rows 13/14.
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, 50, 100);

        // Background row at y=13 should have the opaque black background in at least one pixel.
        boolean foundBackgroundBlack = false;
        for (int x = 2; x < 15; x++) {
            if (buffer.getPixel(x, 13) == 0xFF000000) {
                foundBackgroundBlack = true;
                break;
            }
        }
        assertThat("background row contains black pixels", foundBackgroundBlack, equalTo(true));

        // Foreground row at y=14 is the HSV-swept durability colour (hue 60 at 50% remaining, a
        // yellow-green) painted over the left ~half of the bar. At least one pixel must be opaque
        // and non-black, distinguishing the coloured fill from both the transparent canvas and the
        // black background row.
        int coloredPixels = 0;
        for (int x = 2; x < 14; x++) {
            int pixel = buffer.getPixel(x, 14);
            int alpha = ColorMath.alpha(pixel);
            if (alpha == 0xFF && pixel != 0xFF000000) coloredPixels++;
        }
        assertThat("foreground row has coloured pixels", coloredPixels, greaterThan(0));
    }

}
