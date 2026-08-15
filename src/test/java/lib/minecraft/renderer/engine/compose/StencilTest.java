package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the one mechanism a picture is stamped through.
 * <p>
 * Two pictures go through it - a frame's corner blocks, drawn in a panel's palette, and the anvil's
 * hammer, drawn in colours no palette holds - so what is asserted here is that the seam carries both
 * and that the guards a hand-written loop did not have are now real.
 */
@DisplayName("A stencil stamps a picture through whatever table it is handed")
class StencilTest {

    /** a two-code table, standing in for the two the production pictures resolve through */
    private static final Stencil.Ink LETTERS = code -> switch (code) {
        case 'A' -> 0xFFAAAAAA;
        case 'B' -> 0xFFBBBBBB;
        default -> throw new IllegalArgumentException("Unknown code '%c'".formatted(code));
    };

    private static PixelBuffer filled(int w, int h, int argb) {
        PixelBuffer buffer = PixelBuffer.create(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                buffer.setPixel(x, y, argb);

        return buffer;
    }

    @Test
    @DisplayName("a ragged picture is refused at construction rather than painted ragged")
    void aRaggedPictureIsRefused() {
        // A short row used to paint a short row of hammer in silence, there being nothing between the
        // data and the loop that read it.
        assertThrows(IllegalArgumentException.class, () -> Stencil.of("AB", "A"));
        assertThrows(IllegalArgumentException.class, () -> Stencil.of("A", "AB"));
        assertThrows(IllegalArgumentException.class, () -> Stencil.of());
        assertThrows(IllegalArgumentException.class, () -> Stencil.of(0, "A"));
    }

    @Test
    @DisplayName("a clear cell leaves the destination alone")
    void aClearCellLeavesTheDestinationAlone() {
        PixelBuffer buffer = filled(4, 2, 0xFF123456);
        Stencil.of("....", "....").stamp(buffer, Window.Box.of(4, 2), 0, 0, LETTERS);

        // Resolved before any table is consulted, so the table above - which raises on every code it
        // does not know - is never asked about it.
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 4; x++)
                assertThat(buffer.getPixel(x, y), is(equalTo(0xFF123456)));
    }

    @Test
    @DisplayName("a picture's extent is its own size at its own scale")
    void aPicturesExtentIsItsOwnSizeAtItsOwnScale() {
        assertThat(Stencil.of("AB", "BA").extent(), is(equalTo(new Window.Extent(2, 2))));
        assertThat(Stencil.of(3, "AB").extent(), is(equalTo(new Window.Extent(6, 3))));

        // The hammer is the reason the scale is carried rather than assumed: the shipped panel holds
        // a doubled fifteen, so its extent is derived and cannot disagree with the picture.
        assertThat(Decoration.Hammer.PICTURE.extent(), is(equalTo(new Window.Extent(30, 30))));
        assertThat(new Decoration.Hammer(0, 0).extent(), is(equalTo(Decoration.Hammer.PICTURE.extent())));
    }

    @Test
    @DisplayName("each authored pixel is stamped as a square of the picture's own scale")
    void eachAuthoredPixelIsStampedAsASquare() {
        PixelBuffer buffer = PixelBuffer.create(4, 4);
        Stencil.of(2, "AB").stamp(buffer, Window.Box.of(4, 4), 0, 0, LETTERS);

        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                assertThat("the first code fills its own square", buffer.getPixel(x, y), is(equalTo(0xFFAAAAAA)));
                assertThat("and the second the next", buffer.getPixel(2 + x, y), is(equalTo(0xFFBBBBBB)));
            }
        }

        assertThat("and nothing below the one row it has", buffer.getPixel(0, 2), is(equalTo(0)));
    }

    @Test
    @DisplayName("an offset moves the whole picture within the box")
    void anOffsetMovesTheWholePicture() {
        PixelBuffer buffer = PixelBuffer.create(4, 4);
        Stencil.of("A").stamp(buffer, Window.Box.of(4, 4), 3, 2, LETTERS);

        assertThat(buffer.getPixel(3, 2), is(equalTo(0xFFAAAAAA)));
        assertThat(buffer.getPixel(0, 0), is(equalTo(0)));
    }

}
