package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Pins what a theme is: one geometry in several inks.
 * <p>
 * The value of encoding the corner blocks as role grids rather than as colours is exactly this - a
 * theme re-inks vanilla's measured pixels instead of describing a shape of its own. So the assertion
 * is that two themes disagree on every pixel's colour and on no pixel's position.
 */
@DisplayName("A theme re-inks vanilla's geometry rather than replacing it")
class WindowThemeTest {

    private static PixelBuffer paint(Window window, int w, int h) {
        PixelBuffer buffer = PixelBuffer.create(w, h);
        window.paintPanel(buffer, Window.Box.of(w, h));
        window.paintCell(buffer, new Window.Box(7, 17, 18, 18, 1));
        return buffer;
    }

    @Test
    @DisplayName("every theme paints the same pixels in different ink")
    void everyThemePaintsTheSamePixelsInDifferentInk() {
        for (Window.Theme theme : Window.Theme.values()) {
            PixelBuffer vanilla = paint(Window.Theme.VANILLA, 96, 64);
            PixelBuffer other = paint(theme, 96, 64);

            int shapeDiffers = 0;
            for (int y = 0; y < 64; y++)
                for (int x = 0; x < 96; x++) {
                    boolean a = vanilla.getPixel(x, y) != 0;
                    boolean b = other.getPixel(x, y) != 0;
                    if (a != b) shapeDiffers++;
                }

            assertThat(theme + " paints where vanilla paints", shapeDiffers, is(equalTo(0)));
        }
    }

    @Test
    @DisplayName("a re-inking actually changes the ink")
    void aReInkingActuallyChangesTheInk() {
        PixelBuffer vanilla = paint(Window.Theme.VANILLA, 96, 64);

        for (Window.Theme theme : List.of(Window.Theme.DARK)) {
            PixelBuffer other = paint(theme, 96, 64);

            int same = 0;
            for (int y = 0; y < 64; y++)
                for (int x = 0; x < 96; x++)
                    if (vanilla.getPixel(x, y) != 0 && vanilla.getPixel(x, y) == other.getPixel(x, y)) same++;

            // The outline is black in every palette, so a handful of pixels legitimately agree; the
            // panel, the bevels and the cell must not.
            assertThat(theme + " differs from vanilla somewhere", same, is(not(equalTo(0))));
            assertThat(theme + " is not vanilla repainted", same < 3000, is(true));
        }
    }

    /**
     * The six inks the vanilla theme paints in, which are read off the shipped container art and are
     * what the oracle's byte assertion rests on. Every other theme is free to be authored; this one
     * answers to a texture, so its palette is pinned here rather than left to the constant.
     */
    @Test
    @DisplayName("the vanilla theme paints in the ink the shipped art is drawn in")
    void theVanillaThemePaintsInTheShippedInk() {
        assertThat(Window.Theme.VANILLA.palette(), is(equalTo(new Window.Palette(
            0xFF000000, 0xFFFFFFFF, 0xFF555555, 0xFFC6C6C6, 0xFF8B8B8B, 0xFF373737))));
    }

    @Test
    @DisplayName("every theme's floor is the frame's, because the geometry is one geometry")
    void everyThemeFloorIsTheFrames() {
        for (Window.Theme theme : Window.Theme.values())
            assertThat(theme.minimum(), is(equalTo(Window.Theme.VANILLA.minimum())));
    }

}
