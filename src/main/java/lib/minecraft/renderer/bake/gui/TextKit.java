package lib.minecraft.renderer.bake.gui;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGlyphVector;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Static text rendering utilities for styled Minecraft text, drawn through a
 * {@link MinecraftGraphics} that owns the supersampling factor.
 * <p>
 * Every position is in mcPixels. A line opens where its caller places it, on a fraction of an
 * mcPixel where asked, as the client's {@code Font} draws from a {@code float}, and its pen is a
 * {@code float} as the client keeps it, so a run ending on half an mcPixel hands that half to the run
 * after it; the graphics converts to output pixels where it blits a glyph. The strikethrough and
 * underline bars, which {@code PixelGraphics#fillRect} lays in output pixels, convert at that write.
 * A line's width is {@link #measureLineMcPixels}, the client's {@code Font#width}, and what it lays
 * down is {@link #inkBounds}, answered from glyph data before the line is drawn.
 * <p>
 * Handles font style resolution, color mapping, text shadow, strikethrough, underline, and
 * obfuscation ({@code §k}). Shadow, strikethrough, and underline offsets match vanilla's pixel-level
 * rendering.
 * <p>
 * An obfuscated run draws, in place of each glyph, a random glyph its font lays out at the same
 * non-bold advance ({@link MinecraftFont#obfuscate(String, RandomGenerator)}), so a non-bold run
 * keeps its width on every frame. Every run of a line draws from one stream seeded by the frame and
 * the line's number, so a frame redraws its own scramble, two lines of a frame draw two, the runs of
 * one line draw different ones, and a line scrambles the same wherever it is drawn.
 *
 * @see MinecraftFont
 * @see MinecraftGraphics
 * @see TextSegment
 * @see LineSegment
 */
@UtilityClass
public class TextKit {

    /**
     * Strikethrough top-edge offset from baseline, in mcPixels. Matches vanilla 1.13+'s
     * {@code y + 3.5..y + 4.5} 1-mcPixel-tall rect when combined with
     * {@link #STRIKETHROUGH_THICKNESS_MCPX}.
     */
    static final int STRIKETHROUGH_OFFSET_MCPX = -4;

    /**
     * Underline top-edge offset from baseline, in mcPixels. Matches vanilla's
     * {@code y + 8..y + 9} 1-mcPixel-tall rect when combined with
     * {@link #UNDERLINE_THICKNESS_MCPX}.
     */
    static final int UNDERLINE_OFFSET_MCPX = 1;

    /**
     * Strikethrough bar thickness in mcPixels.
     */
    static final int STRIKETHROUGH_THICKNESS_MCPX = 1;

    /**
     * Underline bar thickness in mcPixels.
     */
    static final int UNDERLINE_THICKNESS_MCPX = 1;

    /**
     * Drop-shadow displacement in mcPixels - one mcPixel right and one mcPixel down.
     */
    static final int SHADOW_OFFSET_MCPX = 1;

    /**
     * Divisor applied to each RGB channel to produce vanilla's ~25%-brightness drop shadow.
     * Matches the {@code (rgb & 0xFCFCFC) >> 2} formula used by {@code Font#getShadowColor}
     * from 1.13 onward, split across channels so the low two bits survive the mask independently.
     */
    private static final int SHADOW_DARKEN_DIVISOR = 4;

    // --- segment rendering ---

    /**
     * Draws all segments in a {@link LineSegment} at the given mcPixel origin with shadow and
     * decoration support. A caller wanting the line's width measures it with
     * {@link #measureLineMcPixels}, and what it lays down with {@link #inkBounds}.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param line the line of styled segments to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the fallback color for segments without an explicit color
     * @param lineIndex the line's number among the lines drawn together, which with the frame seeds
     * the scramble of its obfuscated runs
     * @param frame the animation frame drawn
     */
    public static void drawLine(
        @NotNull MinecraftGraphics g,
        @NotNull LineSegment line,
        float xMcPx, float yMcPx,
        int defaultArgb,
        int lineIndex,
        long frame
    ) {
        drawLine(g, line, xMcPx, yMcPx, defaultArgb, lineIndex, frame, frame);
    }

    /**
     * Draws all segments in a {@link LineSegment}, threading an animation {@code tick} alongside the
     * {@code frame} so gradient segments can scroll. A caller wanting the line's width measures it
     * with {@link #measureLineMcPixels}, and what it lays down with {@link #inkBounds}.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param line the line of styled segments to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the fallback color for segments without an explicit color
     * @param lineIndex the line's number among the lines drawn together, which with the frame seeds
     * the scramble of its obfuscated runs
     * @param frame the animation frame drawn
     * @param tick the absolute animation tick driving gradient scroll phase
     */
    public static void drawLine(
        @NotNull MinecraftGraphics g,
        @NotNull LineSegment line,
        float xMcPx, float yMcPx,
        int defaultArgb,
        int lineIndex,
        long frame,
        long tick
    ) {
        drawLine(g, line, xMcPx, yMcPx, defaultArgb, lineIndex, frame, tick, true);
    }

    /**
     * Draws all segments in a {@link LineSegment}, with the drop shadow declinable. Text drawn into a
     * container's chrome carries none, which is what the client's own label draws pass.
     * <p>
     * The line opens where its caller places it, on a fraction of an mcPixel where asked, and each
     * segment starts where the one before it ended, its fraction kept, as the client's font keeps its
     * pen. A caller wanting the line's width measures it with {@link #measureLineMcPixels}, and what
     * it lays down with {@link #inkBounds}.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param line the line of styled segments to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the fallback color for segments without an explicit color
     * @param lineIndex the line's number among the lines drawn together, which with the frame seeds
     * the scramble of its obfuscated runs
     * @param frame the animation frame drawn
     * @param tick the absolute animation tick driving gradient scroll phase
     * @param dropShadow whether to draw the offset shadow pass beneath the glyphs
     */
    public static void drawLine(
        @NotNull MinecraftGraphics g,
        @NotNull LineSegment line,
        float xMcPx, float yMcPx,
        int defaultArgb,
        int lineIndex,
        long frame,
        long tick,
        boolean dropShadow
    ) {
        walk(line, xMcPx, lineIndex, frame, (segment, font, text, penMcPx) ->
            drawSegment(g, segment, font, text, penMcPx, yMcPx, defaultArgb, tick, dropShadow));
    }

    /**
     * Walks a line's segments as its draw lays them down: each segment holding text in its own font,
     * an obfuscated one substituted from the line's one stream, the pen opening at the line's origin
     * and moving by what each run answers. {@link #drawLine} and {@link #inkBounds} both walk through
     * here, so the text they answer for and where they place it cannot part.
     *
     * @param line the line to walk
     * @param xMcPx the line's starting cursor X in mcPixels
     * @param lineIndex the line's number among the lines drawn together
     * @param frame the animation frame drawn
     * @param run what each segment's run does at its pen
     */
    private static void walk(@NotNull LineSegment line, float xMcPx, int lineIndex, long frame, @NotNull SegmentRun run) {
        RandomGenerator random = obfuscationStream(frame, lineIndex);
        float penMcPx = xMcPx;
        for (TextSegment segment : line.getSegments()) {
            String text = segment.getText();
            if (text.isEmpty()) continue;

            MinecraftFont font = MinecraftFont.Vanilla.of(segment.fontStyle());
            if (segment.isObfuscated())
                text = font.obfuscate(text, random);

            penMcPx += run.at(segment, font, text, penMcPx);
        }
    }

    /**
     * One segment's run at its pen, drawn or measured, answering how far it moves the line's pen.
     */
    @FunctionalInterface
    private interface SegmentRun {

        /**
         * Runs one segment at its pen.
         *
         * @param segment the styled segment
         * @param font the segment's font
         * @param text the segment's text, substituted where the segment is obfuscated
         * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
         * @return the advance width of the segment in mcPixels, its fraction kept
         */
        float at(@NotNull TextSegment segment, @NotNull MinecraftFont font, @NotNull String text, float xMcPx);

    }

    /**
     * Draws a single {@link TextSegment}'s run at an mcPixel origin with shadow, strikethrough and
     * underline support, answering how far it moves the line's pen. A segment carrying a
     * {@link TextSegment#getGradient() gradient} routes to {@link GradientKit}; every other segment
     * takes the solid path.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param segment the styled text segment to render
     * @param font the segment's font
     * @param text the segment's text, substituted where the segment is obfuscated
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the fallback color when the segment has no explicit color
     * @param tick the absolute animation tick driving gradient scroll phase
     * @param dropShadow whether to draw the offset shadow pass beneath the glyphs
     * @return the advance width of the segment in mcPixels, its fraction kept
     */
    private static float drawSegment(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float xMcPx, float yMcPx,
        int defaultArgb,
        long tick,
        boolean dropShadow
    ) {
        if (segment.getGradient().isPresent())
            return GradientKit.drawSegment(g, segment, segment.getGradient().get(), text, xMcPx, yMcPx, tick, dropShadow);

        int pxPerMcPx = MinecraftFont.MC_PIXEL_SCALE;
        int color = resolveColor(segment, defaultArgb);
        int shadow = shadowColor(segment, color);

        float textWidthMcPx = font.metrics().stringAdvanceMcPixels(text);

        // Mark geometry runs in output-pixel coordinates because fillRect (inherited from
        // PixelGraphics) operates in output-pixel space, so the origin and the width convert here -
        // each exactly, every one being a whole number of half mcPixels.
        int xOut = Math.round(xMcPx * pxPerMcPx);
        int yOut = Math.round(yMcPx * pxPerMcPx);
        int shadowOffsetOut = SHADOW_OFFSET_MCPX * pxPerMcPx;
        List<Rectangle> bars = decorations(segment, xOut, yOut, Math.round(textWidthMcPx * pxPerMcPx));

        // Shadow pass
        g.setFont(font);
        if (dropShadow) {
            g.setColor(new java.awt.Color(shadow, true));
            g.drawString(text, xMcPx + SHADOW_OFFSET_MCPX, yMcPx + SHADOW_OFFSET_MCPX);
            for (Rectangle bar : bars)
                g.fillRect(bar.x + shadowOffsetOut, bar.y + shadowOffsetOut, bar.width, bar.height);
        }

        // Main pass
        g.setColor(new java.awt.Color(color, true));
        g.drawString(text, xMcPx, yMcPx);
        for (Rectangle bar : bars)
            g.fillRect(bar.x, bar.y, bar.width, bar.height);

        return textWidthMcPx;
    }

    /**
     * Answers the strikethrough and underline bars a solid run carries, in output pixels and in the
     * order they are drawn - the strikethrough from one output pixel left of the run and as wide as
     * it, vanilla's {@code x - 1}, and the underline from the run's start and one output pixel wider.
     * The draw fills them and {@link #inkBounds} reads them. The thickness and offset constants are
     * declared in mcPixels and scaled up here.
     *
     * @param segment the segment, supplying which bars its run carries
     * @param xOut the run's start X in output pixels
     * @param baselineOut the run's baseline Y in output pixels
     * @param widthOut the run's width in output pixels
     * @return the bars the run carries, in draw order
     */
    private static @NotNull List<Rectangle> decorations(@NotNull TextSegment segment, int xOut, int baselineOut, int widthOut) {
        int pxPerMcPx = MinecraftFont.MC_PIXEL_SCALE;
        int leftPadOut = 1;
        List<Rectangle> bars = new ArrayList<>(2);
        if (segment.isStrikethrough())
            bars.add(new Rectangle(xOut - leftPadOut, baselineOut + STRIKETHROUGH_OFFSET_MCPX * pxPerMcPx, widthOut, STRIKETHROUGH_THICKNESS_MCPX * pxPerMcPx));
        if (segment.isUnderlined())
            bars.add(new Rectangle(xOut, baselineOut + UNDERLINE_OFFSET_MCPX * pxPerMcPx, widthOut + leftPadOut, UNDERLINE_THICKNESS_MCPX * pxPerMcPx));
        return bars;
    }

    /**
     * Draws plain text with a drop shadow at the given mcPixel origin.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param text the text string to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param font the Minecraft font to use
     * @param argb the text color as packed ARGB
     */
    public static void drawText(
        @NotNull MinecraftGraphics g,
        @NotNull String text,
        int xMcPx, int yMcPx,
        @NotNull MinecraftFont font,
        int argb
    ) {
        if (text.isEmpty()) return;

        int shadowArgb = darken(argb);
        g.setFont(font);
        g.setColor(new java.awt.Color(shadowArgb, true));
        g.drawString(text, xMcPx + SHADOW_OFFSET_MCPX, yMcPx + SHADOW_OFFSET_MCPX);
        g.setColor(new java.awt.Color(argb, true));
        g.drawString(text, xMcPx, yMcPx);
    }

    // --- measurement ---

    /**
     * Measures a {@link LineSegment}'s width in whole mcPixels, as the client's {@code Font#width}
     * does - each segment's advance in its own font summed with its fraction kept, then rounded up
     * once, so a line ending on half an mcPixel is answered the whole mcPixel it reaches into. It is
     * what a tooltip canvas is sized from, a title is anchored by, and a text field scrolls and
     * places its caret by.
     *
     * @param line the line to measure
     * @return the width in whole mcPixels
     */
    public static int measureLineMcPixels(@NotNull LineSegment line) {
        float widthMcPx = 0;
        for (TextSegment segment : line.getSegments())
            widthMcPx += MinecraftFont.Vanilla.of(segment.fontStyle()).metrics().stringAdvanceMcPixels(segment.getText());
        return (int) Math.ceil(widthMcPx);
    }

    /**
     * Answers what {@link #drawLine} lays down with the same arguments, before the line is drawn - the
     * bounds of every pixel it draws, in mcPixels, empty where it draws none.
     * <p>
     * It walks the line as the draw does, so an obfuscated run is measured as the scramble the draw
     * picks for that frame and line number, and unions what each run lays down: a solid run's glyphs,
     * as the text library answers the pixels a run draws ({@link MinecraftGlyphVector#getVisualBounds()}),
     * with its bars, and a gradient run's as {@link GradientKit} answers them - each again at the
     * shadow's offset when the shadow is drawn. A glyph that draws no pixel, such as a space, counts
     * for nothing. The bars are counted as an opaque colour lays them down, which is the colour every
     * glyph is laid down in.
     *
     * @param line the line to measure
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param lineIndex the line's number among the lines drawn together
     * @param frame the animation frame drawn
     * @param dropShadow whether the offset shadow pass is drawn
     * @return the bounds of what the line draws in mcPixels, empty where it draws nothing
     */
    public static @NotNull Optional<Rectangle2D> inkBounds(
        @NotNull LineSegment line,
        float xMcPx, float yMcPx,
        int lineIndex,
        long frame,
        boolean dropShadow
    ) {
        List<Rectangle2D> drawn = new ArrayList<>();
        walk(line, xMcPx, lineIndex, frame, (segment, font, text, penMcPx) ->
            segmentInk(segment, font, text, penMcPx, yMcPx, dropShadow, drawn));
        return drawn.stream().reduce(Rectangle2D::createUnion);
    }

    /**
     * Collects what one segment's run lays down at its pen, as {@link #drawSegment} draws it, and
     * answers how far it moves the line's pen.
     *
     * @param segment the styled text segment
     * @param font the segment's font
     * @param text the segment's text, substituted where the segment is obfuscated
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param dropShadow whether the offset shadow pass is drawn
     * @param drawn the rectangles laid down so far, in mcPixels, which this run's rectangles join
     * @return the advance width of the segment in mcPixels, its fraction kept
     */
    private static float segmentInk(
        @NotNull TextSegment segment,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float xMcPx, float yMcPx,
        boolean dropShadow,
        @NotNull List<Rectangle2D> drawn
    ) {
        float textWidthMcPx = font.metrics().stringAdvanceMcPixels(text);
        if (segment.getGradient().isPresent()) {
            GradientKit.inkBounds(segment, text, xMcPx, yMcPx, dropShadow).ifPresent(drawn::add);
            return textWidthMcPx;
        }

        int pxPerMcPx = MinecraftFont.MC_PIXEL_SCALE;
        int xOut = Math.round(xMcPx * pxPerMcPx);
        int yOut = Math.round(yMcPx * pxPerMcPx);
        Rectangle glyphs = font.layout(text).getVisualBounds().getBounds();
        glyphs.translate(xOut, yOut);
        addInk(drawn, glyphs, dropShadow);
        for (Rectangle bar : decorations(segment, xOut, yOut, Math.round(textWidthMcPx * pxPerMcPx)))
            addInk(drawn, bar, dropShadow);
        return textWidthMcPx;
    }

    /**
     * Adds a rectangle a run lays down to {@code drawn}, converting it from output pixels to mcPixels
     * where it reads it, with its copy at the shadow's offset when the shadow is drawn. An empty one
     * lays down nothing and is skipped.
     *
     * @param drawn the rectangles laid down so far, in mcPixels
     * @param outPx the rectangle the run lays down, in output pixels
     * @param dropShadow whether the offset shadow pass lays it down again
     */
    static void addInk(@NotNull List<Rectangle2D> drawn, @NotNull Rectangle outPx, boolean dropShadow) {
        if (outPx.isEmpty()) return;

        float pxPerMcPx = MinecraftFont.MC_PIXEL_SCALE;
        float x = outPx.x / pxPerMcPx;
        float y = outPx.y / pxPerMcPx;
        float width = outPx.width / pxPerMcPx;
        float height = outPx.height / pxPerMcPx;
        drawn.add(new Rectangle2D.Float(x, y, width, height));
        if (dropShadow)
            drawn.add(new Rectangle2D.Float(x + SHADOW_OFFSET_MCPX, y + SHADOW_OFFSET_MCPX, width, height));
    }

    // --- color utilities ---

    /**
     * Resolves a {@link TextSegment}'s color to a packed ARGB int, falling back to the given
     * default when the segment has no explicit color.
     *
     * @param segment the segment to resolve
     * @param defaultArgb the fallback color
     * @return the resolved ARGB color
     */
    public static int resolveColor(@NotNull TextSegment segment, int defaultArgb) {
        return segment.getColor()
            .map(ChatColor::rgb)
            .orElse(defaultArgb);
    }

    /**
     * Returns the shadow color for a segment as packed ARGB. When the segment carries an explicit
     * {@link ChatColor} its {@link ChatColor#shadowRgb() shadowRgb} is used; a colorless
     * segment falls back to {@link #darken(int) darkening} {@code fallbackArgb} to 25% brightness.
     *
     * @param segment the segment
     * @param fallbackArgb the primary color to darken when the segment has no explicit ChatColor
     * @return the shadow ARGB color
     */
    public static int shadowColor(@NotNull TextSegment segment, int fallbackArgb) {
        return segment.getColor()
            .map(ChatColor::shadowRgb)
            .orElseGet(() -> darken(fallbackArgb));
    }

    /**
     * Darkens a color to approximately 25% brightness, matching vanilla's shadow rendering.
     */
    static int darken(int argb) {
        return ColorMath.pack(
            ColorMath.alpha(argb),
            ColorMath.red(argb) / SHADOW_DARKEN_DIVISOR,
            ColorMath.green(argb) / SHADOW_DARKEN_DIVISOR,
            ColorMath.blue(argb) / SHADOW_DARKEN_DIVISOR
        );
    }

    // --- obfuscation ---

    /**
     * Opens the stream every obfuscated run of one line draws from, seeded by the frame and the
     * line's number, so a frame redraws its own scramble, two lines of one frame draw two, and a line
     * scrambles the same wherever it is drawn.
     * <p>
     * The seed is splitmix64's finalizer taken twice: once over the frame alone, then over that XORed
     * with the line's number. The first mix spreads the frame over all 64 bits, so no two line numbers
     * line up with the difference between two frames; XORed in plain, line {@code n} would draw on
     * frame {@code f} what line {@code m} draws on frame {@code f ^ n ^ m}. The second mix keeps near
     * seeds apart, since a {@link Random} seeded with near values steps its first pick through the
     * candidates at a near-fixed stride. The generator is a {@code Random} because its sequence is
     * specified for every JVM, which a frame's bytes need to reproduce.
     *
     * @param frame the animation frame drawn
     * @param lineIndex the line's number among the lines drawn together
     * @return the line's obfuscation stream
     */
    private static @NotNull RandomGenerator obfuscationStream(long frame, int lineIndex) {
        return new Random(splitmix64(splitmix64(frame) ^ lineIndex));
    }

    /**
     * Mixes a value through splitmix64's finalizer, a bijection on {@code long} under which near
     * inputs answer unrelated outputs.
     *
     * @param z the value to mix
     * @return the mixed value
     */
    private static long splitmix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

}
