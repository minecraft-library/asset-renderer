package lib.minecraft.renderer.bake.gui;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.PixelBuffer;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of {@link TextKit}'s obfuscation glyph scrambling for the {@code §k} effect:
 * seed-determinism (one seed answers one output, two seeds answer two), whitespace passthrough,
 * empty-string passthrough, and length preservation - the invariants that let a deterministic
 * per-frame scramble stand in for vanilla's per-tick glyph shuffle.
 * <p>
 * It also pins the line pen and the line width against the client's {@code Font}: a line's pen keeps
 * the half mcPixel a run can end on, so a segment opens where the one before it ended and the bars
 * under a run span all of it, and a line's width is that fractional sum rounded up to a whole mcPixel,
 * walking codepoints. The default fonts reach a half mcPixel through one glyph, {@code ✦}, so every
 * fixture that needs one first asserts its run ends there.
 */
@ExtendWith(MinecraftFontsExtension.class)
class TextKitTest {

    /** Column every draw starts at, in Minecraft pixels. */
    private static final int X0 = 3;

    /** Baseline every draw sits on, in Minecraft pixels. */
    private static final int BASE = 12;

    @Test
    @DisplayName("substitute is deterministic for a fixed seed")
    void substituteIsDeterministic() {
        String result1 = TextKit.substitute("Hello World", 42L);
        String result2 = TextKit.substitute("Hello World", 42L);
        assertThat(result1, equalTo(result2));
    }

    @Test
    @DisplayName("different seeds produce different outputs")
    void differentSeedsProduceDifferentOutput() {
        String a = TextKit.substitute("Hello World", 1L);
        String b = TextKit.substitute("Hello World", 2L);
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    @DisplayName("substitute preserves whitespace")
    void substitutePreservesWhitespace() {
        String result = TextKit.substitute("a b c", 123L);
        assertThat(result.length(), equalTo("a b c".length()));
        assertThat(result.charAt(1), equalTo(' '));
        assertThat(result.charAt(3), equalTo(' '));
    }

    @Test
    @DisplayName("substitute on empty string returns empty")
    void substituteEmpty() {
        assertThat(TextKit.substitute("", 0L), equalTo(""));
    }

    @Test
    @DisplayName("output length matches input length")
    void outputLengthMatches() {
        String input = "The quick brown fox jumps over the lazy dog";
        String output = TextKit.substitute(input, 999L);
        assertThat(output.length(), equalTo(input.length()));
    }

    @Test
    @DisplayName("a line split between segments draws what the same text draws whole")
    void splitLineDrawsAsWhole() {
        endsOnAHalfPixel("+50 ✦ Speed ");

        PixelBuffer split = PixelBuffer.create(200, 24);
        TextKit.drawLine(new MinecraftGraphics(split), line(TextSegment.literal("+50 ✦ Speed "), TextSegment.literal("for")), X0, BASE, 0xFFAAAAAA, 0L);
        PixelBuffer whole = PixelBuffer.create(200, 24);
        TextKit.drawLine(new MinecraftGraphics(whole), line(TextSegment.literal("+50 ✦ Speed for")), X0, BASE, 0xFFAAAAAA, 0L);

        assertThat("pixels that differ", differing(split, whole), is(0));
    }

    @Test
    @DisplayName("a line's width rounds its half pixel up, as the client's Font#width does")
    void lineWidthRoundsUp() {
        float exact = endsOnAHalfPixel("+5 ✦ Speed");

        assertThat(TextKit.measureLineMcPixels(line(TextSegment.literal("+5 ✦ Speed"))), is((int) exact + 1));
    }

    @Test
    @DisplayName("a line's width walks codepoints, so a surrogate pair is one glyph")
    void lineWidthWalksCodepoints() {
        int astral = 0x1D400; // MATHEMATICAL BOLD CAPITAL A (needs a surrogate pair in UTF-16)
        String pair = new String(Character.toChars(astral));
        assertThat(pair.length(), is(2));

        int advanceMcPx = MinecraftFont.Vanilla.REGULAR.glyph(astral).advanceWidth() / MinecraftFont.MC_PIXEL_SCALE;
        assertThat(TextKit.measureLineMcPixels(line(TextSegment.literal(pair))), is(advanceMcPx));
    }

    @Test
    @DisplayName("an underline runs to the end of a run that ends on a half pixel")
    void decorationSpansAHalfPixelRun() {
        endsOnAHalfPixel("✦");
        int scale = MinecraftFont.MC_PIXEL_SCALE;

        PixelBuffer buffer = PixelBuffer.create(60, 30);
        TextKit.drawLine(new MinecraftGraphics(buffer), line(TextSegment.builder().text("✦").underlined(true).build()), X0, BASE, 0xFFFFFFFF, 0L);

        // The main pass's underline row - the shadow's sits a pixel lower and in its darker colour.
        int row = BASE * scale + TextKit.UNDERLINE_OFFSET_MCPX * scale;
        int[] lit = litColumns(buffer, row, 0xFFFFFFFF);
        assertThat("the bar opens at the run's origin", lit[0], is(X0 * scale));
        assertThat("and closes past its last pixel, its one-pixel pad included",
            lit[1], is(X0 * scale + MinecraftFont.Vanilla.REGULAR.glyph('✦').advanceWidth()));
    }

    @Test
    @DisplayName("an underline after a run ending on a half pixel opens where that run ends")
    void decorationAfterAHalfPixelRunStartsAtItsEnd() {
        endsOnAHalfPixel("✦");
        MinecraftFont font = MinecraftFont.Vanilla.REGULAR;
        int scale = MinecraftFont.MC_PIXEL_SCALE;

        PixelBuffer buffer = PixelBuffer.create(80, 30);
        TextSegment underlined = TextSegment.builder().text("for").underlined(true).build();
        TextKit.drawLine(new MinecraftGraphics(buffer), line(TextSegment.literal("✦"), underlined), X0, BASE, 0xFFFFFFFF, 0L);

        int row = BASE * scale + TextKit.UNDERLINE_OFFSET_MCPX * scale;
        int[] lit = litColumns(buffer, row, 0xFFFFFFFF);
        int opens = X0 * scale + font.glyph('✦').advanceWidth();
        assertThat("the bar opens where '✦' ends", lit[0], is(opens));
        assertThat("and closes past the run's last pixel, its one-pixel pad included",
            lit[1], is(opens + "for".codePoints().map(cp -> font.glyph(cp).advanceWidth()).sum()));
    }

    @Test
    @DisplayName("a gradient segment after a run ending on a half pixel starts where that run ends")
    void segmentAfterAHalfPixelRunStartsAtItsEnd() {
        float starMcPx = endsOnAHalfPixel("✦");
        GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END).addStop(0x000000).addStop(0xFFFFFF).build();
        TextSegment gradient = TextSegment.builder().text("M").gradient(spec).build();

        PixelBuffer joined = PixelBuffer.create(80, 30);
        joined.fill(0xFF000000);
        TextKit.drawLine(new MinecraftGraphics(joined), line(TextSegment.literal("✦"), gradient), X0, BASE, 0xFFFFFFFF, 0L);

        PixelBuffer apart = PixelBuffer.create(80, 30);
        apart.fill(0xFF000000);
        MinecraftGraphics g = new MinecraftGraphics(apart);
        TextKit.drawLine(g, line(TextSegment.literal("✦")), X0, BASE, 0xFFFFFFFF, 0L);
        GradientKit.drawSegment(g, gradient, spec, "M", X0 + starMcPx, BASE, 0L);

        assertThat("pixels that differ", differing(joined, apart), is(0));
    }

    /**
     * Asserts a run in the regular font ends on half an mcPixel, which is what makes it a fixture for
     * the fraction, and answers its width.
     *
     * @param text the run
     * @return the run's width in mcPixels
     */
    private static float endsOnAHalfPixel(String text) {
        float exact = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels(text);
        assertThat("'" + text + "' ends on half an mcPixel", exact % 1f, is(0.5f));
        return exact;
    }

    private static LineSegment line(TextSegment... segments) {
        return LineSegment.builder().segments(Concurrent.newList(segments)).build();
    }

    private static int differing(PixelBuffer a, PixelBuffer b) {
        int count = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++;
        return count;
    }

    /**
     * Finds the first and last column of a row holding exactly one colour.
     *
     * @param buffer the buffer to read
     * @param row the row to read
     * @param argb the colour to find
     * @return the first and last column holding it, {@code -1} for both where none does
     */
    private static int[] litColumns(PixelBuffer buffer, int row, int argb) {
        int first = -1, last = -1;
        for (int x = 0; x < buffer.width(); x++)
            if (buffer.getPixel(x, row) == argb) {
                if (first < 0) first = x;
                last = x;
            }
        return new int[] { first, last };
    }

}
