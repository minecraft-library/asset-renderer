package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.stream.Stream;

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
 * @param declaredSlack pixels the screen declares itself to be beyond what it draws, which the
 * player's label is positioned from
 * @param ownRows how many rows of its own cells it has
 * @param ownColumns how many columns of its own cells it has, zero where its row's cells sit at their
 * own spacing and ride {@code extras}
 * @param ownOriginX the left edge of its own grid
 * @param panelColumns how many cells wide the panel itself is, which is what its width comes to
 * @param titleX where the title starts
 * @param extras cells that are not part of the regular grid, such as a crafting result
 * @param marks the marks the screen paints beside its cells, which hold no content and are
 * addressed by no slot index
 */
@Parity(as = MenuRenderer.class, mode = Mode.DEMOTE)
public record MenuScreen(
    int topBand, int labelBand, int declaredSlack,
    int ownRows, int ownColumns, int ownOriginX,
    int panelColumns, @NotNull TitleX titleX,
    @NotNull ConcurrentList<MenuLayout.Cell> extras,
    @NotNull ConcurrentList<MenuLayout.Mark> marks
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

    /** where a title starts on every screen that neither moves it nor centres it */
    private static final @NotNull TitleX TITLE_START = new TitleX.Inset(TITLE_X);

    /** the top edge of a title, which every screen shares */
    public static final int TITLE_Y = 6;

    /** how far above a screen's declared bottom the player's label sits */
    private static final int INVENTORY_LABEL_RISE = 94;

    /**
     * A grid of cells filling the panel, which is what a screen with no shipped art of its own is
     * laid out as. Its bands are a chest's, and it declares no more than it draws.
     *
     * @param rows how many rows of cells
     * @param columns how many columns of cells, which is also how wide the panel is
     * @return the screen
     */
    public static @NotNull MenuScreen grid(int rows, int columns) {
        return new MenuScreen(17, 13, 0, rows, columns, MARGIN, columns, TITLE_START, Concurrent.newList(), Concurrent.newList());
    }

    /**
     * A chest of the given row count.
     * <p>
     * A chest is the one screen the client composes rather than blits whole - one sheet serves every
     * row count, in two draws that skip a source row between them - so its numbers are the composed
     * panel's and sit one pixel above the sheet's below the container rows. The pixel that draw
     * skipped is what it declares and never draws.
     *
     * @param rows how many rows the chest has, three for a single and six for a double
     * @return the screen
     */
    public static @NotNull MenuScreen chest(int rows) {
        return new MenuScreen(17, 13, 1, rows, COLUMNS, MARGIN, COLUMNS, TITLE_START, Concurrent.newList(), Concurrent.newList());
    }

    /**
     * The shulker box, a three-row container whose label band is one pixel short of a chest's.
     * <p>
     * It declares a pixel it never draws, as a chest does, and for a different reason: a chest's is
     * the source row its second blit skips, where this one is blitted whole and simply declares
     * itself one taller than its art. The player's label is positioned from the declared height on
     * both, so the two arrive at the same offset by different routes.
     */
    public static @NotNull MenuScreen shulkerBox() {
        return new MenuScreen(17, 12, 1, 3, COLUMNS, MARGIN, COLUMNS, TITLE_START, Concurrent.newList(), Concurrent.newList());
    }

    /** The hopper, one row of five cells, sitting two pixels lower than a chest's first row. */
    public static @NotNull MenuScreen hopper() {
        return new MenuScreen(19, 13, 0, 1, 5, centred(5), COLUMNS, TITLE_START, Concurrent.newList(), Concurrent.newList());
    }

    /**
     * The dispenser, a centred three by three, and the one shipped screen that centres its title
     * rather than starting it a fixed distance in.
     */
    public static @NotNull MenuScreen dispenser() {
        return new MenuScreen(16, 13, 0, 3, 3, centred(3), COLUMNS, new TitleX.Centred(), Concurrent.newList(), Concurrent.newList());
    }

    /**
     * The crafting table, a three by three that is <b>not</b> centred - its columns sit four pixels
     * left of where a dispenser's do - plus a result cell of 26 rather than 18, and a title starting
     * where a recipe book's tab would end.
     * <p>
     * It is the one shipped screen carrying marks: the arrow between its grid and its result, which
     * the shipped panel paints into its own texture, and the button that opens a recipe book, which
     * the client blits over the panel as a widget. Both are declared here rather than read off art,
     * so both are re-inked with the panel they sit on.
     */
    public static @NotNull MenuScreen craftingTable() {
        ConcurrentList<MenuLayout.Cell> extras = Concurrent.newList();
        extras.add(new MenuLayout.Cell(119, 30, 26, MenuLayout.Role.RESULT));

        ConcurrentList<MenuLayout.Mark> marks = Concurrent.newList();
        marks.add(Decoration.ARROW.at(90, 35));
        marks.add(Decoration.BUTTON.at(5, 34, RECIPE_BOOK_ITEM));

        return new MenuScreen(16, 13, 0, 3, 3, 29, COLUMNS, new TitleX.Inset(29), extras, marks);
    }

    /**
     * The item the recipe-book button draws on its face.
     * <p>
     * The client's own button sprite is a raised frame with this item's texture composited onto it,
     * pixel for pixel, so naming the item rather than the sprite reproduces the button and hands a
     * pack that redraws the item a redrawn button at the same time.
     */
    private static final @NotNull ResourceId RECIPE_BOOK_ITEM = ResourceId.parse("minecraft:knowledge_book");

    /**
     * The anvil, whose row is two inputs and a result at their own spacing rather than a grid, so it
     * has one row of no columns and carries its three cells as extras.
     * <p>
     * It carries the most marks of any shipped screen, and one of them the art cannot supply. The
     * plus and the arrow are painted into the panel's own texture, and its arrow is the crafting
     * table's to the pixel. The hammer above its title is a picture. Where its name field goes the
     * texture holds a rectangle of flat red, which the client covers on every draw with a blitted
     * field and never once shows - so the field is declared here for the same reason the others are
     * and with the opposite justification: not because reading it off the art would freeze its ink,
     * but because reading it off the art would draw the red.
     */
    public static @NotNull MenuScreen anvil() {
        ConcurrentList<MenuLayout.Cell> extras = Concurrent.newList();
        extras.add(new MenuLayout.Cell(26, 46, CELL, MenuLayout.Role.CONTAINER));
        extras.add(new MenuLayout.Cell(75, 46, CELL, MenuLayout.Role.CONTAINER));
        extras.add(new MenuLayout.Cell(133, 46, CELL, MenuLayout.Role.RESULT));

        ConcurrentList<MenuLayout.Mark> marks = Concurrent.newList();
        marks.add(Decoration.HAMMER.at(17, 7));
        marks.add(Decoration.FIELD.at(59, 20));
        marks.add(Decoration.PLUS.at(53, 49));
        marks.add(Decoration.ARROW.at(102, 48));

        return new MenuScreen(46, 19, 0, 1, 0, MARGIN, COLUMNS, new TitleX.Inset(60), extras, marks);
    }

    /**
     * Where a screen starts its title.
     * <p>
     * Most screens fix it a few pixels in from their own left edge, which is a number they can store.
     * One centres it, which is a function of the title's own width, so what a screen holds is the
     * rule rather than the answer.
     */
    public sealed interface TitleX {

        /**
         * Resolves the left edge the title starts at.
         *
         * @param panelWidth the panel's width in Minecraft pixels
         * @param titleWidth the title's width in Minecraft pixels
         * @return the left edge in Minecraft pixels
         */
        int at(int panelWidth, int titleWidth);

        /**
         * A title a fixed distance in from the panel's own left edge.
         *
         * @param x the left edge
         */
        record Inset(int x) implements TitleX {

            /** {@inheritDoc} */
            @Override
            public int at(int panelWidth, int titleWidth) {
                return this.x;
            }

        }

        /**
         * A title centred on the panel, which is what the dispenser does and no other shipped
         * container does.
         */
        record Centred() implements TitleX {

            /** {@inheritDoc} */
            @Override
            public int at(int panelWidth, int titleWidth) {
                return (panelWidth - titleWidth) / 2;
            }

        }

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
     * Returns the smallest panel this screen fills - its top band, one cell of its own grid, every
     * cell it places by hand, and the margin below whichever reaches furthest.
     * <p>
     * This is a content floor and never a {@link Window}'s. What a window answers is what its own art
     * needs to paint a frame, and the two are independent quantities, so a panel is bound by whichever
     * is greater on each axis. Vanilla's drawn geometry closes at eight Minecraft pixels square, well
     * under the thirty-two by forty-two a chest-shaped screen needs for one cell, and a window sliced
     * from art with anchored features can want far more than either.
     *
     * @return the minimum panel extent in Minecraft pixels
     */
    public @NotNull Window.Extent minimum() {
        int width = this.ownOriginX + CELL + MARGIN;
        int height = this.topBand + CELL + MARGIN;

        for (MenuLayout.Cell cell : this.extras) {
            width = Math.max(width, cell.x() + cell.size() + MARGIN);
            height = Math.max(height, cell.y() + cell.size() + MARGIN);
        }

        return new Window.Extent(width, height);
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
        if (!playerSection)
            return new MenuLayout(width(), height + MARGIN, this.titleX, Optional.empty(), cells, this.marks);

        int playerTop = height + this.labelBand;
        for (int row = 0; row < PLAYER_ROWS; row++)
            for (int column = 0; column < COLUMNS; column++)
                cells.add(new MenuLayout.Cell(
                    MARGIN + column * CELL, playerTop + row * CELL, CELL, MenuLayout.Role.PLAYER_MAIN));

        int hotbarTop = playerTop + PLAYER_ROWS * CELL + HOTBAR_GAP;
        for (int column = 0; column < COLUMNS; column++)
            cells.add(new MenuLayout.Cell(MARGIN + column * CELL, hotbarTop, CELL, MenuLayout.Role.HOTBAR));

        int drawn = hotbarTop + CELL + MARGIN;
        MenuLayout.Anchor inventory =
            new MenuLayout.Anchor(TITLE_X, drawn + this.declaredSlack - INVENTORY_LABEL_RISE);

        return new MenuLayout(width(), drawn, this.titleX, Optional.of(inventory), cells, this.marks);
    }

    /**
     * The screens whose geometry has been measured against the client - four against the panels it
     * ships, the chest against the panel it composes one of them into, and the anvil against the
     * slots its menu declares.
     *
     * @return the measured screens
     */
    public static @NotNull ConcurrentList<MenuScreen> measured() {
        return Stream.of(chest(6), shulkerBox(), hopper(), dispenser(), craftingTable(), anvil())
            .collect(Concurrent.toUnmodifiableList());
    }

}
