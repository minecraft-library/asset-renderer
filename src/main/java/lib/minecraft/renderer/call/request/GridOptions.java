package lib.minecraft.renderer.call.request;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.Background;
import lib.minecraft.renderer.GridRenderer;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.slot.GridSlot;
import lib.minecraft.renderer.engine.frame.FrameCompositor;
import lib.minecraft.renderer.engine.frame.FrameLayer;
import lib.minecraft.renderer.engine.frame.FrameLoan;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@link GridRenderer GridRenderer} invocation.
 *
 * <p>Composes a list of {@link GridTile tiles} into a {@code rows x columns} grid with
 * uniform cell size and configurable separation between cells. Mixed static and animated
 * tile sources are handled transparently; if any tile is animated, the renderer promotes the
 * whole output to animated and synchronises tile frames via
 * {@link FrameCompositor FrameCompositor}.
 *
 * <p>A tile fills its cell whatever its own size: an image larger or smaller than the cell is
 * rescaled to it by nearest-neighbour sampling, on a static and an animated grid alike, and an
 * animated tile is rescaled frame by frame.
 *
 *
 * <p><b>Parity.</b> Reaches the grid alone, which this store holds no artifact for.
 *
 * @see lib.minecraft.renderer.GridRenderer
 * @see lib.minecraft.renderer.engine.frame.FrameCompositor
 */
@Getter
@ClassBuilder
@Parity(subject = Subject.GRID)
public class GridOptions implements RenderOptions {

    /**
     * Tiles to place on the grid, each carrying its own cell coordinate and the content the grid renders
     * into it. Empty by default
     */
    private final @NotNull ConcurrentList<GridTile> tiles = Concurrent.newList();

    /**
     * Cell dimensions in pixels (square) - the size every tile is drawn at, rescaled to it when its
     * image is another size
     */
    private final int cellSize = 64;

    /**
     * Number of columns in the grid
     */
    private final int columns = 1;

    /**
     * Number of rows in the grid
     */
    private final int rows = 1;

    /**
     * Pixel gap between adjacent cells; {@code 0} (default) abuts cells with no separation
     */
    private final int separation = 0;

    /**
     * Background fill for empty grid areas (solid colour or checkerboard), defaulting to
     * {@link Background#TRANSPARENT}
     */
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Output frame rate for the animated composite; consulted only when a tile is animated - a fully
     * static grid ignores it. Defaults to {@code 30}.
     */
    private final int framesPerSecond = 30;

    /**
     * Transform applied to the default tile {@link LayerStack} of {@link FrameLayer}s (built under
     * {@link GridSlot#CELL}) before it runs, letting callers splice custom layers (overlays, watermarks)
     * relative to the built-in cells. Defaults to {@linkplain UnaryOperator#identity() identity}. A
     * decorator that changes the stack has every tile rendered and held until the sheet is composed,
     * rather than drawn and let go as it is rendered.
     */
    private final @NotNull UnaryOperator<LayerStack<FrameLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * Whether each tile's pixels are drawn into a buffer leased from the thread's pixel buffer pool for
     * the tile's render and returned once the tile is drawn, so the next tile on the same thread reuses
     * it rather than allocating its own - see {@link FrameLoan}. Consulted only where the sheet streams.
     * Setting it promises that
     * every tile's content renders afresh on each call and keeps nothing it answered, so no tile's
     * picture is read again after the grid draws it. Defaults to {@code false}
     */
    private final boolean recycleTiles = false;

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default options
     */
    public static @NotNull GridOptions defaults() {
        return builder().build();
    }

    /**
     * A single tile to place on the grid at a specific cell coordinate.
     * <p>
     * The grid asks the content for the tile's render on any of its threads, beside other tiles' content
     * and in no order. Where the sheet streams - its decorator leaves the cell layers as they are, its
     * tiles name distinct cells, its separation is not negative and its canvas fits one buffer - the grid
     * draws each tile as it is answered and lets the render go, so the sheet never holds every tile at
     * once; otherwise every tile is asked first and held until the sheet is composed. When any tile
     * answers animated the sheet is composed again, and every tile already asked is asked once more, the
     * animated one included, so content answers the same render each time and is safe on any thread.
     * <p>
     * The image is laid into its cell source-over in the convention it holds. A render, the form every
     * renderer here answers, is a {@link PixelBuffer#canvas canvas} and is laid as it was drawn; an
     * image read from a file, such as one an image editor saved, is straight and is laid as the client
     * lays a straight texel.
     *
     * @param col zero-based column index of the target cell
     * @param row zero-based row index of the target cell
     * @param content what the grid asks for the tile's render - its image, static or animated, and the
     *     stand-ins drawn in it
     */
    public record GridTile(int col, int row, @NotNull Supplier<? extends RenderResult> content) {

        /**
         * Constructs a new {@code GridTile} placing a render already made.
         *
         * @param col zero-based column index of the target cell
         * @param row zero-based row index of the target cell
         * @param result the tile's render - its image, static or animated, and the stand-ins drawn in it
         */
        public GridTile(int col, int row, @NotNull RenderResult result) {
            this(col, row, () -> result);
        }

        /**
         * Constructs a new {@code GridTile} placing pixels rendered elsewhere, carrying no stand-in.
         *
         * @param col zero-based column index of the target cell
         * @param row zero-based row index of the target cell
         * @param image the tile image data (static or animated)
         */
        public GridTile(int col, int row, @NotNull ImageData image) {
            this(col, row, RenderResult.of(image));
        }

    }

}
