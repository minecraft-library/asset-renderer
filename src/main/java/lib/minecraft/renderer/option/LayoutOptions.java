package lib.minecraft.renderer.option;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.LayoutRenderer;
import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.option.slot.LayoutSlot;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@link LayoutRenderer} invocation.
 *
 * <p>Uses a hand-written (non-Lombok) builder so the {@link Builder#child(Renderer, Object)
 * child(Renderer, Options)} overload can erase the child's options type parameter cleanly -
 * each child is captured as a {@link Supplier} of {@link ImageData} whose render is deferred until
 * the layout renderer walks the tree.
 *
 * @see lib.minecraft.renderer.LayoutRenderer
 */
@Getter
public class LayoutOptions implements RenderOptions {

    /**
     * Layout strategy positioning the children on the output canvas.
     */
    private final @NotNull Layout layout;

    /**
     * Deferred child renders, one supplier per appended child, in append order.
     */
    private final @NotNull ConcurrentList<Supplier<ImageData>> children;

    /**
     * Target output frame rate used when any child is animated.
     */
    private final int framesPerSecond;

    /**
     * Canvas background fill applied before blitting any child.
     */
    private final @NotNull Background background;

    /**
     * Transform applied to the default child {@link FrameLayer} stack before it runs.
     */
    private final @NotNull UnaryOperator<LayerStack<FrameLayer>> layerDecorator;

    private LayoutOptions(
        @NotNull Layout layout,
        @NotNull ConcurrentList<Supplier<ImageData>> children,
        int framesPerSecond,
        @NotNull Background background,
        @NotNull UnaryOperator<LayerStack<FrameLayer>> layerDecorator
    ) {
        this.layout = layout;
        this.children = children;
        this.framesPerSecond = framesPerSecond;
        this.background = background;
        this.layerDecorator = layerDecorator;
    }

    /**
     * A new builder seeded with the defaults.
     *
     * @return the builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * The default layout options - an empty {@linkplain Layout#row() row} (8px padding, centered)
     * at 30 fps over a {@linkplain Background#TRANSPARENT transparent} background.
     *
     * @return the default options
     */
    public static @NotNull LayoutOptions defaults() {
        return builder().build();
    }

    /**
     * Mutable builder. Supports both pre-rendered children and deferred renderer+options pairs
     * whose render is postponed until the containing renderer walks the layout.
     */
    public static class Builder {

        private @NotNull Layout layout = Layout.row();
        private final @NotNull ConcurrentList<Supplier<ImageData>> children = Concurrent.newList();
        private int framesPerSecond = 30;
        private @NotNull Background background = Background.TRANSPARENT;
        private @NotNull UnaryOperator<LayerStack<FrameLayer>> layerDecorator = UnaryOperator.identity();

        /**
         * Sets the layout strategy (row, column, grid, stack, custom).
         *
         * @param layout the layout strategy
         * @return this builder
         */
        public @NotNull Builder layout(@NotNull Layout layout) {
            this.layout = layout;
            return this;
        }

        /**
         * Appends a child described by a renderer and its options. The render call is deferred
         * until the parent renderer walks the layout, so a caller can build multiple variants of
         * the same layout cheaply.
         *
         * @param renderer the child renderer
         * @param options the child options
         * @param <O> the options type
         * @return this builder
         */
        public <O extends RenderOptions> @NotNull Builder child(@NotNull Renderer<O> renderer, @NotNull O options) {
            this.children.add(() -> renderer.render(options));
            return this;
        }

        /**
         * Appends a child from a pre-rendered image. Useful when a caller wants to render once
         * and place the result into multiple layouts without repeating the work.
         *
         * @param preRendered the pre-rendered image data
         * @return this builder
         */
        public @NotNull Builder child(@NotNull ImageData preRendered) {
            this.children.add(() -> preRendered);
            return this;
        }

        /**
         * Sets the target output frame rate used when any child is animated.
         *
         * @param fps the target frame rate
         * @return this builder
         */
        public @NotNull Builder framesPerSecond(int fps) {
            this.framesPerSecond = fps;
            return this;
        }

        /**
         * Sets the canvas background fill applied before blitting any child.
         *
         * @param background the background fill (solid colour or checkerboard)
         * @return this builder
         */
        public @NotNull Builder background(@NotNull Background background) {
            this.background = background;
            return this;
        }

        /**
         * Sets the transform applied to the default child {@link FrameLayer} stack before it runs,
         * letting callers splice custom layers. Defaults to
         * {@linkplain UnaryOperator#identity() identity}.
         *
         * @param layerDecorator the layer-stack transform
         * @return this builder
         */
        public @NotNull Builder layerDecorator(@NotNull UnaryOperator<LayerStack<FrameLayer>> layerDecorator) {
            this.layerDecorator = layerDecorator;
            return this;
        }

        /**
         * Builds the immutable options instance.
         *
         * @return the options
         */
        public @NotNull LayoutOptions build() {
            return new LayoutOptions(this.layout, this.children, this.framesPerSecond, this.background, this.layerDecorator);
        }

    }

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
            /**
             * Align children to the leading edge (top or left).
             */
            START,
            /**
             * Center children along the cross axis.
             */
            CENTER,
            /**
             * Align children to the trailing edge (bottom or right).
             */
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
}
