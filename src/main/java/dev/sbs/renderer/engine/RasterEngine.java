package dev.sbs.renderer.engine;

import dev.simplified.image.BlendMode;
import dev.simplified.image.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * A 2D raster drawing engine. Extends {@link TextureEngine} so every 2D renderer can resolve
 * pack-aware textures in addition to drawing with pure pixel operations.
 */
public class RasterEngine extends TextureEngine {

    public RasterEngine(@NotNull RendererContext context) {
        super(context);
    }

    /**
     * Allocates a fresh pixel buffer of the given dimensions.
     *
     * @param width the buffer width in pixels
     * @param height the buffer height in pixels
     * @return a new pixel buffer
     */
    public @NotNull PixelBuffer createBuffer(int width, int height) {
        return PixelBuffer.create(width, height);
    }

    /**
     * Allocates a pixel buffer pre-filled with the given background colour.
     *
     * @param width the buffer width in pixels
     * @param height the buffer height in pixels
     * @param backgroundArgb the fill colour
     * @return a new pixel buffer
     */
    public @NotNull PixelBuffer createBuffer(int width, int height, int backgroundArgb) {
        PixelBuffer buffer = PixelBuffer.create(width, height);
        buffer.fill(backgroundArgb);
        return buffer;
    }

    /**
     * Blits a pack-resolved texture into a buffer at the given origin, upscaling to the
     * requested destination size with nearest-neighbor sampling.
     *
     * @param textureId the namespaced texture identifier
     * @param target the destination buffer
     * @param dx the destination x origin
     * @param dy the destination y origin
     * @param dw the destination width
     * @param dh the destination height
     */
    public void drawTexture(@NotNull String textureId, @NotNull PixelBuffer target, int dx, int dy, int dw, int dh) {
        target.blitScaled(this.resolveTexture(textureId), dx, dy, dw, dh);
    }

    /**
     * Blits a pack-resolved texture into a buffer after tinting every source pixel's RGB by the
     * given ARGB value.
     *
     * @param textureId the namespaced texture identifier
     * @param target the destination buffer
     * @param dx the destination x origin
     * @param dy the destination y origin
     * @param argbTint the ARGB tint applied before compositing
     * @param mode the blend mode for the composite step
     */
    public void drawTintedTexture(
        @NotNull String textureId,
        @NotNull PixelBuffer target,
        int dx,
        int dy,
        int argbTint,
        @NotNull BlendMode mode
    ) {
        target.blitTinted(this.resolveTexture(textureId), dx, dy, argbTint, mode);
    }

}
