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
 * When any segment across any line is marked obfuscated, the renderer produces an animated
 * output of {@link TextOptions#getFrameCount()} frames, each rendering obfuscated spans with a
 * fresh {@link dev.sbs.renderer.kit.ObfuscationKit ObfuscationKit} substitution.
 */
public final class TextRenderer implements Renderer<TextOptions> {

    private static final int BACKGROUND_RGB = 0x100010;
    private static final int BORDER_RGB = 0x2F0054;
    private static final int PIXEL_SIZE = 2;
    private static final int LINE_HEIGHT = 24;
    private static final int DEFAULT_COLOR_ARGB = ChatColor.GRAY.getRGB();

    @Override
    public @NotNull ImageData render(@NotNull TextOptions options) {
        if (options.getLines().isEmpty())
            return RenderEngine.output(singleFrame(1, 1, ColorMath.TRANSPARENT), 0);

        boolean isLore = options.getStyle() == TextOptions.Style.LORE;
        boolean animated = hasObfuscation(options.getLines());
        int pad = isLore ? options.getPadding() : 0;
        int loreGap = isLore && options.getLines().size() > 1 ? PIXEL_SIZE * 2 : 0;
        int canvasW = measureWidth(options) + pad * 2;
        int canvasH = options.getLines().size() * LINE_HEIGHT + pad * 2 + loreGap;

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

        PixelBuffer buffer = PixelBuffer.create(canvasW, canvasH);
        if (isLore) {
            int alpha = Math.clamp(options.getAlpha(), 0, 255);
            buffer.fill((alpha << 24) | BACKGROUND_RGB);
            drawBorder(buffer, canvasW, canvasH, (alpha << 24) | BORDER_RGB);
        }

        int y = pad + MinecraftFont.REGULAR.getFontMetrics().getAscent();

        for (int i = 0; i < options.getLines().size(); i++) {
            TextKit.drawLine(buffer, options.getLines().get(i), pad, y, DEFAULT_COLOR_ARGB, frameSeed);
            y += LINE_HEIGHT;
            if (isLore && i == 0)
                y += PIXEL_SIZE * 2;
        }

        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(buffer);
        return frames;
    }

    private static void drawBorder(@NotNull PixelBuffer buffer, int w, int h, int argb) {
        for (int x = 1; x < w - 1; x++) {
            buffer.setPixel(x, 1, argb);
            buffer.setPixel(x, h - 2, argb);
        }
        for (int y = 1; y < h - 1; y++) {
            buffer.setPixel(1, y, argb);
            buffer.setPixel(w - 2, y, argb);
        }
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
