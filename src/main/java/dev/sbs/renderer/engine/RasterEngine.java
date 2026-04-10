package dev.sbs.renderer.engine;

import dev.sbs.renderer.draw.BlendMode;
import dev.sbs.renderer.draw.Canvas;
import dev.simplified.image.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * A 2D raster drawing engine. Extends {@link TextureEngine} so every 2D renderer can resolve
 * pack-aware textures in addition to drawing with pure pixel operations.
 * <p>
 * Most of the actual pixel work is delegated to {@link Canvas}. This class's purpose is to give
 * the 2D renderers a consistent "one engine per render call" instance to pass around and to tie
 * a pack-aware texture lookup to the raster pipeline.
 */
public class RasterEngine extends TextureEngine {

    public RasterEngine(@NotNull RendererContext context) {
        super(context);
    }

    /**
     * Allocates a fresh canvas of the given dimensions.
     *
     * @param width the canvas width in pixels
     * @param height the canvas height in pixels
     * @return a new canvas
     */
    public @NotNull Canvas createCanvas(int width, int height) {
        return Canvas.of(width, height);
    }

    /**
     * Allocates a canvas pre-filled with the given background colour.
     *
     * @param width the canvas width in pixels
     * @param height the canvas height in pixels
     * @param backgroundArgb the fill colour
     * @return a new canvas
     */
    public @NotNull Canvas createCanvas(int width, int height, int backgroundArgb) {
        Canvas canvas = Canvas.of(width, height);
        canvas.fill(backgroundArgb);
        return canvas;
    }

    /**
     * Blits a pack-resolved texture into a canvas at the given origin, upscaling to the
     * requested destination size with nearest-neighbor sampling.
     *
     * @param textureId the namespaced texture identifier
     * @param canvas the destination canvas
     * @param dx the destination x origin
     * @param dy the destination y origin
     * @param dw the destination width
     * @param dh the destination height
     */
    public void drawTexture(@NotNull String textureId, @NotNull Canvas canvas, int dx, int dy, int dw, int dh) {
        PixelBuffer source = this.resolveTexture(textureId);
        canvas.blitScaled(source, dx, dy, dw, dh);
    }

    /**
     * Blits a pack-resolved texture into a canvas after tinting every source pixel's RGB by the
     * given ARGB value.
     *
     * @param textureId the namespaced texture identifier
     * @param canvas the destination canvas
     * @param dx the destination x origin
     * @param dy the destination y origin
     * @param argbTint the ARGB tint applied before compositing
     * @param mode the blend mode for the composite step
     */
    public void drawTintedTexture(
        @NotNull String textureId,
        @NotNull Canvas canvas,
        int dx,
        int dy,
        int argbTint,
        @NotNull BlendMode mode
    ) {
        PixelBuffer source = this.resolveTexture(textureId);
        canvas.blitTinted(source, dx, dy, argbTint, mode);
    }

}
