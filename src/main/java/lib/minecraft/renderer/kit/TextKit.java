package lib.minecraft.renderer.kit;

import lib.minecraft.text.ChatColor;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import dev.simplified.image.pixel.ColorMath;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Static text rendering utilities for styled Minecraft text. Operates through a
 * {@link MinecraftGraphics} that owns the supersampling factor - callers specify glyph origins
 * and decoration anchors in mcPixel space; the graphics applies all coordinate conversions.
 * <p>
 * Handles font style resolution, color mapping, text shadow, strikethrough, underline, and
 * obfuscation substitution. Shadow, strikethrough, and underline offsets match vanilla's
 * pixel-level rendering.
 *
 * @see MinecraftFont
 * @see MinecraftGraphics
 * @see ColorSegment
 * @see LineSegment
 */
@UtilityClass
public class TextKit {

    /**
     * Strikethrough top-edge offset from baseline, in mcPixels. Matches vanilla 1.13+'s
     * {@code y + 3.5..y + 4.5} 1-mcPixel-tall rect when combined with
     * {@link #STRIKETHROUGH_THICKNESS_MCPX}.
     */
    private static final int STRIKETHROUGH_OFFSET_MCPX = -4;

    /**
     * Underline top-edge offset from baseline, in mcPixels. Matches vanilla's
     * {@code y + 8..y + 9} 1-mcPixel-tall rect when combined with
     * {@link #UNDERLINE_THICKNESS_MCPX}.
     */
    private static final int UNDERLINE_OFFSET_MCPX = 1;

    /** Strikethrough bar thickness in mcPixels. */
    private static final int STRIKETHROUGH_THICKNESS_MCPX = 1;

    /** Underline bar thickness in mcPixels. */
    private static final int UNDERLINE_THICKNESS_MCPX = 1;

    /** Drop-shadow displacement in mcPixels - one mcPixel right and one mcPixel down. */
    private static final int SHADOW_OFFSET_MCPX = 1;

    /**
     * Divisor applied to each RGB channel to produce vanilla's ~25%-brightness drop shadow.
     * Matches the {@code (rgb & 0xFCFCFC) >> 2} formula used by {@code Font#getShadowColor}
     * from 1.13 onward, split across channels so the low two bits survive the mask independently.
     */
    private static final int SHADOW_DARKEN_DIVISOR = 4;

    // --- segment rendering ---

    /**
     * Draws all segments in a {@link LineSegment} at the given mcPixel origin with shadow and
     * decoration support. Returns the total advance in mcPixels.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param line the line of styled segments to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the fallback color for segments without an explicit color
     * @param frameSeed the animation frame seed for obfuscation substitution
     * @return the total advance width of the rendered line in mcPixels
     */
    public static int drawLine(
        @NotNull MinecraftGraphics g,
        @NotNull LineSegment line,
        int xMcPx, int yMcPx,
        int defaultArgb,
        long frameSeed
    ) {
        int startX = xMcPx;
        int cursorMcPx = xMcPx;
        for (ColorSegment segment : line.getSegments())
            cursorMcPx += drawSegment(g, segment, cursorMcPx, yMcPx, defaultArgb, frameSeed);
        return cursorMcPx - startX;
    }

    /**
     * Draws a single {@link ColorSegment} at the given mcPixel origin with shadow,
     * strikethrough, underline, and obfuscation support.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param segment the styled text segment to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param defaultArgb the fallback color when the segment has no explicit color
     * @param frameSeed the animation frame seed for obfuscation substitution
     * @return the advance width of the rendered segment in mcPixels
     */
    public static int drawSegment(
        @NotNull MinecraftGraphics g,
        @NotNull ColorSegment segment,
        int xMcPx, int yMcPx,
        int defaultArgb,
        long frameSeed
    ) {
        String text = segment.getText();
        if (text.isEmpty()) return 0;

        if (segment.isObfuscated())
            text = ObfuscationKit.substitute(text, frameSeed);

        int pxPerMcPx = MinecraftFont.MC_PIXEL_SCALE;
        MinecraftFont font = MinecraftFont.of(segment.fontStyle());
        int color = resolveColor(segment, defaultArgb);
        int shadow = shadowColor(segment, color);

        int textWidthMcPx = measureSegmentMcPixels(text, font);
        int textWidthOutPx = textWidthMcPx * pxPerMcPx;

        // Decoration geometry runs in output-pixel coordinates because fillRect (inherited from
        // PixelGraphics) operates in output-pixel space. The decoration thickness/offset
        // constants are declared in mcPixels and scaled up here.
        int xOut = xMcPx * pxPerMcPx;
        int yOut = yMcPx * pxPerMcPx;
        int shadowOffsetOut = SHADOW_OFFSET_MCPX * pxPerMcPx;
        int strikeThicknessOut = STRIKETHROUGH_THICKNESS_MCPX * pxPerMcPx;
        int underlineThicknessOut = UNDERLINE_THICKNESS_MCPX * pxPerMcPx;
        int strikeOffsetOut = STRIKETHROUGH_OFFSET_MCPX * pxPerMcPx;
        int underlineOffsetOut = UNDERLINE_OFFSET_MCPX * pxPerMcPx;
        int decoLeftPadOut = 1; // 1 output pixel of "first character" left padding (vanilla x-1).

        // Shadow pass
        g.setFont(font);
        g.setColor(new java.awt.Color(shadow, true));
        g.drawString(text, xMcPx + SHADOW_OFFSET_MCPX, yMcPx + SHADOW_OFFSET_MCPX);
        if (segment.isStrikethrough())
            g.fillRect(xOut + shadowOffsetOut - decoLeftPadOut, yOut + shadowOffsetOut + strikeOffsetOut, textWidthOutPx, strikeThicknessOut);
        if (segment.isUnderlined())
            g.fillRect(xOut + shadowOffsetOut, yOut + shadowOffsetOut + underlineOffsetOut, textWidthOutPx + decoLeftPadOut, underlineThicknessOut);

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
     * Draws plain text with a drop shadow at the given mcPixel origin. Returns the advance
     * width in mcPixels.
     *
     * @param g the graphics owning the buffer and sampling factor
     * @param text the text string to render
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param font the Minecraft font to use
     * @param argb the text color as packed ARGB
     * @return the advance width of the rendered text in mcPixels
     */
    public static int drawText(
        @NotNull MinecraftGraphics g,
        @NotNull String text,
        int xMcPx, int yMcPx,
        @NotNull MinecraftFont font,
        int argb
    ) {
        if (text.isEmpty()) return 0;

        int shadowArgb = darken(argb);
        g.setFont(font);
        g.setColor(new java.awt.Color(shadowArgb, true));
        g.drawString(text, xMcPx + SHADOW_OFFSET_MCPX, yMcPx + SHADOW_OFFSET_MCPX);
        g.setColor(new java.awt.Color(argb, true));
        g.drawString(text, xMcPx, yMcPx);
        return measureSegmentMcPixels(text, font);
    }

    // --- measurement ---

    /**
     * Measures the width of a {@link LineSegment} in native output pixels (pre-sampling).
     * Pure arithmetic over cached glyph advance widths.
     *
     * @param line the line to measure
     * @return the total width in native output pixels
     */
    public static int measureLine(@NotNull LineSegment line) {
        int width = 0;

        for (ColorSegment segment : line.getSegments())
            width += measureSegment(segment);

        return width;
    }

    /**
     * Measures the width of a {@link LineSegment} in mcPixels.
     *
     * @param line the line to measure
     * @return the total width in mcPixels
     */
    public static int measureLineMcPixels(@NotNull LineSegment line) {
        return measureLine(line) / MinecraftFont.MC_PIXEL_SCALE;
    }

    /**
     * Measures the width of a single {@link ColorSegment} in native output pixels.
     *
     * @param segment the segment to measure
     * @return the width in native output pixels
     */
    public static int measureSegment(@NotNull ColorSegment segment) {
        if (segment.getText().isEmpty()) return 0;
        return measureText(segment.getText(), MinecraftFont.of(segment.fontStyle()));
    }

    /**
     * Measures the width of a plain string in native output pixels (the native-sampling
     * rasterized width). Pure arithmetic over cached glyph advance widths.
     *
     * @param text the text to measure
     * @param font the font to measure with
     * @return the width in native output pixels
     */
    public static int measureText(@NotNull String text, @NotNull MinecraftFont font) {
        int width = 0;
        for (int i = 0; i < text.length(); i++)
            width += font.glyph(text.charAt(i)).advanceWidth();
        return width;
    }

    /**
     * Measures the width of a plain string in mcPixels.
     *
     * @param text the text to measure
     * @param font the font to measure with
     * @return the width in mcPixels
     */
    public static int measureTextMcPixels(@NotNull String text, @NotNull MinecraftFont font) {
        return measureText(text, font) / MinecraftFont.MC_PIXEL_SCALE;
    }

    private static int measureSegmentMcPixels(@NotNull String text, @NotNull MinecraftFont font) {
        return measureText(text, font) / MinecraftFont.MC_PIXEL_SCALE;
    }

    // --- color utilities ---

    /**
     * Resolves a {@link ColorSegment}'s color to a packed ARGB int, falling back to the given
     * default when the segment has no explicit color.
     *
     * @param segment the segment to resolve
     * @param defaultArgb the fallback color
     * @return the resolved ARGB color
     */
    public static int resolveColor(@NotNull ColorSegment segment, int defaultArgb) {
        return segment.getColor()
            .map(ChatColor::rgb)
            .orElse(defaultArgb);
    }

    /**
     * Returns the shadow color for a segment as packed ARGB. Uses the
     * {@link ChatColor#backgroundRgb()} when available, otherwise darkens the given color
     * to 25% brightness.
     *
     * @param segment the segment
     * @param fallbackArgb the primary color to darken if no ChatColor background is available
     * @return the shadow ARGB color
     */
    public static int shadowColor(@NotNull ColorSegment segment, int fallbackArgb) {
        return segment.getColor()
            .map(ChatColor::backgroundRgb)
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

}
