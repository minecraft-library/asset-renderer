package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.compose.FramePlacement;
import lib.minecraft.renderer.engine.compose.MenuLayout;
import lib.minecraft.renderer.engine.compose.MenuScreen;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.engine.compose.Window;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.renderer.support.StubRendererContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Pins the geometry a menu renders at, over a context that supplies nothing.
 * <p>
 * A menu's chrome is painted from rules and reads no texture, so a panel with no populated slot needs
 * no assets at all - which is what lets the canvas, the cell grid and the ink be asserted on the
 * pixels themselves rather than on the arithmetic that produced them.
 */
@DisplayName("A menu renders at the geometry its screen lays out")
class MenuRendererGeometryTest {

    /** what one Minecraft pixel comes to on a side */
    private static final int SCALE = MenuRenderer.PX_SCALE;

    private static PixelBuffer render(MenuOptions options) {
        ImageData image = new MenuRenderer(StubRendererContext.builder().build()).render(options);
        return image.getFrames().getFirst().pixels();
    }

    private static MenuOptions chest(int rows, boolean playerInventory) {
        return MenuOptions.builder()
            .type(MenuOptions.Type.CHEST)
            .rows(rows)
            .playerInventory(playerInventory)
            .build();
    }

    @Test
    @DisplayName("the canvas is the laid-out panel at scale, and the player section is what changes it")
    void theCanvasIsTheLaidOutPanelAtScale() {
        PixelBuffer bare = render(chest(3, false));
        PixelBuffer banded = render(chest(3, true));

        assertThat("a container section alone",
            bare.width() + "x" + bare.height(), is(equalTo((176 * SCALE) + "x" + ((17 + 3 * 18 + 7) * SCALE))));
        assertThat("the panel the client draws",
            banded.width() + "x" + banded.height(), is(equalTo((176 * SCALE) + "x" + (167 * SCALE))));
    }

    @Test
    @DisplayName("the default is a container section, so a caller asks for the player's")
    void theDefaultIsAContainerSection() {
        assertThat(MenuOptions.defaults().isPlayerInventory(), is(equalTo(false)));
        assertThat(MenuOptions.defaults().getTheme(), is(equalTo(Window.Theme.VANILLA)));
    }

    @Test
    @DisplayName("every cell of the layout is painted where the layout put it")
    void everyCellIsPaintedWhereTheLayoutPutIt() {
        MenuOptions options = chest(3, true);
        PixelBuffer rendered = render(options);
        Window.Palette palette = Window.Palette.VANILLA;
        MenuLayout layout = MenuScreen.chest(3).layout(true);

        for (MenuLayout.Cell cell : layout.cells()) {
            int x = cell.x() * SCALE;
            int y = cell.y() * SCALE;
            int far = (cell.size() - 1) * SCALE;
            String at = "the cell at " + cell.x() + "," + cell.y();

            assertThat(at + " opens on its own shadow", rendered.getPixel(x, y), is(equalTo(palette.cellShadow())));
            assertThat(at + " closes on the light", rendered.getPixel(x + far, y + far), is(equalTo(palette.light())));
        }
    }

    @Test
    @DisplayName("a cell is eighteen Minecraft pixels of it and the pitch between two is the same")
    void aCellIsEighteenAndThePitchIsTheSame() {
        PixelBuffer rendered = render(chest(3, false));
        int origin = 7 * SCALE;
        int pitch = MenuScreen.CELL * SCALE;

        assertThat("the pixel before the second cell is the first one's light",
            rendered.getPixel(origin + pitch - 1, 18 * SCALE), is(equalTo(Window.Palette.VANILLA.light())));
        assertThat("and the second cell opens on its own shadow",
            rendered.getPixel(origin + pitch, 17 * SCALE), is(equalTo(Window.Palette.VANILLA.cellShadow())));
    }

    @Test
    @DisplayName("the panel is drawn in the theme's ink, and only the ink changes with it")
    void thePanelIsDrawnInTheThemesInk() {
        PixelBuffer vanilla = render(chest(3, false));
        PixelBuffer dark = render(chest(3, false).mutate().theme(Window.Theme.DARK).build());

        assertThat("the two panels are one size",
            List.of(dark.width(), dark.height()), is(equalTo(List.of(vanilla.width(), vanilla.height()))));

        int differing = 0;
        int agreeing = 0;
        for (int y = 0; y < vanilla.height(); y++) {
            for (int x = 0; x < vanilla.width(); x++) {
                boolean paintedAlike = (vanilla.getPixel(x, y) == 0) == (dark.getPixel(x, y) == 0);
                assertThat("both themes paint the same pixels", paintedAlike, is(true));
                if (vanilla.getPixel(x, y) == dark.getPixel(x, y)) agreeing++; else differing++;
            }
        }

        assertThat("and disagree about their colour", differing > 0, is(true));
        assertThat("the two share only what neither paints", agreeing > 0, is(true));
    }

    @Test
    @DisplayName("each type names the screen it is, and its cells are that screen's")
    void eachTypeNamesTheScreenItIs() {
        assertThat("a crafting table's grid sits left of centre and carries a result of 26",
            MenuRenderer.screenOf(MenuOptions.builder().type(MenuOptions.Type.VANILLA_CRAFTING).build()),
            is(equalTo(MenuScreen.craftingTable())));
        assertThat("an anvil's row is two inputs and a result at their own spacing",
            MenuRenderer.screenOf(MenuOptions.builder().type(MenuOptions.Type.VANILLA_ANVIL).build()),
            is(equalTo(MenuScreen.anvil())));
        assertThat("both SkyBlock menus are a chest of six",
            MenuRenderer.screenOf(MenuOptions.builder().type(MenuOptions.Type.SKYBLOCK_ANVIL).build()),
            is(equalTo(MenuScreen.chest(6))));
        assertThat("and a single slot is a panel one cell wide",
            MenuRenderer.layoutOf(MenuOptions.builder().type(MenuOptions.Type.SLOT).build()).width(),
            is(equalTo(2 * MenuScreen.MARGIN + MenuScreen.CELL)));
    }

    @Test
    @DisplayName("every cell holds sixteen Minecraft pixels of content, centred in whatever it is")
    void everyCellHoldsSixteenOfContentCentred() {
        ImageData nothing = Timeline.still(PixelBuffer.create(1, 1));
        FramePlacement ordinary = MenuRenderer.inCell(
            new MenuLayout.Cell(7, 17, MenuScreen.CELL, MenuLayout.Role.CONTAINER), nothing);
        FramePlacement result = MenuRenderer.inCell(
            new MenuLayout.Cell(119, 30, 26, MenuLayout.Role.RESULT), nothing);

        assertThat("the content is one size whatever holds it",
            MenuRenderer.CONTENT_PX, is(equalTo(16 * SCALE)));
        assertThat("a cell of eighteen centres it one Minecraft pixel in",
            List.of(ordinary.x(), ordinary.y()), is(equalTo(List.of(8 * SCALE, 18 * SCALE))));
        assertThat("and a crafting result of twenty-six centres the same sixteen five in",
            List.of(result.x(), result.y()), is(equalTo(List.of(124 * SCALE, 35 * SCALE))));
    }

    @Test
    @DisplayName("an item is drawn at the size a slot holds, not at its own default")
    void anItemIsDrawnAtTheSizeASlotHolds() {
        ItemOptions asked = ItemOptions.builder().itemId("minecraft:diamond").type(ItemOptions.Type.GUI_ICON).build();
        ItemOptions fitted = MenuRenderer.intoSlot(asked);

        assertThat("an item's own default is far larger than any cell",
            asked.getOutput().getCanvasSize(), is(equalTo(256)));
        assertThat("so the slot answers for it",
            fitted.getOutput().getCanvasSize(), is(equalTo(16 * SCALE)));
        assertThat("and nothing else the caller asked for is taken away",
            fitted.getItemId(), is(equalTo(asked.getItemId())));
        assertThat("the antialias the caller asked for included",
            fitted.getOutput().isAntiAlias(), is(equalTo(asked.getOutput().isAntiAlias())));
    }

    @Test
    @DisplayName("a caller's slot index reaches the cell the layout put at that index")
    void aSlotIndexReachesTheCellAtThatIndex() {
        MenuLayout crafting = MenuRenderer.layoutOf(MenuOptions.builder().type(MenuOptions.Type.VANILLA_CRAFTING).build());

        assertThat("nine grid cells and the result", crafting.slotCells().size(), is(equalTo(10)));
        assertThat("the result is the last of them",
            crafting.slotCells().getLast(), is(equalTo(new MenuLayout.Cell(119, 30, 26, MenuLayout.Role.RESULT))));

        MenuLayout anvil = MenuRenderer.layoutOf(MenuOptions.builder().type(MenuOptions.Type.VANILLA_ANVIL).build());
        assertThat("two inputs and a result", anvil.slotCells().size(), is(equalTo(3)));
        assertThat("the second input sits where the anvil's own menu declares it",
            anvil.slotCells().get(1).x(), is(equalTo(75)));
    }

}
