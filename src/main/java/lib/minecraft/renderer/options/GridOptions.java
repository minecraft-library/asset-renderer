package lib.minecraft.renderer.options;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.GridRenderer;
import lib.minecraft.renderer.engine.compose.FrameCompositor;
import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerSlot;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
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
 * @see lib.minecraft.renderer.GridRenderer
 * @see lib.minecraft.renderer.engine.compose.FrameCompositor
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class GridOptions implements Option {

    /**
     * Tile images to place on the grid, each carrying its own cell coordinate. Empty by default
     */
    @lombok.Builder.Default
    private final @NotNull ConcurrentList<GridTile> tiles = Concurrent.newList();

    /**
     * Cell dimensions in pixels (square)
     */
    @lombok.Builder.Default
    private final int cellSize = 64;

    /**
     * Number of columns in the grid
     */
    @lombok.Builder.Default
    private final int columns = 1;

    /**
     * Number of rows in the grid
     */
    @lombok.Builder.Default
    private final int rows = 1;

    /**
     * Pixel gap between adjacent cells; {@code 0} (default) abuts cells with no separation
     */
    @lombok.Builder.Default
    private final int separation = 0;

    /**
     * Background fill for empty grid areas (solid colour or checkerboard), defaulting to
     * {@link Background#TRANSPARENT}
     */
    @lombok.Builder.Default
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Transform applied to the default tile {@link LayerStack} of {@link FrameLayer}s (built under
     * {@link Slot#CELL}) before it runs, letting callers splice custom layers (overlays, watermarks)
     * relative to the built-in cells. Defaults to {@linkplain UnaryOperator#identity() identity}.
     */
    @lombok.Builder.Default
    private final @NotNull UnaryOperator<LayerStack<FrameLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a
     * few fields changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull GridOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default options
     */
    public static @NotNull GridOptions defaults() {
        return builder().build();
    }

    /**
     * Render-order slots for the grid's {@link FrameLayer} stack. Every tile is a {@link #CELL}; the
     * single built-in slot exists so callers can splice layers relative to the cells via
     * {@link #layerDecorator}.
     */
    public enum Slot implements LayerSlot {

        /** A single grid cell (tile placement). */
        CELL;

        /** {@inheritDoc} */
        @Override
        public int order() {
            return ordinal();
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String id() {
            return name();
        }
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
