package dev.sbs.renderer.draw;

import dev.simplified.image.ColorMath;
import dev.simplified.image.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

class ItemBarKitTest {

    @Test
    @DisplayName("drawDamageBar is a no-op when damage is zero")
    void noBarForUndamagedItem() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemBarKit.drawDamageBar(buffer, 0, 100);

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++)
                assertThat("x=" + x + " y=" + y, buffer.getPixel(x, y), equalTo(0x00000000));
        }
    }

    @Test
    @DisplayName("drawDamageBar is a no-op when max durability is zero")
    void noBarForNonDamageableItem() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemBarKit.drawDamageBar(buffer, 5, 0);

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++)
                assertThat(buffer.getPixel(x, y), equalTo(0x00000000));
        }
    }

    @Test
    @DisplayName("drawDamageBar at 50% damage fills background and partial foreground")
    void halfDamagePaintsExpectedPixels() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemBarKit.drawDamageBar(buffer, 50, 100);

        // Background row at y=13 should have the opaque black background in at least one pixel.
        boolean foundBackgroundBlack = false;
        for (int x = 2; x < 15; x++) {
            if (buffer.getPixel(x, 13) == 0xFF000000) {
                foundBackgroundBlack = true;
                break;
            }
        }
        assertThat("background row contains black pixels", foundBackgroundBlack, equalTo(true));

        // Foreground row at y=14 should have at least one coloured pixel (not transparent, not black).
        int coloredPixels = 0;
        for (int x = 2; x < 14; x++) {
            int pixel = buffer.getPixel(x, 14);
            int alpha = ColorMath.alpha(pixel);
            if (alpha == 0xFF && pixel != 0xFF000000) coloredPixels++;
        }
        assertThat("foreground row has coloured pixels", coloredPixels, greaterThan(0));
    }

}
