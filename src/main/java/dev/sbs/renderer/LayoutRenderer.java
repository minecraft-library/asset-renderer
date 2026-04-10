package dev.sbs.renderer;

import dev.sbs.renderer.draw.FrameMerger;
import dev.sbs.renderer.options.Layout;
import dev.sbs.renderer.options.LayoutOptions;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.function.Supplier;

/**
 * Composes multiple renderers (or pre-rendered images) into a single output, transparently
 * promoting the result to animated when any child is animated.
 * <p>
 * Child positions are derived from the selected {@link Layout} strategy. Measurement uses each
 * child's first frame to decide canvas dimensions, then the merged composite is produced by
 * delegating to {@link FrameMerger}.
 */
public final class LayoutRenderer implements Renderer<LayoutOptions> {

    @Override
    public @NotNull ImageData render(@NotNull LayoutOptions options) {
        ConcurrentList<ImageData> resolved = resolveChildren(options.getChildren());
        int[] sizes = measure(resolved);
        int[][] positions = layoutChildren(options.getLayout(), resolved, sizes);
        int[] canvas = computeCanvas(positions, sizes, options.getLayout().padding());

        ConcurrentList<FrameMerger.Layer> layers = Concurrent.newList();
        for (int i = 0; i < resolved.size(); i++)
            layers.add(new FrameMerger.Layer(positions[i][0], positions[i][1], resolved.get(i)));

        return FrameMerger.merge(layers, canvas[0], canvas[1], options.getFramesPerSecond(), options.getBackgroundArgb());
    }

    private static @NotNull ConcurrentList<ImageData> resolveChildren(@NotNull ConcurrentList<Supplier<ImageData>> children) {
        ConcurrentList<ImageData> resolved = Concurrent.newList();
        for (Supplier<ImageData> supplier : children)
            resolved.add(supplier.get());
        return resolved;
    }

    private static int @NotNull [] measure(@NotNull ConcurrentList<ImageData> children) {
        int[] dims = new int[children.size() * 2];
        for (int i = 0; i < children.size(); i++) {
            var image = children.get(i).toBufferedImage();
            dims[i * 2] = image.getWidth();
            dims[i * 2 + 1] = image.getHeight();
        }
        return dims;
    }

    private static int @NonNull [][] layoutChildren(
        @NotNull Layout layout,
        @NotNull ConcurrentList<ImageData> children,
        int @NotNull [] sizes
    ) {
        int count = children.size();
        int[][] positions = new int[count][2];
        int padding = layout.padding();

        switch (layout) {
            case Layout.Row row -> {
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
            case Layout.Column col -> {
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
            case Layout.Grid grid -> {
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
            case Layout.Stack stack -> {
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
            case Layout.Custom custom -> {
                for (int i = 0; i < count; i++) {
                    if (i < custom.positions().size()) {
                        Layout.Custom.Position pos = custom.positions().get(i);
                        positions[i] = new int[]{ pos.x() + padding, pos.y() + padding };
                    } else {
                        positions[i] = new int[]{ padding, padding };
                    }
                }
            }
        }
        return positions;
    }

    private static int alignOffset(int slack, @NotNull Layout.Alignment alignment) {
        return switch (alignment) {
            case START -> 0;
            case CENTER -> slack / 2;
            case END -> slack;
        };
    }

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
