package lib.minecraft.renderer.bake.gui;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;

import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Static text rendering utilities for styled Minecraft text, drawn through a
 * {@link MinecraftGraphics} that owns the supersampling factor.
 * <p>
 * Every position is in mcPixels. A line opens on a whole mcPixel and its pen is a {@code float}, as
 * the client's {@code Font} keeps it, so a run ending on half an mcPixel hands that half to the run
 * after it; the graphics converts to output pixels where it blits a glyph. The strikethrough and
 * underline bars, which {@code PixelGraphics#fillRect} lays in output pixels, convert at that write.
 * A line's width is {@link #measureLineMcPixels}, the client's {@code Font#width}.
 * <p>
 * Handles font style resolution, color mapping, text shadow, strikethrough, underline, and
 * obfuscation ({@code §k}). Shadow, strikethrough, and underline offsets match vanilla's pixel-level
 * rendering.
 * <p>
 * An obfuscated run draws, in place of each glyph, a random glyph its font lays out at the same
 * non-bold advance ({@link MinecraftFont#obfuscate(String, RandomGenerator)}), so a non-bold run
 * keeps its width on every frame. Every run of a line draws from one stream seeded by the frame and
 * the line's origin, so a frame redraws its own scramble while the lines and runs of one frame draw
 * different ones.
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
     * {@link #measureLineMcPixels}.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param line the line of styled segments to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the fallback color for segments without an explicit color
     * @param frame the animation frame drawn, which with the line's origin seeds the scramble of its
     * obfuscated runs
     */
    public static void drawLine(
        @NotNull MinecraftGraphics g,
        @NotNull LineSegment line,
        int xMcPx, int yMcPx,
        int defaultArgb,
        long frame
    ) {
        drawLine(g, line, xMcPx, yMcPx, defaultArgb, frame, frame);
    }

    /**
     * Draws all segments in a {@link LineSegment}, threading an animation {@code tick} alongside the
     * {@code frame} so gradient segments can scroll. A caller wanting the line's width measures it
     * with {@link #measureLineMcPixels}.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param line the line of styled segments to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the fallback color for segments without an explicit color
     * @param frame the animation frame drawn, which with the line's origin seeds the scramble of its
     * obfuscated runs
     * @param tick the absolute animation tick driving gradient scroll phase
     */
    public static void drawLine(
        @NotNull MinecraftGraphics g,
        @NotNull LineSegment line,
        int xMcPx, int yMcPx,
        int defaultArgb,
        long frame,
        long tick
    ) {
        drawLine(g, line, xMcPx, yMcPx, defaultArgb, frame, tick, true);
    }

    /**
     * Draws all segments in a {@link LineSegment}, with the drop shadow declinable. Text drawn into a
     * container's chrome carries none, which is what the client's own label draws pass.
     * <p>
     * The line opens on a whole mcPixel and each segment starts where the one before it ended, its
     * fraction kept, as the client's font keeps its pen. A caller wanting the line's width measures
     * it with {@link #measureLineMcPixels}.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param line the line of styled segments to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the fallback color for segments without an explicit color
     * @param frame the animation frame drawn, which with the line's origin seeds the scramble of its
     * obfuscated runs
     * @param tick the absolute animation tick driving gradient scroll phase
     * @param dropShadow whether to draw the offset shadow pass beneath the glyphs
     */
    public static void drawLine(
        @NotNull MinecraftGraphics g,
        @NotNull LineSegment line,
        int xMcPx, int yMcPx,
        int defaultArgb,
        long frame,
        long tick,
        boolean dropShadow
    ) {
        RandomGenerator random = obfuscationStream(frame, xMcPx, yMcPx);
        float penMcPx = xMcPx;
        for (TextSegment segment : line.getSegments())
            penMcPx += drawSegment(g, segment, penMcPx, yMcPx, defaultArgb, random, tick, dropShadow);
    }

    /**
     * Draws a single {@link TextSegment} at an mcPixel origin with shadow, strikethrough, underline,
     * and obfuscation support, answering how far it moves the line's pen. A segment carrying a
     * {@link TextSegment#getGradient() gradient} routes to {@link GradientKit}; every other segment
     * takes the solid path.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param segment the styled text segment to render
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the fallback color when the segment has no explicit color
     * @param random the line's obfuscation stream, drawn from only by an obfuscated segment
     * @param tick the absolute animation tick driving gradient scroll phase
     * @param dropShadow whether to draw the offset shadow pass beneath the glyphs
     * @return the advance width of the segment in mcPixels, its fraction kept
     */
    private static float drawSegment(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        float xMcPx, float yMcPx,
        int defaultArgb,
        @NotNull RandomGenerator random,
        long tick,
        boolean dropShadow
    ) {
        String text = segment.getText();
        if (text.isEmpty()) return 0;

        MinecraftFont font = MinecraftFont.Vanilla.of(segment.fontStyle());
        if (segment.isObfuscated())
            text = font.obfuscate(text, random);

        if (segment.getGradient().isPresent())
            return GradientKit.drawSegment(g, segment, segment.getGradient().get(), text, xMcPx, yMcPx, tick, dropShadow);

        int pxPerMcPx = MinecraftFont.MC_PIXEL_SCALE;
        int color = resolveColor(segment, defaultArgb);
        int shadow = shadowColor(segment, color);

        float textWidthMcPx = font.metrics().stringAdvanceMcPixels(text);

        // Mark geometry runs in output-pixel coordinates because fillRect (inherited from
        // PixelGraphics) operates in output-pixel space, so the origin and the width convert here -
        // each exactly, every one being a whole number of half mcPixels. The decoration
        // thickness/offset constants are declared in mcPixels and scaled up here.
        int xOut = Math.round(xMcPx * pxPerMcPx);
        int yOut = Math.round(yMcPx * pxPerMcPx);
        int textWidthOutPx = Math.round(textWidthMcPx * pxPerMcPx);
        int shadowOffsetOut = SHADOW_OFFSET_MCPX * pxPerMcPx;
        int strikeThicknessOut = STRIKETHROUGH_THICKNESS_MCPX * pxPerMcPx;
        int underlineThicknessOut = UNDERLINE_THICKNESS_MCPX * pxPerMcPx;
        int strikeOffsetOut = STRIKETHROUGH_OFFSET_MCPX * pxPerMcPx;
        int underlineOffsetOut = UNDERLINE_OFFSET_MCPX * pxPerMcPx;
        int decoLeftPadOut = 1; // 1 output pixel of "first character" left padding (vanilla x-1).

        // Shadow pass
        g.setFont(font);
        if (dropShadow) {
            g.setColor(new java.awt.Color(shadow, true));
            g.drawString(text, xMcPx + SHADOW_OFFSET_MCPX, yMcPx + SHADOW_OFFSET_MCPX);
            if (segment.isStrikethrough())
                g.fillRect(xOut + shadowOffsetOut - decoLeftPadOut, yOut + shadowOffsetOut + strikeOffsetOut, textWidthOutPx, strikeThicknessOut);
            if (segment.isUnderlined())
                g.fillRect(xOut + shadowOffsetOut, yOut + shadowOffsetOut + underlineOffsetOut, textWidthOutPx + decoLeftPadOut, underlineThicknessOut);
        }

        // Main pass
        g.setColor(new java.awt.Color(color, true));
        g.drawString(text, xMcPx, yMcPx);
        if (segment.isStrikethrough())
            g.fillRect(xOut - decoLeftPadOut, yOut + strikeOffsetOut, textWidthOutPx, strikeThicknessOut);
        if (segment.isUnderlined())
            g.fillRect(xOut, yOut + underlineOffsetOut, textWidthOutPx + decoLeftPadOut, underlineThicknessOut);

        return textWidthMcPx;
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
     * line's origin, so a frame redraws its own scramble and two lines of one frame draw two.
     * <p>
     * The seed is splitmix64's finalizer taken twice: once over the frame alone, then over that XORed
     * with the origin, which packs injectively, its baseline in the high word and its column in the
     * low. The first mix spreads the frame over all 64 bits, so no two columns line up with the
     * difference between two frames; XORed in plain, the frame would share the column's word, and a
     * line at column {@code c} would draw on frame {@code f} what a line on its baseline at column
     * {@code d} draws on frame {@code f ^ c ^ d}. The second mix keeps near seeds apart, since a
     * {@link Random} seeded with near values steps its first pick through the candidates at a
     * near-fixed stride. The generator is a {@code Random} because its sequence is specified for every
     * JVM, which a frame's bytes need to reproduce.
     *
     * @param frame the animation frame drawn
     * @param xMcPx the line's starting cursor X in mcPixels
     * @param yMcPx the line's baseline Y in mcPixels
     * @return the line's obfuscation stream
     */
    private static @NotNull RandomGenerator obfuscationStream(long frame, int xMcPx, int yMcPx) {
        return new Random(splitmix64(splitmix64(frame) ^ ((long) yMcPx << 32 | xMcPx & 0xFFFFFFFFL)));
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
