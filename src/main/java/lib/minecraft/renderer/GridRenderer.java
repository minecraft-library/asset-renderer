package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.kit.FrameMerger;
import lib.minecraft.renderer.options.GridOptions;
import org.jetbrains.annotations.NotNull;

/**
 * Composes a set of {@link GridOptions.GridTile tiles} into a single grid image.
 *
 * <p>Two paint paths share one dispatch:
 * <ul>
 *   <li><b>All-static fast path</b> - allocates a single {@link PixelBuffer}, fills the
 *       configured background, and blits every tile in parallel. Disjoint destination
 *       rectangles (separation is non-negative) make the parallel writes race-free without
 *       per-tile synchronisation.</li>
 *   <li><b>Mixed / any-animated path</b> - promotes the entire output to animated and walks
 *       every tile through {@link FrameMerger#merge FrameMerger.merge}, which computes a
 *       merged loop period (LCM of animated layers, capped at 10 seconds) and samples each
 *       layer per output frame.</li>
 * </ul>
 *
 * <p>Mixing PNG and WebP tile sources requires no caller-side coordination - the renderer
 * detects the mix and routes through the animated path automatically.
 *
 * @see GridOptions
 * @see FrameMerger
 */
public final class GridRenderer implements Renderer<GridOptions> {

    /**
     * Default animated-grid output frame rate when promoting a mixed static/animated composite.
     */
    private static final int DEFAULT_FRAME_FPS = 30;

    @Override
    public @NotNull ImageData render(@NotNull GridOptions options) {
        int cellSize = options.getCellSize();
        int separation = options.getSeparation();
        int canvasW = options.getColumns() * (cellSize + separation) + separation;
        int canvasH = options.getRows() * (cellSize + separation) + separation;

        boolean anyAnimated = options.getTiles()
            .stream()
            .anyMatch(tile -> tile.image().isAnimated());

        if (!anyAnimated) {
            PixelBuffer buffer = PixelBuffer.create(canvasW, canvasH);
            buffer.fill(options.getBackgroundArgb());

            // Tile-parallel blit. Each tile's (x, y, cellSize) destination rectangle is disjoint
            // from every other tile's (separation is non-negative, so rectangles never overlap),
            // which means blitScaled writes to non-aliasing int[] index ranges across threads.
            // tile.image().toBufferedImage() allocates a fresh BufferedImage per call, so the
            // PixelBuffer.wrap snapshot is thread-local.
            options.getTiles().parallelStream().forEach(tile -> {
                PixelBuffer tileBuffer = PixelBuffer.wrap(tile.image().toBufferedImage());
                int x = tile.col() * (cellSize + separation) + separation;
                int y = tile.row() * (cellSize + separation) + separation;
                buffer.blitScaled(tileBuffer, x, y, cellSize, cellSize);
            });

            return RenderEngine.staticFrame(buffer);
        }

        ConcurrentList<FrameMerger.Layer> layers = Concurrent.newList();
        for (GridOptions.GridTile tile : options.getTiles()) {
            int x = tile.col() * (cellSize + separation) + separation;
            int y = tile.row() * (cellSize + separation) + separation;
            layers.add(new FrameMerger.Layer(x, y, tile.image()));
        }

        return FrameMerger.merge(layers, canvasW, canvasH, DEFAULT_FRAME_FPS, options.getBackgroundArgb());
    }

}
