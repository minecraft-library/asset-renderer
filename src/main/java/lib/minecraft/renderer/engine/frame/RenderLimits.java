package lib.minecraft.renderer.engine.frame;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.exception.RenderException;
import org.jetbrains.annotations.NotNull;

/**
 * The ceilings a render request is drawn under - how much one request may ask a renderer to hold.
 *
 * <p>A request is a few numbers that name a far larger thing: a canvas size names its square, a
 * supersample factor the square it is rasterized at, a frame count that many canvases, a row and a
 * column count the sheet they lay out. Each ceiling here bounds one of those, and each is tested in
 * {@code long} arithmetic before the thing it bounds is allocated, so a request that names more is
 * refused with a {@link RenderException} for the cost of reading its options.
 *
 * <p>The ceilings are constants. Each is set from what this library's own drivers, examples and sweeps
 * ask for, with room above it, rather than for the heap any one caller runs in; the figure it was
 * measured against is given with the constant. A caller that forwards sizes from its own users holds
 * them to its own budget before it builds the options.
 */
@UtilityClass
public class RenderLimits {

    /**
     * The longest edge of one render, in pixels: an output canvas, the raster a supersampled canvas is
     * drawn at, and an atlas tile. The largest raster the examples draw is a 512 pixel canvas
     * supersampled four times, 2,048 pixels, and the entity sweeps cap a canvas at 1,024.
     */
    public static final int MAX_EDGE = 1 << 14;

    /**
     * The pixels one render may draw: its raster's width by its height, by its frame count. It is
     * {@link #MAX_EDGE} squared, one gibibyte of packed pixels, so a still fills it at the longest edge
     * and an animation trades size for frames - 4,096 frames at the default 256 pixel canvas. The
     * heaviest example draws 40 frames of a 256 pixel canvas supersampled twice, a twenty-fifth of it.
     */
    public static final long MAX_PIXELS = (long) MAX_EDGE * MAX_EDGE;

    /**
     * The frames one render may draw, whatever their size. The longest strip the examples name is 40
     * frames and the longest the suite does is 64; a derived loop never passes
     * {@link Timeline#MAX_LOOP_TICKS}.
     */
    public static final int MAX_FRAMES = 1 << 12;

    /**
     * Answers an edge a request names, refusing one over {@link #MAX_EDGE}.
     *
     * @param what the edge as a refusal names it, an option's name among its words
     * @param edge the edge in pixels
     * @return the edge
     * @throws RenderException if the edge is over the ceiling
     */
    public static int edge(@NotNull String what, long edge) {
        if (edge > MAX_EDGE)
            throw new RenderException("%s '%d' is over the limit of '%d' pixels", what, edge, MAX_EDGE);

        return (int) edge;
    }

    /**
     * Answers a frame count a request names, refusing one over {@link #MAX_FRAMES}.
     *
     * @param what the count as a refusal names it, an option's name among its words
     * @param frames the frame count
     * @return the frame count
     * @throws RenderException if the count is over the ceiling
     */
    public static int frames(@NotNull String what, long frames) {
        if (frames > MAX_FRAMES)
            throw new RenderException("%s '%d' is over the limit of '%d' frames", what, frames, MAX_FRAMES);

        return (int) frames;
    }

    /**
     * Refuses a render whose frames together hold more than {@link #MAX_PIXELS}, or whose raster has
     * an edge over {@link #MAX_EDGE} or below zero, which is what a size wraps to when it is computed
     * past what an {@code int} holds.
     *
     * @param what the render as a refusal names it
     * @param width the raster width in pixels
     * @param height the raster height in pixels
     * @param frames the frame count, read as one where it is less
     * @throws RenderException if the render is over a ceiling, or an edge is negative
     */
    public static void render(@NotNull String what, long width, long height, long frames) {
        if (width > MAX_EDGE || height > MAX_EDGE)
            throw new RenderException("%s '%dx%d' is over the limit of '%d' pixels an edge", what, width, height, MAX_EDGE);

        pixels(what, width, height, frames);
    }

    /**
     * Refuses a canvas whose frames together hold more than {@link #MAX_PIXELS}, or that has a negative
     * edge, and holds neither edge to {@link #MAX_EDGE} - the ceiling for a canvas that is long one way
     * by nature, as a column of text is.
     *
     * @param what the canvas as a refusal names it
     * @param width the canvas width in pixels
     * @param height the canvas height in pixels
     * @param frames the frame count, read as one where it is less
     * @throws RenderException if the canvas is over the ceiling, or an edge is negative
     */
    public static void pixels(@NotNull String what, long width, long height, long frames) {
        if (width < 0 || height < 0)
            throw new RenderException("%s '%dx%d' has a negative edge", what, width, height);

        // An edge alone over the ceiling settles it, which keeps each product below within a long.
        if (width > MAX_PIXELS || height > MAX_PIXELS || width * height > MAX_PIXELS
            || width * height * Math.max(1, Math.min(frames, Integer.MAX_VALUE)) > MAX_PIXELS)
            throw new RenderException(
                "%s '%dx%d' over '%d' frames is over the limit of '%d' pixels a render draws",
                what, width, height, frames, MAX_PIXELS);
    }

    /**
     * Refuses a sheet that does not fit one buffer: a width, a height or a pixel count that is negative
     * or over what an {@code int} holds. A sheet is as large as the tiles laid out in it, so its
     * ceiling is the buffer's own and not a render's.
     *
     * @param what the sheet as a refusal names it
     * @param width the sheet width in pixels
     * @param height the sheet height in pixels
     * @throws RenderException if the sheet does not fit one buffer
     */
    public static void sheet(@NotNull String what, long width, long height) {
        if (width < 0 || height < 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE
            || width * height > Integer.MAX_VALUE)
            throw new RenderException(
                "%s '%dx%d' is not a sheet one buffer holds, which is at most '%d' pixels",
                what, width, height, Integer.MAX_VALUE);
    }

}
