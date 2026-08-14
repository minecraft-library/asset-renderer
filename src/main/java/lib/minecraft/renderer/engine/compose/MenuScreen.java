package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Where a container screen puts its cells, in Minecraft pixels.
 * <p>
 * Five shipped containers reduce to two numbers each. The vertical stack is
 * {@code topBand + rows * 18 + labelBand + 54 + 4 + 18 + 7} when the player section is drawn, and
 * {@code topBand + rows * 18 + 7} when it is not, which reproduces every drawn height the client
 * ships. Everything else - the player rows, the gap before the hotbar, the margins - is shared.
 * <p>
 * The horizontal origin of a screen's own grid is stored rather than derived. Most containers centre
 * theirs, and the crafting table does not.
 *
 * @param topBand pixels from the panel's top edge to the first of its own cells
 * @param labelBand pixels from the last of its own cells to the player section
 * @param ownRows how many rows of its own cells it has
 * @param ownColumns how many columns of its own cells it has, zero where its row's cells sit at their
 * own spacing and ride {@code extras}
 * @param ownOriginX the left edge of its own grid
 * @param panelColumns how many cells wide the panel itself is, which is what its width comes to
 * @param titleX the left edge of the title, in from the panel's own
 * @param extras cells that are not part of the regular grid, such as a crafting result
 */
public record MenuScreen(
    int topBand, int labelBand,
    int ownRows, int ownColumns, int ownOriginX,
    int panelColumns, int titleX,
    @NotNull ConcurrentList<MenuLayout.Cell> extras
) {

    /** side of one cell, and the pitch between two, in Minecraft pixels */
    public static final int CELL = 18;

    /** the margin from the panel edge to the full-width grid, on both sides and at the bottom */
    public static final int MARGIN = 7;

    /** every full-width row is nine cells, which is what makes a panel 176 wide */
    public static final int COLUMNS = 9;

    /** rows in the player's main inventory */
    private static final int PLAYER_ROWS = 3;

    /** pixels between the player's main inventory and the hotbar */
    private static final int HOTBAR_GAP = 4;

    /** the left edge of a title, on every screen that does not move it */
    private static final int TITLE_X = 8;

    /**
     * A grid of cells filling the panel, which is what a chest is and what a screen with no shipped
     * art of its own is laid out as. Its bands are a chest's.
     *
     * @param rows how many rows of cells
     * @param columns how many columns of cells, which is also how wide the panel is
     * @return the screen
     */
    public static @NotNull MenuScreen grid(int rows, int columns) {
        return new MenuScreen(17, 13, rows, columns, MARGIN, columns, TITLE_X, Concurrent.newList());
    }

    /**
     * A chest of the given row count.
     * <p>
     * A chest is the one screen the client composes rather than blits whole - one sheet serves every
     * row count, in two draws that skip a source row between them - so its numbers are the composed
     * panel's and sit one pixel above the sheet's below the container rows.
     *
     * @param rows how many rows the chest has, three for a single and six for a double
     * @return the screen
     */
    public static @NotNull MenuScreen chest(int rows) {
        return grid(rows, COLUMNS);
    }

    /** The shulker box, a three-row container whose label band is one pixel short of a chest's. */
    public static @NotNull MenuScreen shulkerBox() {
        return new MenuScreen(17, 12, 3, COLUMNS, MARGIN, COLUMNS, TITLE_X, Concurrent.newList());
    }

    /** The hopper, one row of five cells, sitting two pixels lower than a chest's first row. */
    public static @NotNull MenuScreen hopper() {
        return new MenuScreen(19, 13, 1, 5, centred(5), COLUMNS, TITLE_X, Concurrent.newList());
    }

    /** The dispenser, a centred three by three. */
    public static @NotNull MenuScreen dispenser() {
        return new MenuScreen(16, 13, 3, 3, centred(3), COLUMNS, TITLE_X, Concurrent.newList());
    }

    /**
     * The crafting table, a three by three that is <b>not</b> centred - its columns sit four pixels
     * left of where a dispenser's do - plus a result cell of 26 rather than 18, and a title starting
     * where a recipe book's tab would end.
     */
    public static @NotNull MenuScreen craftingTable() {
        ConcurrentList<MenuLayout.Cell> extras = Concurrent.newList();
        extras.add(new MenuLayout.Cell(119, 30, 26, MenuLayout.Role.RESULT));
        return new MenuScreen(16, 13, 3, 3, 29, COLUMNS, 29, extras);
    }

    /**
     * The anvil, whose row is two inputs and a result at their own spacing rather than a grid, so it
     * has one row of no columns and carries its three cells as extras.
     */
    public static @NotNull MenuScreen anvil() {
        ConcurrentList<MenuLayout.Cell> extras = Concurrent.newList();
        extras.add(new MenuLayout.Cell(26, 46, CELL, MenuLayout.Role.CONTAINER));
        extras.add(new MenuLayout.Cell(75, 46, CELL, MenuLayout.Role.CONTAINER));
        extras.add(new MenuLayout.Cell(133, 46, CELL, MenuLayout.Role.RESULT));
        return new MenuScreen(46, 19, 1, 0, MARGIN, COLUMNS, 60, extras);
    }

    /**
     * The left edge that centres a grid of the given width on a nine-column panel.
     *
     * @param columns how many columns to centre
     * @return the left edge in Minecraft pixels
     */
    public static int centred(int columns) {
        return (width(COLUMNS) - columns * CELL) / 2;
    }

    /**
     * The width a panel of the given cell count comes to.
     *
     * @param panelColumns how many cells wide the panel is
     * @return the width in Minecraft pixels
     */
    public static int width(int panelColumns) {
        return 2 * MARGIN + panelColumns * CELL;
    }

    /**
     * This panel's width.
     *
     * @return the width in Minecraft pixels
     */
    public int width() {
        return width(this.panelColumns);
    }

    /**
     * Lays this screen out.
     *
     * @param playerSection whether the player's inventory and hotbar are drawn below the container
     * @return the panel extent and every cell in it
     */
    public @NotNull MenuLayout layout(boolean playerSection) {
        ConcurrentList<MenuLayout.Cell> cells = Concurrent.newList();

        for (int row = 0; row < this.ownRows; row++)
            for (int column = 0; column < this.ownColumns; column++)
                cells.add(new MenuLayout.Cell(
                    this.ownOriginX + column * CELL,
                    this.topBand + row * CELL,
                    CELL, MenuLayout.Role.CONTAINER));

        cells.addAll(this.extras);

        int height = this.topBand + this.ownRows * CELL;
        if (!playerSection) return new MenuLayout(width(), height + MARGIN, cells);

        int playerTop = height + this.labelBand;
        for (int row = 0; row < PLAYER_ROWS; row++)
            for (int column = 0; column < COLUMNS; column++)
                cells.add(new MenuLayout.Cell(
                    MARGIN + column * CELL, playerTop + row * CELL, CELL, MenuLayout.Role.PLAYER_MAIN));

        int hotbarTop = playerTop + PLAYER_ROWS * CELL + HOTBAR_GAP;
        for (int column = 0; column < COLUMNS; column++)
            cells.add(new MenuLayout.Cell(MARGIN + column * CELL, hotbarTop, CELL, MenuLayout.Role.HOTBAR));

        return new MenuLayout(width(), hotbarTop + CELL + MARGIN, cells);
    }

    /**
     * The screens whose geometry has been measured against the client - four against the panels it
     * ships, the chest against the panel it composes one of them into, and the anvil against the
     * slots its menu declares.
     *
     * @return the measured screens
     */
    public static @NotNull List<MenuScreen> measured() {
        return List.of(chest(6), shulkerBox(), hopper(), dispenser(), craftingTable(), anvil());
    }

}
