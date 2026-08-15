package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.engine.compose.FramePlacement;
import lib.minecraft.renderer.engine.compose.MenuLayout;
import lib.minecraft.renderer.engine.compose.MenuScreen;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.engine.compose.Window;
import lib.minecraft.renderer.engine.kit.TextKit;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.renderer.support.StubRendererContext;
import lib.minecraft.text.ColorSegment;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static MenuScreen screenOf(MenuOptions.Type type) {
        return MenuRenderer.screenOf(MenuOptions.builder().type(type).build());
    }

    @Test
    @DisplayName("each type names the screen it is, and its cells are that screen's")
    void eachTypeNamesTheScreenItIs() {
        assertThat("a crafting table's grid sits left of centre and carries a result of 26",
            screenOf(MenuOptions.Type.CRAFTING_TABLE), is(equalTo(MenuScreen.craftingTable())));
        assertThat("an anvil's row is two inputs and a result at their own spacing",
            screenOf(MenuOptions.Type.ANVIL), is(equalTo(MenuScreen.anvil())));
        assertThat("a hopper's is one row of five",
            screenOf(MenuOptions.Type.HOPPER), is(equalTo(MenuScreen.hopper())));
        assertThat("a dispenser's is a centred three by three",
            screenOf(MenuOptions.Type.DISPENSER), is(equalTo(MenuScreen.dispenser())));
        assertThat("a shulker box is three rows of nine and is not a chest of three",
            screenOf(MenuOptions.Type.SHULKER_BOX), is(equalTo(MenuScreen.shulkerBox())));
        assertThat("the player's own view is four rows of nine",
            screenOf(MenuOptions.Type.PLAYER), is(equalTo(MenuScreen.grid(4, MenuScreen.COLUMNS))));
    }

    @Test
    @DisplayName("nine columns is the chest the client composes, and any other width is a plain grid")
    void nineColumnsIsTheChestTheClientComposes() {
        assertThat("a chest at its own width",
            MenuRenderer.screenOf(chest(3, false)), is(equalTo(MenuScreen.chest(3))));

        MenuOptions oneCell = MenuOptions.builder().type(MenuOptions.Type.CHEST).rows(1).columns(1).build();
        assertThat("and a panel there is no sheet to compose",
            MenuRenderer.screenOf(oneCell), is(equalTo(MenuScreen.grid(1, 1))));
        assertThat("which at one cell is a panel one cell wide",
            MenuRenderer.layoutOf(oneCell).width(), is(equalTo(2 * MenuScreen.MARGIN + MenuScreen.CELL)));
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
    @DisplayName("a title inks over the chrome and overpaints none of it")
    void aTitleInksOverTheChromeAndOverpaintsNoneOfIt() {
        MenuOptions bare = chest(3, true);
        PixelBuffer without = render(bare);
        PixelBuffer with = render(bare.mutate().title("Chest").build());
        int argb = bare.getDefaultTitleArgb();

        // A label is a layer of its own over a chrome layer that holds no ink, so the only pixels the
        // two renders can disagree about are the ones the label wrote - never a chrome pixel the
        // label's own draw happened to sit on.
        int differing = 0;
        for (int y = 0; y < without.height(); y++) {
            for (int x = 0; x < without.width(); x++) {
                if (without.getPixel(x, y) == with.getPixel(x, y)) continue;
                differing++;
                assertThat("the pixel at " + x + "," + y + " is the title's own ink",
                    with.getPixel(x, y), is(equalTo(argb)));
            }
        }

        assertThat("a title drew something", differing, is(greaterThan(0)));
    }

    /** Where a title of the given text starts, measured the way the renderer measures it. */
    private static MenuLayout.Anchor anchorOf(MenuLayout layout, String title) {
        return layout.titleAnchor(TextKit.measureLineMcPixels(ColorSegment.fromLegacy(title, '§')));
    }

    @Test
    @DisplayName("the labels start where the client starts them")
    void theLabelsStartWhereTheClientStartsThem() {
        // A fixed anchor ignores the title's own width, so the width handed in below is deliberately
        // not zero: a screen that centred would answer differently for it.
        int anyWidth = 40;

        assertThat("a container's title", MenuScreen.chest(6).layout(true).titleAnchor(anyWidth),
            is(equalTo(new MenuLayout.Anchor(8, 6))));
        assertThat("a crafting table's, past where its recipe tab would end",
            MenuScreen.craftingTable().layout(true).titleAnchor(anyWidth), is(equalTo(new MenuLayout.Anchor(29, 6))));
        assertThat("an anvil's", MenuScreen.anvil().layout(true).titleAnchor(anyWidth),
            is(equalTo(new MenuLayout.Anchor(60, 6))));
        assertThat("and a dispenser's, which is the one screen that centres rather than fixing it",
            MenuScreen.dispenser().layout(true).titleAnchor(anyWidth),
            is(equalTo(new MenuLayout.Anchor((176 - anyWidth) / 2, 6))));

        assertThat("the player's label, ninety-four above a six-row chest's declared bottom",
            MenuScreen.chest(6).layout(true).inventoryAnchor(),
            is(equalTo(Optional.of(new MenuLayout.Anchor(8, 128)))));
        // A shulker box declares 167 and draws 166, read off ShulkerBoxScreen's own (176, 167)
        // construction, so its label sits a pixel below where its drawn height alone would put it.
        assertThat("and above a shulker box's, which declares a pixel it never draws",
            MenuScreen.shulkerBox().layout(true).inventoryAnchor(),
            is(equalTo(Optional.of(new MenuLayout.Anchor(8, 73)))));
        assertThat("and a hopper's", MenuScreen.hopper().layout(true).inventoryAnchor(),
            is(equalTo(Optional.of(new MenuLayout.Anchor(8, 39)))));

        assertThat("a panel with no player section has no label for one",
            MenuScreen.chest(6).layout(false).inventoryAnchor(), is(equalTo(Optional.empty())));
    }

    @Test
    @DisplayName("a centred title reaches the ink and not just the anchor")
    void aCentredTitleReachesTheInk() {
        String title = "Dispenser";
        MenuOptions options = MenuOptions.builder().type(MenuOptions.Type.DISPENSER).title(title).build();
        MenuLayout layout = MenuRenderer.layoutOf(options);
        MenuLayout.Anchor anchor = anchorOf(layout, title);

        assertThat("the same title on a chest starts where every fixed one does",
            anchorOf(MenuScreen.chest(3).layout(false), title).x(), is(equalTo(8)));
        assertThat("and on a dispenser it starts half the slack in",
            anchor.x(), is(equalTo((layout.width() - TextKit.measureLineMcPixels(
                ColorSegment.fromLegacy(title, '§'))) / 2)));

        PixelBuffer rendered = render(options);
        assertThat("which is the column the leftmost glyph inks",
            firstInkColumn(rendered, options.getDefaultTitleArgb()), is(equalTo(anchor.x() * SCALE)));
    }

    /** The leftmost column carrying a pixel of the label colour. */
    private static int firstInkColumn(PixelBuffer buffer, int argb) {
        for (int x = 0; x < buffer.width(); x++)
            for (int y = 0; y < buffer.height(); y++)
                if (buffer.getPixel(x, y) == argb) return x;

        return -1;
    }

    @Test
    @DisplayName("the chrome is the theme where a caller names no art, and named art that is missing raises")
    void namedChromeArtRaisesWhereItIsMissing() {
        StubRendererContext context = StubRendererContext.builder().build();

        assertThat("naming nothing selects the theme's drawn geometry",
            MenuRenderer.windowOf(context, chest(3, false)), is(equalTo(Window.Theme.VANILLA)));
        assertThat("and the theme the caller chose, not a constant one",
            MenuRenderer.windowOf(context, chest(3, false).mutate().theme(Window.Theme.DARK).build()),
            is(equalTo(Window.Theme.DARK)));

        // Absence and failure are different states. A stub resolves no texture, so naming one is the
        // second, and painting the vanilla panel instead would make a broken pack look like a working
        // one.
        MenuOptions named = chest(3, false).mutate()
            .chromeSprite(Optional.of(ResourceId.parse("minecraft:gui/container/nothing_here")))
            .build();
        assertThrows(RenderException.class, () -> MenuRenderer.windowOf(context, named));
    }

    @Test
    @DisplayName("a menu draws at the one scale a title rasterises at, and refuses any other")
    void aMenuDrawsAtTheOneScaleATitleRasterisesAt() {
        assertThat("the default is that scale", MenuOptions.defaults().getPxScale(), is(equalTo(SCALE)));

        MenuOptions doubled = chest(3, false).mutate().pxScale(SCALE * 2).build();
        assertThrows(RenderException.class, () -> new MenuRenderer(StubRendererContext.builder().build()).render(doubled));
    }

    @Test
    @DisplayName("a caller who names no chrome art still names the cell art a sliced window would take")
    void theCellArtDefaultsToTheShippedSlot() {
        assertThat("no panel art", MenuOptions.defaults().getChromeSprite(), is(equalTo(Optional.empty())));
        assertThat("and the cell the client ships, for the window that would read it",
            MenuOptions.defaults().getCellSprite(),
            is(equalTo(Optional.of(ResourceId.parse("minecraft:gui/sprites/container/slot")))));
    }

    @Test
    @DisplayName("a label's ink opens on the row the client anchors it to")
    void aLabelsInkOpensOnItsAnchorRow() {
        MenuOptions options = chest(3, true).mutate().title("Chest").build();
        PixelBuffer rendered = render(options);
        MenuLayout layout = MenuRenderer.layoutOf(options);
        int argb = options.getDefaultTitleArgb();

        // The vanilla font's cells open on the top row of a capital - 'C' inks rows 0 through 6 of an
        // eight-row cell - so an anchor and the ink it produces are the same row and not an ascent
        // apart.
        int titleTop = firstInkRow(rendered, argb);
        assertThat("the title's first inked row", titleTop, is(equalTo(anchorOf(layout, "Chest").y() * SCALE)));

        int inventoryTop = firstInkRow(rendered, argb, layout.inventoryAnchor().orElseThrow().y() * SCALE - SCALE);
        assertThat("the player's label's",
            inventoryTop, is(equalTo(layout.inventoryAnchor().orElseThrow().y() * SCALE)));
    }

    /** The first row at or below {@code from} carrying a pixel of the label colour. */
    private static int firstInkRow(PixelBuffer buffer, int argb, int from) {
        for (int y = Math.max(0, from); y < buffer.height(); y++)
            for (int x = 0; x < buffer.width(); x++)
                if (buffer.getPixel(x, y) == argb) return y;

        return -1;
    }

    private static int firstInkRow(PixelBuffer buffer, int argb) {
        return firstInkRow(buffer, argb, 0);
    }

    @Test
    @DisplayName("a title draws no drop shadow, which is what the client's own label draws pass")
    void aTitleDrawsNoDropShadow() {
        PixelBuffer rendered = render(chest(3, true).mutate().title("Chest").build());

        // The shadow of the default label colour, by vanilla's own (rgb & 0xFCFCFC) >> 2. Nothing in
        // the panel's palette is this value, so one pixel of it would be a shadow and nothing else.
        int shadowOfDefault = 0xFF000000 | ((0x404040 & 0xFCFCFC) >> 2);
        int found = 0;
        for (int y = 0; y < rendered.height(); y++)
            for (int x = 0; x < rendered.width(); x++)
                if (rendered.getPixel(x, y) == shadowOfDefault) found++;

        assertThat("pixels of the title's shadow colour", found, is(equalTo(0)));
    }

    @Test
    @DisplayName("a title's format codes are the client's, so an ampersand is a character")
    void aTitleParsesOnTheSectionSign() {
        PixelBuffer ampersand = render(chest(1, false).mutate().title("&cRed").build());
        PixelBuffer section = render(chest(1, false).mutate().title("§cRed").build());

        int differing = 0;
        for (int y = 0; y < ampersand.height(); y++)
            for (int x = 0; x < ampersand.width(); x++)
                if (ampersand.getPixel(x, y) != section.getPixel(x, y)) differing++;

        assertThat("the one draws five characters where the other draws three in red",
            differing, is(greaterThan(0)));
    }

    @Test
    @DisplayName("a caller's slot index reaches the cell the layout put at that index")
    void aSlotIndexReachesTheCellAtThatIndex() {
        MenuLayout crafting = MenuRenderer.layoutOf(MenuOptions.builder().type(MenuOptions.Type.CRAFTING_TABLE).build());

        assertThat("nine grid cells and the result", crafting.slotCells().size(), is(equalTo(10)));
        assertThat("the result is the last of them",
            crafting.slotCells().getLast(), is(equalTo(new MenuLayout.Cell(119, 30, 26, MenuLayout.Role.RESULT))));

        MenuLayout anvil = MenuRenderer.layoutOf(MenuOptions.builder().type(MenuOptions.Type.ANVIL).build());
        assertThat("two inputs and a result", anvil.slotCells().size(), is(equalTo(3)));
        assertThat("the second input sits where the anvil's own menu declares it",
            anvil.slotCells().get(1).x(), is(equalTo(75)));
    }

    /**
     * A window whose art wants more room than any menu's content floor would ask for, which is what a
     * panel sliced from art with anchored features along its bands comes to.
     *
     * @param side the smallest extent it paints, on both axes
     */
    private record WideFrame(int side) implements Window {

        /** {@inheritDoc} */
        @Override
        public void paintPanel(@NotNull PixelBuffer dest, @NotNull Box box) {}

        /** {@inheritDoc} */
        @Override
        public void paintCell(@NotNull PixelBuffer dest, @NotNull Box box) {}

        /** {@inheritDoc} */
        @Override
        public @NotNull Extent minimum() {
            return new Extent(this.side, this.side);
        }

    }

    @Test
    @DisplayName("a window wanting more room than its panel has is refused on its own art")
    void aWindowWantingMoreRoomThanItsPanelIsRefused() {
        MenuOptions chest = chest(3, true);
        MenuLayout laid = MenuRenderer.layoutOf(chest);

        // 176 by 167 clears every content floor a chest has, so what refuses here is the art floor
        // alone - the arm a guard reading the layout's own floor would never reach.
        assertThat("a panel past every content floor", List.of(laid.width(), laid.height()),
            is(equalTo(List.of(176, 167))));
        assertThrows(RenderException.class, () -> MenuRenderer.validateExtent(new WideFrame(400), chest, laid));
        assertDoesNotThrow(() -> MenuRenderer.validateExtent(Window.Theme.VANILLA, chest, laid));
    }

    @Test
    @DisplayName("a panel with room for a frame but none for a cell is refused, which one floor admits")
    void aPanelWithRoomForNoCellIsRefused() {
        MenuRenderer renderer = new MenuRenderer(StubRendererContext.builder().build());
        MenuOptions noRows = MenuOptions.builder().type(MenuOptions.Type.CHEST).rows(0).build();
        MenuOptions noColumns = MenuOptions.builder().type(MenuOptions.Type.CHEST).rows(3).columns(0).build();

        // Vanilla's drawn geometry closes at eight Minecraft pixels square. Both of these clear that
        // on both axes and neither has room for a cell, so each is a panel the art floor would have
        // admitted and the two together are why the refusal reads both.
        assertThat("a chest of no rows is its two bands and nothing between them",
            MenuRenderer.layoutOf(noRows).height(), is(equalTo(17 + MenuScreen.MARGIN)));
        assertThat("and a grid of no columns is its two margins",
            MenuRenderer.layoutOf(noColumns).width(), is(equalTo(2 * MenuScreen.MARGIN)));

        assertThrows(RenderException.class, () -> renderer.render(noRows));
        assertThrows(RenderException.class, () -> renderer.render(noColumns));
    }

    /** One Minecraft pixel of a rendered panel, a negative index counting back from the far edge. */
    private static int mcPixel(PixelBuffer buffer, int x, int y) {
        int width = buffer.width() / SCALE;
        int height = buffer.height() / SCALE;
        return buffer.getPixel(Math.floorMod(x, width) * SCALE, Math.floorMod(y, height) * SCALE);
    }

    @Test
    @DisplayName("a panel past every size the client ships carries the same frame, the bars alone growing")
    void aPanelPastEveryShippedSizeCarriesTheSameFrame() {
        MenuOptions wide = MenuOptions.builder().type(MenuOptions.Type.CHEST).rows(13).columns(19).build();
        PixelBuffer rendered = render(wide);
        PixelBuffer shipped = render(chest(3, false));

        assertThat("nineteen cells across and thirteen down, in Minecraft pixels",
            (rendered.width() / SCALE) + "x" + (rendered.height() / SCALE),
            is(equalTo((2 * 7 + 19 * 18) + "x" + (17 + 13 * 18 + 7))));
        assertThat("and every one of them laid out",
            MenuRenderer.layoutOf(wide).cells().size(), is(equalTo(19 * 13)));

        // The frame is four corner blocks of four by four and four bars of a one-pixel period, so
        // widening the panel lengthens the bars and moves nothing else. Reading a width the client
        // ships no art for against one it does is what makes that falsifiable - a frame that
        // stretched, or that read its own extent anywhere, would disagree here.
        for (int y = -4; y < 4; y++)
            for (int x = -4; x < 4; x++)
                assertThat("the corner pixel at " + x + "," + y,
                    mcPixel(rendered, x, y), is(equalTo(mcPixel(shipped, x, y))));

        for (int depth = 0; depth < 4; depth++) {
            assertThat("the top bar at depth " + depth,
                mcPixel(rendered, 100, depth), is(equalTo(mcPixel(shipped, 100, depth))));
            assertThat("the bottom bar at depth " + depth,
                mcPixel(rendered, 100, -1 - depth), is(equalTo(mcPixel(shipped, 100, -1 - depth))));
            assertThat("the left bar at depth " + depth,
                mcPixel(rendered, depth, 40), is(equalTo(mcPixel(shipped, depth, 40))));
            assertThat("the right bar at depth " + depth,
                mcPixel(rendered, -1 - depth, 40), is(equalTo(mcPixel(shipped, -1 - depth, 40))));
        }

        // And the lattice reaches the far corner at its own pitch rather than at a stretched one.
        Window.Palette palette = Window.Palette.VANILLA;
        int lastX = MenuScreen.MARGIN + 18 * MenuScreen.CELL;
        int lastY = 17 + 12 * MenuScreen.CELL;
        assertThat("the last cell opens on its own shadow",
            mcPixel(rendered, lastX, lastY), is(equalTo(palette.cellShadow())));
        assertThat("and closes on the light",
            mcPixel(rendered, lastX + MenuScreen.CELL - 1, lastY + MenuScreen.CELL - 1),
            is(equalTo(palette.light())));
    }

    @Test
    @DisplayName("a slot past the cells the screen has is refused rather than clipped")
    void aSlotPastTheScreensCellsIsRefused() {
        ConcurrentMap<Integer, MenuOptions.MenuSlotContent> past = Concurrent.newMap();
        past.put(5, MenuOptions.MenuSlotContent.of("minecraft:diamond"));

        // A hopper is five cells, so index 5 is the first one it has not got - and the count comes off
        // the laid-out screen rather than a table beside it, so a screen cannot declare one and
        // address another.
        MenuOptions options = MenuOptions.builder().type(MenuOptions.Type.HOPPER).slots(past).build();
        assertThrows(RenderException.class, () -> new MenuRenderer(StubRendererContext.builder().build()).render(options));
    }

}
