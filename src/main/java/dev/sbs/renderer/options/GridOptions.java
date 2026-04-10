package dev.sbs.renderer.options;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFormat;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Configures a single {@code GridRenderer} invocation.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class GridOptions {

    @lombok.Builder.Default
    private final @NotNull ConcurrentList<GridTile> tiles = Concurrent.newList();

    @lombok.Builder.Default
    private final int cellSize = 64;

    @lombok.Builder.Default
    private final int columns = 1;

    @lombok.Builder.Default
    private final int rows = 1;

    @lombok.Builder.Default
    private final int separation = 0;

    @lombok.Builder.Default
    private final int backgroundArgb = 0x00000000;

    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

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
