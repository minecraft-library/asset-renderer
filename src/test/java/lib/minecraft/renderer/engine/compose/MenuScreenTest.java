package lib.minecraft.renderer.engine.compose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Pins {@link MenuScreen} against the cell origins detected in the art the client ships.
 * <p>
 * The origins below were not read off this class. They were found by scanning each shipped container
 * background for an exact match of every cell size from 17 to 30 at every position, so a layout that
 * agrees with them agrees with vanilla rather than with itself. The drawn heights are the opaque
 * heights of the same textures.
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
    @DisplayName("the six-row chest matches generic_54")
    void sixRowChestMatchesGenericFiftyFour() {
        MenuLayout layout = MenuScreen.chest(6).layout(true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x222")));
        assertThat(origins(layout, MenuLayout.Role.CONTAINER),
            is(equalTo(grid(NINE, new int[] { 17, 35, 53, 71, 89, 107 }, 18))));
        assertThat(origins(layout, MenuLayout.Role.PLAYER_MAIN),
            is(equalTo(grid(NINE, new int[] { 139, 157, 175 }, 18))));
        assertThat(origins(layout, MenuLayout.Role.HOTBAR),
            is(equalTo(grid(NINE, new int[] { 197 }, 18))));
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

}
