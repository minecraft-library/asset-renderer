package lib.minecraft.renderer.engine.frame;

import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Holds the supersampled frame's scale-down to a copy of what was drawn. The hi-res frame is
 * finished, so a translucent pixel in it - a colour already scaled by its alpha, as drawing onto a
 * transparent buffer leaves it - reaches the output as it is, and a supersampled frame answers what
 * the same draw answers without supersampling.
 */
@DisplayName("A supersampled frame keeps what was drawn")
class RasterPassSupersampleTest {

    @Test
    @DisplayName("a translucent pixel reaches the output as it was drawn, as it does without supersampling")
    void aTranslucentPixelReachesTheOutputAsDrawn() {
        int drawn = ColorMath.blend(0x80FFFFFF, ColorMath.TRANSPARENT, BlendMode.NORMAL);
        RasterPass.FrameRasterizer translucent = (target, tick) -> target.fill(drawn);

        PixelBuffer once = RasterPass.of(4, 4, 1, false, translucent).renderFrame(0, 0f);
        PixelBuffer supersampled = RasterPass.of(4, 4, 2, false, translucent).renderFrame(0, 0f);

        assertThat(once.getPixel(1, 1), is(drawn));
        assertArrayEquals(once.data(), supersampled.data());
    }

}
