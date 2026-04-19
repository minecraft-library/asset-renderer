package lib.minecraft.renderer.options;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.LayoutRenderer;
import org.jetbrains.annotations.NotNull;

/**
 * Describes how a {@link LayoutRenderer} positions its children on the output canvas.
 * <p>
 * Three axis-based variants ({@link Row}, {@link Column}, {@link Grid}), an overlap variant
 * ({@link Stack}), and an explicit-position variant ({@link Custom}) cover the common layout
 * scenarios without a full constraint system.
 */
public sealed interface Layout permits Layout.Row, Layout.Column, Layout.Grid, Layout.Stack, Layout.Custom {

    /**
     * The padding in pixels applied between children and around the canvas edge.
     *
     * @return the padding
     */
    int padding();

    /**
     * The cross-axis alignment applied to each child.
     *
     * @return the alignment
     */
    @NotNull Alignment alignment();

    /**
     * Cross-axis alignment applied to children whose minor dimension is smaller than the layout's
     * minor extent.
     */
    enum Alignment {
        /** Align children to the leading edge (top or left). */
        START,
        /** Center children along the cross axis. */
        CENTER,
        /** Align children to the trailing edge (bottom or right). */
        END
    }

    /**
     * Arranges children left to right along a horizontal axis.
     *
     * @param padding the inter-child padding and canvas edge padding in pixels
     * @param alignment the cross-axis (vertical) alignment
     */
    record Row(int padding, @NotNull Alignment alignment) implements Layout {}

    /**
     * Arranges children top to bottom along a vertical axis.
     *
     * @param padding the inter-child padding and canvas edge padding in pixels
     * @param alignment the cross-axis (horizontal) alignment
     */
    record Column(int padding, @NotNull Alignment alignment) implements Layout {}

    /**
     * Arranges children left to right and wrap-around every {@code columns} children.
     *
     * @param padding the inter-cell padding and canvas edge padding in pixels
     * @param columns the number of columns before wrapping to the next row
     * @param alignment the cross-axis alignment within each cell
     */
    record Grid(int padding, int columns, @NotNull Alignment alignment) implements Layout {}

    /**
     * Overlays all children at the canvas origin, inset by {@code padding}. The canvas is sized to
     * the largest child. Useful for badge overlays on top of a base icon.
     *
     * @param padding the inset applied to all children
     * @param alignment the cross-axis alignment applied to smaller children relative to the largest
     */
    record Stack(int padding, @NotNull Alignment alignment) implements Layout {}

    /**
     * Places children at explicit caller-supplied positions. Canvas dimensions are derived from the
     * furthest-right and furthest-bottom occupied pixel.
     *
     * @param padding the canvas edge padding in pixels
     * @param alignment the default alignment (unused for custom positioning, kept for interface conformance)
     * @param positions the child positions, one per child in the matching order
     */
    record Custom(int padding, @NotNull Alignment alignment, @NotNull ConcurrentList<Position> positions) implements Layout {

        /**
         * An absolute child position on the layout canvas.
         *
         * @param x the horizontal offset in pixels
         * @param y the vertical offset in pixels
         */
        public record Position(int x, int y) {}

    }

    // --- convenience factories ---

    /**
     * A horizontal row with 8 pixels of padding and centered cross-axis alignment.
     *
     * @return the layout
     */
    static @NotNull Layout row() {
        return new Row(8, Alignment.CENTER);
    }

    /**
     * A horizontal row with the given padding and centered cross-axis alignment.
     *
     * @param padding the padding in pixels
     * @return the layout
     */
    static @NotNull Layout row(int padding) {
        return new Row(padding, Alignment.CENTER);
    }

    /**
     * A vertical column with 8 pixels of padding and centered cross-axis alignment.
     *
     * @return the layout
     */
    static @NotNull Layout column() {
        return new Column(8, Alignment.CENTER);
    }

    /**
     * A vertical column with the given padding and centered cross-axis alignment.
     *
     * @param padding the padding in pixels
     * @return the layout
     */
    static @NotNull Layout column(int padding) {
        return new Column(padding, Alignment.CENTER);
    }

    /**
     * A grid with the given column count, 8 pixels of padding, and centered cell alignment.
     *
     * @param columns the number of columns
     * @return the layout
     */
    static @NotNull Layout grid(int columns) {
        return new Grid(8, columns, Alignment.CENTER);
    }

    /**
     * An overlapping stack with zero padding and centered alignment.
     *
     * @return the layout
     */
    static @NotNull Layout stack() {
        return new Stack(0, Alignment.CENTER);
    }

    /**
     * A custom layout with explicit child positions and zero padding.
     *
     * @param positions the child positions
     * @return the layout
     */
    static @NotNull Layout custom(@NotNull ConcurrentList<Custom.Position> positions) {
        return new Custom(0, Alignment.START, positions);
    }

    /**
     * A custom layout with a single child at the given position.
     *
     * @param x the horizontal offset in pixels
     * @param y the vertical offset in pixels
     * @return the layout
     */
    static @NotNull Layout custom(int x, int y) {
        ConcurrentList<Custom.Position> positions = Concurrent.newList();
        positions.add(new Custom.Position(x, y));
        return new Custom(0, Alignment.START, positions);
    }

}
