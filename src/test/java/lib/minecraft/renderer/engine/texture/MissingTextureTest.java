package lib.minecraft.renderer.engine.texture;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.support.StubRendererContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link MissingTexture}: the generated sprite's size, orientation, colours and opacity,
 * the odd-size floor split that tells a transcribed expression from a stamped literal, the one shared
 * instance every caller is handed, and the once-per-id diagnostic.
 * <p>
 * The reporting set is static and lives as long as the process, so every id below is unique to the
 * test that names it and no test asserts that nothing has been reported yet.
 */
@DisplayName("MissingTexture generated sprite")
class MissingTextureTest {

    private static final int OPAQUE_ALPHA = 0xFF;

    @Test
    @DisplayName("the sprite is 16 by 16")
    void spriteIsSixteenSquare() {
        assertThat(MissingTexture.sprite().width(), is(MissingTexture.SIZE));
        assertThat(MissingTexture.sprite().height(), is(MissingTexture.SIZE));
    }

    @Test
    @DisplayName("black sits on the leading diagonal and magenta on the anti-diagonal")
    void quadrantsSitBlackOnTheLeadingDiagonal() {
        // Orientation rather than membership: a checker with the diagonal reversed still holds the
        // right two colours in the right proportion, so the corners are what pin which is which.
        PixelBuffer sprite = MissingTexture.sprite();
        assertThat("top-left", sprite.getPixel(0, 0), is(MissingTexture.BLACK_ARGB));
        assertThat("bottom-right", sprite.getPixel(15, 15), is(MissingTexture.BLACK_ARGB));
        assertThat("top-right", sprite.getPixel(15, 0), is(MissingTexture.MAGENTA_ARGB));
        assertThat("bottom-left", sprite.getPixel(0, 15), is(MissingTexture.MAGENTA_ARGB));
    }

    @Test
    @DisplayName("each 8x8 quadrant is one solid colour")
    void eachQuadrantIsSolid() {
        PixelBuffer sprite = MissingTexture.sprite();

        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                assertThat("top-left " + x + "," + y, sprite.getPixel(x, y), is(MissingTexture.BLACK_ARGB));
                assertThat("top-right " + x + "," + y, sprite.getPixel(x + 8, y), is(MissingTexture.MAGENTA_ARGB));
                assertThat("bottom-left " + x + "," + y, sprite.getPixel(x, y + 8), is(MissingTexture.MAGENTA_ARGB));
                assertThat("bottom-right " + x + "," + y, sprite.getPixel(x + 8, y + 8), is(MissingTexture.BLACK_ARGB));
            }
    }

    @Test
    @DisplayName("the two colours are the client's own")
    void constantsMatchTheClientValues() {
        assertThat(MissingTexture.MAGENTA_ARGB, is(0xFFF800F8));
        assertThat(MissingTexture.BLACK_ARGB, is(0xFF000000));
        assertThat("magenta as a signed int", MissingTexture.MAGENTA_ARGB, is(-524040));
        assertThat("black as a signed int", MissingTexture.BLACK_ARGB, is(-16777216));
    }

    @Test
    @DisplayName("every texel is fully opaque")
    void everyTexelIsOpaque() {
        PixelBuffer sprite = MissingTexture.sprite();

        for (int y = 0; y < sprite.height(); y++)
            for (int x = 0; x < sprite.width(); x++)
                assertThat("alpha at " + x + "," + y, sprite.getPixel(x, y) >>> 24, is(OPAQUE_ALPHA));
    }

    @Test
    @DisplayName("an odd size splits on the floor, eight columns then nine")
    void anOddSizeSplitsOnTheFloor() {
        // Both halves of the selector are integer divisions, so 17 halves to 8 and the right side
        // carries the extra column. A stamped two-character literal cannot express that.
        PixelBuffer odd = MissingTexture.generate(17, 17);
        assertThat(odd.width(), is(17));
        assertThat(odd.height(), is(17));
        assertThat("column 7 of the top row", odd.getPixel(7, 0), is(MissingTexture.BLACK_ARGB));
        assertThat("column 8 of the top row", odd.getPixel(8, 0), is(MissingTexture.MAGENTA_ARGB));

        int black = 0;
        for (int x = 0; x < 17; x++)
            if (odd.getPixel(x, 0) == MissingTexture.BLACK_ARGB) black++;

        assertThat("black run in the top row", black, is(8));
    }

    @Test
    @DisplayName("every caller is handed the same buffer, not a copy")
    void spriteIsTheSameInstanceEveryCall() {
        assertThat(MissingTexture.sprite() == MissingTexture.sprite(), is(true));
    }

    @Test
    @DisplayName("a miss substitutes the sprite and reports the id once")
    void reportsAnUnresolvedIdOnce() {
        StubRendererContext context = StubRendererContext.builder().build();
        String id = "minecraft:block/missing_texture_test_reported_once";

        String first = errDuring(() ->
            assertThat(MissingTexture.texture(context, id, true) == MissingTexture.sprite(), is(true)));
        String second = errDuring(() -> MissingTexture.texture(context, id, true));

        assertThat(first, containsString("Missing texture '" + id + "' - drawing the checkerboard"));
        assertThat("the ninetieth face does not re-report", second, is(emptyString()));
    }

    @Test
    @DisplayName("a second distinct id still reports")
    void reportsEachDistinctIdSeparately() {
        StubRendererContext context = StubRendererContext.builder().build();
        String first = "minecraft:block/missing_texture_test_distinct_one";
        String second = "minecraft:block/missing_texture_test_distinct_two";

        errDuring(() -> MissingTexture.texture(context, first, true));
        String output = errDuring(() -> MissingTexture.textureAtTick(context, second, 3, true));

        assertThat(output, containsString("Missing texture '" + second + "'"));
    }

    /**
     * Runs a body with {@code System.err} captured, restoring the real stream afterwards.
     *
     * @param body the call whose diagnostic output is being read
     * @return everything the body wrote to {@code System.err}
     */
    private static String errDuring(Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try {
            body.run();
        } finally {
            System.setErr(original);
        }

        return captured.toString(StandardCharsets.UTF_8);
    }

}
