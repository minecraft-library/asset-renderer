package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.call.request.GridOptions;
import lib.minecraft.renderer.call.result.GridResult;
import lib.minecraft.renderer.call.result.RenderResult;
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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

/**
 * Composes a set of {@link GridOptions.GridTile tiles} into a single grid image, and answers a
 * {@link GridResult} naming the cell each tile was placed in.
 *
 * <p>A tile is supplied rather than handed over finished: the grid asks each tile for its render as it
 * draws that tile, so a caller can have its tiles rendered on the grid's own workers. Two paint paths
 * share one dispatch:
 * <ul>
 *   <li><b>Streamed path</b> - for a grid whose decorator leaves the cell layers as they are, whose
 *       tiles name distinct cells, whose separation is not negative and whose canvas fits one buffer.
 *       The buffer is allocated and filled with the background first, then every tile is rendered, drawn
 *       into its cell and let go on a parallel pass, so the tiles are never all held at once. Distinct
 *       cells a non-negative separation apart are disjoint rectangles, which make the parallel writes
 *       race-free without per-tile synchronisation. A tile that answers animated ends the pass, and the
 *       sheet is composed again on the held path.</li>
 *   <li><b>Held path</b> - for any other grid, and for a sheet a tile turns out animated in. Every
 *       tile is rendered first, in parallel, and the decorated layers are folded into placements, so
 *       every tile is held until the sheet is composed. A static sheet fills the background and blits the
 *       placements one after another in stack order, so a layer or a tile overlapping another lands over
 *       it as the animated merge lands it; a sheet with an animated placement is promoted to animated
 *       whole and walked through
 *       {@link FrameCompositor#merge FrameCompositor.merge}, which computes a merged loop period (LCM of
 *       animated layers, capped at 10 seconds) and samples each layer per output frame.</li>
 * </ul>
 *
 * <p>Both paths draw every placement - each tile, and any layer the decorator splices in - into the
 * cell-sized square at its origin. An image of any other size is rescaled to the cell by the
 * nearest-neighbour sampling of {@link PixelBuffer#blitScaled}, so a tile spans exactly its cell: it
 * never reaches past it into a neighbour or the separation, and never falls short of it. The animated
 * path hands the compositor a {@linkplain FramePlacement#fittedTo fitted placement}, which rescales the
 * frame a tile shows at each output frame's time; a tile already the cell's size is drawn as it is.
 *
 * <p>Every placement is laid down with {@link BlendMode#PREMULTIPLIED}, because a tile is a finished
 * render whose colours already carry their alpha. A translucent pixel keeps the colour it was rendered
 * with over a transparent cell, and over a background shows the background through it as drawing the
 * subject over that background would.
 *
 * <p>Mixing PNG and WebP tile sources requires no caller-side coordination - the renderer
 * detects the mix and routes through the animated path automatically.
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

        // Build the tile placements as a FrameLayer stack so callers can splice layers via
        // GridOptions.layerDecorator. A cell layer renders its tile only when it is contributed, so the
        // decorator runs before any tile exists.
        ConcurrentList<GridOptions.GridTile> tiles = options.getTiles();
        RenderResult[] resolved = new RenderResult[tiles.size()];
        CellLayer[] cells = new CellLayer[tiles.size()];
        LayerStack<FrameLayer> stack = new LayerStack<>();
        for (int i = 0; i < tiles.size(); i++) {
            GridOptions.GridTile tile = tiles.get(i);
            int x = tile.col() * (cellSize + separation) + separation;
            int y = tile.row() * (cellSize + separation) + separation;
            cells[i] = new CellLayer(i, tile, x, y, resolved);
            stack.append(GridSlot.CELL, cells[i]);
        }
        LayerStack<FrameLayer> decorated = options.getLayerDecorator().apply(stack);

        if (streamable(options, decorated, cells, resolved) && allocatable(options))
            return streamed(options, cells, canvasW, canvasH)
                .orElseGet(() -> held(options, decorated, cells, canvasW, canvasH));

        return held(options, decorated, cells, canvasW, canvasH);
    }

    /**
     * Allocates the buffer, then renders, draws and lets go of every tile on a parallel pass.
     *
     * @param options the grid's options
     * @param cells the cell layers, in tile order
     * @param canvasW the canvas width
     * @param canvasH the canvas height
     * @return the static sheet, or empty when a tile answers animated, which only the held path composes
     */
    private static @NotNull Optional<GridResult> streamed(@NotNull GridOptions options, @NotNull CellLayer @NotNull [] cells, int canvasW, int canvasH) {
        int cellSize = options.getCellSize();
        PixelBuffer buffer = blank(canvasW, canvasH, options.getBackground());
        GridResult.Cell[] placed = new GridResult.Cell[cells.length];
        AtomicBoolean animated = new AtomicBoolean();

        // Tile-parallel render and blit. The tiles name distinct cells a non-negative separation apart,
        // so each (x, y, cellSize) destination rectangle is disjoint from every other's and blitScaled
        // writes non-aliasing int[] index ranges across threads; a tile's render is unreachable once its
        // cell is recorded. toPixelBuffer() is the direct
        // ImageData -> PixelBuffer conversion. The join orders every draw before the wrap.
        IntStream.range(0, cells.length).parallel().forEach(i -> {
            if (animated.get()) return;

            CellLayer cell = cells[i];
            RenderResult result = cell.render();
            if (result.image().isAnimated()) {
                animated.set(true);
                return;
            }

            buffer.blitScaled(result.image().toPixelBuffer(), cell.x, cell.y, cellSize, cellSize, BlendMode.PREMULTIPLIED);
            placed[i] = cell.cell(cellSize, result);
        });

        if (animated.get())
            return Optional.empty();

        return Optional.of(new GridResult(Timeline.still(buffer), Concurrent.newList(Arrays.asList(placed))));
    }

    /**
     * Renders every tile, folds the decorated layers into placements and composes them at once - a
     * static sheet blitted in parallel, or an animated one merged frame by frame.
     *
     * @param options the grid's options
     * @param decorated the decorated layer stack
     * @param cells the cell layers, in tile order
     * @param canvasW the canvas width
     * @param canvasH the canvas height
     * @return the sheet
     */
    private static @NotNull GridResult held(
        @NotNull GridOptions options,
        @NotNull LayerStack<FrameLayer> decorated,
        @NotNull CellLayer @NotNull [] cells,
        int canvasW,
        int canvasH
    ) {
        int cellSize = options.getCellSize();

        // Every tile renders before the fold, in parallel, so contributing a cell layer reads its tile.
        // A tile the decorator dropped still renders, so its cell names its stand-ins whatever the
        // decorator does.
        IntStream.range(0, cells.length).parallel().forEach(i -> cells[i].resolve());
        ConcurrentList<GridResult.Cell> placed = Arrays.stream(cells)
            .map(cell -> cell.cell(cellSize, cell.resolve()))
            .collect(Concurrent.toList());

        ConcurrentList<FramePlacement> placements = Concurrent.newList();
        Layers.foldInto(decorated, UnaryOperator.identity(), placements);

        boolean anyAnimated = placements.stream().anyMatch(placement -> placement.source().isAnimated());

        if (!anyAnimated) {
            PixelBuffer buffer = blank(canvasW, canvasH, options.getBackground());

            // Placement by placement, in stack order: a decorator's layer, a cell two tiles share or cells a
            // negative separation crosses may overlap, and a later placement composites over an earlier
            // one, as the animated merge composites them. blitScaled splits a large placement's rows
            // across threads itself. toPixelBuffer() is the direct ImageData -> PixelBuffer conversion,
            // avoiding the PixelBuffer -> BufferedImage -> PixelBuffer round-trip of
            // wrap(toBufferedImage()).
            placements.forEach(placement ->
                buffer.blitScaled(placement.source().toPixelBuffer(), placement.x(), placement.y(), cellSize, cellSize, BlendMode.PREMULTIPLIED));

            return new GridResult(Timeline.still(buffer), placed);
        }

        // Each placement is fitted to its cell, as the static branch scales it, so a tile of another size
        // has every frame rescaled by the same blitScaled and stays inside the cell.
        ConcurrentList<FramePlacement> fitted = placements.stream()
            .map(placement -> placement.fittedTo(cellSize, cellSize))
            .collect(Concurrent.toList());

        return new GridResult(
            FrameCompositor.merge(fitted, canvasW, canvasH, options.getFramesPerSecond(), options.getBackground()),
            placed);
    }

    /**
     * Answers whether the grid's tiles may be drawn in parallel as they are rendered: the decorated stack
     * draws exactly the cells, each once and in tile order, with no cell's tile rendered while it was
     * decorated, and the tiles name distinct cells a non-negative separation apart, so no two draws
     * overlap.
     *
     * @param options the grid's options
     * @param decorated the decorated layer stack
     * @param cells the cell layers, in tile order
     * @param resolved the tiles rendered so far, by tile index
     * @return whether the grid may stream its tiles
     */
    private static boolean streamable(
        @NotNull GridOptions options,
        @NotNull LayerStack<FrameLayer> decorated,
        @NotNull CellLayer @NotNull [] cells,
        RenderResult @NotNull [] resolved
    ) {
        List<FrameLayer> ordered = decorated.ordered();
        return options.getSeparation() >= 0
            && ordered.size() == cells.length
            && IntStream.range(0, cells.length).allMatch(i -> ordered.get(i) == cells[i])
            && Arrays.stream(resolved).allMatch(Objects::isNull)
            && Arrays.stream(cells).mapToLong(cell -> ((long) cell.tile.col() << 32) | (cell.tile.row() & 0xFFFFFFFFL)).distinct().count() == cells.length;
    }

    /**
     * Answers whether the grid's canvas fits one buffer: a width and a height that are not negative, each
     * and their product within an {@code int}, every factor bounded before it is multiplied.
     *
     * @param options the grid's options
     * @return whether the canvas can be allocated up front
     */
    private static boolean allocatable(@NotNull GridOptions options) {
        long pitch = (long) options.getCellSize() + options.getSeparation();
        long width = options.getColumns() * pitch + options.getSeparation();
        long height = options.getRows() * pitch + options.getSeparation();
        return width >= 0 && height >= 0
            && width <= Integer.MAX_VALUE && height <= Integer.MAX_VALUE
            && width * height <= Integer.MAX_VALUE;
    }

    /**
     * Allocates a grid's buffer with its background laid down - which a fully transparent black fill
     * leaves to the allocation, as a new buffer already holds transparent black in every pixel. A
     * transparent fill of any other colour is still laid down, as its pixels carry that colour.
     *
     * @param width the buffer's width
     * @param height the buffer's height
     * @param background the fill behind every tile
     * @return the buffer
     */
    private static @NotNull PixelBuffer blank(int width, int height, @NotNull Background background) {
        PixelBuffer buffer = PixelBuffer.create(width, height);
        if (!(background instanceof Background.Solid(int argb) && argb == 0))
            background.fill(buffer);

        return buffer;
    }

    /**
     * One tile's layer in the stack: its cell, and its render once a fold contributes it.
     */
    private static final class CellLayer implements FrameLayer {

        /** The tile's position in the options' tile list. */
        private final int index;

        /** The tile, its cell coordinate and its content. */
        private final @NotNull GridOptions.GridTile tile;

        /** The cell's left edge, in output pixels. */
        private final int x;

        /** The cell's top edge, in output pixels. */
        private final int y;

        /** The tiles the held path has rendered, by tile index, shared by every cell of one render. */
        private final RenderResult @NotNull [] resolved;

        private CellLayer(int index, @NotNull GridOptions.GridTile tile, int x, int y, RenderResult @NotNull [] resolved) {
            this.index = index;
            this.tile = tile;
            this.x = x;
            this.y = y;
            this.resolved = resolved;
        }

        /** {@inheritDoc} */
        @Override
        public void contribute(@NotNull ConcurrentList<FramePlacement> target) {
            target.add(new FramePlacement(this.x, this.y, resolve().image()));
        }

        /**
         * Renders the tile, without keeping it.
         *
         * @return the tile's render
         * @throws NullPointerException if the tile's content answers {@code null}
         */
        @NotNull RenderResult render() {
            return Objects.requireNonNull(this.tile.content().get(), "Grid tile content answered null");
        }

        /**
         * Renders the tile once and keeps it, answering the kept render after the first call.
         *
         * @return the tile's render
         */
        @NotNull RenderResult resolve() {
            RenderResult result = this.resolved[this.index];
            if (result == null)
                this.resolved[this.index] = result = render();

            return result;
        }

        /**
         * Builds the cell the tile was drawn in, keeping its stand-ins and none of its pixels.
         *
         * @param cellSize the cell's edge length
         * @param result the tile's render
         * @return the cell
         */
        @NotNull GridResult.Cell cell(int cellSize, @NotNull RenderResult result) {
            return new GridResult.Cell(this.index, this.tile.col(), this.tile.row(), this.x, this.y, cellSize, cellSize, result.substitutions());
        }

    }

}
