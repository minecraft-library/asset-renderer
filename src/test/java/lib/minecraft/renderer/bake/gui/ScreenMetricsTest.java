package lib.minecraft.renderer.bake.gui;

import lib.minecraft.renderer.vanilla.gui.Mark;
import lib.minecraft.renderer.vanilla.gui.ScreenMetrics;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link ScreenMetrics} against the cell origins detected in the art the client ships.
 * <p>
 * The origins below were not read off this class. They were found by scanning each shipped container
 * background for an exact match of every cell size from 17 to 30 at every position, so a layout that
 * agrees with them agrees with vanilla rather than with itself. The drawn heights are the opaque
 * heights of the same textures.
 * <p>
 * A chest is scanned differently, because no sheet ships one: the client composes each row count it
 * opens from two draws out of {@code generic_54}, the second reading from a source row past the one
 * the first ended on, so the panel a player sees is a pixel shorter than the sheet and everything
 * below the container rows sits a pixel higher. The chest rows below were scanned on that
 * composition, which is what a render has to reproduce.
 * <p>
 * The regions a shipped screen composes its sheet from are the client's own blits, written from its
 * background draws rather than read off this class.
 */
@DisplayName("ScreenMetrics lays cells where vanilla drew them")
class ScreenMetricsTest {

    /** the nine columns every full-width row sits at, detected rather than assumed */
    private static final int[] NINE = { 7, 25, 43, 61, 79, 97, 115, 133, 151 };

    private static List<String> origins(MenuLayout layout, ScreenMetrics.Role role) {
        List<String> out = new ArrayList<>();
        for (ScreenMetrics.Cell cell : layout.cells())
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
        MenuLayout layout = MenuLayout.of(ScreenMetrics.chest(6), true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x221")));
        assertThat(origins(layout, ScreenMetrics.Role.CONTAINER),
            is(equalTo(grid(NINE, new int[] { 17, 35, 53, 71, 89, 107 }, 18))));
        assertThat(origins(layout, ScreenMetrics.Role.PLAYER_MAIN),
            is(equalTo(grid(NINE, new int[] { 138, 156, 174 }, 18))));
        assertThat(origins(layout, ScreenMetrics.Role.HOTBAR),
            is(equalTo(grid(NINE, new int[] { 196 }, 18))));
    }

    @Test
    @DisplayName("one sheet composes each chest row count the client opens, each with its own scanned rows")
    void oneSheetComposesEveryChestRowCount() {
        record Chest(int rows, int height, int[] container, int[] player, int hotbar) {}

        List<Chest> chests = List.of(
            new Chest(1, 131, new int[] { 17 }, new int[] { 48, 66, 84 }, 106),
            new Chest(2, 149, new int[] { 17, 35 }, new int[] { 66, 84, 102 }, 124),
            new Chest(3, 167, new int[] { 17, 35, 53 }, new int[] { 84, 102, 120 }, 142),
            new Chest(6, 221, new int[] { 17, 35, 53, 71, 89, 107 }, new int[] { 138, 156, 174 }, 196));

        for (Chest chest : chests) {
            MenuLayout layout = MenuLayout.of(ScreenMetrics.chest(chest.rows()), true);
            String at = "a chest of " + chest.rows() + " rows";

            assertThat(at + ", drawn height", layout.height(), is(equalTo(chest.height())));
            assertThat(at + ", its own cells",
                origins(layout, ScreenMetrics.Role.CONTAINER), is(equalTo(grid(NINE, chest.container(), 18))));
            assertThat(at + ", the player's",
                origins(layout, ScreenMetrics.Role.PLAYER_MAIN), is(equalTo(grid(NINE, chest.player(), 18))));
            assertThat(at + ", the hotbar",
                origins(layout, ScreenMetrics.Role.HOTBAR), is(equalTo(grid(NINE, new int[] { chest.hotbar() }, 18))));
        }
    }

    @Test
    @DisplayName("a three-row chest is not a shulker box, though both hold three rows of nine")
    void aThreeRowChestIsNotAShulkerBox() {
        MenuLayout chest = MenuLayout.of(ScreenMetrics.chest(3), true);
        MenuLayout shulker = MenuLayout.of(ScreenMetrics.shulkerBox(), true);

        assertThat("their own cells agree",
            origins(chest, ScreenMetrics.Role.CONTAINER), is(equalTo(origins(shulker, ScreenMetrics.Role.CONTAINER))));
        assertThat("and everything below them sits a pixel lower on the chest",
            chest.height() - shulker.height(), is(equalTo(1)));
    }

    @Test
    @DisplayName("the shulker box matches shulker_box")
    void shulkerBoxMatchesItsArt() {
        MenuLayout layout = MenuLayout.of(ScreenMetrics.shulkerBox(), true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x166")));
        assertThat(origins(layout, ScreenMetrics.Role.CONTAINER),
            is(equalTo(grid(NINE, new int[] { 17, 35, 53 }, 18))));
        assertThat(origins(layout, ScreenMetrics.Role.PLAYER_MAIN),
            is(equalTo(grid(NINE, new int[] { 83, 101, 119 }, 18))));
        assertThat(origins(layout, ScreenMetrics.Role.HOTBAR),
            is(equalTo(grid(NINE, new int[] { 141 }, 18))));
    }

    @Test
    @DisplayName("the hopper matches hopper, its row two pixels lower and inset from the nine")
    void hopperMatchesItsArt() {
        MenuLayout layout = MenuLayout.of(ScreenMetrics.hopper(), true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x133")));
        assertThat(origins(layout, ScreenMetrics.Role.CONTAINER),
            is(equalTo(grid(new int[] { 43, 61, 79, 97, 115 }, new int[] { 19 }, 18))));
        assertThat(origins(layout, ScreenMetrics.Role.PLAYER_MAIN),
            is(equalTo(grid(NINE, new int[] { 50, 68, 86 }, 18))));
        assertThat(origins(layout, ScreenMetrics.Role.HOTBAR),
            is(equalTo(grid(NINE, new int[] { 108 }, 18))));
    }

    @Test
    @DisplayName("the dispenser matches dispenser, its grid one pixel above a chest's")
    void dispenserMatchesItsArt() {
        MenuLayout layout = MenuLayout.of(ScreenMetrics.dispenser(), true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x166")));
        assertThat(origins(layout, ScreenMetrics.Role.CONTAINER),
            is(equalTo(grid(new int[] { 61, 79, 97 }, new int[] { 16, 34, 52 }, 18))));
    }

    @Test
    @DisplayName("the crafting table matches crafting_table, including a result cell of 26")
    void craftingTableMatchesItsArt() {
        MenuLayout layout = MenuLayout.of(ScreenMetrics.craftingTable(), true);

        assertThat("drawn size", layout.width() + "x" + layout.height(), is(equalTo("176x166")));
        assertThat("its columns are four pixels left of a centred grid's",
            origins(layout, ScreenMetrics.Role.CONTAINER),
            is(equalTo(grid(new int[] { 29, 47, 65 }, new int[] { 16, 34, 52 }, 18))));
        assertThat(origins(layout, ScreenMetrics.Role.RESULT), is(equalTo(List.of("119,30,26"))));
    }

    @Test
    @DisplayName("the crafting table is the one screen that does not centre its grid")
    void theCraftingTableIsTheOneScreenThatDoesNotCentre() {
        assertThat("a dispenser centres three columns", ScreenMetrics.dispenser().ownOriginX(),
            is(equalTo(ScreenMetrics.centred(3))));
        assertThat("a hopper centres five", ScreenMetrics.hopper().ownOriginX(),
            is(equalTo(ScreenMetrics.centred(5))));
        assertThat("a crafting table does not", ScreenMetrics.craftingTable().ownOriginX(),
            is(not(equalTo(ScreenMetrics.centred(3)))));
    }

    @Test
    @DisplayName("suppressing the player section drops it and shortens the panel to its own margin")
    void suppressingThePlayerSectionShortensThePanel() {
        MenuLayout with = MenuLayout.of(ScreenMetrics.chest(3), true);
        MenuLayout without = MenuLayout.of(ScreenMetrics.chest(3), false);

        assertThat("the container cells are unmoved",
            origins(without, ScreenMetrics.Role.CONTAINER), is(equalTo(origins(with, ScreenMetrics.Role.CONTAINER))));
        assertThat("no player cells", origins(without, ScreenMetrics.Role.PLAYER_MAIN).size(), is(equalTo(0)));
        assertThat("no hotbar", origins(without, ScreenMetrics.Role.HOTBAR).size(), is(equalTo(0)));
        assertThat("height is the top band, the rows and the margin",
            without.height(), is(equalTo(17 + 3 * 18 + 7)));
    }

    @Test
    @DisplayName("every measured screen is 176 wide, which is what nine cells and two margins come to")
    void everyMeasuredScreenIsOneSevenSix() {
        for (ScreenMetrics screen : ScreenMetrics.measured())
            assertThat(MenuLayout.of(screen, true).width(), is(equalTo(176)));
    }

    @Test
    @DisplayName("the smallest panel a chest fills is one cell inside its bands")
    void theSmallestChestPanelIsOneCellInsideItsBands() {
        assertThat("the top band, one cell and the bottom margin, by two margins and one cell across",
            MenuLayout.minimum(ScreenMetrics.chest(3)), is(equalTo(new Window.Extent(7 + 18 + 7, 17 + 18 + 7))));
        assertThat("a grid of any width answers the same, its floor being one cell rather than its own",
            MenuLayout.minimum(ScreenMetrics.grid(13, 19)), is(equalTo(MenuLayout.minimum(ScreenMetrics.chest(3)))));
        assertThat("a hopper's is wider, its row starting where a centred five does",
            MenuLayout.minimum(ScreenMetrics.hopper()).width(), is(equalTo(43 + 18 + 7)));
    }

    @Test
    @DisplayName("every measured screen clears the floor it declares, cells it places by hand included")
    void everyMeasuredScreenClearsItsOwnFloor() {
        for (ScreenMetrics screen : ScreenMetrics.measured()) {
            // The container section alone, which is the smaller of the two panels a screen lays out -
            // so a screen whose hand-placed cells fell off its own panel is caught here rather than
            // hidden by the player band's height.
            MenuLayout layout = MenuLayout.of(screen, false);
            Window.Extent floor = MenuLayout.minimum(screen);

            assertThat("a screen whose panel is " + layout.width() + "x" + layout.height() + " wide enough",
                layout.width() >= floor.width(), is(true));
            assertThat("and tall enough", layout.height() >= floor.height(), is(true));
        }
    }

    @Test
    @DisplayName("every shipped screen, a chest of one to six rows among them, composes its panel from exactly the regions of its sheet the client blits")
    void everyShippedScreenComposesItsPanelFromRegionsOfItsSheet() {
        // The client's own blits, from each screen's background draw: a chest's two out of generic_54,
        // and every other screen's one, whole, at the size it declares. The shulker box's 167 is a row
        // taller than the panel it draws, a row its art leaves clear.
        record Blits(String name, ScreenMetrics screen, List<Rectangle> regions) {}

        List<Blits> shipped = new ArrayList<>();
        for (int rows = 1; rows <= 6; rows++) {
            shipped.add(new Blits("a chest of " + rows + " rows", ScreenMetrics.chest(rows),
                List.of(new Rectangle(0, 0, 176, 17 + rows * 18), new Rectangle(0, 126, 176, 96))));
        }
        shipped.add(new Blits("the shulker box", ScreenMetrics.shulkerBox(), List.of(new Rectangle(0, 0, 176, 167))));
        shipped.add(new Blits("the hopper", ScreenMetrics.hopper(), List.of(new Rectangle(0, 0, 176, 133))));
        shipped.add(new Blits("the dispenser", ScreenMetrics.dispenser(), List.of(new Rectangle(0, 0, 176, 166))));
        shipped.add(new Blits("the crafting table", ScreenMetrics.craftingTable(), List.of(new Rectangle(0, 0, 176, 166))));
        shipped.add(new Blits("the anvil", ScreenMetrics.anvil(), List.of(new Rectangle(0, 0, 176, 166))));

        for (Blits blits : shipped) {
            assertThat(blits.name() + " composes the regions the client blits",
                new ArrayList<>(blits.screen().regions()), is(equalTo(blits.regions())));
        }
    }

    @Test
    @DisplayName("a screen the client ships no sheet for, a chest of other than one to six rows among them, composes none")
    void aScreenTheClientShipsNoSheetForComposesNone() {
        List<ScreenMetrics> unshipped = List.of(
            ScreenMetrics.grid(4, 9), ScreenMetrics.grid(3, 7), ScreenMetrics.chest(0), ScreenMetrics.chest(7));

        for (ScreenMetrics screen : unshipped)
            assertThat("a " + screen.ownRows() + " by " + screen.ownColumns() + " composes none", screen.regions().isEmpty(), is(true));
    }

    @Test
    @DisplayName("a slot index reaches the container's cells and its result, and nothing else")
    void aSlotIndexReachesTheContainersCellsAndItsResult() {
        assertThat("a container's own cell", ScreenMetrics.Role.CONTAINER.addressed(), is(true));
        assertThat("and the cell its output sits in", ScreenMetrics.Role.RESULT.addressed(), is(true));
        assertThat("the player's main inventory is drawn and never addressed",
            ScreenMetrics.Role.PLAYER_MAIN.addressed(), is(false));
        assertThat("as is the hotbar", ScreenMetrics.Role.HOTBAR.addressed(), is(false));

        // The two together are the slot space, so an anvil's three cells are what its caller can
        // reach and the twenty-seven below them are not.
        MenuLayout anvil = MenuLayout.of(ScreenMetrics.anvil(), true);
        assertThat("an anvil addresses three cells", anvil.slotCells().size(), is(equalTo(3)));
        assertThat("out of the thirty-nine it draws", anvil.cells().size(), is(equalTo(39)));
    }

    @Test
    @DisplayName("a mark carries an icon exactly when its kind draws one")
    void aMarkCarriesAnIconExactlyWhenItsKindDrawsOne() {
        // A kind and a position are two values now, so nothing about the type stops a button being
        // placed with no item on its face or an arrow being handed one. The guard is what says so,
        // and this is what says the guard is there.
        assertThrows(IllegalArgumentException.class, () -> Mark.BUTTON.at(5, 34));
        assertThrows(IllegalArgumentException.class,
            () -> Mark.ARROW.at(90, 35, ResourceId.parse("minecraft:knowledge_book")));

        assertThat("a button placed with its item is fine",
            Mark.BUTTON.at(5, 34, ResourceId.parse("minecraft:knowledge_book")).icon().isPresent(),
            is(true));
        assertThat("and every other mark is placed without one",
            Mark.ARROW.at(90, 35).icon().isEmpty(), is(true));
    }

    @Test
    @DisplayName("every mark a shipped screen places sits inside its own panel")
    void everyMarkSitsInsideItsOwnPanel() {
        for (ScreenMetrics screen : ScreenMetrics.measured()) {
            MenuLayout layout = MenuLayout.of(screen, true);

            for (Mark.Placement mark : layout.marks()) {
                Window.Extent extent = Window.Extent.of(mark.kind());

                assertThat(mark.kind() + " opens inside the panel", mark.x() >= 0 && mark.y() >= 0, is(true));
                assertThat(mark.kind() + " closes inside it",
                    mark.x() + extent.width() <= layout.width()
                        && mark.y() + extent.height() <= layout.height(), is(true));
            }
        }
    }

    @Test
    @DisplayName("the anvil places the field and the cross its slots call for, as the client's screen draws them")
    void theAnvilPlacesTheFieldAndTheCrossItsSlotsCallFor() {
        ScreenMetrics opened = ScreenMetrics.anvil();
        assertThat("the anvil as it opens is the anvil with every slot empty",
            opened, is(equalTo(ScreenMetrics.anvil(slot -> false))));

        for (int state = 0; state < 8; state++) {
            int held = state;
            boolean first = (held & 1) != 0, second = (held & 2) != 0, result = (held & 4) != 0;
            ScreenMetrics anvil = ScreenMetrics.anvil(slot -> (held >> slot & 1) != 0);
            String at = "an anvil holding " + (first ? "the first input " : "") + (second ? "the second input " : "")
                + (result ? "the result " : "") + (held == 0 ? "nothing " : "");

            // AnvilScreen blits text_field at (59, 20) while its first input holds an item and
            // text_field_disabled otherwise, and its error over the arrow at (99, 45) while either input
            // holds one and the result holds none - after the sheet, so after the arrow the sheet carries.
            List<Mark.Placement> marks = new ArrayList<>(List.of(Mark.HAMMER.at(17, 7),
                (first ? Mark.FIELD : Mark.FIELD_DISABLED).at(59, 20), Mark.PLUS.at(53, 49), Mark.ARROW.at(102, 48)));
            if ((first || second) && !result) marks.add(Mark.ERROR.at(99, 45));

            assertThat(at + "places the marks its slots call for", new ArrayList<>(anvil.marks()), is(equalTo(marks)));
            assertThat(at + "is otherwise the anvil as it opens",
                new ScreenMetrics(anvil.topBand(), anvil.labelBand(), anvil.declaredSlack(), anvil.ownRows(),
                    anvil.ownColumns(), anvil.ownOriginX(), anvil.panelColumns(), anvil.titleX(), anvil.extras(),
                    opened.marks(), anvil.regions()),
                is(equalTo(opened)));

            for (boolean playerSection : new boolean[] { true, false }) {
                MenuLayout layout = MenuLayout.of(anvil, playerSection);

                for (Mark.Placement mark : layout.marks()) {
                    Window.Extent extent = Window.Extent.of(mark.kind());
                    assertThat(at + "sits its " + mark.kind() + " inside its panel, the player's section "
                            + (playerSection ? "drawn" : "not"),
                        mark.x() >= 0 && mark.y() >= 0
                            && mark.x() + extent.width() <= layout.width()
                            && mark.y() + extent.height() <= layout.height(), is(true));
                }
            }
        }
    }

}
