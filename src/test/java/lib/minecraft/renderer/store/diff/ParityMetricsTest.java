package lib.minecraft.renderer.store.diff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * The comparison every sweep reports through, asserted on the pixel pairs where its two counts part
 * company.
 *
 * <p>The over-white count is what a person reads and the raw count is the byte gate, and on a table
 * whose rows differ in colour wherever they differ at all the two print the same number. So the
 * definition of the raw count is held here, on the two shapes over-white is blind to, rather than by
 * any table.
 */
@DisplayName("The parity comparison counts raw ARGB differences beside the over-white ones")
final class ParityMetricsTest {

    /**
     * A one-pixel image holding exactly the given packed ARGB.
     *
     * @param argb the pixel, non-premultiplied
     * @return the image
     */
    private static BufferedImage pixel(int argb) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        return image;
    }

    @Test
    @DisplayName("two fully transparent pixels with different colour differ raw and not over white")
    void theColourUnderZeroAlphaIsARawDifference() {
        ParityMetrics.Stats stats = ParityMetrics.compareImages(pixel(0x00FF0000), pixel(0x0000FF00));

        assertThat("over white, a pixel at zero alpha is the white it sits on whatever colour it "
            + "holds", stats.differingPixels(), equalTo(0L));
        assertThat("raw, the held bytes differ, which is the difference a writer that keeps the "
            + "colour under zero alpha and one that clears it cannot hide from a byte gate",
            stats.rawDifferingPixels(), equalTo(1L));
    }

    @Test
    @DisplayName("two translucent pixels landing on one colour over white differ raw and not over white")
    void aTranslucentCollisionIsARawDifference() {
        // At alpha 1 a channel holding 0 and one holding 1 composite alike: (0 + 255 * 254 + 127) / 255
        // and (1 + 255 * 254 + 127) / 255 both floor to 254.
        ParityMetrics.Stats stats = ParityMetrics.compareImages(pixel(0x01000000), pixel(0x01010101));

        assertThat("over white, the rounding folds the two onto one colour", stats.differingPixels(),
            equalTo(0L));
        assertThat("raw, the one-step difference in every channel is still there",
            stats.rawDifferingPixels(), equalTo(1L));
    }

    @Test
    @DisplayName("equal pixels differ under neither count, and opaque different ones under both")
    void theCountsAgreeWhereOverWhiteHidesNothing() {
        ParityMetrics.Stats same = ParityMetrics.compareImages(pixel(0x80123456), pixel(0x80123456));
        ParityMetrics.Stats opaque = ParityMetrics.compareImages(pixel(0xFF000000), pixel(0xFF000001));

        assertThat("equal bytes, over white", same.differingPixels(), equalTo(0L));
        assertThat("equal bytes, raw", same.rawDifferingPixels(), equalTo(0L));
        assertThat("an opaque one-step difference, over white", opaque.differingPixels(), equalTo(1L));
        assertThat("an opaque one-step difference, raw", opaque.rawDifferingPixels(), equalTo(1L));
    }

}
