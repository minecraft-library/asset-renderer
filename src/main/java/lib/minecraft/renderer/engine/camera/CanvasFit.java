package lib.minecraft.renderer.engine.camera;

/**
 * Canvas size + NDC scale for one render.
 *
 * @param canvasW width of the destination buffer in pixels
 * @param canvasH height of the destination buffer in pixels
 * @param ndcScale the model-units-to-NDC scale the kit applies so the rasterizer projects each
 *     entity-pixel-unit to {@code PIXELS_PER_BLOCK/16} canvas pixels
 */
public record CanvasFit(int canvasW, int canvasH, float ndcScale) { }
