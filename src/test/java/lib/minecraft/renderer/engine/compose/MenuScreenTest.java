package lib.minecraft.renderer.engine.compose;

import lib.minecraft.renderer.asset.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link MenuScreen} against the cell origins detected in the art the client ships.
 * <p>
 * The origins below were not read off this class. They were found by scanning each shipped container
 * background for an exact match of every cell size from 17 to 30 at every position, so a layout that
 * agrees with them agrees with vanilla rather than with itself. The drawn heights are the opaque
 * heights of the same textures.
 * <p>
 * A chest is scanned differently, because no sheet ships one: the client composes each row count from
 * two draws out of {@code generic_54}, the second reading from a source row past the one the first
 * ended on, so the panel a player sees is a pixel shorter than the sheet and everything below the
 * container rows sits a pixel higher. The chest rows below were scanned on that composition, which is
 * what a render has to reproduce.
 */
@DisplayName("MenuScreen lays cells where vanilla drew them")
class MenuScreenTest {

    /** the nine columns every full-width row sits at, detected rather than assumed */
    private static final int[] NINE = { 7, 25, 43, 61, 79, 97, 115, 133, 151 };

    private static List<String> origins(MenuLayout layout, MenuLayout.Role role) {
        List<String> out = new ArrayList<>();
        for (MenuLayout.Cell cell : layout.cells())
            if (cell.role() == role) out.add(cell.x() + "," + cell.y() + "," + cell.size());

        return out;
    }

    private static List<String> grid(int[] xs, int[] ys, int size) {
        List<String> out = new ArrayList<>();
        for (int y : ys)
            for (int x : xs) out.add(x + "," + y + "," + size);

        return out;
    }

    @Test
    @DisplayName("the six-row chest matches the panel generic_54 is composed into")
    void sixRowChestMatchesItsComposedPanel() {
        MenuLayout layout = MenuScreen.chest(6).layout(true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x221")));
        assertThat(origins(layout, MenuLayout.Role.CONTAINER),
            is(equalTo(grid(NINE, new int[] { 17, 35, 53, 71, 89, 107 }, 18))));
        assertThat(origins(layout, MenuLayout.Role.PLAYER_MAIN),
            is(equalTo(grid(NINE, new int[] { 138, 156, 174 }, 18))));
        assertThat(origins(layout, MenuLayout.Role.HOTBAR),
            is(equalTo(grid(NINE, new int[] { 196 }, 18))));
    }

    @Test
    @DisplayName("one sheet composes every chest row count, each with its own scanned rows")
    void oneSheetComposesEveryChestRowCount() {
        record Chest(int rows, int height, int[] container, int[] player, int hotbar) {}

        List<Chest> chests = List.of(
            new Chest(1, 131, new int[] { 17 }, new int[] { 48, 66, 84 }, 106),
            new Chest(2, 149, new int[] { 17, 35 }, new int[] { 66, 84, 102 }, 124),
            new Chest(3, 167, new int[] { 17, 35, 53 }, new int[] { 84, 102, 120 }, 142),
            new Chest(6, 221, new int[] { 17, 35, 53, 71, 89, 107 }, new int[] { 138, 156, 174 }, 196));

        for (Chest chest : chests) {
            MenuLayout layout = MenuScreen.chest(chest.rows()).layout(true);
            String at = "a chest of " + chest.rows() + " rows";

            assertThat(at + ", drawn height", layout.height(), is(equalTo(chest.height())));
            assertThat(at + ", its own cells",
                origins(layout, MenuLayout.Role.CONTAINER), is(equalTo(grid(NINE, chest.container(), 18))));
            assertThat(at + ", the player's",
                origins(layout, MenuLayout.Role.PLAYER_MAIN), is(equalTo(grid(NINE, chest.player(), 18))));
            assertThat(at + ", the hotbar",
                origins(layout, MenuLayout.Role.HOTBAR), is(equalTo(grid(NINE, new int[] { chest.hotbar() }, 18))));
        }
    }

    @Test
    @DisplayName("a three-row chest is not a shulker box, though both hold three rows of nine")
    void aThreeRowChestIsNotAShulkerBox() {
        MenuLayout chest = MenuScreen.chest(3).layout(true);
        MenuLayout shulker = MenuScreen.shulkerBox().layout(true);

        assertThat("their own cells agree",
            origins(chest, MenuLayout.Role.CONTAINER), is(equalTo(origins(shulker, MenuLayout.Role.CONTAINER))));
        assertThat("and everything below them sits a pixel lower on the chest",
            chest.height() - shulker.height(), is(equalTo(1)));
    }

    @Test
    @DisplayName("the shulker box matches shulker_box")
    void shulkerBoxMatchesItsArt() {
        MenuLayout layout = MenuScreen.shulkerBox().layout(true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x166")));
        assertThat(origins(layout, MenuLayout.Role.CONTAINER),
            is(equalTo(grid(NINE, new int[] { 17, 35, 53 }, 18))));
        assertThat(origins(layout, MenuLayout.Role.PLAYER_MAIN),
            is(equalTo(grid(NINE, new int[] { 83, 101, 119 }, 18))));
        assertThat(origins(layout, MenuLayout.Role.HOTBAR),
            is(equalTo(grid(NINE, new int[] { 141 }, 18))));
    }

    @Test
    @DisplayName("the hopper matches hopper, its row two pixels lower and inset from the nine")
    void hopperMatchesItsArt() {
        MenuLayout layout = MenuScreen.hopper().layout(true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x133")));
        assertThat(origins(layout, MenuLayout.Role.CONTAINER),
            is(equalTo(grid(new int[] { 43, 61, 79, 97, 115 }, new int[] { 19 }, 18))));
        assertThat(origins(layout, MenuLayout.Role.PLAYER_MAIN),
            is(equalTo(grid(NINE, new int[] { 50, 68, 86 }, 18))));
        assertThat(origins(layout, MenuLayout.Role.HOTBAR),
            is(equalTo(grid(NINE, new int[] { 108 }, 18))));
    }

    @Test
    @DisplayName("the dispenser matches dispenser, its grid one pixel above a chest's")
    void dispenserMatchesItsArt() {
        MenuLayout layout = MenuScreen.dispenser().layout(true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x166")));
        assertThat(origins(layout, MenuLayout.Role.CONTAINER),
            is(equalTo(grid(new int[] { 61, 79, 97 }, new int[] { 16, 34, 52 }, 18))));
    }

    @Test
    @DisplayName("the crafting table matches crafting_table, including a result cell of 26")
    void craftingTableMatchesItsArt() {
        MenuLayout layout = MenuScreen.craftingTable().layout(true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x166")));
        assertThat("its columns are four pixels left of a centred grid's",
            origins(layout, MenuLayout.Role.CONTAINER),
            is(equalTo(grid(new int[] { 29, 47, 65 }, new int[] { 16, 34, 52 }, 18))));
        assertThat(origins(layout, MenuLayout.Role.RESULT), is(equalTo(List.of("119,30,26"))));
    }

    @Test
    @DisplayName("the crafting table is the one screen that does not centre its grid")
    void theCraftingTableIsTheOneScreenThatDoesNotCentre() {
        assertThat("a dispenser centres three columns", MenuScreen.dispenser().ownOriginX(),
            is(equalTo(MenuScreen.centred(3))));
        assertThat("a hopper centres five", MenuScreen.hopper().ownOriginX(),
            is(equalTo(MenuScreen.centred(5))));
        assertThat("a crafting table does not", MenuScreen.craftingTable().ownOriginX(),
            is(not(equalTo(MenuScreen.centred(3)))));
    }

    @Test
    @DisplayName("suppressing the player section drops it and shortens the panel to its own margin")
    void suppressingThePlayerSectionShortensThePanel() {
        MenuLayout with = MenuScreen.chest(3).layout(true);
        MenuLayout without = MenuScreen.chest(3).layout(false);

        assertThat("the container cells are unmoved",
            origins(without, MenuLayout.Role.CONTAINER), is(equalTo(origins(with, MenuLayout.Role.CONTAINER))));
        assertThat("no player cells", origins(without, MenuLayout.Role.PLAYER_MAIN).size(), is(equalTo(0)));
        assertThat("no hotbar", origins(without, MenuLayout.Role.HOTBAR).size(), is(equalTo(0)));
        assertThat("height is the top band, the rows and the margin",
            without.height(), is(equalTo(17 + 3 * 18 + 7)));
    }

    @Test
    @DisplayName("every measured screen is 176 wide, which is what nine cells and two margins come to")
    void everyMeasuredScreenIsOneSevenSix() {
        for (MenuScreen screen : MenuScreen.measured())
            assertThat(screen.layout(true).width(), is(equalTo(176)));
    }

    @Test
    @DisplayName("the smallest panel a chest fills is one cell inside its bands")
    void theSmallestChestPanelIsOneCellInsideItsBands() {
        assertThat("the top band, one cell and the bottom margin, by two margins and one cell across",
            MenuScreen.chest(3).minimum(), is(equalTo(new Window.Extent(7 + 18 + 7, 17 + 18 + 7))));
        assertThat("a grid of any width answers the same, its floor being one cell rather than its own",
            MenuScreen.grid(13, 19).minimum(), is(equalTo(MenuScreen.chest(3).minimum())));
        assertThat("a hopper's is wider, its row starting where a centred five does",
            MenuScreen.hopper().minimum().width(), is(equalTo(43 + 18 + 7)));
    }

    @Test
    @DisplayName("every measured screen clears the floor it declares, cells it places by hand included")
    void everyMeasuredScreenClearsItsOwnFloor() {
        for (MenuScreen screen : MenuScreen.measured()) {
            // The container section alone, which is the smaller of the two panels a screen lays out -
            // so a screen whose hand-placed cells fell off its own panel is caught here rather than
            // hidden by the player band's height.
            MenuLayout layout = screen.layout(false);
            Window.Extent floor = screen.minimum();

            assertThat("a screen whose panel is " + layout.width() + "x" + layout.height() + " wide enough",
                layout.width() >= floor.width(), is(true));
            assertThat("and tall enough", layout.height() >= floor.height(), is(true));
        }
    }

    @Test
    @DisplayName("a mark carries an icon exactly when its kind draws one")
    void aMarkCarriesAnIconExactlyWhenItsKindDrawsOne() {
        // A kind and a position are two values now, so nothing about the type stops a button being
        // placed with no item on its face or an arrow being handed one. The guard is what says so,
        // and this is what says the guard is there.
        assertThrows(IllegalArgumentException.class, () -> Decoration.BUTTON.at(5, 34));
        assertThrows(IllegalArgumentException.class,
            () -> Decoration.ARROW.at(90, 35, ResourceId.parse("minecraft:knowledge_book")));

        assertThat("a button placed with its item is fine",
            Decoration.BUTTON.at(5, 34, ResourceId.parse("minecraft:knowledge_book")).icon().isPresent(),
            is(true));
        assertThat("and every other mark is placed without one",
            Decoration.ARROW.at(90, 35).icon().isEmpty(), is(true));
    }

    @Test
    @DisplayName("every mark a shipped screen places sits inside its own panel")
    void everyMarkSitsInsideItsOwnPanel() {
        for (MenuScreen screen : MenuScreen.measured()) {
            MenuLayout layout = screen.layout(true);

            for (MenuLayout.Mark mark : layout.marks()) {
                Window.Extent extent = mark.kind().extent();

                assertThat(mark.kind() + " opens inside the panel", mark.x() >= 0 && mark.y() >= 0, is(true));
                assertThat(mark.kind() + " closes inside it",
                    mark.x() + extent.width() <= layout.width()
                        && mark.y() + extent.height() <= layout.height(), is(true));
            }
        }
    }

}
