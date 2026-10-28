package lib.minecraft.renderer.bake.gui;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.PixelBuffer;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of what {@link TextKit} draws for obfuscated ({@code §k}) text: a run keeps its width on
 * every frame, solid or carrying a gradient; a frame redraws its own scramble; consecutive frames, two
 * lines of one frame and two runs of one line draw different scrambles; a line numbered apart from
 * another draws none of the other's scrambles, on its frame or any other; a line scrambles the same
 * wherever it is drawn; and the first pick does not step through its candidates at a stride from frame
 * to frame or line to line. What a pick may be is
 * {@link MinecraftFont#obfuscate(int, RandomGenerator)}'s, pinned by the text library's own tests.
 * <p>
 * It also pins the line pen and the line width against the client's {@code Font}: a line's pen keeps
 * the half mcPixel a run can end on, so a segment opens where the one before it ended and the bars
 * under a run span all of it, and a line's width is that fractional sum rounded up to a whole mcPixel,
 * walking codepoints. The default fonts reach a half mcPixel through one glyph, {@code ✦}, so every
 * fixture that needs one first asserts its run ends there.
 * <p>
 * And it pins {@link TextKit#inkBounds} against the draw it answers for: at a whole and a half
 * mcPixel, with the shadow and without, over every style, bar, gradient path and scramble a line can
 * carry, the answer is the bounds of the pixels {@link TextKit#drawLine} lays down, and empty exactly
 * where it lays down none - spaces and empty segments included.
 */
@ExtendWith(MinecraftFontsExtension.class)
class TextKitTest {

    /** Column every draw starts at, in Minecraft pixels. */
    private static final int X0 = 3;

    /** Baseline every draw sits on, in Minecraft pixels. */
    private static final int BASE = 12;

    /** Baseline of a second origin a line is drawn at, a line's pitch below the first, in Minecraft pixels. */
    private static final int SECOND_BASE = BASE + 10;

    /** Column of a second origin a line is drawn at, past where {@code Hello World} ends, in Minecraft pixels. */
    private static final int SECOND_COLUMN = X0 + 60;

    /** Frames every obfuscation pin draws, from frame 0. */
    private static final int FRAMES = 64;

    @Test
    @DisplayName("an obfuscated run keeps its width on every frame")
    void obfuscatedRunKeepsItsWidth() {
        keepsItsWidth(obfuscated("Hello World"));
    }

    @Test
    @DisplayName("an obfuscated run carrying a gradient keeps its width on every frame")
    void obfuscatedGradientRunKeepsItsWidth() {
        GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END).addStop(0x000000).addStop(0xFFFFFF).build();
        keepsItsWidth(TextSegment.builder().text("Hello World").obfuscated(true).gradient(spec).build());
    }

    @Test
    @DisplayName("a frame redraws its own scramble")
    void frameRedrawsItsScramble() {
        LineSegment line = line(obfuscated("Hello World"));
        Recording first = new Recording();
        TextKit.drawLine(first, line, X0, BASE, 0xFFFFFFFF, 0, 7L);
        Recording second = new Recording();
        TextKit.drawLine(second, line, X0, BASE, 0xFFFFFFFF, 0, 7L);

        assertThat(second.draws, is(equalTo(first.draws)));
        assertThat("pixels that differ", differing(first.target(), second.target()), is(0));
    }

    @Test
    @DisplayName("consecutive frames draw different scrambles")
    void consecutiveFramesScrambleApart() {
        LineSegment line = line(obfuscated("Hello World"));

        ConcurrentList<Long> repeated = Concurrent.newList();
        String previous = mainPass(line, 0, 0).getFirst().text();
        for (long frame = 1; frame < FRAMES; frame++) {
            String scrambled = mainPass(line, 0, frame).getFirst().text();
            if (scrambled.equals(previous)) repeated.add(frame);
            previous = scrambled;
        }

        assertThat("frames drawing the scramble of the frame before", repeated, is(empty()));
    }

    @Test
    @DisplayName("two lines of one frame draw different scrambles")
    void linesOfAFrameScrambleApart() {
        LineSegment line = line(obfuscated("Hello World"));

        ConcurrentList<Long> repeated = Concurrent.newList();
        for (long frame = 0; frame < FRAMES; frame++)
            if (mainPass(line, 0, frame).getFirst().text().equals(mainPass(line, 1, frame).getFirst().text()))
                repeated.add(frame);

        assertThat("frames whose second line draws the first line's scramble", repeated, is(empty()));
    }

    @Test
    @DisplayName("two line numbers draw none of each other's scrambles, on one frame or on two")
    void lineNumbersScrambleApart() {
        LineSegment line = line(obfuscated("Hello World"));

        String[] first = new String[FRAMES];
        String[] second = new String[FRAMES];
        for (int frame = 0; frame < FRAMES; frame++) {
            first[frame] = mainPass(line, 0, frame).getFirst().text();
            second[frame] = mainPass(line, 1, frame).getFirst().text();
        }

        ConcurrentList<String> repeated = Concurrent.newList();
        for (int frame = 0; frame < FRAMES; frame++)
            for (int other = 0; other < FRAMES; other++)
                if (second[frame].equals(first[other])) repeated.add("frame " + frame + " draws frame " + other + "'s");

        assertThat("frames whose second line draws a scramble the first line drew", repeated, is(empty()));
    }

    @Test
    @DisplayName("a line scrambles the same wherever it is drawn")
    void aLineScramblesTheSameWhereverItIsDrawn() {
        LineSegment line = line(obfuscated("Hello World"));

        ConcurrentList<Long> moved = Concurrent.newList();
        for (long frame = 0; frame < FRAMES; frame++) {
            Recording here = new Recording();
            TextKit.drawLine(here, line, X0, BASE, 0xFFFFFFFF, 3, frame);
            Recording there = new Recording();
            TextKit.drawLine(there, line, SECOND_COLUMN + 0.5f, SECOND_BASE, 0xFFFFFFFF, 3, frame);
            if (!here.texts().equals(there.texts())) moved.add(frame);
        }

        assertThat("frames whose line draws another scramble at another origin", moved, is(empty()));
    }

    @Test
    @DisplayName("two runs of one line draw different scrambles")
    void runsOfALineScrambleApart() {
        LineSegment line = line(obfuscated("Hello"), TextSegment.literal(" "), obfuscated("Hello"));

        ConcurrentList<Long> repeated = Concurrent.newList();
        for (long frame = 0; frame < FRAMES; frame++) {
            ConcurrentList<Draw> drawn = mainPass(line, 0, frame);
            if (drawn.getFirst().text().equals(drawn.getLast().text())) repeated.add(frame);
        }

        assertThat("frames whose second run draws the first run's scramble", repeated, is(empty()));
    }

    @Test
    @DisplayName("the first pick does not step through its candidates at a stride, from frame to frame or line to line")
    void firstPickKeepsNoStride() {
        MinecraftFont font = MinecraftFont.Vanilla.REGULAR;
        FixedPick probe = new FixedPick(0);
        font.obfuscate('a', probe);
        int candidates = probe.bound;
        ConcurrentMap<Integer, Integer> index = Concurrent.newMap();
        for (int k = 0; k < candidates; k++)
            index.put(font.obfuscate('a', new FixedPick(k)), k);

        LineSegment line = line(obfuscated("a"));
        int[] byFrame = new int[FRAMES];
        int[] byLine = new int[FRAMES];
        for (int k = 0; k < FRAMES; k++) {
            String pick = mainPass(line, 0, k).getFirst().text();
            assertThat("frame " + k + " draws '" + pick + "', a candidate of 'a'", index.containsKey(pick.codePointAt(0)), is(true));
            byFrame[k] = index.get(pick.codePointAt(0));

            pick = mainPass(line, k, 0).getFirst().text();
            assertThat("line " + k + " draws '" + pick + "', a candidate of 'a'", index.containsKey(pick.codePointAt(0)), is(true));
            byLine[k] = index.get(pick.codePointAt(0));
        }

        keepsNoStride("frame-to-frame", byFrame, candidates);
        keepsNoStride("line-to-line", byLine, candidates);
    }

    @Test
    @DisplayName("a line split between segments draws what the same text draws whole")
    void splitLineDrawsAsWhole() {
        endsOnAHalfPixel("+50 ✦ Speed ");

        PixelBuffer split = PixelBuffer.create(200, 24);
        TextKit.drawLine(new MinecraftGraphics(split), line(TextSegment.literal("+50 ✦ Speed "), TextSegment.literal("for")), X0, BASE, 0xFFAAAAAA, 0, 0L);
        PixelBuffer whole = PixelBuffer.create(200, 24);
        TextKit.drawLine(new MinecraftGraphics(whole), line(TextSegment.literal("+50 ✦ Speed for")), X0, BASE, 0xFFAAAAAA, 0, 0L);

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
        TextKit.drawLine(new MinecraftGraphics(buffer), line(TextSegment.builder().text("✦").underlined(true).build()), X0, BASE, 0xFFFFFFFF, 0, 0L);

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
        TextKit.drawLine(new MinecraftGraphics(buffer), line(TextSegment.literal("✦"), underlined), X0, BASE, 0xFFFFFFFF, 0, 0L);

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
        TextKit.drawLine(new MinecraftGraphics(joined), line(TextSegment.literal("✦"), gradient), X0, BASE, 0xFFFFFFFF, 0, 0L);

        PixelBuffer apart = PixelBuffer.create(80, 30);
        apart.fill(0xFF000000);
        MinecraftGraphics g = new MinecraftGraphics(apart);
        TextKit.drawLine(g, line(TextSegment.literal("✦")), X0, BASE, 0xFFFFFFFF, 0, 0L);
        GradientKit.drawSegment(g, gradient, spec, "M", X0 + starMcPx, BASE, 0L);

        assertThat("pixels that differ", differing(joined, apart), is(0));
    }

    @Test
    @DisplayName("what a line lays down is answered before it is drawn, at a whole or a half pixel, with the shadow or without")
    void inkBoundsIsWhatDrawLineLaysDown() {
        GradientSpec perLetter = GradientSpec.builder(GradientSpec.Mode.START_END).addStop(0x000000).addStop(0xFFFFFF).build();
        GradientSpec banded = GradientSpec.builder(GradientSpec.Mode.START_END).addStop(0x000000).addStop(0xFFFFFF).bandPx(1).build();
        Map<String, LineSegment> corpus = new LinkedHashMap<>();
        corpus.put("plain", line(TextSegment.literal("Hello World")));
        corpus.put("bold", line(TextSegment.builder().text("Bold text").bold(true).build()));
        corpus.put("italic", line(TextSegment.builder().text("fitting gpq").italic(true).build()));
        corpus.put("bold italic", line(TextSegment.builder().text("Both fly").bold(true).italic(true).build()));
        corpus.put("strikethrough first", line(TextSegment.builder().text("Struck").strikethrough(true).build(), TextSegment.literal(" tail")));
        corpus.put("strikethrough last", line(TextSegment.literal("head "), TextSegment.builder().text("Struck").strikethrough(true).build()));
        corpus.put("underline first", line(TextSegment.builder().text("Under").underlined(true).build(), TextSegment.literal(" tail")));
        corpus.put("underline last", line(TextSegment.literal("head "), TextSegment.builder().text("Under").underlined(true).build()));
        corpus.put("a half-pixel glyph", line(TextSegment.literal("+5 ✦ Speed")));
        corpus.put("leading spaces", line(TextSegment.literal("  Lead")));
        corpus.put("trailing spaces", line(TextSegment.literal("Trail   ")));
        corpus.put("an empty segment between two", line(TextSegment.literal("a"), TextSegment.literal(""), TextSegment.literal("b")));
        corpus.put("an empty segment alone", line(TextSegment.literal("")));
        corpus.put("spaces alone", line(TextSegment.literal("   ")));
        corpus.put("obfuscated", line(obfuscated("Hello")));
        corpus.put("bold obfuscated", line(TextSegment.builder().text("ab cd").bold(true).obfuscated(true).build(), TextSegment.literal("|")));
        corpus.put("per-letter gradient", line(TextSegment.builder().text("Gradient").gradient(perLetter).strikethrough(true).underlined(true).build()));
        corpus.put("banded gradient", line(TextSegment.builder().text("Gradient").gradient(banded).strikethrough(true).underlined(true).build()));

        ConcurrentList<String> mismatched = Concurrent.newList();
        for (Map.Entry<String, LineSegment> row : corpus.entrySet()) {
            LineSegment line = row.getValue();
            int frames = line.getSegments().stream().anyMatch(TextSegment::isObfuscated) ? 8 : 1;
            for (float x : new float[] { X0, X0 + 0.5f })
                for (boolean shadow : new boolean[] { true, false })
                    for (int frame = 0; frame < frames; frame++) {
                        PixelBuffer canvas = PixelBuffer.create(400, 64);
                        TextKit.drawLine(new MinecraftGraphics(canvas), line, x, BASE, 0xFFFFFFFF, 0, frame, frame, shadow);
                        Rectangle drawn = canvas.opaqueBounds();
                        String drew = drawn.isEmpty() ? "nothing" : described(drawn, 1);
                        String answered = TextKit.inkBounds(line, x, BASE, 0, frame, shadow)
                            .map(bounds -> described(bounds, MinecraftFont.MC_PIXEL_SCALE))
                            .orElse("nothing");
                        if (!answered.equals(drew))
                            mismatched.add(String.format("%s at %s %s the shadow, frame %d: answered %s, drew %s",
                                row.getKey(), x, shadow ? "with" : "without", frame, answered, drew));
                    }
        }

        assertThat("rows whose answer is not what the line lays down", mismatched, is(empty()));
    }

    /**
     * Describes a rectangle in output pixels.
     *
     * @param bounds the rectangle
     * @param scale the output pixels one of its units comes to
     * @return its corner and size in output pixels
     */
    private static String described(Rectangle2D bounds, int scale) {
        return String.format("(%s,%s %sx%s)", bounds.getX() * scale, bounds.getY() * scale, bounds.getWidth() * scale, bounds.getHeight() * scale);
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

    private static TextSegment obfuscated(String text) {
        return TextSegment.builder().text(text).obfuscated(true).build();
    }

    /**
     * Asserts an obfuscated {@code Hello World} run, followed by a plain {@code |}, draws a scramble
     * that ends where {@code Hello World} does on every frame.
     *
     * @param run the obfuscated run, its text {@code Hello World}
     */
    private static void keepsItsWidth(TextSegment run) {
        LineSegment line = line(run, TextSegment.literal("|"));
        float ends = X0 + MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("Hello World");

        ConcurrentList<Long> moved = Concurrent.newList();
        ConcurrentList<Long> unscrambled = Concurrent.newList();
        for (long frame = 0; frame < FRAMES; frame++) {
            ConcurrentList<Draw> drawn = mainPass(line, 0, frame);
            if (drawn.getLast().xMcPx() != ends) moved.add(frame);

            String scrambled = drawn.stream().limit(drawn.size() - 1).map(Draw::text).collect(Collectors.joining());
            if (scrambled.equals("Hello World")) unscrambled.add(frame);
        }

        assertThat("frames whose '|' is not drawn where 'Hello World' ends", moved, is(empty()));
        assertThat("frames drawing 'Hello World' as it is", unscrambled, is(empty()));
    }

    /**
     * Asserts a walk of first picks steps through its candidates in more distinct steps than half its
     * steps, steps within two of each other counted as one, the way a stride shows: a {@code Random}
     * seeded with the plain frame steps through the candidates of {@code a} by 321 and 322, 771 and 772.
     *
     * @param walk what the walk steps over, for the message
     * @param picks each pick's index among the candidates, in walk order
     * @param candidates how many candidates there are
     */
    private static void keepsNoStride(String walk, int[] picks, int candidates) {
        int[] steps = new int[picks.length - 1];
        for (int i = 1; i < picks.length; i++)
            steps[i - 1] = Math.floorMod(picks[i] - picks[i - 1], candidates);
        Arrays.sort(steps);
        int groups = 0;
        for (int i = 0; i < steps.length; i++)
            if (i == 0 || steps[i] - steps[i - 1] > 2) groups++;

        assertThat("distinct " + walk + " steps of " + Arrays.toString(steps), groups, is(greaterThan(steps.length / 2)));
    }

    /**
     * Draws a line at the column and baseline every pin draws at, and answers the runs its main pass
     * handed the graphics.
     *
     * @param line the line to draw
     * @param lineIndex the line's number among the lines drawn together
     * @param frame the animation frame drawn
     * @return the runs drawn on the baseline, in order
     */
    private static ConcurrentList<Draw> mainPass(LineSegment line, int lineIndex, long frame) {
        Recording g = new Recording();
        TextKit.drawLine(g, line, X0, BASE, 0xFFFFFFFF, lineIndex, frame);
        return g.on(BASE);
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

    /**
     * A graphics recording every run handed to it and where it was drawn, then drawing it - the solid
     * path's shadow and main passes and a gradient run's letters all reach it here.
     */
    private static final class Recording extends MinecraftGraphics {

        /** Every run drawn, in order. */
        private final @NotNull ConcurrentList<Draw> draws = Concurrent.newList();

        private Recording() {
            super(PixelBuffer.create(160, 60));
        }

        @Override
        public void drawString(@NotNull String str, float xMcPx, float yMcPx) {
            this.draws.add(new Draw(str, xMcPx, yMcPx));
            super.drawString(str, xMcPx, yMcPx);
        }

        /**
         * Answers the runs drawn on a baseline, which are a line's main pass - its shadow sits a pixel
         * lower.
         *
         * @param baseline the baseline in Minecraft pixels
         * @return the runs drawn on it, in order
         */
        private @NotNull ConcurrentList<Draw> on(int baseline) {
            ConcurrentList<Draw> on = Concurrent.newList();
            for (Draw draw : this.draws)
                if (draw.yMcPx() == baseline) on.add(draw);
            return on;
        }

        /**
         * Answers the text of every run drawn, shadow and main pass alike, in order and wherever it
         * was drawn.
         *
         * @return the texts drawn
         */
        private @NotNull ConcurrentList<String> texts() {
            ConcurrentList<String> texts = Concurrent.newList();
            for (Draw draw : this.draws)
                texts.add(draw.text());
            return texts;
        }

    }

    /**
     * One run handed to the graphics.
     *
     * @param text the text drawn
     * @param xMcPx where the pen stood, in Minecraft pixels
     * @param yMcPx the baseline it sat on, in Minecraft pixels
     */
    private record Draw(@NotNull String text, float xMcPx, float yMcPx) {}

    /**
     * A generator whose every bounded draw answers one index and records the bound it was asked for,
     * which reads {@link MinecraftFont#obfuscate(int, RandomGenerator)}'s candidates in their order -
     * one {@code nextInt(n)} over them is that method's contract.
     */
    private static final class FixedPick implements RandomGenerator {

        /** The index every bounded draw answers. */
        private final int index;

        /** The bound of the last bounded draw, {@code -1} before one. */
        private int bound = -1;

        private FixedPick(int index) {
            this.index = index;
        }

        @Override
        public int nextInt(int bound) {
            this.bound = bound;
            return this.index;
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException("Only a bounded int is drawn");
        }

    }

}
