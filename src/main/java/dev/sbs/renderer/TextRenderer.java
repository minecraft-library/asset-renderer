package dev.sbs.renderer;

import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.ColorKit;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.TextEngine;
import dev.sbs.renderer.options.TextOptions;
import dev.sbs.renderer.text.ChatColor;
import dev.sbs.renderer.text.MinecraftFont;
import dev.sbs.renderer.text.segment.ColorSegment;
import dev.sbs.renderer.text.segment.LineSegment;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * Renders styled Minecraft text in one of two modes: item-style lore tooltips with a purple
 * bordered background, or plain chat text on a transparent canvas.
 * <p>
 * When any segment across any line is marked obfuscated, the renderer produces an animated
 * output of {@link TextOptions#getFrameCount()} frames, each rendering obfuscated spans with a
 * fresh {@link dev.sbs.renderer.draw.ObfuscationKit ObfuscationKit} substitution.
 */
public final class TextRenderer implements Renderer<TextOptions> {

    private static final int BACKGROUND_RGB = 0x100010;
    private static final int BORDER_RGB = 0x2F0054;
    private static final int PIXEL_SIZE = 2;
    private static final int LINE_HEIGHT = 24;
    private static final @NotNull Color DEFAULT_COLOR = ChatColor.GRAY.getColor();

    @Override
    public @NotNull ImageData render(@NotNull TextOptions options) {
        if (options.getLines().isEmpty())
            return RenderEngine.output(singleFrame(1, 1, ColorKit.TRANSPARENT), 0);

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

        Canvas canvas = Canvas.of(canvasW, canvasH);
        if (isLore) {
            int alpha = Math.clamp(options.getAlpha(), 0, 255);
            canvas.fill((alpha << 24) | BACKGROUND_RGB);
            drawBorder(canvas, canvasW, canvasH, (alpha << 24) | BORDER_RGB);
        }

        Graphics2D g = canvas.graphics();
        g.setFont(MinecraftFont.REGULAR.getActual());
        int y = pad + g.getFontMetrics().getAscent();

        for (int i = 0; i < options.getLines().size(); i++) {
            TextEngine.drawLine(canvas, options.getLines().get(i), pad, y, DEFAULT_COLOR, frameSeed);
            y += LINE_HEIGHT;
            if (isLore && i == 0)
                y += PIXEL_SIZE * 2;
        }

        canvas.disposeGraphics();
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(canvas.getBuffer());
        return frames;
    }

    private static void drawBorder(@NotNull Canvas canvas, int w, int h, int argb) {
        for (int x = 1; x < w - 1; x++) {
            canvas.getBuffer().setPixel(x, 1, argb);
            canvas.getBuffer().setPixel(x, h - 2, argb);
        }
        for (int y = 1; y < h - 1; y++) {
            canvas.getBuffer().setPixel(1, y, argb);
            canvas.getBuffer().setPixel(w - 2, y, argb);
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
        Canvas scratch = Canvas.of(1, 1);
        int max = 0;
        for (LineSegment line : options.getLines())
            max = Math.max(max, TextEngine.measureLine(scratch, line));
        scratch.disposeGraphics();
        return Math.max(32, max);
    }

    private static @NotNull ConcurrentList<PixelBuffer> singleFrame(int w, int h, int fill) {
        Canvas canvas = Canvas.of(w, h);
        canvas.fill(fill);
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(canvas.getBuffer());
        return frames;
    }

}
