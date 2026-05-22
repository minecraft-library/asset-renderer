package lib.minecraft.renderer.engine;

import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.TextRenderer;
import org.jetbrains.annotations.NotNull;

/**
 * A 2D raster drawing engine for renderers that compose pixels into a flat output buffer
 * without going through the {@link ModelEngine} triangle rasterizer.
 *
 * <p>Extends {@link TextureEngine} so every 2D renderer inherits pack-aware texture
 * resolution alongside its allocation and blit helpers. Used by:
 * <ul>
 *   <li>{@link MenuRenderer MenuRenderer} - inventory chrome and slot
 *       backgrounds.</li>
 *   <li>{@link TextRenderer TextRenderer} - tooltip backgrounds and
 *       borders.</li>
 *   <li>2D sub-renderers like {@code FluidRenderer.FluidFace2D} and
 *       {@code PortalRenderer.PortalFace2D}.</li>
 * </ul>
 *
 * <p>Two helper families:
 * <ul>
 *   <li><b>{@code createBuffer}</b> - blank and pre-filled buffer allocation.</li>
 *   <li><b>{@code drawTexture} / {@code drawTintedTexture}</b> - pack-resolved texture blits
 *       at a destination rectangle, optionally tinted through a {@link BlendMode}.</li>
 * </ul>
 *
 * @see TextureEngine
 * @see ModelEngine
 */
public class RasterEngine extends TextureEngine {

    /**
     * Constructs a raster engine bound to the given context.
     *
     * @param context the renderer context
     */
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
