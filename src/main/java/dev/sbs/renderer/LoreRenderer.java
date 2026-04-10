package dev.sbs.renderer;

import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.ColorKit;
import dev.sbs.renderer.draw.ObfuscationKit;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.options.LoreOptions;
import dev.sbs.renderer.text.ChatFormat;
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
 * Renders item-style lore text in the classic Minecraft tooltip aesthetic - purple bordered
 * box, line-by-line styled text, optional obfuscation animation.
 * <p>
 * When any segment across any line is marked obfuscated, the renderer produces an animated
 * output of {@link LoreOptions#getFrameCount()} frames, each rendering obfuscated spans with a
 * fresh {@link ObfuscationKit} substitution. Non-obfuscated segments are drawn once per frame.
 */
public final class LoreRenderer implements Renderer<LoreOptions> {

    private static final int BACKGROUND_ARGB = 0xFF100010;
    private static final int BORDER_ARGB = 0xFF2F0054;
    private static final int LINE_HEIGHT = 24;

    @Override
    public @NotNull ImageData render(@NotNull LoreOptions options) {
        if (options.getLines().isEmpty())
            return RenderEngine.output(singleFrame(1, 1, ColorKit.TRANSPARENT), 0);

        boolean animated = hasObfuscation(options.getLines());
        int canvasW = measureWidth(options) + options.getPadding() * 2;
        int canvasH = options.getLines().size() * LINE_HEIGHT + options.getPadding() * 2;

        if (!animated)
            return RenderEngine.output(drawSingleFrame(options, canvasW, canvasH, 0L), 0);

        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        for (int frameIndex = 0; frameIndex < options.getFrameCount(); frameIndex++)
            frames.addAll(drawSingleFrame(options, canvasW, canvasH, frameIndex));

        int delayMs = Math.max(1, Math.round(1000f / options.getFramesPerSecond()));
        return RenderEngine.output(frames, delayMs);
    }

    private static @NotNull ConcurrentList<PixelBuffer> drawSingleFrame(
        @NotNull LoreOptions options,
        int canvasW,
        int canvasH,
        long frameSeed
    ) {
        Canvas canvas = Canvas.of(canvasW, canvasH);
        canvas.fill(BACKGROUND_ARGB);
        drawBorder(canvas, canvasW, canvasH);

        Graphics2D g = canvas.graphics();
        g.setFont(MinecraftFont.REGULAR.getActual());

        int y = options.getPadding() + g.getFontMetrics().getAscent();
        for (LineSegment line : options.getLines()) {
            int x = options.getPadding();
            for (ColorSegment segment : line.getSegments()) {
                MinecraftFont font = MinecraftFont.of(segment.fontStyle());
                g.setFont(font.getActual());

                Color color = segment.getColor().filter(ChatFormat::isColor).map(ChatFormat::getColor).orElse(Color.WHITE);
                g.setColor(color);

                String text = segment.isObfuscated()
                    ? ObfuscationKit.substitute(segment.getText(), frameSeed)
                    : segment.getText();
                g.drawString(text, x, y);
                x += g.getFontMetrics().stringWidth(text);
            }
            y += LINE_HEIGHT;
        }

        canvas.disposeGraphics();
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(canvas.getBuffer());
        return frames;
    }

    private static void drawBorder(@NotNull Canvas canvas, int w, int h) {
        // Inset purple border one pixel inside the edge.
        for (int x = 1; x < w - 1; x++) {
            canvas.getBuffer().setPixel(x, 1, BORDER_ARGB);
            canvas.getBuffer().setPixel(x, h - 2, BORDER_ARGB);
        }
        for (int y = 1; y < h - 1; y++) {
            canvas.getBuffer().setPixel(1, y, BORDER_ARGB);
            canvas.getBuffer().setPixel(w - 2, y, BORDER_ARGB);
        }
    }

    private static boolean hasObfuscation(@NotNull ConcurrentList<LineSegment> lines) {
        for (LineSegment line : lines) {
            for (ColorSegment segment : line.getSegments()) {
                if (segment.isObfuscated()) return true;
            }
        }
        return false;
    }

    private static int measureWidth(@NotNull LoreOptions options) {
        int max = 0;
        for (LineSegment line : options.getLines()) {
            int pxWidth = 0;
            for (ColorSegment segment : line.getSegments())
                pxWidth += segment.getText().length() * 12;  // rough estimate; refined by drawSingleFrame
            if (pxWidth > max) max = pxWidth;
        }
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
