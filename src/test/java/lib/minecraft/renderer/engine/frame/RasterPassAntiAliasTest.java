package lib.minecraft.renderer.engine.frame;

import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Holds an anti-aliased frame to the form every other frame has: each colour scaled by its alpha. The
 * image library's FXAA filters colours that are not, so a frame passed straight through it comes out
 * with its softened silhouette pixels at full colour over partial alpha - a pixel no premultiplied
 * frame can hold, and one that lands brighter than the subject when the frame is laid over anything.
 */
@DisplayName("An anti-aliased frame keeps its colours carrying their alpha")
class RasterPassAntiAliasTest {

    @Test
    @DisplayName("a softened silhouette never holds a colour above its alpha, with and without supersampling")
    void aSoftenedSilhouetteStaysPremultiplied() {
        // A disc and a staircase of uneven steps: along a curved or broken edge the filter's two samples
        // fall at different coverage, which is where blending colours that carry no alpha overshoots.
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
                    if (disc || stairs) target.setPixel(x, y, 0xFFFFE070);
                }
        };

        for (int ssaa : new int[]{ 1, 2 }) {
            PixelBuffer frame = RasterPass.of(48, 48, ssaa, true, shapes).renderFrame(0, 0f);
            int softened = 0;

            for (int pixel : frame.data()) {
                int alpha = ColorMath.alpha(pixel);
                if (alpha > 0 && alpha < 0xFF) softened++;
                int colour = Math.max(ColorMath.red(pixel), Math.max(ColorMath.green(pixel), ColorMath.blue(pixel)));
                assertThat("ssaa %d pixel %08X".formatted(ssaa, pixel), colour, is(lessThanOrEqualTo(alpha)));
            }

            assertThat("ssaa %d softened the silhouette".formatted(ssaa), softened, is(greaterThan(0)));
        }
    }

    @Test
    @DisplayName("a pixel the filter leaves alone keeps its exact value")
    void anUnfilteredPixelKeepsItsValue() {
        // Uniform, so the filter finds no contrast anywhere and rewrites nothing. Divided through by
        // its alpha and scaled back, this pixel would round to 199: what is left alone must not round.
        int drawn = ColorMath.pack(201, 200, 200, 200);
        PixelBuffer frame = PixelBuffer.create(16, 16);
        frame.fill(drawn);

        RasterPass.antiAlias(frame);

        assertThat(Arrays.stream(frame.data()).boxed().toList(), everyItem(is(drawn)));
    }

}
