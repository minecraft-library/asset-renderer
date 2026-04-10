package dev.sbs.renderer;

import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.FrameMerger;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.options.GridOptions;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.StaticImageData;
import org.jetbrains.annotations.NotNull;

/**
 * Composes a set of {@link GridOptions.GridTile tiles} into a single grid image.
 * <p>
 * The renderer supports mixed static and animated tiles via {@link FrameMerger}, so a single call
 * can combine PNG and WebP tile sources into one output without caller-side coordination.
 */
public final class GridRenderer implements Renderer<GridOptions> {

    @Override
    public @NotNull ImageData render(@NotNull GridOptions options) {
        int cellSize = options.getCellSize();
        int separation = options.getSeparation();
        int canvasW = options.getColumns() * (cellSize + separation) + separation;
        int canvasH = options.getRows() * (cellSize + separation) + separation;

        boolean anyAnimated = options.getTiles().stream()
            .anyMatch(tile -> !(tile.image() instanceof StaticImageData));

        if (!anyAnimated) {
            Canvas canvas = Canvas.of(canvasW, canvasH);
            canvas.fill(options.getBackgroundArgb());
            for (GridOptions.GridTile tile : options.getTiles()) {
                PixelBuffer buffer = PixelBuffer.wrap(tile.image().toBufferedImage());
                int x = tile.col() * (cellSize + separation) + separation;
                int y = tile.row() * (cellSize + separation) + separation;
                canvas.blitScaled(buffer, x, y, cellSize, cellSize);
            }

            return RenderEngine.staticFrame(canvas);
        }

        ConcurrentList<FrameMerger.Layer> layers = Concurrent.newList();
        for (GridOptions.GridTile tile : options.getTiles()) {
            int x = tile.col() * (cellSize + separation) + separation;
            int y = tile.row() * (cellSize + separation) + separation;
            layers.add(new FrameMerger.Layer(x, y, tile.image()));
        }

        return FrameMerger.merge(layers, canvasW, canvasH, 30, options.getBackgroundArgb());
    }

}
