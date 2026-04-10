package dev.sbs.renderer.draw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

class ItemBarKitTest {

    @Test
    @DisplayName("drawDamageBar is a no-op when damage is zero")
    void noBarForUndamagedItem() {
        Canvas canvas = Canvas.of(16, 16);
        canvas.fill(0x00000000);
        ItemBarKit.drawDamageBar(canvas, 0, 100);

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++)
                assertThat("x=" + x + " y=" + y, canvas.getBuffer().getPixel(x, y), equalTo(0x00000000));
        }
    }

    @Test
    @DisplayName("drawDamageBar is a no-op when max durability is zero")
    void noBarForNonDamageableItem() {
        Canvas canvas = Canvas.of(16, 16);
        canvas.fill(0x00000000);
        ItemBarKit.drawDamageBar(canvas, 5, 0);

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++)
                assertThat(canvas.getBuffer().getPixel(x, y), equalTo(0x00000000));
        }
    }

    @Test
    @DisplayName("drawDamageBar at 50% damage fills background and partial foreground")
    void halfDamagePaintsExpectedPixels() {
        Canvas canvas = Canvas.of(16, 16);
        canvas.fill(0x00000000);
        ItemBarKit.drawDamageBar(canvas, 50, 100);

        // Background row at y=13 should have the opaque black background in at least one pixel.
        boolean foundBackgroundBlack = false;
        for (int x = 2; x < 15; x++) {
            if (canvas.getBuffer().getPixel(x, 13) == 0xFF000000) {
                foundBackgroundBlack = true;
                break;
            }
        }
        assertThat("background row contains black pixels", foundBackgroundBlack, equalTo(true));

        // Foreground row at y=14 should have at least one coloured pixel (not transparent, not black).
        int coloredPixels = 0;
        for (int x = 2; x < 14; x++) {
            int pixel = canvas.getBuffer().getPixel(x, 14);
            int alpha = ColorKit.alpha(pixel);
            if (alpha == 0xFF && pixel != 0xFF000000) coloredPixels++;
        }
        assertThat("foreground row has coloured pixels", coloredPixels, greaterThan(0));
    }

}
