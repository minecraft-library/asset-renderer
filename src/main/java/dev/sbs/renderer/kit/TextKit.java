package dev.sbs.renderer.kit;

import dev.sbs.renderer.text.ChatColor;
import dev.sbs.renderer.text.ColorSegment;
import dev.sbs.renderer.text.LineSegment;
import dev.sbs.renderer.text.font.MinecraftFont;
import dev.sbs.renderer.text.font.MinecraftGraphics;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelGraphics;
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

    private static final int PIXEL_SIZE = 2;
    private static final int STRIKETHROUGH_OFFSET = -8;
    private static final int UNDERLINE_OFFSET = 2;

    // --- segment rendering ---

    /**
     * Draws all segments in a {@link LineSegment} at the given position with shadow and
     * decoration support. Returns the total pixel width drawn.
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
        int startX = x;

        for (ColorSegment segment : line.getSegments())
            x += drawSegment(buffer, segment, x, y, defaultArgb, frameSeed);

        return x - startX;
    }

    /**
     * Draws a single {@link ColorSegment} at the given position with shadow, strikethrough,
     * underline, and obfuscation support. Returns the pixel width of the rendered text.
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
        String text = segment.getText();
        if (text.isEmpty()) return 0;

        if (segment.isObfuscated())
            text = ObfuscationKit.substitute(text, frameSeed);

        MinecraftFont font = MinecraftFont.of(segment.fontStyle());
        int color = resolveColor(segment, defaultArgb);
        int shadow = shadowColor(segment, color);
        int textWidth = measureText(text, font);

        PixelGraphics g = new MinecraftGraphics(buffer);
        g.setFont(font.getActual());

        // Shadow pass
        g.setColor(new java.awt.Color(shadow, true));
        g.drawString(text, x + PIXEL_SIZE, y + PIXEL_SIZE);
        if (segment.isStrikethrough())
            g.fillRect(x + PIXEL_SIZE - 1, y + PIXEL_SIZE + STRIKETHROUGH_OFFSET, textWidth, PIXEL_SIZE);
        if (segment.isUnderlined())
            g.fillRect(x + PIXEL_SIZE, y + PIXEL_SIZE + UNDERLINE_OFFSET, textWidth + 1, PIXEL_SIZE);

        // Main pass
        g.setColor(new java.awt.Color(color, true));
        g.drawString(text, x, y);
        if (segment.isStrikethrough())
            g.fillRect(x - 1, y + STRIKETHROUGH_OFFSET, textWidth, PIXEL_SIZE);
        if (segment.isUnderlined())
            g.fillRect(x, y + UNDERLINE_OFFSET, textWidth + 1, PIXEL_SIZE);

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
        PixelGraphics g = new MinecraftGraphics(buffer, scale);
        g.setFont(font.getActual());

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
            .map(ChatColor::getRGB)
            .orElse(defaultArgb);
    }

    /**
     * Returns the shadow color for a segment as packed ARGB. Uses the
     * {@link ChatColor#getBackgroundRGB()} when available, otherwise darkens the given color
     * to 25% brightness.
     *
     * @param segment the segment
     * @param fallbackArgb the primary color to darken if no ChatColor background is available
     * @return the shadow ARGB color
     */
    public static int shadowColor(@NotNull ColorSegment segment, int fallbackArgb) {
        return segment.getColor()
            .map(ChatColor::getBackgroundRGB)
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
