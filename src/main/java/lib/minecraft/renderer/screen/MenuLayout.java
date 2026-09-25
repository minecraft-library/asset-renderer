package lib.minecraft.renderer.screen;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.gui.Mark;
import lib.minecraft.renderer.vanilla.gui.ScreenMetrics;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A laid-out menu panel - how big it is and where every cell in it sits, in Minecraft pixels.
 * <p>
 * Nothing here knows how a cell is painted or what goes in one. It is the arithmetic between a
 * {@link ScreenMetrics} and a {@link Window}, so the same layout serves a panel drawn from rules and a
 * panel sliced from art.
 *
 * @param width the panel width
 * @param height the panel height
 * @param titleX the rule the container's own title starts by
 * @param inventoryAnchor where the player's label starts, present exactly when the player section is
 * laid out
 * @param cells every cell, in the order they are laid out
 * @param marks the marks the screen paints beside its cells, at the positions it declares
 */
@Parity(as = MenuRenderer.class, mode = Mode.DEMOTE)
public record MenuLayout(
    int width, int height,
    @NotNull ScreenMetrics.TitleX titleX, @NotNull Optional<Origin> inventoryAnchor,
    @NotNull ConcurrentList<ScreenMetrics.Cell> cells,
    @NotNull ConcurrentList<Mark.Placement> marks
) {

    /**
     * Lays a screen out.
     *
     * @param screen the screen to lay out
     * @param playerSection whether the player's inventory and hotbar are drawn below the container
     * @return the panel extent and every cell in it
     */
    public static @NotNull MenuLayout of(@NotNull ScreenMetrics screen, boolean playerSection) {
        ConcurrentList<ScreenMetrics.Cell> cells = Concurrent.newList();

        for (int row = 0; row < screen.ownRows(); row++)
            for (int column = 0; column < screen.ownColumns(); column++)
                cells.add(new ScreenMetrics.Cell(
                    screen.ownOriginX() + column * ScreenMetrics.CELL,
                    screen.topBand() + row * ScreenMetrics.CELL,
                    ScreenMetrics.CELL, ScreenMetrics.Role.CONTAINER));

        cells.addAll(screen.extras());

        int height = screen.topBand() + screen.ownRows() * ScreenMetrics.CELL;
        if (!playerSection)
            return new MenuLayout(screen.width(), height + ScreenMetrics.MARGIN, screen.titleX(), Optional.empty(), cells, screen.marks());

        int playerTop = height + screen.labelBand();
        for (int row = 0; row < ScreenMetrics.PLAYER_ROWS; row++)
            for (int column = 0; column < ScreenMetrics.COLUMNS; column++)
                cells.add(new ScreenMetrics.Cell(
                    ScreenMetrics.MARGIN + column * ScreenMetrics.CELL, playerTop + row * ScreenMetrics.CELL,
                    ScreenMetrics.CELL, ScreenMetrics.Role.PLAYER_MAIN));

        int hotbarTop = playerTop + ScreenMetrics.PLAYER_ROWS * ScreenMetrics.CELL + ScreenMetrics.HOTBAR_GAP;
        for (int column = 0; column < ScreenMetrics.COLUMNS; column++)
            cells.add(new ScreenMetrics.Cell(
                ScreenMetrics.MARGIN + column * ScreenMetrics.CELL, hotbarTop, ScreenMetrics.CELL,
                ScreenMetrics.Role.HOTBAR));

        int drawn = hotbarTop + ScreenMetrics.CELL + ScreenMetrics.MARGIN;
        Origin inventory =
            new Origin(ScreenMetrics.TITLE_X, drawn + screen.declaredSlack() - ScreenMetrics.INVENTORY_LABEL_RISE);

        return new MenuLayout(screen.width(), drawn, screen.titleX(), Optional.of(inventory), cells, screen.marks());
    }

    /**
     * Returns the smallest panel a screen fills - its top band, one cell of its own grid, every cell
     * it places by hand, and the margin below whichever reaches furthest.
     * <p>
     * This is a content floor and never a {@link Window}'s. What a window answers is what its own art
     * needs to paint a frame, and the two are independent quantities, so a panel is bound by whichever
     * is greater on each axis. Vanilla's drawn geometry closes at eight Minecraft pixels square, well
     * under the thirty-two by forty-two a chest-shaped screen needs for one cell, and a window sliced
     * from art with anchored features can want far more than either.
     *
     * @param screen the screen whose floor is answered
     * @return the minimum panel extent in Minecraft pixels
     */
    public static @NotNull Window.Extent minimum(@NotNull ScreenMetrics screen) {
        int width = screen.ownOriginX() + ScreenMetrics.CELL + ScreenMetrics.MARGIN;
        int height = screen.topBand() + ScreenMetrics.CELL + ScreenMetrics.MARGIN;

        for (ScreenMetrics.Cell cell : screen.extras()) {
            width = Math.max(width, cell.x() + cell.size() + ScreenMetrics.MARGIN);
            height = Math.max(height, cell.y() + cell.size() + ScreenMetrics.MARGIN);
        }

        return new Window.Extent(width, height);
    }

    /**
     * Where the container's own title starts.
     * <p>
     * The width is an argument because one screen centres its title, and what a title comes to is a
     * measurement over the glyphs in it rather than anything a layout can derive. A screen that fixes
     * its title ignores the argument.
     *
     * @param titleWidth the title's width in Minecraft pixels
     * @return the title's anchor
     */
    public @NotNull Origin titleAnchor(int titleWidth) {
        return new Origin(this.titleX.at(this.width, titleWidth), ScreenMetrics.TITLE_Y);
    }

    /**
     * Where a label starts, in Minecraft pixels from the panel's own corner. The vertical is the top
     * of the glyph cell rather than the baseline, which is what the client positions a label by.
     *
     * @param x the left edge
     * @param y the top edge
     */
    public record Origin(int x, int y) {}

    /**
     * The cells a caller's slot indices address, in layout order - the container's own, and the one a
     * result sits in where the screen has one. The player's section is drawn and never addressed.
     *
     * @return the addressable cells
     */
    public @NotNull ConcurrentList<ScreenMetrics.Cell> slotCells() {
        return this.cells.stream()
            .filter(cell -> cell.role().addressed())
            .collect(Concurrent.toList());
    }

    /**
     * The extent this panel needs as a {@link Window.Box} at the given output scale.
     *
     * @param scale the output pixels each Minecraft pixel occupies on a side
     * @return the panel box
     */
    public @NotNull Window.Box box(int scale) {
        return new Window.Box(0, 0, this.width, this.height, scale);
    }

}
