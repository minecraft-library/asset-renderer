package lib.minecraft.renderer.bake.gui;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.pack.TextPalette;
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
 * after it. The strikethrough and underline bars are in mcPixels too, and the graphics converts to
 * output pixels where it lays a glyph or a bar. A line's width is {@link #measureLineMcPixels}, the
 * client's {@code Font#width}, and what it lays down is {@link #inkBounds}, answered from glyph data
 * before the line is drawn.
 * <p>
 * A run is laid as the client lays text - each pixel in its colour's alpha, none the client's text
 * shader would discard, blended over the canvas - and all of that is the graphics', through
 * {@link MinecraftGraphics#drawString(String, float, float)} for the glyphs and
 * {@link MinecraftGraphics#fillEffect(float, float, float, float)} for the bars, so the kit never
 * blends.
 * <p>
 * A run is drawn in its segment's own font ({@link TextSegment#resolveFont()}) - its font id, the
 * default font where it names none, in its bold and italic style - and that one font is the one its
 * obfuscation picks from, its glyphs are drawn in and its width and ink are measured in; a gradient
 * run is handed the same font.
 * <p>
 * Handles color mapping, text shadow, strikethrough, underline, and obfuscation ({@code §k}). A run's
 * strikethrough and underline bars run from one mcPixel left of the run to its end, the strikethrough
 * across 3.5 to 4.5 and the underline across 8 to 9 mcPixels below the line's top, and are laid after
 * every glyph of the line, each bar's shadow copy first, as the client's font lays a line's glyphs and
 * then its effects.
 * <p>
 * A run is drawn in the colours the client's font derives for it ({@link #resolveColor},
 * {@link #shadowColor}): a segment naming a colour draws its red, green and blue in the alpha of the
 * colour the line is drawn in, and one naming none draws that colour. Its shadow is the segment's
 * shadow colour where it names one, that colour's alpha scaled by a see-through text colour's, and is
 * drawn whether or not the caller asks for a shadow; otherwise it is the text colour at a quarter, drawn
 * where the caller asks for one. A gradient run is drawn opaque in {@link GradientKit}'s colours, and
 * its shadow is derived by the same rule from the colour each letter is drawn in.
 * <p>
 * Every colour a run lays down - a glyph's, its drop shadow's and its strikethrough and underline
 * bars' - is drawn in the colour the caller's {@link TextPalette} swaps it for, as a pack's text shader
 * swaps every text vertex colour. The shadow's colour is resolved first, from the unswapped text colour
 * or as the segment's own, and swapped as a colour of its own, as the client derives a shadow's vertex
 * colour before its shader runs.
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
     * Strikethrough top-edge offset from the baseline, in mcPixels - the client's
     * {@code y + 4.5F - 1.0F}, from a line top the regular font's ascent, 7 mcPixels, above the
     * baseline.
     */
    static final float STRIKETHROUGH_OFFSET_MCPX = -3.5F;

    /**
     * Underline top-edge offset from the baseline, in mcPixels - the client's {@code y + 9.0F - 1.0F},
     * from a line top the regular font's ascent, 7 mcPixels, above the baseline.
     */
    static final float UNDERLINE_OFFSET_MCPX = 1.0F;

    /**
     * Strikethrough bar thickness in mcPixels - the client's {@code 1.0F} between the bar's top and
     * bottom edges.
     */
    static final float STRIKETHROUGH_THICKNESS_MCPX = 1.0F;

    /**
     * Underline bar thickness in mcPixels - the client's {@code 1.0F} between the bar's top and bottom
     * edges.
     */
    static final float UNDERLINE_THICKNESS_MCPX = 1.0F;

    /**
     * How far a run's bars reach left of the run, in mcPixels - the client opens the bars of the first
     * glyph of each run of one style at {@code x - 1.0F}.
     */
    static final float BAR_LEAD_MCPX = 1.0F;

    /**
     * Drop-shadow displacement in mcPixels - one mcPixel right and one mcPixel down.
     */
    static final int SHADOW_OFFSET_MCPX = 1;

    /**
     * Divisor applied to each red, green and blue channel to produce the client's quarter-brightness
     * drop shadow. Dividing a channel by it truncates as the client's {@code (int) (channel * 0.25F)}
     * does, for every channel value.
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
     * @param defaultArgb the colour the line is drawn in - a segment naming no colour draws in it, and
     * one naming a colour keeps its alpha
     * @param palette the colours a pack's text shader draws in place of the ones the line is given
     * @param lineIndex the line's number among the lines drawn together, which with the frame seeds
     * the scramble of its obfuscated runs
     * @param frame the animation frame drawn
     */
    public static void drawLine(
        @NotNull MinecraftGraphics g,
        @NotNull LineSegment line,
        float xMcPx, float yMcPx,
        int defaultArgb,
        @NotNull TextPalette palette,
        int lineIndex,
        long frame
    ) {
        drawLine(g, line, xMcPx, yMcPx, defaultArgb, palette, lineIndex, frame, frame);
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
     * @param defaultArgb the colour the line is drawn in - a segment naming no colour draws in it, and
     * one naming a colour keeps its alpha
     * @param palette the colours a pack's text shader draws in place of the ones the line is given
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
        @NotNull TextPalette palette,
        int lineIndex,
        long frame,
        long tick
    ) {
        drawLine(g, line, xMcPx, yMcPx, defaultArgb, palette, lineIndex, frame, tick, true);
    }

    /**
     * Draws all segments in a {@link LineSegment}, with the drop shadow a run derives from its colour
     * declinable, as a container's labels decline it - the client's own label draws pass none. A run
     * naming its own shadow colour draws it regardless, as the client's font does.
     * <p>
     * The line opens where its caller places it, on a fraction of an mcPixel where asked, and each
     * segment starts where the one before it ended, its fraction kept, as the client's font keeps its
     * pen. A caller wanting the line's width measures it with {@link #measureLineMcPixels}, and what
     * it lays down with {@link #inkBounds}.
     * <p>
     * Every glyph of the line is laid before any of its bars, and each bar's shadow copy before the
     * bar, as the client lays a line's glyphs and then its effects.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param line the line of styled segments to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the colour the line is drawn in - a segment naming no colour draws in it, and
     * one naming a colour keeps its alpha
     * @param palette the colours a pack's text shader draws in place of the ones the line is given
     * @param lineIndex the line's number among the lines drawn together, which with the frame seeds
     * the scramble of its obfuscated runs
     * @param frame the animation frame drawn
     * @param tick the absolute animation tick driving gradient scroll phase
     * @param dropShadow whether to draw the shadow a run derives from its colour - a segment naming a
     * shadow colour draws it either way
     */
    public static void drawLine(
        @NotNull MinecraftGraphics g,
        @NotNull LineSegment line,
        float xMcPx, float yMcPx,
        int defaultArgb,
        @NotNull TextPalette palette,
        int lineIndex,
        long frame,
        long tick,
        boolean dropShadow
    ) {
        walk(line, xMcPx, lineIndex, frame, (segment, font, text, penMcPx) ->
            drawGlyphs(g, segment, font, text, penMcPx, yMcPx, defaultArgb, palette, tick, dropShadow));
        walk(line, xMcPx, lineIndex, frame, (segment, font, text, penMcPx) ->
            drawBars(g, segment, font, text, penMcPx, yMcPx, defaultArgb, palette, tick, dropShadow));
    }

    /**
     * Walks a line's segments as its draw lays them down: each segment holding text in its own font,
     * an obfuscated one substituted from the line's one stream, the pen opening at the line's origin
     * and moving by what each run answers. {@link #drawLine} and {@link #inkBounds} both walk through
     * here, so the text they answer for and where they place it cannot part.
     * <p>
     * {@link #drawLine} walks a line twice, its glyphs then its bars, and {@link #inkBounds} once; each
     * walk opens the line's scramble stream afresh, so every walk of one line on one frame substitutes
     * the same text.
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

            MinecraftFont font = segment.resolveFont();
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
     * Draws a single {@link TextSegment}'s glyphs at an mcPixel origin with their shadow, answering how
     * far the run moves the line's pen. Its bars are {@link #drawBars}', which {@link #drawLine} lays in
     * a walk of their own after every glyph of the line. A segment carrying a
     * {@link TextSegment#getGradient() gradient} routes to {@link GradientKit}; every other segment
     * takes the solid path. The shadow colour is resolved from the unswapped text colour, or is the
     * segment's own, before either is swapped, and a shadow colour of {@code 0} draws no shadow pass, as
     * the client emits no shadow quad for it.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param segment the styled text segment to render
     * @param font the segment's font
     * @param text the segment's text, substituted where the segment is obfuscated
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the colour the line is drawn in - a segment naming no colour draws in it, and
     * one naming a colour keeps its alpha
     * @param palette the colours a pack's text shader draws in place of the ones the segment is given
     * @param tick the absolute animation tick driving gradient scroll phase
     * @param dropShadow whether the caller asks for the shadow the run derives from its colour; a
     * segment naming a shadow colour draws it either way
     * @return the advance width of the segment in mcPixels, its fraction kept
     */
    private static float drawGlyphs(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float xMcPx, float yMcPx,
        int defaultArgb,
        @NotNull TextPalette palette,
        long tick,
        boolean dropShadow
    ) {
        if (segment.getGradient().isPresent())
            return GradientKit.drawSegment(g, segment, segment.getGradient().get(), font, text, xMcPx, yMcPx, palette, tick, dropShadow);

        int color = resolveColor(segment, defaultArgb);
        int shadow = shadowColor(segment, color, dropShadow);

        // Shadow pass
        g.setFont(font);
        if (shadow != 0) {
            g.setColor(new java.awt.Color(palette.swap(shadow), true));
            g.drawString(text, xMcPx + SHADOW_OFFSET_MCPX, yMcPx + SHADOW_OFFSET_MCPX);
        }

        // Main pass
        g.setColor(new java.awt.Color(palette.swap(color), true));
        g.drawString(text, xMcPx, yMcPx);

        return font.metrics().stringAdvanceMcPixels(text);
    }

    /**
     * Draws a single {@link TextSegment}'s strikethrough and underline bars at an mcPixel origin,
     * answering how far the run moves the line's pen. Each bar runs from one mcPixel left of the run to
     * its end ({@link #bars}), and its shadow copy is laid first, one mcPixel right and down, where the
     * run draws a shadow - as the client lays an effect's shadow copy where its shadow colour is not
     * {@code 0} - then the bar, each through
     * {@link MinecraftGraphics#fillEffect(float, float, float, float)}. {@link #drawLine} calls it in a
     * walk of its own after every glyph of the line is laid, as the client lays a prepared text's
     * effects after its glyphs. A segment carrying a {@link TextSegment#getGradient() gradient} routes
     * to {@link GradientKit}; every other segment's colours are resolved as {@link #drawGlyphs} resolves
     * them.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param segment the styled text segment whose bars are drawn
     * @param font the segment's font
     * @param text the segment's text, substituted where the segment is obfuscated
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the colour the line is drawn in - a segment naming no colour draws in it, and
     * one naming a colour keeps its alpha
     * @param palette the colours a pack's text shader draws in place of the ones the segment is given
     * @param tick the absolute animation tick driving gradient scroll phase
     * @param dropShadow whether the caller asks for the shadow the run derives from its colour; a
     * segment naming a shadow colour draws it either way
     * @return the advance width of the segment in mcPixels, its fraction kept
     */
    private static float drawBars(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float xMcPx, float yMcPx,
        int defaultArgb,
        @NotNull TextPalette palette,
        long tick,
        boolean dropShadow
    ) {
        if (segment.getGradient().isPresent())
            return GradientKit.drawBars(g, segment, segment.getGradient().get(), font, text, xMcPx, yMcPx, palette, tick, dropShadow);

        int color = resolveColor(segment, defaultArgb);
        int shadow = shadowColor(segment, color, dropShadow);
        float textWidthMcPx = font.metrics().stringAdvanceMcPixels(text);

        for (Rectangle2D.Float bar : bars(segment, xMcPx - BAR_LEAD_MCPX, xMcPx + textWidthMcPx, yMcPx)) {
            if (shadow != 0) {
                g.setColor(new java.awt.Color(palette.swap(shadow), true));
                g.fillEffect(bar.x + SHADOW_OFFSET_MCPX, bar.y + SHADOW_OFFSET_MCPX, bar.width, bar.height);
            }

            g.setColor(new java.awt.Color(palette.swap(color), true));
            g.fillEffect(bar.x, bar.y, bar.width, bar.height);
        }

        return textWidthMcPx;
    }

    /**
     * Answers the strikethrough and underline bars a run carries between two edges, in mcPixels and in
     * the order the client adds them, the strikethrough first - the client's
     * {@code createEffect(x0, y0, x1, y1)} edges. A run's bars run from one mcPixel left of the run
     * ({@link #BAR_LEAD_MCPX}) to its end, the left edge passed already led, as the client's font
     * computes it for the first glyph of a run. The draw lays them through
     * {@link MinecraftGraphics#fillEffect(float, float, float, float)} and {@link #inkBounds} reads
     * them. Each edge sits a whole number of output pixels from the run's origin, so a bar lands on the
     * output pixels the origin's own rounding puts the run on.
     *
     * @param segment the segment, supplying which bars its run carries
     * @param x0McPx the bars' left edge in mcPixels
     * @param x1McPx the bars' right edge in mcPixels
     * @param baselineMcPx the run's baseline Y in mcPixels
     * @return the bars the run carries, in draw order
     */
    static @NotNull List<Rectangle2D.Float> bars(@NotNull TextSegment segment, float x0McPx, float x1McPx, float baselineMcPx) {
        List<Rectangle2D.Float> bars = new ArrayList<>(2);
        if (segment.isStrikethrough())
            bars.add(new Rectangle2D.Float(x0McPx, baselineMcPx + STRIKETHROUGH_OFFSET_MCPX, x1McPx - x0McPx, STRIKETHROUGH_THICKNESS_MCPX));
        if (segment.isUnderlined())
            bars.add(new Rectangle2D.Float(x0McPx, baselineMcPx + UNDERLINE_OFFSET_MCPX, x1McPx - x0McPx, UNDERLINE_THICKNESS_MCPX));
        return bars;
    }

    /**
     * Draws plain text with a drop shadow at the given mcPixel origin, the text and its shadow each in
     * the colour the palette swaps it for.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param text the text string to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param font the Minecraft font to use
     * @param argb the text color as packed ARGB
     * @param palette the colours a pack's text shader draws in place of the ones the text is given
     */
    public static void drawText(
        @NotNull MinecraftGraphics g,
        @NotNull String text,
        int xMcPx, int yMcPx,
        @NotNull MinecraftFont font,
        int argb,
        @NotNull TextPalette palette
    ) {
        if (text.isEmpty()) return;

        int shadowArgb = palette.swap(darken(argb));
        g.setFont(font);
        g.setColor(new java.awt.Color(shadowArgb, true));
        g.drawString(text, xMcPx + SHADOW_OFFSET_MCPX, yMcPx + SHADOW_OFFSET_MCPX);
        g.setColor(new java.awt.Color(palette.swap(argb), true));
        g.drawString(text, xMcPx, yMcPx);
    }

    // --- measurement ---

    /**
     * Measures a {@link LineSegment}'s width in whole mcPixels, as the client's {@code Font#width}
     * does - each segment's advance in its own font summed with its fraction kept, then rounded up
     * once, so a line ending on half an mcPixel is answered the whole mcPixel it reaches into. It is
     * what a tooltip canvas is sized from, a title is anchored by, and a text field steps its pen and
     * opens its highlight band by.
     *
     * @param line the line to measure
     * @return the width in whole mcPixels
     */
    public static int measureLineMcPixels(@NotNull LineSegment line) {
        float widthMcPx = 0;
        for (TextSegment segment : line.getSegments())
            widthMcPx += segment.resolveFont().metrics().stringAdvanceMcPixels(segment.getText());
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
     * shadow's offset where the run draws a shadow: its own shadow colour, or the one it derives where
     * the shadow is asked for. A glyph that draws no pixel, such as a space, counts for nothing.
     * <p>
     * A solid run's pass whose colour the client's text shader discards whole - alpha
     * {@link MinecraftGraphics#DISCARD_ALPHA} or less - lays nothing and counts for nothing. In any
     * colour above that a vanilla glyph lays every pixel the library answers, its texels being opaque
     * or clear, and a bar lays all of its own. A gradient run's letters and bands are laid opaque, so
     * they always count, and its shadow counts where its shadow colour clears that cut.
     *
     * @param line the line to measure
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the colour the line is drawn in, as {@link #drawLine} takes it
     * @param lineIndex the line's number among the lines drawn together
     * @param frame the animation frame drawn
     * @param dropShadow whether the shadow a run derives from its colour is drawn - a segment naming a
     * shadow colour draws it either way
     * @return the bounds of what the line draws in mcPixels, empty where it draws nothing
     */
    public static @NotNull Optional<Rectangle2D> inkBounds(
        @NotNull LineSegment line,
        float xMcPx, float yMcPx,
        int defaultArgb,
        int lineIndex,
        long frame,
        boolean dropShadow
    ) {
        List<Rectangle2D> drawn = new ArrayList<>();
        walk(line, xMcPx, lineIndex, frame, (segment, font, text, penMcPx) ->
            segmentInk(segment, font, text, penMcPx, yMcPx, defaultArgb, dropShadow, drawn));
        return drawn.stream().reduce(Rectangle2D::createUnion);
    }

    /**
     * Collects what one segment's run lays down at its pen, as {@link #drawGlyphs} and
     * {@link #drawBars} draw it, and answers how far it moves the line's pen. A solid run's colour
     * and its shadow's are resolved as the draw resolves them, and a pass is counted only where its
     * colour clears {@link MinecraftGraphics#DISCARD_ALPHA}. The run is read from the output pixel
     * its origin rounds to, which is where the graphics lays its glyphs and its bars.
     *
     * @param segment the styled text segment
     * @param font the segment's font
     * @param text the segment's text, substituted where the segment is obfuscated
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the colour the line is drawn in - a segment naming no colour draws in it, and
     * one naming a colour keeps its alpha
     * @param dropShadow whether the shadow the run derives from its colour is drawn - a segment naming a
     * shadow colour draws it either way
     * @param drawn the rectangles laid down so far, in mcPixels, which this run's rectangles join
     * @return the advance width of the segment in mcPixels, its fraction kept
     */
    private static float segmentInk(
        @NotNull TextSegment segment,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float xMcPx, float yMcPx,
        int defaultArgb,
        boolean dropShadow,
        @NotNull List<Rectangle2D> drawn
    ) {
        float textWidthMcPx = font.metrics().stringAdvanceMcPixels(text);
        if (segment.getGradient().isPresent()) {
            GradientKit.inkBounds(segment, font, text, xMcPx, yMcPx, dropShadow).ifPresent(drawn::add);
            return textWidthMcPx;
        }

        int color = resolveColor(segment, defaultArgb);
        boolean main = ColorMath.alpha(color) > MinecraftGraphics.DISCARD_ALPHA;
        boolean shadow = ColorMath.alpha(shadowColor(segment, color, dropShadow)) > MinecraftGraphics.DISCARD_ALPHA;

        int pxPerMcPx = MinecraftFont.MC_PIXEL_SCALE;
        int xOut = Math.round(xMcPx * pxPerMcPx);
        int yOut = Math.round(yMcPx * pxPerMcPx);
        Rectangle glyphs = font.layout(text).getVisualBounds().getBounds();
        glyphs.translate(xOut, yOut);
        addInk(drawn, mcPixels(glyphs), main, shadow);
        float originMcPx = xOut / (float) pxPerMcPx;
        for (Rectangle2D.Float bar : bars(segment, originMcPx - BAR_LEAD_MCPX, originMcPx + textWidthMcPx, yOut / (float) pxPerMcPx))
            addInk(drawn, bar, main, shadow);
        return textWidthMcPx;
    }

    /**
     * Adds a rectangle a run lays down to {@code drawn} on each pass that lays it - where it is on the
     * main pass, at the shadow's offset on the shadow pass. An empty one lays down nothing and is
     * skipped.
     *
     * @param drawn the rectangles laid down so far, in mcPixels
     * @param mcPx the rectangle the run lays down, in mcPixels
     * @param main whether the main pass lays it down
     * @param shadow whether the offset shadow pass lays it down
     */
    static void addInk(@NotNull List<Rectangle2D> drawn, @NotNull Rectangle2D.Float mcPx, boolean main, boolean shadow) {
        if (mcPx.isEmpty()) return;

        if (main)
            drawn.add(mcPx);
        if (shadow)
            drawn.add(new Rectangle2D.Float(mcPx.x + SHADOW_OFFSET_MCPX, mcPx.y + SHADOW_OFFSET_MCPX, mcPx.width, mcPx.height));
    }

    /**
     * Converts a rectangle in output pixels to mcPixels.
     *
     * @param outPx the rectangle in output pixels
     * @return the same rectangle in mcPixels
     */
    static @NotNull Rectangle2D.Float mcPixels(@NotNull Rectangle outPx) {
        float pxPerMcPx = MinecraftFont.MC_PIXEL_SCALE;
        return new Rectangle2D.Float(outPx.x / pxPerMcPx, outPx.y / pxPerMcPx, outPx.width / pxPerMcPx, outPx.height / pxPerMcPx);
    }

    // --- color utilities ---

    /**
     * Resolves the colour a segment's run is drawn in, as the client's font does - a segment naming a
     * colour draws its red, green and blue in the alpha of the colour the line is drawn in, and one
     * naming none draws that colour.
     *
     * @param segment the segment to resolve
     * @param defaultArgb the colour the line is drawn in, which a segment naming no colour draws in and
     * whose alpha a segment naming one keeps
     * @return the colour the run's text is drawn in, as packed ARGB
     */
    public static int resolveColor(@NotNull TextSegment segment, int defaultArgb) {
        return segment.getColor()
            .map(colour -> ColorMath.withAlpha(colour.rgb(), ColorMath.alpha(defaultArgb)))
            .orElse(defaultArgb);
    }

    /**
     * Answers the colour a run's shadow is drawn in, as the client's font derives it - the segment's
     * shadow colour where it names one, its alpha scaled by a see-through text colour's and floored,
     * drawn whether or not the caller asks for a shadow; otherwise the text colour at a quarter
     * ({@link #darken(int)}) where the caller asks for one, and {@code 0}, no shadow, where it asks for
     * none.
     *
     * @param segment the segment
     * @param textArgb the colour the run's text is drawn in, as {@link #resolveColor} answers it
     * @param dropShadow whether the caller asks for the shadow a run derives from its colour
     * @return the shadow colour as packed ARGB, {@code 0} where the run draws no shadow
     */
    public static int shadowColor(@NotNull TextSegment segment, int textArgb, boolean dropShadow) {
        return segment.getShadowColor()
            .map(ChatColor::rgb)
            .map(shadow -> {
                float textAlpha = ColorMath.alpha(textArgb) / 255.0F;
                float shadowAlpha = ColorMath.alpha(shadow) / 255.0F;
                return textAlpha != 1.0F ? ColorMath.withAlpha(shadow, (int) Math.floor(textAlpha * shadowAlpha * 255.0F)) : shadow;
            })
            .orElseGet(() -> dropShadow ? darken(textArgb) : 0);
    }

    /**
     * Darkens a colour to the client's derived shadow - each of its red, green and blue at a quarter,
     * truncated, and its alpha kept, as the client's {@code ARGB.scaleRGB(argb, 0.25F)} answers it.
     *
     * @param argb the colour as packed ARGB
     * @return the darkened colour as packed ARGB
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
