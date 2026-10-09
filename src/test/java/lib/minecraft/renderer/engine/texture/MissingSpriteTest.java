package lib.minecraft.renderer.engine.texture;

import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link MissingSprite}: the generated sprite's size, orientation, colours and opacity,
 * the odd-size floor split that tells a transcribed expression from a stamped literal, the one
 * shared instance every caller is handed and the identity it is told apart by, and the stretch a
 * reader cropping a sheet by texel coordinates takes it through.
 */
@DisplayName("MissingSprite generated sprite")
class MissingSpriteTest {

    private static final int OPAQUE_ALPHA = 0xFF;

    @Test
    @DisplayName("the sprite is 16 by 16")
    void spriteIsSixteenSquare() {
        assertThat(MissingSprite.sprite().width(), is(MissingSprite.SIZE));
        assertThat(MissingSprite.sprite().height(), is(MissingSprite.SIZE));
    }

    @Test
    @DisplayName("black sits on the leading diagonal and magenta on the anti-diagonal")
    void quadrantsSitBlackOnTheLeadingDiagonal() {
        // Orientation rather than membership: a checker with the diagonal reversed still holds the
        // right two colours in the right proportion, so the corners are what pin which is which.
        PixelBuffer sprite = MissingSprite.sprite();
        assertThat("top-left", sprite.getPixel(0, 0), is(MissingSprite.BLACK_ARGB));
        assertThat("bottom-right", sprite.getPixel(15, 15), is(MissingSprite.BLACK_ARGB));
        assertThat("top-right", sprite.getPixel(15, 0), is(MissingSprite.MAGENTA_ARGB));
        assertThat("bottom-left", sprite.getPixel(0, 15), is(MissingSprite.MAGENTA_ARGB));
    }

    @Test
    @DisplayName("each 8x8 quadrant is one solid colour")
    void eachQuadrantIsSolid() {
        PixelBuffer sprite = MissingSprite.sprite();

        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                assertThat("top-left " + x + "," + y, sprite.getPixel(x, y), is(MissingSprite.BLACK_ARGB));
                assertThat("top-right " + x + "," + y, sprite.getPixel(x + 8, y), is(MissingSprite.MAGENTA_ARGB));
                assertThat("bottom-left " + x + "," + y, sprite.getPixel(x, y + 8), is(MissingSprite.MAGENTA_ARGB));
                assertThat("bottom-right " + x + "," + y, sprite.getPixel(x + 8, y + 8), is(MissingSprite.BLACK_ARGB));
            }
    }

    @Test
    @DisplayName("the two colours are the client's own")
    void constantsMatchTheClientValues() {
        assertThat(MissingSprite.MAGENTA_ARGB, is(0xFFF800F8));
        assertThat(MissingSprite.BLACK_ARGB, is(0xFF000000));
        assertThat("magenta as a signed int", MissingSprite.MAGENTA_ARGB, is(-524040));
        assertThat("black as a signed int", MissingSprite.BLACK_ARGB, is(-16777216));
    }

    @Test
    @DisplayName("every texel is fully opaque")
    void everyTexelIsOpaque() {
        PixelBuffer sprite = MissingSprite.sprite();

        for (int y = 0; y < sprite.height(); y++)
            for (int x = 0; x < sprite.width(); x++)
                assertThat("alpha at " + x + "," + y, sprite.getPixel(x, y) >>> 24, is(OPAQUE_ALPHA));
    }

    @Test
    @DisplayName("an odd size splits on the floor, eight columns then nine")
    void anOddSizeSplitsOnTheFloor() {
        // Both halves of the selector are integer divisions, so 17 halves to 8 and the right side
        // carries the extra column. A stamped two-character literal cannot express that.
        PixelBuffer odd = MissingSprite.generate(17, 17);
        assertThat(odd.width(), is(17));
        assertThat(odd.height(), is(17));
        assertThat("column 7 of the top row", odd.getPixel(7, 0), is(MissingSprite.BLACK_ARGB));
        assertThat("column 8 of the top row", odd.getPixel(8, 0), is(MissingSprite.MAGENTA_ARGB));

        int black = 0;
        for (int x = 0; x < 17; x++)
            if (odd.getPixel(x, 0) == MissingSprite.BLACK_ARGB) black++;

        assertThat("black run in the top row", black, is(8));
    }

    @Test
    @DisplayName("every caller is handed the same buffer, not a copy")
    void spriteIsTheSameInstanceEveryCall() {
        assertThat(MissingSprite.sprite() == MissingSprite.sprite(), is(true));
    }

    @Test
    @DisplayName("the sprite is told apart by identity, so a copy holding the same texels is a texture")
    void theSpriteIsToldApartByIdentity() {
        assertThat(MissingSprite.isSprite(MissingSprite.sprite()), is(true));
        assertThat(MissingSprite.isSprite(MissingSprite.sprite().copy()), is(false));
        assertThat(MissingSprite.isSprite(MissingSprite.generate(MissingSprite.SIZE, MissingSprite.SIZE)), is(false));
    }

    @Test
    @DisplayName("a sheet stretches the sprite across every texel, each the sprite texel under its centre")
    void aSheetStretchesTheSpriteAcrossEveryTexel() {
        for (int[] sheet : new int[][] { { 64, 32 }, { 64, 64 }, { 128, 128 }, { 22, 17 } }) {
            int width = sheet[0];
            int height = sheet[1];
            PixelBuffer stretched = MissingSprite.stretchedTo(MissingSprite.sprite(), width, height);
            String label = width + "x" + height;

            assertThat(label + " width", stretched.width(), is(width));
            assertThat(label + " height", stretched.height(), is(height));
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    int under = MissingSprite.sprite().getPixel(
                        (int) ((x + 0.5) * MissingSprite.SIZE / width), (int) ((y + 0.5) * MissingSprite.SIZE / height));
                    assertThat(label + " texel " + x + "," + y, stretched.getPixel(x, y), is(under));
                    assertThat(label + " alpha " + x + "," + y, stretched.getPixel(x, y) >>> 24, is(OPAQUE_ALPHA));
                }
        }
    }

    @Test
    @DisplayName("a whole-number stretch keeps the quadrants whole, black on the leading diagonal")
    void aWholeNumberStretchKeepsTheQuadrants() {
        PixelBuffer stretched = MissingSprite.stretchedTo(MissingSprite.sprite(), 64, 32);

        assertThat("top-left", stretched.getPixel(31, 15), is(MissingSprite.BLACK_ARGB));
        assertThat("top-right", stretched.getPixel(32, 15), is(MissingSprite.MAGENTA_ARGB));
        assertThat("bottom-left", stretched.getPixel(31, 16), is(MissingSprite.MAGENTA_ARGB));
        assertThat("bottom-right", stretched.getPixel(63, 31), is(MissingSprite.BLACK_ARGB));
    }

    @Test
    @DisplayName("a texture that is not the sprite, and the sprite at its own size, come back untouched")
    void anythingElseComesBackUntouched() {
        PixelBuffer texture = MissingSprite.sprite().copy();

        assertThat(MissingSprite.stretchedTo(texture, 64, 32) == texture, is(true));
        assertThat(MissingSprite.stretchedTo(MissingSprite.sprite(), MissingSprite.SIZE, MissingSprite.SIZE)
            == MissingSprite.sprite(), is(true));
        assertThat("one sheet size shares one buffer",
            MissingSprite.stretchedTo(MissingSprite.sprite(), 64, 32) == MissingSprite.stretchedTo(MissingSprite.sprite(), 64, 32),
            is(true));
    }

}
