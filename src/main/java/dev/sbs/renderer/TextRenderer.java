package dev.sbs.renderer;

import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.kit.TextKit;
import dev.sbs.renderer.options.TextOptions;
import dev.sbs.renderer.text.ChatColor;
import dev.sbs.renderer.text.ColorSegment;
import dev.sbs.renderer.text.LineSegment;
import dev.sbs.renderer.text.font.MinecraftFont;
import dev.sbs.renderer.text.font.MinecraftGraphics;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * Renders styled Minecraft text in one of two modes: item-style lore tooltips with a purple
 * bordered background, or plain chat text on a transparent canvas.
 * <p>
 * Tooltip colors match the vanilla palette used by every client from 1.8.9 through 26.1:
 * background {@code 0xF0100010}, border gradient {@code 0x505000FF} (top) to
 * {@code 0x5028007F} (bottom). The background and border alphas are independently
 * configurable via {@link TextOptions#getBackgroundAlpha()} and
 * {@link TextOptions#getBorderAlpha()}.
 * <p>
 * When any segment across any line is marked obfuscated, the renderer produces an animated
 * output of {@link TextOptions#getFrameCount()} frames, each rendering obfuscated spans with a
 * fresh {@link dev.sbs.renderer.kit.ObfuscationKit ObfuscationKit} substitution.
 * <p>
 * Supersampling is supported via {@link TextOptions#getSampling()}: values above 1 render the
 * entire canvas at that integer factor and box-filter the result back down, producing smooth
 * gradient edges and sub-pixel decoration positioning.
 */
public final class TextRenderer implements Renderer<TextOptions> {

    /**
     * Vanilla tooltip background RGB component - identical across 1.8.9 through 26.1. The
     * full ARGB is {@code 0xF0100010}; the alpha {@code 0xF0} (240) is applied from
     * {@link TextOptions#getBackgroundAlpha()} at composite time so callers can override it.
     */
    private static final int VANILLA_TOOLTIP_BG_RGB = 0x100010;

    /**
     * Vanilla tooltip border gradient top RGB component. Full ARGB is {@code 0x505000FF};
     * alpha {@code 0x50} (80) is applied from {@link TextOptions#getBorderAlpha()}.
     */
    private static final int VANILLA_TOOLTIP_BORDER_TOP_RGB = 0x5000FF;

    /**
     * Vanilla tooltip border gradient bottom RGB component. Full ARGB is {@code 0x5028007F};
     * alpha {@code 0x50} (80) is applied from {@link TextOptions#getBorderAlpha()}.
     */
    private static final int VANILLA_TOOLTIP_BORDER_BOTTOM_RGB = 0x28007F;

    /**
     * Distance between consecutive text baselines in mcPixels. Vanilla tooltip rendering
     * (every version from 1.8.9 through 26.1) advances {@code 10} mcPixels per line -
     * 8 glyph + 1 descender + 1 row of leading.
     */
    private static final int LINE_HEIGHT_MCPX = 10;

    /** Inter-line gap between the title and body in lore tooltips, in mcPixels. */
    private static final int LORE_GAP_MCPX = 2;

    private static final int DEFAULT_COLOR_ARGB = ChatColor.Legacy.GRAY.rgb();

    @Override
    public @NotNull ImageData render(@NotNull TextOptions options) {
        if (options.getLines().isEmpty())
            return RenderEngine.output(singleFrame(1, 1, ColorMath.TRANSPARENT), 0);

        boolean isLore = options.getStyle() == TextOptions.Style.LORE;
        boolean animated = hasObfuscation(options.getLines());
        int padMcPx = isLore ? options.getPadding() : 0;
        int loreGapMcPx = isLore && options.getLines().size() > 1 ? LORE_GAP_MCPX : 0;
        int canvasWMcPx = measureWidthMcPixels(options) + padMcPx * 2;

        // Canvas height measures from the top padding to the last glyph's descender plus
        // bottom padding - NOT a full LINE_HEIGHT past the last baseline. Symmetric padding
        // matches in-game vanilla rendering.
        int ascentMcPx = MinecraftFont.REGULAR.getFontMetrics().getAscentMcPixels();
        int descentMcPx = MinecraftFont.REGULAR.getFontMetrics().getDescentMcPixels();
        int linesHeightMcPx = (options.getLines().size() - 1) * LINE_HEIGHT_MCPX + ascentMcPx + descentMcPx;
        int canvasHMcPx = linesHeightMcPx + padMcPx * 2 + loreGapMcPx;

        if (!animated)
            return RenderEngine.output(drawSingleFrame(options, canvasWMcPx, canvasHMcPx, 0L), 0);

        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        for (int frameIndex = 0; frameIndex < options.getFrameCount(); frameIndex++)
            frames.addAll(drawSingleFrame(options, canvasWMcPx, canvasHMcPx, frameIndex));

        int delayMs = Math.max(1, Math.round(1000f / options.getFramesPerSecond()));
        return RenderEngine.output(frames, delayMs);
    }

    private static @NotNull ConcurrentList<PixelBuffer> drawSingleFrame(
        @NotNull TextOptions options,
        int canvasWMcPx,
        int canvasHMcPx,
        long frameSeed
    ) {
        boolean isLore = options.getStyle() == TextOptions.Style.LORE;
        int padMcPx = isLore ? options.getPadding() : 0;

        int w = canvasWMcPx * MinecraftFont.MC_PIXEL_SCALE;
        int h = canvasHMcPx * MinecraftFont.MC_PIXEL_SCALE;
        PixelBuffer buffer = PixelBuffer.create(w, h);

        if (isLore) {
            int bgAlpha = Math.clamp(options.getBackgroundAlpha(), 0, 255);
            int borderAlpha = Math.clamp(options.getBorderAlpha(), 0, 255);
            buffer.fill((bgAlpha << 24) | VANILLA_TOOLTIP_BG_RGB);
            drawGradientBorder(buffer, w, h, borderAlpha);
        }

        MinecraftGraphics g = new MinecraftGraphics(buffer);
        int baselineMcPx = padMcPx + MinecraftFont.REGULAR.getFontMetrics().getAscentMcPixels();

        for (int i = 0; i < options.getLines().size(); i++) {
            TextKit.drawLine(g, options.getLines().get(i), padMcPx, baselineMcPx, DEFAULT_COLOR_ARGB, frameSeed);
            baselineMcPx += LINE_HEIGHT_MCPX;
            if (isLore && i == 0)
                baselineMcPx += LORE_GAP_MCPX;
        }

        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(buffer);
        return frames;
    }

    /**
     * Draws the vanilla tooltip border - a 1-{@code mcPixel}-thick frame inset 1 {@code mcPixel}
     * from the canvas edge, filled with a vertical gradient from {@link #VANILLA_TOOLTIP_BORDER_TOP_RGB} at the
     * top row to {@link #VANILLA_TOOLTIP_BORDER_BOTTOM_RGB} at the bottom row. Horizontal edges receive the
     * endpoint colors; the interpolation interior runs along the vertical edges.
     *
     * @param buffer the output buffer to draw onto
     * @param w the buffer width in output pixels
     * @param h the buffer height in output pixels
     * @param alpha the border alpha channel in {@code [0, 255]}
     */
    private static void drawGradientBorder(@NotNull PixelBuffer buffer, int w, int h, int alpha) {
        int inset = MinecraftFont.MC_PIXEL_SCALE;   // border is inset 1 mcPixel from edge
        int stroke = MinecraftFont.MC_PIXEL_SCALE;  // border stroke is 1 mcPixel thick
        int topY0 = inset;
        int topY1 = inset + stroke;
        int botY0 = h - inset - stroke;
        int botY1 = h - inset;
        int leftX0 = inset;
        int leftX1 = inset + stroke;
        int rightX0 = w - inset - stroke;
        int rightX1 = w - inset;

        int innerTop = topY1;
        int innerBottom = botY0;
        int innerSpan = Math.max(1, innerBottom - 1 - innerTop); // rows in gradient interior

        // Top stroke - solid top color
        int topArgb = (alpha << 24) | VANILLA_TOOLTIP_BORDER_TOP_RGB;
        for (int y = topY0; y < topY1; y++)
            for (int x = leftX0; x < rightX1; x++)
                buffer.setPixel(x, y, topArgb);

        // Bottom stroke - solid bottom color
        int botArgb = (alpha << 24) | VANILLA_TOOLTIP_BORDER_BOTTOM_RGB;
        for (int y = botY0; y < botY1; y++)
            for (int x = leftX0; x < rightX1; x++)
                buffer.setPixel(x, y, botArgb);

        // Left and right vertical edges - interpolate between top and bottom colors
        for (int y = innerTop; y < innerBottom; y++) {
            int argb = lerpArgb(topArgb, botArgb, y - innerTop, innerSpan);
            for (int x = leftX0; x < leftX1; x++)
                buffer.setPixel(x, y, argb);
            for (int x = rightX0; x < rightX1; x++)
                buffer.setPixel(x, y, argb);
        }
    }

    /**
     * Linearly interpolates between two packed ARGB colors in straight (non-premultiplied)
     * space. Returns {@code from} when {@code t == 0} and {@code to} when {@code t == steps}.
     */
    private static int lerpArgb(int from, int to, int t, int steps) {
        int a = lerp(ColorMath.alpha(from), ColorMath.alpha(to), t, steps);
        int r = lerp(ColorMath.red(from), ColorMath.red(to), t, steps);
        int g = lerp(ColorMath.green(from), ColorMath.green(to), t, steps);
        int b = lerp(ColorMath.blue(from), ColorMath.blue(to), t, steps);
        return ColorMath.pack(a, r, g, b);
    }

    private static int lerp(int from, int to, int t, int steps) {
        return from + ((to - from) * t) / steps;
    }

    private static boolean hasObfuscation(@NotNull ConcurrentList<LineSegment> lines) {
        for (LineSegment line : lines) {
            for (ColorSegment segment : line.getSegments())
                if (segment.isObfuscated()) return true;
        }
        return false;
    }

    private static int measureWidthMcPixels(@NotNull TextOptions options) {
        int max = 0;
        for (LineSegment line : options.getLines())
            max = Math.max(max, TextKit.measureLineMcPixels(line));
        return Math.max(16, max);
    }

    private static @NotNull ConcurrentList<PixelBuffer> singleFrame(int w, int h, int fill) {
        PixelBuffer buffer = PixelBuffer.create(w, h);
        buffer.fill(fill);
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(buffer);
        return frames;
    }

}
