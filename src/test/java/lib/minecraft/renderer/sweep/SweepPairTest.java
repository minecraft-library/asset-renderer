package lib.minecraft.renderer.sweep;

import lib.minecraft.renderer.store.diff.ParityMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Which rows of a single-pair sweep keep a diff and a panel: raw identity of the two renders, and the
 * stale pair a row that stopped differing would otherwise keep.
 */
@DisplayName("A sweep row writes its diff and panel only where its two renders differ")
final class SweepPairTest {

    /** A translucent grey, the shape a render's edge pixels take. */
    private static final int GREY = 0x80808080;

    /**
     * Builds a canvas of one colour.
     *
     * @param width the canvas width
     * @param height the canvas height
     * @param argb the colour every pixel holds
     * @return the canvas
     */
    private static BufferedImage filled(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, argb);
        return image;
    }

    /**
     * Answers the identity rule over two images compared the way a sweep compares them.
     *
     * @param vanilla the reference image
     * @param java the render
     * @return whether the two are one image
     */
    private static boolean identical(BufferedImage vanilla, BufferedImage java) {
        int width = Math.max(vanilla.getWidth(), java.getWidth());
        int height = Math.max(vanilla.getHeight(), java.getHeight());
        return SweepPair.identical(vanilla, java, ParityMetrics.compareImages(
            ParityMetrics.padToCanvas(vanilla, width, height), ParityMetrics.padToCanvas(java, width, height)));
    }

    @Test
    @DisplayName("one canvas holding one packed ARGB at every pixel is identical")
    void theSameImageIsIdentical() {
        assertThat(identical(filled(4, 4, GREY), filled(4, 4, GREY)), is(true));
    }

    @Test
    @DisplayName("one pixel apart is not")
    void onePixelApartIsNot() {
        BufferedImage java = filled(4, 4, GREY);
        java.setRGB(2, 1, 0x80808081);

        assertThat(identical(filled(4, 4, GREY), java), is(false));
    }

    @Test
    @DisplayName("the colour under a transparent pixel counts, which over white hides")
    void theColourUnderATransparentPixelCounts() {
        BufferedImage vanilla = filled(4, 4, 0x00000000);
        BufferedImage java = filled(4, 4, 0x00000000);
        java.setRGB(0, 0, 0x00FFFFFF);
        ParityMetrics.Stats stats = ParityMetrics.compareImages(vanilla, java);

        assertThat("over white the two agree", stats.differingPixels(), equalTo(0L));
        assertThat("raw they do not", identical(vanilla, java), is(false));
    }

    @Test
    @DisplayName("two canvases are not one image, even where padding makes every pixel agree")
    void twoCanvasesAreNotOneImage() {
        assertThat(identical(filled(4, 4, 0x00000000), filled(2, 2, 0x00000000)), is(false));
    }

    @Test
    @DisplayName("an identical pair deletes the diff and panel an earlier run left")
    void anIdenticalPairDeletesAStalePair(@TempDir Path row) throws IOException {
        Files.writeString(row.resolve(SweepPair.DIFF), "stale");
        Files.writeString(row.resolve(SweepPair.PANEL), "stale");

        ParityMetrics.Stats stats = SweepPair.compare(row, filled(4, 4, GREY), filled(4, 4, GREY));

        assertThat("the comparison still runs", stats.rawDifferingPixels(), equalTo(0L));
        assertThat(Files.exists(row.resolve(SweepPair.DIFF)), is(false));
        assertThat(Files.exists(row.resolve(SweepPair.PANEL)), is(false));
    }

    @Test
    @DisplayName("a differing pair writes both")
    void aDifferingPairWritesBoth(@TempDir Path row) throws IOException {
        BufferedImage java = filled(4, 4, GREY);
        java.setRGB(3, 3, 0xFF000000);

        ParityMetrics.Stats stats = SweepPair.compare(row, filled(4, 4, GREY), java);

        assertThat(stats.rawDifferingPixels(), equalTo(1L));
        assertThat(Files.isRegularFile(row.resolve(SweepPair.DIFF)), is(true));
        assertThat(Files.isRegularFile(row.resolve(SweepPair.PANEL)), is(true));
    }

}
