package dev.sbs.renderer.engine;

import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.ObfuscationKit;
import dev.sbs.renderer.text.ChatColor;
import dev.sbs.renderer.text.MinecraftFont;
import dev.sbs.renderer.text.segment.ColorSegment;
import dev.sbs.renderer.text.segment.LineSegment;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * Static text rendering utilities shared by every renderer that draws styled Minecraft text.
 * <p>
 * Handles font style resolution, color mapping, text shadow, strikethrough, underline, and
 * obfuscation substitution in one place so callers only need to supply a {@link Canvas},
 * a segment or line, and a position. All text is rendered via AWT {@link Graphics2D} using
 * the pre-loaded {@link MinecraftFont} variants.
 * <p>
 * Shadow, strikethrough, and underline offsets are extracted from the minecraft-api module's
 * {@code MinecraftText} class and match vanilla's pixel-level rendering.
 *
 * @see MinecraftFont
 * @see ColorSegment
 * @see LineSegment
 */
@UtilityClass
public class TextEngine {

    private static final int PIXEL_SIZE = 2;
    private static final int STRIKETHROUGH_OFFSET = -8;
    private static final int UNDERLINE_OFFSET = 2;

    // --- segment rendering ---

    /**
     * Draws all segments in a {@link LineSegment} at the given position with shadow and
     * decoration support. Returns the total pixel width drawn.
     *
     * @param canvas the canvas to draw onto
     * @param line the line of styled segments to render
     * @param x the starting X position
     * @param y the text baseline Y position
     * @param defaultColor the fallback color for segments without an explicit color
     * @param frameSeed the animation frame seed for obfuscation substitution
     * @return the total pixel width of the rendered line
     */
    public static int drawLine(
        @NotNull Canvas canvas,
        @NotNull LineSegment line,
        int x, int y,
        @NotNull Color defaultColor,
        long frameSeed
    ) {
        int startX = x;

        for (ColorSegment segment : line.getSegments())
            x += drawSegment(canvas, segment, x, y, defaultColor, frameSeed);

        return x - startX;
    }

    /**
     * Draws a single {@link ColorSegment} at the given position with shadow, strikethrough,
     * underline, and obfuscation support. Returns the pixel width of the rendered text.
     *
     * @param canvas the canvas to draw onto
     * @param segment the styled text segment to render
     * @param x the starting X position
     * @param y the text baseline Y position
     * @param defaultColor the fallback color when the segment has no explicit color
     * @param frameSeed the animation frame seed for obfuscation substitution
     * @return the pixel width of the rendered segment
     */
    public static int drawSegment(
        @NotNull Canvas canvas,
        @NotNull ColorSegment segment,
        int x, int y,
        @NotNull Color defaultColor,
        long frameSeed
    ) {
        String text = segment.getText();
        if (text.isEmpty()) return 0;

        if (segment.isObfuscated())
            text = ObfuscationKit.substitute(text, frameSeed);

        MinecraftFont font = MinecraftFont.of(segment.fontStyle());
        Color color = resolveColor(segment, defaultColor);
        Color shadow = shadowColor(segment, color);

        Graphics2D g = canvas.graphics();
        g.setFont(font.getActual());
        int textWidth = g.getFontMetrics().stringWidth(text);

        // Shadow pass
        g.setColor(shadow);
        g.drawString(text, x + PIXEL_SIZE, y + PIXEL_SIZE);
        if (segment.isStrikethrough())
            g.fillRect(x + PIXEL_SIZE - 1, y + PIXEL_SIZE + STRIKETHROUGH_OFFSET, textWidth, PIXEL_SIZE);
        if (segment.isUnderlined())
            g.fillRect(x + PIXEL_SIZE, y + PIXEL_SIZE + UNDERLINE_OFFSET, textWidth + 1, PIXEL_SIZE);

        // Main pass
        g.setColor(color);
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
     * @param canvas the canvas to draw onto
     * @param text the text string to render
     * @param x the X position
     * @param y the text baseline Y position
     * @param font the AWT font to use
     * @param color the text color
     * @return the pixel width of the rendered text
     */
    public static int drawText(@NotNull Canvas canvas, @NotNull String text, int x, int y, @NotNull Font font, @NotNull Color color) {
        if (text.isEmpty()) return 0;
        Graphics2D g = canvas.graphics();
        g.setFont(font);
        int textWidth = g.getFontMetrics().stringWidth(text);

        g.setColor(darken(color));
        g.drawString(text, x + PIXEL_SIZE, y + PIXEL_SIZE);
        g.setColor(color);
        g.drawString(text, x, y);
        return textWidth;
    }

    // --- measurement ---

    /**
     * Measures the pixel width of a {@link LineSegment} without drawing.
     *
     * @param canvas the canvas (used for font metrics)
     * @param line the line to measure
     * @return the total pixel width
     */
    public static int measureLine(@NotNull Canvas canvas, @NotNull LineSegment line) {
        int width = 0;

        for (ColorSegment segment : line.getSegments())
            width += measureSegment(canvas, segment);

        return width;
    }

    /**
     * Measures the pixel width of a single {@link ColorSegment} without drawing.
     *
     * @param canvas the canvas (used for font metrics)
     * @param segment the segment to measure
     * @return the pixel width
     */
    public static int measureSegment(@NotNull Canvas canvas, @NotNull ColorSegment segment) {
        if (segment.getText().isEmpty()) return 0;
        Graphics2D g = canvas.graphics();
        g.setFont(MinecraftFont.of(segment.fontStyle()).getActual());
        return g.getFontMetrics().stringWidth(segment.getText());
    }

    // --- color utilities ---

    /**
     * Resolves a {@link ColorSegment}'s color to an AWT {@link Color}, falling back to
     * the given default when the segment has no explicit color or a non-color format code.
     *
     * @param segment the segment to resolve
     * @param defaultColor the fallback color
     * @return the resolved color
     */
    public static @NotNull Color resolveColor(@NotNull ColorSegment segment, @NotNull Color defaultColor) {
        return segment.getColor()
            .map(ChatColor::getColor)
            .orElse(defaultColor);
    }

    /**
     * Returns the shadow color for a segment. Uses the {@link ChatColor#getBackgroundColor()}
     * when available, otherwise darkens the given color to 25% brightness.
     *
     * @param segment the segment (may have a ChatColor with a pre-defined background color)
     * @param fallback the primary color to darken if no ChatColor background is available
     * @return the shadow color
     */
    public static @NotNull Color shadowColor(@NotNull ColorSegment segment, @NotNull Color fallback) {
        return segment.getColor()
            .map(ChatColor::getBackgroundColor)
            .orElseGet(() -> darken(fallback));
    }

    /**
     * Darkens a color to approximately 25% brightness, matching vanilla's shadow rendering.
     */
    private static @NotNull Color darken(@NotNull Color color) {
        return new Color(color.getRed() / 4, color.getGreen() / 4, color.getBlue() / 4, color.getAlpha());
    }

}
