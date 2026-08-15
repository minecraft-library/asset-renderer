package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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

    /** Paints one mark alone onto a transparent buffer at its own extent. */
    private static PixelBuffer mark(Window window, Decoration decoration) {
        Window.Extent extent = decoration.extent();
        PixelBuffer buffer = PixelBuffer.create(extent.width(), extent.height());
        window.paintDecoration(buffer, MenuLayout.box(decoration, 1), decoration);
        return buffer;
    }

    @Test
    @DisplayName("an arrow is a shaft and a triangle, in the ink its cells are filled with")
    void anArrowIsAShaftAndATriangle() {
        PixelBuffer arrow = mark(Window.Theme.VANILLA, new Decoration.Arrow(0, 0));
        int fill = Window.Palette.VANILLA.cellFill();

        int inked = 0;
        for (int y = 0; y < 15; y++)
            for (int x = 0; x < 22; x++)
                if (arrow.getPixel(x, y) != 0) {
                    assertThat("the arrow is one ink", arrow.getPixel(x, y), is(equalTo(fill)));
                    inked++;
                }

        // The shipped crafting panel paints its arrow into its own texture, and this is that arrow:
        // fourteen by three of shaft, and a triangle whose rows narrow by one from eight to one on
        // each side of the middle.
        assertThat("the whole mark", inked, is(equalTo(14 * 3 + 64)));
        assertThat("the shaft opens at the left edge", arrow.getPixel(0, 7), is(equalTo(fill)));
        assertThat("the tip is the far middle", arrow.getPixel(21, 7), is(equalTo(fill)));
        assertThat("and nothing sits above the shaft at the left", arrow.getPixel(0, 5), is(equalTo(0)));
        assertThat("the head's first row is one pixel", arrow.getPixel(14, 0), is(equalTo(fill)));
        assertThat("and no wider", arrow.getPixel(15, 0), is(equalTo(0)));
    }

    @Test
    @DisplayName("a button is a cell's bevel the other way up")
    void aButtonIsACellsBevelTheOtherWayUp() {
        Window.Palette palette = Window.Palette.VANILLA;
        PixelBuffer button = mark(Window.Theme.VANILLA, new Decoration.Button(0, 0, ResourceId.parse("minecraft:knowledge_book")));

        // A cell sinks into the panel - shadow along its top and left, light along its bottom and
        // right. A button stands off it, so every one of those four reads the other way.
        assertThat("the light runs along the top", button.getPixel(10, 1), is(equalTo(palette.light())));
        assertThat("and down the left", button.getPixel(1, 8), is(equalTo(palette.light())));
        assertThat("the shadow along the bottom", button.getPixel(10, 16), is(equalTo(palette.shadow())));
        assertThat("and down the right", button.getPixel(18, 8), is(equalTo(palette.shadow())));
        assertThat("the face is the panel's own fill", button.getPixel(10, 8), is(equalTo(palette.panel())));
        assertThat("the outline rings it", button.getPixel(10, 0), is(equalTo(palette.outline())));
        assertThat("and the corner is chamfered away", button.getPixel(0, 0), is(equalTo(0)));
    }

    @Test
    @DisplayName("a mark is re-inked with the panel it sits on")
    void aMarkIsReInkedWithThePanel() {
        Decoration arrow = new Decoration.Arrow(0, 0);
        PixelBuffer vanilla = mark(Window.Theme.VANILLA, arrow);
        PixelBuffer dark = mark(Window.Theme.DARK, arrow);

        // This is the whole reason a mark is declared rather than sliced out of a texture: an arrow
        // read off the shipped panel would keep vanilla's grey on a panel that is no longer grey.
        int painted = 0;
        for (int y = 0; y < 15; y++) {
            for (int x = 0; x < 22; x++) {
                assertThat("both themes paint the same pixels",
                    vanilla.getPixel(x, y) == 0, is(equalTo(dark.getPixel(x, y) == 0)));
                if (vanilla.getPixel(x, y) == 0) continue;
                assertThat("in each one's own cell fill",
                    dark.getPixel(x, y), is(equalTo(Window.Palette.DARK.cellFill())));
                painted++;
            }
        }

        assertThat("the mark drew", painted, is(equalTo(106)));
    }

    @Test
    @DisplayName("art carries its own marks, so a sliced window paints none")
    void artCarriesItsOwnMarks() {
        // The shipped crafting panel has its arrow painted into the texture, so a window slicing that
        // texture would draw a second one over the first.
        PixelBuffer buffer = PixelBuffer.create(22, 15);
        Window.Sliced sliced = Window.Sliced.of(
            PixelBuffer.create(8, 8), Optional.empty(), Optional.empty(), Optional.empty());
        sliced.paintDecoration(buffer, MenuLayout.box(new Decoration.Arrow(0, 0), 1), new Decoration.Arrow(0, 0));

        int painted = 0;
        for (int y = 0; y < 15; y++)
            for (int x = 0; x < 22; x++)
                if (buffer.getPixel(x, y) != 0) painted++;

        assertThat("a sliced window paints no mark of its own", painted, is(equalTo(0)));
    }

}
