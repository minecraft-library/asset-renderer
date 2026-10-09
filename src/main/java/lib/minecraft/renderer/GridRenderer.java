package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.call.request.GridOptions;
import lib.minecraft.renderer.call.result.GridResult;
import lib.minecraft.renderer.call.slot.GridSlot;
import lib.minecraft.renderer.engine.frame.FrameCompositor;
import lib.minecraft.renderer.engine.frame.FrameLayer;
import lib.minecraft.renderer.engine.frame.FramePlacement;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * Composes a set of {@link GridOptions.GridTile tiles} into a single grid image, and answers a
 * {@link GridResult} naming the cell each tile was placed in.
 *
 * <p>Two paint paths share one dispatch:
 * <ul>
 *   <li><b>All-static fast path</b> - allocates a single {@link PixelBuffer}, fills the
 *       configured background, and blits every tile in parallel. Disjoint destination
 *       rectangles (separation is non-negative) make the parallel writes race-free without
 *       per-tile synchronisation.</li>
 *   <li><b>Mixed / any-animated path</b> - promotes the entire output to animated and walks
 *       every tile through {@link FrameCompositor#merge FrameCompositor.merge}, which computes a
 *       merged loop period (LCM of animated layers, capped at 10 seconds) and samples each
 *       layer per output frame.</li>
 * </ul>
 *
 * <p>Mixing PNG and WebP tile sources requires no caller-side coordination - the renderer
 * detects the mix and routes through the animated path automatically.
 *
 *
 * <p><b>Parity.</b> This store holds no artifact for the grid. Nothing roots at it, and what it
 * arranges is measured where that content is produced rather than in the sheet it is arranged into.
 *
 * @see GridOptions
 * @see FrameCompositor
 */
@Parity(subject = Subject.GRID)
public final class GridRenderer implements Renderer<GridOptions> {

    /** {@inheritDoc} */
    @Override
    public @NotNull GridResult render(@NotNull GridOptions options) {
        int cellSize = options.getCellSize();
        int separation = options.getSeparation();
        int canvasW = options.getColumns() * (cellSize + separation) + separation;
        int canvasH = options.getRows() * (cellSize + separation) + separation;

        // Build tile placements as a FrameLayer stack so callers can splice layers via
        // GridOptions.layerDecorator, then fold for dispatch. Each tile's cell is recorded here, beside
        // the placement and from the same numbers, so the result names it whatever the decorator does.
        LayerStack<FrameLayer> stack = new LayerStack<>();
        ConcurrentList<GridResult.Cell> cells = Concurrent.newList();
        ConcurrentList<GridOptions.GridTile> tiles = options.getTiles();
        for (int i = 0; i < tiles.size(); i++) {
            GridOptions.GridTile tile = tiles.get(i);
            int x = tile.col() * (cellSize + separation) + separation;
            int y = tile.row() * (cellSize + separation) + separation;
            ImageData image = tile.result().image();
            stack.append(GridSlot.CELL, sink -> sink.add(new FramePlacement(x, y, image)));
            cells.add(new GridResult.Cell(i, tile.col(), tile.row(), x, y, cellSize, cellSize, tile.result()));
        }
        ConcurrentList<FramePlacement> placements = Concurrent.newList();
        Layers.foldInto(stack, options.getLayerDecorator(), placements);

        boolean anyAnimated = placements.stream().anyMatch(placement -> placement.source().isAnimated());

        if (!anyAnimated) {
            PixelBuffer buffer = PixelBuffer.create(canvasW, canvasH);
            options.getBackground().fill(buffer);

            // Placement-parallel blit. Each placement's (x, y, cellSize) destination rectangle is
            // disjoint from every other's (separation is non-negative, so rectangles never overlap),
            // so blitScaled writes to non-aliasing int[] index ranges across threads; the source is
            // read-only. toPixelBuffer() is the direct ImageData -> PixelBuffer conversion, avoiding
            // the PixelBuffer -> BufferedImage -> PixelBuffer round-trip of wrap(toBufferedImage()).
            placements.parallelStream().forEach(placement ->
                buffer.blitScaled(placement.source().toPixelBuffer(), placement.x(), placement.y(), cellSize, cellSize));

            return new GridResult(Timeline.still(buffer), cells);
        }

        return new GridResult(
            FrameCompositor.merge(placements, canvasW, canvasH, options.getFramesPerSecond(), options.getBackground()),
            cells);
    }

}
