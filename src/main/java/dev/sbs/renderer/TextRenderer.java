package dev.sbs.renderer;

import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.kit.TextKit;
import dev.sbs.renderer.options.TextOptions;
import dev.sbs.renderer.text.ChatColor;
import dev.sbs.renderer.text.ColorSegment;
import dev.sbs.renderer.text.LineSegment;
import dev.sbs.renderer.text.font.MinecraftFont;
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

    /** Vanilla tooltip background RGB - 1.8.9 through 26.1 all use {@code 0xF0100010}. */
    private static final int BACKGROUND_RGB = 0x100010;

    /** Vanilla tooltip border gradient top RGB ({@code 0x505000FF} with alpha). */
    private static final int BORDER_TOP_RGB = 0x5000FF;

    /** Vanilla tooltip border gradient bottom RGB ({@code 0x5028007F} with alpha). */
    private static final int BORDER_BOTTOM_RGB = 0x28007F;

    private static final int PIXEL_SIZE = MinecraftFont.MC_PIXEL_SCALE;

    /**
     * Distance between consecutive text baselines in output pixels. Vanilla tooltip rendering
     * (every version from 1.8.9 through 26.1) advances {@code 10} mcPixels per line -
     * 8 glyph + 1 descender + 1 row of leading.
     */
    private static final int LINE_HEIGHT = 10 * MinecraftFont.MC_PIXEL_SCALE;

    private static final int DEFAULT_COLOR_ARGB = ChatColor.Legacy.GRAY.rgb();

    @Override
    public @NotNull ImageData render(@NotNull TextOptions options) {
        if (options.getLines().isEmpty())
            return RenderEngine.output(singleFrame(1, 1, ColorMath.TRANSPARENT), 0);

        boolean isLore = options.getStyle() == TextOptions.Style.LORE;
        boolean animated = hasObfuscation(options.getLines());
        int pad = isLore ? options.getPadding() : 0;
        int loreGap = isLore && options.getLines().size() > 1 ? PIXEL_SIZE * 2 : 0;
        int canvasW = measureWidth(options) + pad * 2;

        // Canvas height measures from the top padding to the last glyph's descender plus
        // bottom padding - NOT a full LINE_HEIGHT past the last baseline. The previous
        // `n * LINE_HEIGHT + 2*pad` formula left `LINE_HEIGHT - ascent - descent` output
        // pixels of dead space below the last line, which made vanilla tooltips look
        // bottom-heavy. Symmetric padding now matches in-game rendering.
        int ascent = MinecraftFont.REGULAR.getFontMetrics().getAscent();
        int descent = MinecraftFont.REGULAR.getFontMetrics().getDescent();
        int linesHeight = (options.getLines().size() - 1) * LINE_HEIGHT + ascent + descent;
        int canvasH = linesHeight + pad * 2 + loreGap;

        if (!animated)
            return RenderEngine.output(drawSingleFrame(options, canvasW, canvasH, 0L), 0);

        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        for (int frameIndex = 0; frameIndex < options.getFrameCount(); frameIndex++)
            frames.addAll(drawSingleFrame(options, canvasW, canvasH, frameIndex));

        int delayMs = Math.max(1, Math.round(1000f / options.getFramesPerSecond()));
        return RenderEngine.output(frames, delayMs);
    }

    private static @NotNull ConcurrentList<PixelBuffer> drawSingleFrame(
        @NotNull TextOptions options,
        int canvasW,
        int canvasH,
        long frameSeed
    ) {
        boolean isLore = options.getStyle() == TextOptions.Style.LORE;
        int pad = isLore ? options.getPadding() : 0;
        int sampling = Math.max(1, options.getSampling());

        // All coordinates below operate in the supersampled buffer space; divide at the end.
        int hiW = canvasW * sampling;
        int hiH = canvasH * sampling;
        PixelBuffer buffer = PixelBuffer.create(hiW, hiH);

        if (isLore) {
            int bgAlpha = Math.clamp(options.getBackgroundAlpha(), 0, 255);
            int borderAlpha = Math.clamp(options.getBorderAlpha(), 0, 255);
            buffer.fill((bgAlpha << 24) | BACKGROUND_RGB);
            drawGradientBorder(buffer, hiW, hiH, sampling, borderAlpha);
        }

        int baseline = pad + MinecraftFont.REGULAR.getFontMetrics().getAscent();

        for (int i = 0; i < options.getLines().size(); i++) {
            TextKit.drawLine(buffer, options.getLines().get(i), pad * sampling, baseline * sampling, DEFAULT_COLOR_ARGB, frameSeed, sampling);
            baseline += LINE_HEIGHT;
            if (isLore && i == 0)
                baseline += PIXEL_SIZE * 2;
        }

        PixelBuffer output = sampling == 1 ? buffer : boxDownsample(buffer, sampling);
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(output);
        return frames;
    }

    /**
     * Draws the vanilla tooltip border - a 1-{@code mcPixel}-thick frame inset 1 {@code mcPixel}
     * from the canvas edge, filled with a vertical gradient from {@link #BORDER_TOP_RGB} at the
     * top row to {@link #BORDER_BOTTOM_RGB} at the bottom row. Horizontal edges receive the
     * endpoint colors; the interpolation interior runs along the vertical edges.
     *
     * @param buffer the supersampled buffer to draw onto
     * @param w the buffer width in output pixels
     * @param h the buffer height in output pixels
     * @param sampling the supersampling factor - border stroke thickness is 1 {@code mcPixel}
     *                 which equals {@code sampling} output pixels
     * @param alpha the border alpha channel in {@code [0, 255]}
     */
    private static void drawGradientBorder(@NotNull PixelBuffer buffer, int w, int h, int sampling, int alpha) {
        int inset = sampling;            // border is inset 1 mcPixel from edge
        int stroke = sampling;           // border stroke is 1 mcPixel thick
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
        int topArgb = (alpha << 24) | BORDER_TOP_RGB;
        for (int y = topY0; y < topY1; y++)
            for (int x = leftX0; x < rightX1; x++)
                buffer.setPixel(x, y, topArgb);

        // Bottom stroke - solid bottom color
        int botArgb = (alpha << 24) | BORDER_BOTTOM_RGB;
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

    /**
     * Box-filter downsamples {@code src} by the given integer factor, averaging each
     * {@code factor x factor} block into a single output pixel in straight (non-premultiplied)
     * ARGB space.
     *
     * @param src the supersampled source buffer; dimensions must be divisible by {@code factor}
     * @param factor the downsampling factor (must be {@code >= 1})
     * @return a new buffer of size {@code src.width / factor} by {@code src.height / factor}
     */
    private static @NotNull PixelBuffer boxDownsample(@NotNull PixelBuffer src, int factor) {
        int outW = src.width() / factor;
        int outH = src.height() / factor;
        int area = factor * factor;
        PixelBuffer dst = PixelBuffer.create(outW, outH);

        for (int oy = 0; oy < outH; oy++) {
            int syBase = oy * factor;
            for (int ox = 0; ox < outW; ox++) {
                int sxBase = ox * factor;
                int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
                for (int dy = 0; dy < factor; dy++) {
                    int sy = syBase + dy;
                    for (int dx = 0; dx < factor; dx++) {
                        int px = src.getPixel(sxBase + dx, sy);
                        sumA += ColorMath.alpha(px);
                        sumR += ColorMath.red(px);
                        sumG += ColorMath.green(px);
                        sumB += ColorMath.blue(px);
                    }
                }
                dst.setPixel(ox, oy, ColorMath.pack(sumA / area, sumR / area, sumG / area, sumB / area));
            }
        }
        return dst;
    }

    private static boolean hasObfuscation(@NotNull ConcurrentList<LineSegment> lines) {
        for (LineSegment line : lines) {
            for (ColorSegment segment : line.getSegments())
                if (segment.isObfuscated()) return true;
        }
        return false;
    }

    private static int measureWidth(@NotNull TextOptions options) {
        int max = 0;
        for (LineSegment line : options.getLines())
            max = Math.max(max, TextKit.measureLine(line));
        return Math.max(32, max);
    }

    private static @NotNull ConcurrentList<PixelBuffer> singleFrame(int w, int h, int fill) {
        PixelBuffer buffer = PixelBuffer.create(w, h);
        buffer.fill(fill);
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(buffer);
        return frames;
    }

}
