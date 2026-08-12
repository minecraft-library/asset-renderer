package lib.minecraft.renderer.engine.kit;

import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

/**
 * Positional and per-channel coverage of {@link ItemStackKit#drawDamageBar}: the two no-op guards,
 * the exact row and column extent of both bar rows, the foreground colour at three damage
 * fractions, the empty-foreground guard at and past full damage, and the integer truncation of the
 * GUI scale.
 * <p>
 * Every colour claim is asserted channel by channel at a named pixel rather than through a
 * luminance or a count of "some opaque pixel". A hue fault cancels in luma, and green-to-red is the
 * entire signal a durability bar carries - a bar drawn in the wrong hue at the right brightness is
 * invisible to any aggregate, and a bar drawn one row off is invisible to any existence check.
 * <p>
 * The geometry pinned here is what the code draws, which is not what its own field documentation
 * describes. The doc on the foreground width says the row is "inset one pixel narrower than the
 * background so the black border frames it", but the background is a single row at logical Y 13
 * while the foreground is a separate row at logical Y 14, and both start at logical X 2. No black
 * pixel lands left of, right of, or below the coloured run, so nothing is framed. These tests hold
 * the observed output.
 */
class ItemStackKitTest {

    /**
     * Opaque black, the colour of the bar's background row
     */
    private static final int BLACK = 0xFF000000;

    /**
     * A fully transparent pixel, the state of every buffer cell the bar does not write
     */
    private static final int CLEAR = 0x00000000;

    /**
     * The foreground colour at exactly half damage, hue 60 degrees at full saturation and value
     */
    private static final int HALF_DAMAGE_YELLOW = 0xFFFFFF00;

    /**
     * Draws the bar on a fresh 16x16 buffer and returns the leftmost foreground pixel.
     *
     * @param damage the current damage value
     * @param maxDurability the maximum durability
     * @return the packed ARGB pixel at the bar's foreground origin, logical (2, 14)
     */
    private static int foregroundColor(int damage, int maxDurability) {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, damage, maxDurability);
        return buffer.getPixel(2, 14);
    }

    /**
     * Draws the bar on a fresh 16x16 buffer and counts the opaque pixels on the foreground row.
     *
     * @param damage the current damage value
     * @param maxDurability the maximum durability
     * @return the number of painted pixels on logical row 14
     */
    private static int foregroundWidth(int damage, int maxDurability) {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, damage, maxDurability);

        int painted = 0;
        for (int x = 0; x < buffer.width(); x++)
            if (ColorMath.alpha(buffer.getPixel(x, 14)) == 0xFF) painted++;
        return painted;
    }

    /**
     * Asserts that a row holds {@code argb} across the inclusive column span and nothing anywhere
     * else on that row.
     *
     * @param buffer the buffer to inspect
     * @param y the row index
     * @param fromX the first painted column, inclusive
     * @param toX the last painted column, inclusive
     * @param argb the packed ARGB colour the span must hold
     */
    private static void assertRowRun(PixelBuffer buffer, int y, int fromX, int toX, int argb) {
        for (int x = 0; x < buffer.width(); x++) {
            int expected = x >= fromX && x <= toX ? argb : CLEAR;
            String where = String.format("x=%d y=%d expected %08X", x, y, expected);
            assertThat(where, buffer.getPixel(x, y), equalTo(expected));
        }
    }

    /**
     * Asserts that every pixel on a row is untouched.
     *
     * @param buffer the buffer to inspect
     * @param y the row index
     */
    private static void assertRowClear(PixelBuffer buffer, int y) {
        for (int x = 0; x < buffer.width(); x++)
            assertThat("x=" + x + " y=" + y, buffer.getPixel(x, y), equalTo(CLEAR));
    }

    /**
     * Asserts that every row outside the inclusive band is untouched.
     *
     * @param buffer the buffer to inspect
     * @param firstY the first painted row, inclusive
     * @param lastY the last painted row, inclusive
     */
    private static void assertRowsOutsideAreClear(PixelBuffer buffer, int firstY, int lastY) {
        for (int y = 0; y < buffer.height(); y++) {
            if (y < firstY || y > lastY) assertRowClear(buffer, y);
        }
    }

    /**
     * Verifies that an undamaged item leaves the buffer untouched.
     */
    @Test
    @DisplayName("drawDamageBar is a no-op when damage is zero")
    void noBarForUndamagedItem() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, 0, 100);

        for (int y = 0; y < buffer.height(); y++)
            assertRowClear(buffer, y);
    }

    /**
     * Verifies that a non-damageable item leaves the buffer untouched even with damage recorded.
     */
    @Test
    @DisplayName("drawDamageBar is a no-op when max durability is zero")
    void noBarForNonDamageableItem() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, 5, 0);

        for (int y = 0; y < buffer.height(); y++)
            assertRowClear(buffer, y);
    }

    /**
     * Pins the background row to columns 2 through 14 of row 13, opaque black and nothing else.
     */
    @Test
    @DisplayName("the background row is 13 opaque black pixels from column 2 of row 13")
    void backgroundRowSpansThirteenColumnsFromColumnTwo() {
        // A 16-wide buffer gives GUI scale 1, so logical rows 13 and 14 land on pixel rows 13 and 14.
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, 50, 100);

        assertRowRun(buffer, 13, 2, 14, BLACK);
    }

    /**
     * Pins the foreground run at half damage to six pixels of hue 60 starting at column 2 of row 14,
     * with no other row written.
     */
    @Test
    @DisplayName("50% damage paints 6 foreground pixels on row 14 and leaves every other row clear")
    void halfDamagePaintsSixForegroundPixelsOnRowFourteen() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, 50, 100);

        // round(12 * 0.5) = 6 pixels wide, at hue 0.5 * 120 = 60 degrees.
        assertRowRun(buffer, 14, 2, 7, HALF_DAMAGE_YELLOW);
        assertRowsOutsideAreClear(buffer, 13, 14);
    }

    /**
     * Pins the observation that the black row borders the coloured run on no side, contradicting the
     * "black border frames it" wording on the foreground width constant.
     */
    @Test
    @DisplayName("the black row neither underlies nor flanks the coloured run")
    void backgroundRowFramesNothingAroundTheColouredRun() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, 50, 100);

        assertThat("left of the coloured run", buffer.getPixel(1, 14), equalTo(CLEAR));
        assertThat("right of the coloured run", buffer.getPixel(8, 14), equalTo(CLEAR));
        assertThat("below the coloured run", buffer.getPixel(2, 15), equalTo(CLEAR));
        assertThat("black sits only on the row above", buffer.getPixel(2, 13), equalTo(BLACK));
    }

    /**
     * Pins the foreground width to {@code round(12 * remaining / max)} across four fractions.
     */
    @Test
    @DisplayName("foreground width is round(12 * remaining fraction), 12 pixels at most")
    void foregroundWidthIsTwelveTimesTheRemainingFraction() {
        assertThat("1 of 100 damage", foregroundWidth(1, 100), equalTo(12));
        assertThat("25 of 100 damage", foregroundWidth(25, 100), equalTo(9));
        assertThat("50 of 100 damage", foregroundWidth(50, 100), equalTo(6));
        assertThat("75 of 100 damage", foregroundWidth(75, 100), equalTo(3));
    }

    /**
     * Pins the foreground colour per channel at three damage fractions and the direction each
     * channel moves as damage rises.
     */
    @Test
    @DisplayName("foreground hue ramps green to red per channel as damage rises")
    void foregroundHueRampsGreenToRedPerChannel() {
        // Hue is the remaining fraction times 120 degrees at saturation and value 1, so the sweep
        // runs 90 -> 60 -> 30 degrees and blue never leaves zero.
        int lightlyDamaged = foregroundColor(25, 100);
        int halfDamaged = foregroundColor(50, 100);
        int heavilyDamaged = foregroundColor(75, 100);

        assertThat("25% damage red", ColorMath.red(lightlyDamaged), equalTo(128));
        assertThat("25% damage green", ColorMath.green(lightlyDamaged), equalTo(255));
        assertThat("25% damage blue", ColorMath.blue(lightlyDamaged), equalTo(0));

        assertThat("50% damage red", ColorMath.red(halfDamaged), equalTo(255));
        assertThat("50% damage green", ColorMath.green(halfDamaged), equalTo(255));
        assertThat("50% damage blue", ColorMath.blue(halfDamaged), equalTo(0));

        assertThat("75% damage red", ColorMath.red(heavilyDamaged), equalTo(255));
        assertThat("75% damage green", ColorMath.green(heavilyDamaged), equalTo(128));
        assertThat("75% damage blue", ColorMath.blue(heavilyDamaged), equalTo(0));

        assertThat(
            "red rises with damage",
            ColorMath.red(heavilyDamaged),
            greaterThan(ColorMath.red(lightlyDamaged)));
        assertThat(
            "green falls with damage",
            ColorMath.green(heavilyDamaged),
            lessThan(ColorMath.green(lightlyDamaged)));
        assertThat("the bar is fully opaque", ColorMath.alpha(halfDamaged), equalTo(0xFF));
    }

    /**
     * Verifies that a fully damaged item still draws the background row while the zero-width
     * foreground guard suppresses the coloured row entirely.
     */
    @Test
    @DisplayName("a fully damaged item draws the background row and no foreground at all")
    void fullDamageDrawsBackgroundWithoutForeground() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, 100, 100);

        assertRowRun(buffer, 13, 2, 14, BLACK);
        assertRowClear(buffer, 14);
    }

    /**
     * Verifies that damage past the maximum clamps the remaining durability to zero rather than
     * producing a negative-width foreground.
     */
    @Test
    @DisplayName("damage past max clamps to an empty foreground rather than a negative width")
    void damageBeyondMaxClampsToEmptyForeground() {
        PixelBuffer buffer = PixelBuffer.create(16, 16);
        ItemStackKit.drawDamageBar(buffer, 200, 100);

        assertRowRun(buffer, 13, 2, 14, BLACK);
        assertRowClear(buffer, 14);
    }

    /**
     * Pins every bar coordinate and both row heights doubling on a 32-wide buffer.
     */
    @Test
    @DisplayName("a 32-wide buffer doubles every bar coordinate and both row heights")
    void barCoordinatesScaleWithBufferWidth() {
        PixelBuffer buffer = PixelBuffer.create(32, 32);
        ItemStackKit.drawDamageBar(buffer, 50, 100);

        // Scale 2: the background is 26 px wide over rows 26 and 27, the foreground 12 px wide over
        // rows 28 and 29, both starting at column 4.
        assertRowRun(buffer, 26, 4, 29, BLACK);
        assertRowRun(buffer, 27, 4, 29, BLACK);
        assertRowRun(buffer, 28, 4, 15, HALF_DAMAGE_YELLOW);
        assertRowRun(buffer, 29, 4, 15, HALF_DAMAGE_YELLOW);
        assertRowsOutsideAreClear(buffer, 26, 29);
    }

    /**
     * Pins the GUI scale to integer division, so a buffer that is not a multiple of 16 draws the bar
     * at the next scale down rather than stretching to a fractional one.
     */
    @Test
    @DisplayName("the GUI scale is integer-truncated, so a 24-wide buffer draws at scale 1")
    void guiScaleTruncatesSoANonMultipleBufferDoesNotStretch() {
        PixelBuffer buffer = PixelBuffer.create(24, 24);
        ItemStackKit.drawDamageBar(buffer, 50, 100);

        // 24 / 16 truncates to 1, so the bar lands on exactly the rows and columns a 16-wide buffer
        // uses instead of stretching to 1.5x.
        assertRowRun(buffer, 13, 2, 14, BLACK);
        assertRowRun(buffer, 14, 2, 7, HALF_DAMAGE_YELLOW);
        assertRowsOutsideAreClear(buffer, 13, 14);
    }

    /**
     * Pins the scale floor of one, which places the bar past the bottom edge of a buffer smaller
     * than the logical GUI canvas so the fill clips away completely.
     */
    @Test
    @DisplayName("a buffer narrower than 16 clamps to scale 1 and clips the bar off the canvas")
    void subGuiSizedBufferDrawsNothing() {
        PixelBuffer buffer = PixelBuffer.create(8, 8);
        ItemStackKit.drawDamageBar(buffer, 50, 100);

        // 8 / 16 truncates to 0 and the max(1, ..) floor lifts it to 1, putting the bar rows at
        // y 13 and 14 - past the bottom of an 8-tall buffer, where fillRect clips them entirely.
        for (int y = 0; y < buffer.height(); y++)
            assertRowClear(buffer, y);
    }

}
