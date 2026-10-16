package lib.minecraft.renderer.engine.frame;

import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Holds an anti-aliased frame to the form every other frame has: each colour scaled by its alpha. A
 * frame drawn onto a transparent buffer is premultiplied, so a softened silhouette pixel of a
 * one-colour shape carries that colour at the pixel's own coverage - neither the full colour, which
 * lands brighter than the subject when the frame is laid over anything, nor less of it, which lands
 * as a dark rim.
 */
@DisplayName("An anti-aliased frame keeps its colours carrying their alpha")
class RasterPassAntiAliasTest {

    /** The one colour the test shapes are drawn in. */
    private static final int FACE = 0xFFFFE070;

    @Test
    @DisplayName("a softened silhouette carries the shape's colour at its own coverage, with and without supersampling")
    void aSoftenedSilhouetteCarriesItsColourAtItsCoverage() {
        // A disc and a staircase of uneven steps: along a curved or broken edge the filter's two samples
        // fall at different coverage, which is where a mix that mishandles alpha shows.
        RasterPass.FrameRasterizer shapes = (target, tick) -> {
            int size = target.width();
            float centre = size * 0.3f;
            float radius = size * 0.22f;
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++) {
                    float dx = x + 0.5f - centre;
                    float dy = y + 0.5f - centre;
                    boolean disc = dx * dx + dy * dy < radius * radius;
                    boolean stairs = x > size / 2 && y > size / 2 && x - size / 2 < (y - size / 2) * (y % 3 + 1) / 2;
                    if (disc || stairs) target.setPixel(x, y, FACE);
                }
        };
        int[] full = { ColorMath.red(FACE), ColorMath.green(FACE), ColorMath.blue(FACE) };

        for (int ssaa : new int[]{ 1, 2 }) {
            PixelBuffer frame = RasterPass.of(48, 48, ssaa, true, shapes).renderFrame(0, 0f);
            int softened = 0;

            for (int pixel : frame.data()) {
                int alpha = ColorMath.alpha(pixel);
                if (alpha > 0 && alpha < 0xFF) softened++;
                int[] held = { ColorMath.red(pixel), ColorMath.green(pixel), ColorMath.blue(pixel) };

                for (int c = 0; c < 3; c++)
                    assertThat("ssaa %d pixel %08X".formatted(ssaa, pixel), (double) held[c], closeTo(full[c] * alpha / 255.0, 1.0));
            }

            assertThat("ssaa %d softened the silhouette".formatted(ssaa), softened, is(greaterThan(0)));
        }
    }

}
