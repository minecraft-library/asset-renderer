package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.engine.compose.FrameCompositor;
import lib.minecraft.renderer.engine.compose.FramePlacement;
import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.options.LayoutOptions;
import lib.minecraft.renderer.options.slot.LayoutSlot;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.function.Supplier;

/**
 * Composes multiple renderers (or pre-rendered images) into a single output, transparently
 * promoting the result to animated when any child is animated.
 *
 * <p>Three steps per render:
 * <ol>
 *   <li><b>Resolve</b> every {@code Supplier<ImageData>} child to its concrete image. Suppliers
 *       are invoked once and re-used for every layout / paint step that follows.</li>
 *   <li><b>Layout</b> child positions via the selected {@link LayoutOptions.Layout} strategy
 *       ({@link LayoutOptions.Layout.Row}, {@link LayoutOptions.Layout.Column}, {@link LayoutOptions.Layout.Grid}, {@link LayoutOptions.Layout.Stack},
 *       {@link LayoutOptions.Layout.Custom}). Measurement uses each child's first frame to decide canvas
 *       dimensions and per-child {@code (x, y)} positions.</li>
 *   <li><b>Composite</b> via {@link FrameCompositor#merge FrameCompositor.merge}. If every
 *       child is static, the compositor short-circuits to a single-frame composite; if any child is
 *       animated, it picks a merged loop period and samples each child per output frame.</li>
 * </ol>
 *
 * @see LayoutOptions
 * @see LayoutOptions.Layout
 * @see FrameCompositor
 */
public final class LayoutRenderer implements Renderer<LayoutOptions> {

    /** {@inheritDoc} */
    @Override
    public @NotNull ImageData render(@NotNull LayoutOptions options) {
        ConcurrentList<ImageData> resolved = resolveChildren(options.getChildren());
        int[] sizes = measure(resolved);
        int[][] positions = layoutChildren(options.getLayout(), resolved, sizes);
        int[] canvas = computeCanvas(positions, sizes, options.getLayout().padding());

        LayerStack<FrameLayer> stack = new LayerStack<>();
        for (int i = 0; i < resolved.size(); i++) {
            int x = positions[i][0];
            int y = positions[i][1];
            ImageData child = resolved.get(i);
            stack.append(LayoutSlot.CHILD, sink -> sink.add(new FramePlacement(x, y, child)));
        }

        ConcurrentList<FramePlacement> placements = Concurrent.newList();
        Layers.foldInto(stack, options.getLayerDecorator(), placements);
        return FrameCompositor.merge(placements,
            canvas[0], canvas[1], options.getFramesPerSecond(), options.getBackground());
    }

    /**
     * Invokes every child supplier exactly once, materialising the concrete images the layout and
     * composite steps then re-use.
     */
    private static @NotNull ConcurrentList<ImageData> resolveChildren(@NotNull ConcurrentList<Supplier<ImageData>> children) {
        ConcurrentList<ImageData> resolved = Concurrent.newList();
        for (Supplier<ImageData> supplier : children)
            resolved.add(supplier.get());
        return resolved;
    }

    /**
     * Measures each child's first-frame dimensions, returning a flat {@code [w0, h0, w1, h1, ...]}
     * array indexed as {@code sizes[i*2]} = width, {@code sizes[i*2 + 1]} = height.
     */
    private static int @NotNull [] measure(@NotNull ConcurrentList<ImageData> children) {
        int[] dims = new int[children.size() * 2];
        for (int i = 0; i < children.size(); i++) {
            var image = children.get(i).toBufferedImage();
            dims[i * 2] = image.getWidth();
            dims[i * 2 + 1] = image.getHeight();
        }
        return dims;
    }

    /**
     * Computes each child's top-left {@code (x, y)} position for the selected layout strategy.
     * <p>
     * {@link LayoutOptions.Layout.Row} / {@link LayoutOptions.Layout.Column} flow children along one
     * axis and cross-align on the other; {@link LayoutOptions.Layout.Grid} fills a fixed column
     * count with uniform max-sized cells; {@link LayoutOptions.Layout.Stack} overlaps every child at
     * the same padded origin; {@link LayoutOptions.Layout.Custom} honours explicit per-child
     * positions (padded), defaulting extras to the padded origin.
     *
     * @param layout the layout strategy driving placement
     * @param children the resolved child images (used only for the count)
     * @param sizes the flat {@code [w0, h0, ...]} dimensions from {@link #measure}
     * @return a {@code [count][2]} array of {@code (x, y)} top-left positions
     */
    private static int @NonNull [][] layoutChildren(
        @NotNull LayoutOptions.Layout layout,
        @NotNull ConcurrentList<ImageData> children,
        int @NotNull [] sizes
    ) {
        int count = children.size();
        int[][] positions = new int[count][2];
        int padding = layout.padding();

        switch (layout) {
            case LayoutOptions.Layout.Row row -> {
                int maxHeight = 0;
                for (int i = 0; i < count; i++)
                    maxHeight = Math.max(maxHeight, sizes[i * 2 + 1]);

                int x = padding;
                for (int i = 0; i < count; i++) {
                    int w = sizes[i * 2];
                    int h = sizes[i * 2 + 1];
                    int y = alignOffset(maxHeight - h, row.alignment()) + padding;
                    positions[i] = new int[]{ x, y };
                    x += w + padding;
                }
            }
            case LayoutOptions.Layout.Column col -> {
                int maxWidth = 0;
                for (int i = 0; i < count; i++)
                    maxWidth = Math.max(maxWidth, sizes[i * 2]);

                int y = padding;
                for (int i = 0; i < count; i++) {
                    int w = sizes[i * 2];
                    int h = sizes[i * 2 + 1];
                    int x = alignOffset(maxWidth - w, col.alignment()) + padding;
                    positions[i] = new int[]{ x, y };
                    y += h + padding;
                }
            }
            case LayoutOptions.Layout.Grid grid -> {
                int columns = Math.max(1, grid.columns());
                int cellW = 0;
                int cellH = 0;
                for (int i = 0; i < count; i++) {
                    cellW = Math.max(cellW, sizes[i * 2]);
                    cellH = Math.max(cellH, sizes[i * 2 + 1]);
                }
                for (int i = 0; i < count; i++) {
                    int colIdx = i % columns;
                    int rowIdx = i / columns;
                    int x = padding + colIdx * (cellW + padding);
                    int y = padding + rowIdx * (cellH + padding);
                    positions[i] = new int[]{ x, y };
                }
            }
            case LayoutOptions.Layout.Stack stack -> {
                int maxW = 0;
                int maxH = 0;
                for (int i = 0; i < count; i++) {
                    maxW = Math.max(maxW, sizes[i * 2]);
                    maxH = Math.max(maxH, sizes[i * 2 + 1]);
                }
                for (int i = 0; i < count; i++) {
                    int x = alignOffset(maxW - sizes[i * 2], stack.alignment()) + stack.padding();
                    int y = alignOffset(maxH - sizes[i * 2 + 1], stack.alignment()) + stack.padding();
                    positions[i] = new int[]{ x, y };
                }
            }
            case LayoutOptions.Layout.Custom custom -> {
                for (int i = 0; i < count; i++) {
                    if (i < custom.positions().size()) {
                        LayoutOptions.Layout.Custom.Position pos = custom.positions().get(i);
                        positions[i] = new int[]{ pos.x() + padding, pos.y() + padding };
                    } else {
                        positions[i] = new int[]{ padding, padding };
                    }
                }
            }
        }
        return positions;
    }

    /**
     * Distributes the given cross-axis {@code slack} for an alignment: none for {@code START}, half
     * for {@code CENTER}, all for {@code END}.
     */
    private static int alignOffset(int slack, @NotNull LayoutOptions.Layout.Alignment alignment) {
        return switch (alignment) {
            case START -> 0;
            case CENTER -> slack / 2;
            case END -> slack;
        };
    }

    /**
     * Computes the canvas size as the bounding box of every placed child plus a trailing
     * {@code padding} margin, clamped to a minimum of {@code 1x1} pixels.
     *
     * @param positions the per-child {@code (x, y)} top-left positions
     * @param sizes the flat {@code [w0, h0, ...]} dimensions from {@link #measure}
     * @param padding the trailing margin added to the right and bottom extents
     * @return a two-element {@code [width, height]} canvas size
     */
    private static int @NotNull [] computeCanvas(int @NonNull [][] positions, int @NotNull [] sizes, int padding) {
        int maxX = 0;
        int maxY = 0;
        for (int i = 0; i < positions.length; i++) {
            int right = positions[i][0] + sizes[i * 2];
            int bottom = positions[i][1] + sizes[i * 2 + 1];
            if (right > maxX) maxX = right;
            if (bottom > maxY) maxY = bottom;
        }
        return new int[]{ Math.max(1, maxX + padding), Math.max(1, maxY + padding) };
    }

}
