package lib.minecraft.refharness.api;

import java.util.Optional;

/**
 * The pixel canvas a subject renders onto, carrying the caller-measured fit when the sweep measures
 * one.
 *
 * <p>An absent {@link #fit} means the renderer measures the subject's own bounds and scales them
 * into the canvas itself. A present one pins the scale and anchor from outside, which is how several
 * subjects are made to share one canvas and one scale so their common geometry lands on identical
 * pixels.
 *
 * @param width the canvas width in pixels
 * @param height the canvas height in pixels
 * @param fit the caller-measured scale and anchor, or empty to let the renderer measure
 */
public record Canvas(int width, int height, Optional<Fit> fit) {

    /**
     * Returns a square self-measuring canvas.
     *
     * @param size the edge length in pixels
     * @return the canvas
     */
    public static Canvas square(int size) {
        return new Canvas(size, size, Optional.empty());
    }

    /**
     * Returns a canvas with a caller-measured fit.
     *
     * @param width the canvas width in pixels
     * @param height the canvas height in pixels
     * @param fit the scale and anchor to render at
     * @return the canvas
     */
    public static Canvas of(int width, int height, Fit fit) {
        return new Canvas(width, height, Optional.of(fit));
    }

    /**
     * A scale and anchor measured by the caller rather than by the renderer.
     *
     * @param scale pixels per subject-local screen-coordinate unit
     * @param anchorX the subject-local x that maps to the canvas centre
     * @param anchorY the subject-local y that maps to the canvas centre
     */
    public record Fit(float scale, float anchorX, float anchorY) {}
}
