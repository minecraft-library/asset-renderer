package dev.sbs.renderer.draw;

import dev.simplified.image.PixelBuffer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * A mutable 2D drawing surface backed by a {@link PixelBuffer} with a lazy {@link Graphics2D}
 * view for text and vector operations.
 * <p>
 * The canvas is the primary unit of composition for every renderer in this module. Its
 * underlying pixel array is shared by reference with the pixel buffer, so edits made through
 * either API are visible from the other without a flush step.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Canvas {

    private final @NotNull PixelBuffer buffer;
    private final @NotNull BufferedImage image;
    private @Nullable Graphics2D graphics;

    /**
     * Creates a new canvas of the given dimensions, initially filled with transparent black.
     *
     * @param width the canvas width in pixels
     * @param height the canvas height in pixels
     * @return a new canvas
     */
    public static @NotNull Canvas of(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelBuffer buffer = PixelBuffer.wrap(image);
        buffer.toBufferedImage();
        return new Canvas(buffer, image);
    }

    /**
     * Wraps an existing pixel buffer as a canvas, reusing its pixel array directly.
     *
     * @param buffer the pixel buffer to wrap
     * @return a new canvas sharing the same pixel array
     */
    public static @NotNull Canvas wrap(@NotNull PixelBuffer buffer) {
        return new Canvas(buffer, buffer.toBufferedImage());
    }

    /**
     * Returns a lazily initialized {@link Graphics2D} view of the underlying image. Use this for
     * text, vector, and AWT primitive operations that are impractical to express through raw
     * pixel writes.
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
     * Fills the entire canvas with the given ARGB colour. Discards any existing content.
     *
     * @param argb the fill colour
     */
    public void fill(int argb) {
        int[] pixels = this.buffer.pixels();
        Arrays.fill(pixels, argb);
    }

    /**
     * Copies the source pixel buffer onto this canvas at the given origin using normal alpha
     * compositing. Out-of-bounds pixels are silently dropped.
     *
     * @param source the source buffer
     * @param dx the destination x origin
     * @param dy the destination y origin
     */
    public void blit(@NotNull PixelBuffer source, int dx, int dy) {
        this.blit(source, dx, dy, BlendMode.NORMAL);
    }

    /**
     * Copies the source pixel buffer onto this canvas at the given origin using the specified
     * blend mode.
     *
     * @param source the source buffer
     * @param dx the destination x origin
     * @param dy the destination y origin
     * @param mode the blend mode to use for compositing
     */
    public void blit(@NotNull PixelBuffer source, int dx, int dy, @NotNull BlendMode mode) {
        int sw = source.width();
        int sh = source.height();
        int w = this.width();
        int h = this.height();

        for (int y = 0; y < sh; y++) {
            int dyy = dy + y;
            if (dyy < 0 || dyy >= h) continue;
            for (int x = 0; x < sw; x++) {
                int dxx = dx + x;
                if (dxx < 0 || dxx >= w) continue;

                int src = source.getPixel(x, y);
                if (ColorKit.alpha(src) == 0) continue;
                int dst = this.buffer.getPixel(dxx, dyy);
                this.buffer.setPixel(dxx, dyy, ColorKit.blend(src, dst, mode));
            }
        }
    }

    /**
     * Copies the source pixel buffer onto this canvas at the given origin after tinting every
     * source pixel's RGB channels by {@code argbTint}. Preserves the source's alpha.
     *
     * @param source the source buffer
     * @param dx the destination x origin
     * @param dy the destination y origin
     * @param argbTint the ARGB tint colour
     * @param mode the blend mode to use for compositing
     */
    public void blitTinted(@NotNull PixelBuffer source, int dx, int dy, int argbTint, @NotNull BlendMode mode) {
        this.blit(ColorKit.tint(source, argbTint), dx, dy, mode);
    }

    /**
     * Copies the source pixel buffer onto this canvas at the given origin, rescaling to the
     * specified width and height using nearest-neighbor sampling. Useful for upscaling 16x16 GUI
     * textures to match the renderer's output size.
     *
     * @param source the source buffer
     * @param dx the destination x origin
     * @param dy the destination y origin
     * @param dw the destination width
     * @param dh the destination height
     */
    public void blitScaled(@NotNull PixelBuffer source, int dx, int dy, int dw, int dh) {
        int sw = source.width();
        int sh = source.height();
        int w = this.width();
        int h = this.height();

        for (int y = 0; y < dh; y++) {
            int dyy = dy + y;
            if (dyy < 0 || dyy >= h) continue;
            int sy = (y * sh) / dh;
            for (int x = 0; x < dw; x++) {
                int dxx = dx + x;
                if (dxx < 0 || dxx >= w) continue;
                int sx = (x * sw) / dw;

                int src = source.getPixel(sx, sy);
                if (ColorKit.alpha(src) == 0) continue;
                int dst = this.buffer.getPixel(dxx, dyy);
                this.buffer.setPixel(dxx, dyy, ColorKit.blend(src, dst, BlendMode.NORMAL));
            }
        }
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
