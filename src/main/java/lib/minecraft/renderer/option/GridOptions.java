package lib.minecraft.renderer.option;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.GridRenderer;
import lib.minecraft.renderer.engine.compose.FrameCompositor;
import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.option.slot.GridSlot;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

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
 *
 * <p><b>Parity.</b> Reaches the grid alone, which this store holds no artifact for.
 *
 * @see lib.minecraft.renderer.GridRenderer
 * @see lib.minecraft.renderer.engine.compose.FrameCompositor
 */
@Getter
@ClassBuilder
@Parity(subject = Subject.GRID)
public class GridOptions implements RenderOptions {

    /**
     * Tile images to place on the grid, each carrying its own cell coordinate. Empty by default
     */
    private final @NotNull ConcurrentList<GridTile> tiles = Concurrent.newList();

    /**
     * Cell dimensions in pixels (square)
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
     * relative to the built-in cells. Defaults to {@linkplain UnaryOperator#identity() identity}.
     */
    private final @NotNull UnaryOperator<LayerStack<FrameLayer>> layerDecorator = UnaryOperator.identity();

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
     *
     * @param col zero-based column index of the target cell
     * @param row zero-based row index of the target cell
     * @param image the tile image data (static or animated)
     */
    public record GridTile(int col, int row, @NotNull ImageData image) {}

}
