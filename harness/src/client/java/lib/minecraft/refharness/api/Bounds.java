package lib.minecraft.refharness.api;

/**
 * Screen-space extents of a subject at its render pose, in subject-local screen coordinates -
 * post-rotation, pre-canvas-scale.
 *
 * <p>A sweep that sizes a shared canvas across several subjects unions their bounds and derives the
 * canvas dimensions and anchor from the result, so shared geometry lands on identical canvas pixels
 * whichever subject is rendering.
 *
 * @param minX the leftmost extent
 * @param maxX the rightmost extent
 * @param minY the bottommost extent
 * @param maxY the topmost extent
 */
public record Bounds(float minX, float maxX, float minY, float maxY) {

    /** The horizontal span. */
    public float width() {
        return maxX - minX;
    }

    /** The vertical span. */
    public float height() {
        return maxY - minY;
    }

    /**
     * Returns the smallest bounds that fully contains both this and {@code other}.
     *
     * <p>Monotone: a union can only grow the box or leave it unchanged, never shrink it. That is
     * what lets a canvas sized from a union be guaranteed to fit every member of it.
     *
     * @param other the bounds to absorb
     * @return the containing bounds
     */
    public Bounds union(Bounds other) {
        return new Bounds(
            Math.min(minX, other.minX),
            Math.max(maxX, other.maxX),
            Math.min(minY, other.minY),
            Math.max(maxY, other.maxY));
    }

    /** The horizontal midpoint. */
    public float centerX() {
        return (minX + maxX) * 0.5f;
    }

    /** The vertical midpoint. */
    public float centerY() {
        return (minY + maxY) * 0.5f;
    }
}
