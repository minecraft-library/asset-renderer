package lib.minecraft.renderer.options;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.GridRenderer;
import lib.minecraft.renderer.kit.FrameMerger;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Configures a single {@link GridRenderer GridRenderer} invocation.
 *
 * <p>Composes a list of {@link GridTile tiles} into a {@code rows x columns} grid with
 * uniform cell size and configurable separation between cells. Mixed static and animated
 * tile sources are handled transparently; if any tile is animated, the renderer promotes the
 * whole output to animated and synchronises tile frames via
 * {@link FrameMerger FrameMerger}.
 *
 * @see lib.minecraft.renderer.GridRenderer
 * @see lib.minecraft.renderer.kit.FrameMerger
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class GridOptions {

    /**
     * Tile images to place on the grid
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
     * Pixel gap between adjacent cells
     */
    @lombok.Builder.Default
    private final int separation = 0;

    /**
     * Background fill for empty areas (solid colour or checkerboard).
     */
    @lombok.Builder.Default
    private final @NotNull Background background = Background.TRANSPARENT;

    public @NotNull GridOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull GridOptions defaults() {
        return builder().build();
    }

    /**
     * A single tile to place on the grid at a specific cell coordinate.
     *
     * @param col the column index, zero-based
     * @param row the row index, zero-based
     * @param image the tile image data
     */
    public record GridTile(int col, int row, @NotNull ImageData image) {}

}
