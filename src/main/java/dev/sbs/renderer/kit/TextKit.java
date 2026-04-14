package dev.sbs.renderer.kit;

import dev.sbs.renderer.text.ChatColor;
import dev.sbs.renderer.text.ColorSegment;
import dev.sbs.renderer.text.LineSegment;
import dev.sbs.renderer.text.font.MinecraftFont;
import dev.sbs.renderer.text.font.MinecraftGraphics;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Static text rendering utilities for styled Minecraft text, operating directly on
 * {@link PixelBuffer} via the {@link MinecraftFont} glyph atlas - no AWT {@link java.awt.Graphics2D}
 * at render time.
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

    private static final int PIXEL_SIZE = MinecraftFont.MC_PIXEL_SCALE;

    /**
     * Strikethrough top-edge offset from baseline, in the native 2x-rasterized font space.
     * Matches vanilla's {@code y+3.5..y+4.5} 1-mc-pixel-tall rect when combined with
     * {@link #STRIKETHROUGH_THICKNESS}.
     */
    private static final int STRIKETHROUGH_OFFSET = -8;

    /**
     * Underline top-edge offset from baseline, in the native 2x-rasterized font space. Matches
     * vanilla's {@code y+8..y+9} 1-mc-pixel-tall rect when combined with
     * {@link #UNDERLINE_THICKNESS}.
     */
    private static final int UNDERLINE_OFFSET = 2;

    /** Strikethrough bar thickness in native 2x-rasterized font space (1 mcPixel). */
    private static final int STRIKETHROUGH_THICKNESS = PIXEL_SIZE;

    /** Underline bar thickness in native 2x-rasterized font space (1 mcPixel). */
    private static final int UNDERLINE_THICKNESS = PIXEL_SIZE;

    // --- segment rendering ---

    /**
     * Draws all segments in a {@link LineSegment} at the given position with shadow and
     * decoration support at the native pixel scale. Returns the total pixel width drawn.
     *
     * @param buffer the pixel buffer to draw onto
     * @param line the line of styled segments to render
     * @param x the starting X position
     * @param y the text baseline Y position
     * @param defaultArgb the fallback color for segments without an explicit color
     * @param frameSeed the animation frame seed for obfuscation substitution
     * @return the total pixel width of the rendered line
     */
    public static int drawLine(
        @NotNull PixelBuffer buffer,
        @NotNull LineSegment line,
        int x, int y,
        int defaultArgb,
        long frameSeed
    ) {
        return drawLine(buffer, line, x, y, defaultArgb, frameSeed, 1);
    }

    /**
     * Draws all segments in a {@link LineSegment} at the given position with shadow and
     * decoration support, scaled uniformly by {@code scale} for supersampled rendering.
     * Returns the total pixel width drawn in the output buffer.
     *
     * @param buffer the pixel buffer to draw onto
     * @param line the line of styled segments to render
     * @param x the starting X position (in output pixels)
     * @param y the text baseline Y position (in output pixels)
     * @param defaultArgb the fallback color for segments without an explicit color
     * @param frameSeed the animation frame seed for obfuscation substitution
     * @param scale the supersampling factor - glyphs, shadow offset and decoration geometry
     *              all scale uniformly
     * @return the total pixel width of the rendered line, in output pixels
     */
    public static int drawLine(
        @NotNull PixelBuffer buffer,
        @NotNull LineSegment line,
        int x, int y,
        int defaultArgb,
        long frameSeed,
        int scale
    ) {
        int startX = x;

        for (ColorSegment segment : line.getSegments())
            x += drawSegment(buffer, segment, x, y, defaultArgb, frameSeed, scale);

        return x - startX;
    }

    /**
     * Draws a single {@link ColorSegment} at the given position with shadow, strikethrough,
     * underline, and obfuscation support at native pixel scale. Returns the pixel width of
     * the rendered text.
     *
     * @param buffer the pixel buffer to draw onto
     * @param segment the styled text segment to render
     * @param x the starting X position
     * @param y the text baseline Y position
     * @param defaultArgb the fallback color when the segment has no explicit color
     * @param frameSeed the animation frame seed for obfuscation substitution
     * @return the pixel width of the rendered segment
     */
    public static int drawSegment(
        @NotNull PixelBuffer buffer,
        @NotNull ColorSegment segment,
        int x, int y,
        int defaultArgb,
        long frameSeed
    ) {
        return drawSegment(buffer, segment, x, y, defaultArgb, frameSeed, 1);
    }

    /**
     * Draws a single {@link ColorSegment} at the given position scaled by {@code scale} for
     * supersampled rendering. Glyph bitmaps are nearest-neighbor upscaled; the shadow offset
     * and decoration rectangles are scaled by the same factor so they retain their 1-mcPixel
     * thickness in the final (pre-downsample) output.
     *
     * @param buffer the pixel buffer to draw onto
     * @param segment the styled text segment to render
     * @param x the starting X position (in output pixels)
     * @param y the text baseline Y position (in output pixels)
     * @param defaultArgb the fallback color when the segment has no explicit color
     * @param frameSeed the animation frame seed for obfuscation substitution
     * @param scale the supersampling factor applied uniformly to glyphs, shadow offset and
     *              decoration geometry
     * @return the pixel width of the rendered segment in output pixels
     */
    public static int drawSegment(
        @NotNull PixelBuffer buffer,
        @NotNull ColorSegment segment,
        int x, int y,
        int defaultArgb,
        long frameSeed,
        int scale
    ) {
        String text = segment.getText();
        if (text.isEmpty()) return 0;

        if (segment.isObfuscated())
            text = ObfuscationKit.substitute(text, frameSeed);

        int s = Math.max(1, scale);
        MinecraftFont font = MinecraftFont.of(segment.fontStyle());
        int color = resolveColor(segment, defaultArgb);
        int shadow = shadowColor(segment, color);
        int textWidthNative = measureText(text, font);
        int textWidth = textWidthNative * s;

        int shadowOffset = PIXEL_SIZE * s;
        int strikeThicknessScaled = STRIKETHROUGH_THICKNESS * s;
        int underlineThicknessScaled = UNDERLINE_THICKNESS * s;
        int strikeOffsetScaled = STRIKETHROUGH_OFFSET * s;
        int underlineOffsetScaled = UNDERLINE_OFFSET * s;
        int decoLeftPad = s; // 1 mcPixel of "first character" left padding (vanilla x-1)

        // Vanilla 1.13+ rasters strikethrough as y+3.5..y+4.5 - a half-mcPixel down from the
        // 1.8.9 integer y+3..y+4 row. At native sampling this half-pixel rounds to the same
        // output row; at s >= 2 we add (s / 2) supersample pixels, which the box-downsample
        // averages into a 2-row strikethrough with 50% top/bottom coverage - the anti-aliased
        // sub-pixel position that matches modern clients. Underline stays integer because
        // vanilla has always rendered it at y+8..y+9 with no fractional component.
        int strikeSubPixelShift = s >= 2 ? s / 2 : 0;

        MinecraftGraphics g = new MinecraftGraphics(buffer, s);
        g.setFont(font);

        // Shadow pass
        g.setColor(new java.awt.Color(shadow, true));
        g.drawString(text, x + shadowOffset, y + shadowOffset);
        if (segment.isStrikethrough())
            g.fillRect(x + shadowOffset - decoLeftPad, y + shadowOffset + strikeOffsetScaled + strikeSubPixelShift, textWidth, strikeThicknessScaled);
        if (segment.isUnderlined())
            g.fillRect(x + shadowOffset, y + shadowOffset + underlineOffsetScaled, textWidth + decoLeftPad, underlineThicknessScaled);

        // Main pass
        g.setColor(new java.awt.Color(color, true));
        g.drawString(text, x, y);
        if (segment.isStrikethrough())
            g.fillRect(x - decoLeftPad, y + strikeOffsetScaled + strikeSubPixelShift, textWidth, strikeThicknessScaled);
        if (segment.isUnderlined())
            g.fillRect(x, y + underlineOffsetScaled, textWidth + decoLeftPad, underlineThicknessScaled);

        return textWidth;
    }

    /**
     * Draws plain text with shadow at the given position. Returns the pixel width.
     *
     * @param buffer the pixel buffer to draw onto
     * @param text the text string to render
     * @param x the X position
     * @param y the text baseline Y position
     * @param font the Minecraft font to use
     * @param argb the text color as packed ARGB
     * @return the pixel width of the rendered text
     */
    public static int drawText(@NotNull PixelBuffer buffer, @NotNull String text, int x, int y, @NotNull MinecraftFont font, int argb) {
        return drawText(buffer, text, x, y, font, argb, 1);
    }

    /**
     * Draws plain text with shadow at the given position and glyph scale factor. Returns the
     * pixel width.
     *
     * @param buffer the pixel buffer to draw onto
     * @param text the text string to render
     * @param x the X position
     * @param y the text baseline Y position
     * @param font the Minecraft font to use
     * @param argb the text color as packed ARGB
     * @param scale the glyph scale factor (1 = native size)
     * @return the pixel width of the rendered text
     */
    public static int drawText(@NotNull PixelBuffer buffer, @NotNull String text, int x, int y, @NotNull MinecraftFont font, int argb, int scale) {
        if (text.isEmpty()) return 0;

        int shadowArgb = darken(argb);
        MinecraftGraphics g = new MinecraftGraphics(buffer, scale);
        g.setFont(font);

        g.setColor(new java.awt.Color(shadowArgb, true));
        g.drawString(text, x + PIXEL_SIZE, y + PIXEL_SIZE);
        g.setColor(new java.awt.Color(argb, true));
        g.drawString(text, x, y);
        return measureText(text, font) * scale;
    }

    // --- measurement ---

    /**
     * Measures the pixel width of a {@link LineSegment} without drawing. Pure arithmetic over
     * cached glyph advance widths - no buffer or Graphics2D needed.
     *
     * @param line the line to measure
     * @return the total pixel width
     */
    public static int measureLine(@NotNull LineSegment line) {
        int width = 0;

        for (ColorSegment segment : line.getSegments())
            width += measureSegment(segment);

        return width;
    }

    /**
     * Measures the pixel width of a single {@link ColorSegment} without drawing.
     *
     * @param segment the segment to measure
     * @return the pixel width
     */
    public static int measureSegment(@NotNull ColorSegment segment) {
        if (segment.getText().isEmpty()) return 0;
        return measureText(segment.getText(), MinecraftFont.of(segment.fontStyle()));
    }

    /**
     * Measures the pixel width of a plain text string in the given font. Pure arithmetic over
     * cached glyph advance widths.
     *
     * @param text the text to measure
     * @param font the font to measure with
     * @return the pixel width
     */
    public static int measureText(@NotNull String text, @NotNull MinecraftFont font) {
        int width = 0;
        for (int i = 0; i < text.length(); i++)
            width += font.glyph(text.charAt(i)).advanceWidth();
        return width;
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
            ColorMath.red(argb) / 4,
            ColorMath.green(argb) / 4,
            ColorMath.blue(argb) / 4
        );
    }

}
