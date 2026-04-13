package dev.sbs.renderer.draw;

import dev.simplified.image.PixelBuffer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * A thin wrapper that pairs a {@link PixelBuffer} with a lazy {@link Graphics2D} context for
 * text rendering. All pixel-level operations (blit, fill, scale) live on {@link PixelBuffer}
 * directly; this class exists solely because {@link Graphics2D} requires a
 * {@link BufferedImage} host.
 * <p>
 * The underlying pixel array is shared by reference between the pixel buffer and the buffered
 * image, so edits made through either API are visible from the other without a flush step.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Canvas {

    private final @NotNull PixelBuffer buffer;
    private final @NotNull BufferedImage image;
    private @Nullable Graphics2D graphics;

    /**
     * Creates a new canvas of the given dimensions, initially filled with transparent black.
     * Allocates a {@link BufferedImage} so that {@link #graphics()} can provide a
     * {@link Graphics2D} context sharing the same pixel array.
     *
     * @param width the canvas width in pixels
     * @param height the canvas height in pixels
     * @return a new canvas
     */
    public static @NotNull Canvas of(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelBuffer buffer = PixelBuffer.wrap(image);
        return new Canvas(buffer, image);
    }

    /**
     * Wraps an existing pixel buffer as a canvas, creating a zero-copy {@link BufferedImage}
     * that shares the same backing array.
     *
     * @param buffer the pixel buffer to wrap
     * @return a new canvas sharing the same pixel array
     */
    public static @NotNull Canvas wrap(@NotNull PixelBuffer buffer) {
        return new Canvas(buffer, buffer.toBufferedImage());
    }

    /**
     * Returns a lazily initialized {@link Graphics2D} view of the underlying image. Use this for
     * text and font metric operations that require AWT.
     *
     * @return the graphics context
     */
    public @NotNull Graphics2D graphics() {
        if (this.graphics == null) {
            this.graphics = this.image.createGraphics();
            this.graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            this.graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        }

        return this.graphics;
    }

    /**
     * The canvas width in pixels.
     *
     * @return the width
     */
    public int width() {
        return this.buffer.width();
    }

    /**
     * The canvas height in pixels.
     *
     * @return the height
     */
    public int height() {
        return this.buffer.height();
    }

    /**
     * Releases the graphics context if one was created. Safe to call multiple times. After
     * disposal, a subsequent call to {@link #graphics()} creates a fresh context.
     */
    public void disposeGraphics() {
        if (this.graphics != null) {
            this.graphics.dispose();
            this.graphics = null;
        }
    }

}
