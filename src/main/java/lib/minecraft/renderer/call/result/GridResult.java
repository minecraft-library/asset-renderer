package lib.minecraft.renderer.call.result;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * What a grid render hands back: the sheet, and the cell each tile was placed in. Its stand-ins are its
 * tiles'.
 * <p>
 * There is one cell per tile, in the order of the options' tile list, so a list naming one cell twice
 * answers two cells at the same place. A cell is where its tile was drawn: a static and an animated
 * sheet alike scale each tile to it, so its rectangle is exactly the pixels the tile covers.
 *
 * @param image the composed sheet, static for one frame and animated for several
 * @param cells one cell per tile, in the order of the options' tile list
 */
@Parity(subject = Subject.GRID)
public record GridResult(@NotNull ImageData image, @NotNull ConcurrentList<Cell> cells) implements RenderResult {

    /**
     * Constructs a new {@code GridResult}, keeping the cells unmodifiable in the order given.
     */
    public GridResult {
        cells = cells.stream().collect(Concurrent.toUnmodifiableList());
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull ConcurrentList<Substitution> substitutions() {
        return PlainResult.distinctSorted(this.cells.stream()
            .flatMap(cell -> cell.result().substitutions().stream()));
    }

    /**
     * One tile placed in the grid.
     *
     * @param index the tile's position in the options' tile list
     * @param col the column of its cell
     * @param row the row of its cell
     * @param x the cell's left edge, in output pixels
     * @param y the cell's top edge, in output pixels
     * @param width the cell's width, in output pixels
     * @param height the cell's height, in output pixels
     * @param result the tile's own result
     */
    public record Cell(int index, int col, int row, int x, int y, int width, int height, @NotNull RenderResult result) {}

}
