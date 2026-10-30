package lib.minecraft.renderer.bake.gui;

import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.Resample;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition;
import lib.minecraft.renderer.bake.gui.chrome.ChromeSlicer;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.pack.MCMetaParser;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.gui.Mark;
import lib.minecraft.renderer.vanilla.gui.ScreenMetrics;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.fixture.TooltipFixtures.stubContext;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Pins {@link Window.Sliced} - the arm that paints a panel from authored container art rather than by
 * drawing rules - against the client's blits of the sheets it ships at each screen's own size, and
 * holds it to the drawn arm on the frame.
 * <p>
 * The client's blits are written here from its own draws rather than read off
 * {@link ScreenMetrics#regions()}, so a composition that agrees with them agrees with the client and
 * not with itself. Where the client draws a widget over the sheet - the crafting table's recipe-book
 * button, the anvil's name field - the panel is held to the sheet blits alone, the widget being no
 * part of the panel.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("Window.Sliced paints a panel from art")
class WindowSlicedTest {

    private static final String GENERIC_54 = "minecraft:gui/container/generic_54";

    private static final String SHULKER_BOX = "minecraft:gui/container/shulker_box";

    private static final String HOPPER = "minecraft:gui/container/hopper";

    private static final String CRAFTING_TABLE = "minecraft:gui/container/crafting_table";

    private static final String ANVIL = "minecraft:gui/container/anvil";

    private static final String SLOT = "minecraft:gui/sprites/container/slot";

    /** the rows the client's margin takes below the last cell, which close a panel cut to fit */
    private static final int MARGIN_ROWS = 7;

    private static RendererContext textures;

    @BeforeAll
    static void resolveTextures() {
        textures = ClientAssetsExtension.context();
    }

    /**
     * One screen the client ships, the sheet it draws it from and the regions of that sheet it blits.
     *
     * @param name what the assertions call it
     * @param sheet the sheet's texture id
     * @param screen the screen it is laid out as
     * @param blits the regions the client's {@code extractBackground} blits, each laid under the one before
     * @param ownEnd the row the screen's own cells end on, which is where a panel without the player's
     *     section is cut
     */
    private record Shipped(String name, String sheet, ScreenMetrics screen, List<Rectangle> blits, int ownEnd) {}

    /**
     * Lists every screen the client ships a sheet for, a chest at each row count it opens with.
     *
     * @return the shipped screens, each with the client's blits of its sheet
     */
    private static List<Shipped> shipped() {
        List<Shipped> out = new ArrayList<>();
        for (int rows = 1; rows <= 6; rows++) {
            out.add(new Shipped("a chest of " + rows + " rows", GENERIC_54, ScreenMetrics.chest(rows),
                List.of(new Rectangle(0, 0, 176, rows * 18 + 17), new Rectangle(0, 126, 176, 96)), rows * 18 + 17));
        }

        out.add(new Shipped("the shulker box", SHULKER_BOX, ScreenMetrics.shulkerBox(),
            List.of(new Rectangle(0, 0, 176, 167)), 71));
        out.add(new Shipped("the hopper", HOPPER, ScreenMetrics.hopper(),
            List.of(new Rectangle(0, 0, 176, 133)), 37));
        out.add(new Shipped("the dispenser", "minecraft:gui/container/dispenser", ScreenMetrics.dispenser(),
            List.of(new Rectangle(0, 0, 176, 166)), 70));
        out.add(new Shipped("the crafting table, against its sheet blits alone", CRAFTING_TABLE,
            ScreenMetrics.craftingTable(), List.of(new Rectangle(0, 0, 176, 166)), 70));
        out.add(new Shipped("the anvil, against its sheet blits alone", ANVIL, ScreenMetrics.anvil(),
            List.of(new Rectangle(0, 0, 176, 166)), 64));
        return out;
    }

    /**
     * Finds the shipped screen drawn from a sheet, the first where several share it.
     *
     * @param sheet the sheet's texture id
     * @return the screen and the client's blits of that sheet
     */
    private static Shipped shipped(String sheet) {
        return shipped().stream().filter(screen -> screen.sheet().equals(sheet)).findFirst().orElseThrow();
    }

    private static PixelBuffer resolve(String id) {
        Possible<PixelBuffer> found = Flipbook.atTick(textures.resolveTexture(id), textures.findFlipbook(id), 0);
        assertThat("texture '" + id + "' resolves", found.isPresent(), is(true));
        return found.get();
    }

    /**
     * Lays the client's blits of a screen's sheet top-down onto the panel it draws with the player's
     * section.
     *
     * @param shipped the screen and the client's blits of its sheet
     * @return the panel the client draws
     */
    private static PixelBuffer clientBlits(Shipped shipped) {
        PixelBuffer sheet = resolve(shipped.sheet());
        MenuLayout drawn = MenuLayout.of(shipped.screen(), true);
        PixelBuffer out = PixelBuffer.create(drawn.width(), drawn.height());

        int y = 0;
        for (Rectangle blit : shipped.blits()) {
            out.blit(sheet.crop(blit.x, blit.y, blit.width, blit.height), 0, y, BlendMode.REPLACE);
            y += blit.height;
        }

        return out;
    }

    /** Resolves a shipped screen's sheet with no cell named, for the panel with or without the player's section. */
    private static Window.Sliced named(Shipped shipped, boolean playerSection) {
        return Window.Sliced.resolve(textures, ResourceId.parse(shipped.sheet()), Optional.empty(),
            shipped.screen(), MenuLayout.of(shipped.screen(), playerSection));
    }

    /** Paints a window's panel, every cell and every mark of a layout at scale one, in the order a menu paints its chrome. */
    private static PixelBuffer paint(Window window, MenuLayout layout) {
        PixelBuffer buffer = PixelBuffer.create(layout.width(), layout.height());

        window.paintPanel(buffer, layout.box(1));
        for (ScreenMetrics.Cell cell : layout.cells())
            window.paintCell(buffer, Window.Box.of(cell, 1));
        for (Mark.Placement mark : layout.marks())
            window.paintDecoration(buffer, Window.Box.of(mark, 1), mark.kind());

        return buffer;
    }

    private static String size(PixelBuffer buffer) {
        return buffer.width() + "x" + buffer.height();
    }

    private static int differing(PixelBuffer a, PixelBuffer b) {
        assertThat("the two are one size", size(a), is(equalTo(size(b))));

        int count = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++;

        return count;
    }

    @Test
    @DisplayName("named vanilla art at its screen's own size is the client's blits, with no cell drawn over it")
    void namedArtAtItsOwnSizeIsTheClientsBlits() {
        for (Shipped shipped : shipped()) {
            MenuLayout layout = MenuLayout.of(shipped.screen(), true);
            Window.Sliced window = named(shipped, true);

            assertThat(shipped.name() + " reads no cell sprite", window.cellArt().isPresent(), is(false));
            assertThat(shipped.name() + " clears its own floor",
                window.minimum().width() <= layout.width() && window.minimum().height() <= layout.height(), is(true));
            assertThat(shipped.name() + " paints what the client blits",
                differing(paint(window, layout), clientBlits(shipped)), is(equalTo(0)));
        }

        // The shulker box blits a row more than its canvas holds, and its art leaves that row clear.
        PixelBuffer shulker = resolve(SHULKER_BOX);
        int drawn = 0;
        for (int x = 0; x < 176; x++)
            if (shulker.getPixel(x, 166) >>> 24 != 0) drawn++;

        assertThat("the shulker box's last blitted row draws nothing", drawn, is(equalTo(0)));
    }

    @Test
    @DisplayName("named vanilla art without the player's section is the client's blits cut after the screen's own rows and closed by their last margin")
    void namedArtWithoutThePlayersSectionIsTheBlitsCutToFit() {
        for (Shipped shipped : shipped()) {
            MenuLayout layout = MenuLayout.of(shipped.screen(), false);
            assertThat(shipped.name() + "'s panel is its own rows and a margin",
                layout.height(), is(equalTo(shipped.ownEnd() + MARGIN_ROWS)));

            PixelBuffer blits = clientBlits(shipped);
            PixelBuffer cut = PixelBuffer.create(blits.width(), layout.height());
            cut.blit(blits.crop(0, 0, blits.width(), shipped.ownEnd()), 0, 0, BlendMode.REPLACE);
            cut.blit(blits.crop(0, blits.height() - MARGIN_ROWS, blits.width(), MARGIN_ROWS), 0, shipped.ownEnd(), BlendMode.REPLACE);

            Window.Sliced window = named(shipped, false);
            assertThat(shipped.name() + " reads no cell sprite", window.cellArt().isPresent(), is(false));
            assertThat(shipped.name() + " paints the blits cut to fit", differing(paint(window, layout), cut), is(equalTo(0)));
        }
    }

    @Test
    @DisplayName("a cell sprite a caller names is drawn over the art at its own size")
    void aNamedCellIsDrawnOverTheArtAtItsOwnSize() {
        ScreenMetrics screen = ScreenMetrics.craftingTable();
        Window.Sliced window = Window.Sliced.resolve(textures, ResourceId.parse(CRAFTING_TABLE),
            Optional.of(ResourceId.parse(SLOT)), screen, MenuLayout.of(screen, true));

        assertThat("the named cell is read", window.cellArt().isPresent(), is(true));
        assertThat("and is the shipped slot", differing(window.cellArt().orElseThrow(), resolve(SLOT)), is(equalTo(0)));
    }

    @Test
    @DisplayName("a cell naming nothing is the client's slot over a panel resized for a screen it ships no sheet for, and nothing over a shipped screen's art")
    void anUnnamedCellIsTheSlotOnlyWhereTheArtIsResized() {
        ScreenMetrics craftingTable = ScreenMetrics.craftingTable();
        Window.Sliced bare = Window.Sliced.resolve(textures, ResourceId.parse(CRAFTING_TABLE), Optional.empty(),
            craftingTable, MenuLayout.of(craftingTable, false));
        assertThat("a shipped screen without its player's section reads none", bare.cellArt().isPresent(), is(false));

        ScreenMetrics grid = ScreenMetrics.grid(3, 7);
        Window.Sliced resized = Window.Sliced.resolve(textures, ResourceId.parse(GENERIC_54), Optional.empty(),
            grid, MenuLayout.of(grid, false));
        assertThat("a screen the client ships no sheet for reads one", resized.cellArt().isPresent(), is(true));
        assertThat("and it is the shipped slot", differing(resized.cellArt().orElseThrow(), resolve(SLOT)), is(equalTo(0)));
    }

    @Test
    @DisplayName("a sheet a nearest-neighbour upscale doubled is read back at the extent of the client's 256-texel space")
    void aDoubledSheetIsReadInTheClientsSheetSpace() {
        Shipped anvil = shipped(ANVIL);
        RendererContext doubled = stubContext(Map.of(ANVIL, resolve(ANVIL).resize(512, 512, Resample.NEAREST)), Map.of());
        Window.Sliced window = Window.Sliced.resolve(doubled, ResourceId.parse(ANVIL), Optional.empty(),
            anvil.screen(), MenuLayout.of(anvil.screen(), true));

        assertThat("the panel is the anvil's", size(window.panelArt()), is(equalTo("176x166")));
        assertThat("composed from the sheet as the shipped one is", differing(window.panelArt(), clientBlits(anvil)), is(equalTo(0)));
    }

    @Test
    @DisplayName("a screen the client ships no sheet for resizes the texture at its own size, cut to what it draws")
    void aScreenWithNoSheetResizesTheTexturesDrawnExtent() {
        ScreenMetrics grid = ScreenMetrics.grid(3, 7);
        MenuLayout layout = MenuLayout.of(grid, false);
        Window.Sliced window = Window.Sliced.resolve(textures, ResourceId.parse(GENERIC_54), Optional.empty(), grid, layout);

        assertThat("the panel is what the sheet draws", size(window.panelArt()), is(equalTo("176x222")));
        assertThat("and nothing else", differing(window.panelArt(), resolve(GENERIC_54).crop(0, 0, 176, 222)), is(equalTo(0)));

        // A 24x24 texture drawing a 20x20 square inside a clear ring. No client blit declares a texture
        // size for a screen it ships no sheet for, so the texture is not read in the 256-texel space.
        PixelBuffer small = PixelBuffer.create(24, 24);
        for (int y = 2; y < 22; y++)
            for (int x = 2; x < 22; x++)
                small.setPixel(x, y, 0xFF808080);
        Window.Sliced own = Window.Sliced.resolve(stubContext(Map.of(GENERIC_54, small), Map.of()),
            ResourceId.parse(GENERIC_54), Optional.empty(), grid, layout);

        assertThat("art of another size is read at its own, cut the same way", size(own.panelArt()), is(equalTo("20x20")));
    }

    @Test
    @DisplayName("a sheet carrying an animation is read whole, each row from where the client's coordinates fall on the strip")
    void anAnimatedSheetIsReadWhole() {
        // Two frames of one flat colour each, stacked into a 256x512 strip. The client reads a container
        // sheet as a plain texture, so the 256-texel space its blits declare spans the whole strip: sheet
        // row y is strip row 2y, the first frame above row 128 and the second from it on.
        int first = 0xFF336699, second = 0xFF996633;
        PixelBuffer strip = PixelBuffer.create(256, 512);
        strip.fill(first);
        PixelBuffer lower = PixelBuffer.create(256, 256);
        lower.fill(second);
        strip.blit(lower, 0, 256, BlendMode.REPLACE);

        RendererContext animated = stubContext(Map.of(HOPPER, strip), Map.of(),
            Map.of(HOPPER, new MCMeta.Animation(1, false, Optional.empty(), Optional.empty(), Optional.empty())));
        ScreenMetrics hopper = ScreenMetrics.hopper();
        Window.Sliced window = Window.Sliced.resolve(animated, ResourceId.parse(HOPPER), Optional.empty(),
            hopper, MenuLayout.of(hopper, true));
        PixelBuffer panel = window.panelArt();
        assertThat("the panel is the hopper's", size(panel), is(equalTo("176x133")));

        int misplaced = 0;
        for (int y = 0; y < panel.height(); y++)
            for (int x = 0; x < panel.width(); x++)
                if (panel.getPixel(x, y) != (y < 128 ? first : second)) misplaced++;

        assertThat("every row is the frame its strip row falls in", misplaced, is(equalTo(0)));
    }

    @Test
    @DisplayName("the sliced arm and the drawn arm agree on the frame vanilla ships")
    void theTwoArmsAgreeOnTheFrame() {
        Window sliced = named(shipped(SHULKER_BOX), true);

        PixelBuffer fromArt = PixelBuffer.create(176, 166);
        sliced.paintPanel(fromArt, Window.Box.of(176, 166));

        PixelBuffer fromRules = PixelBuffer.create(176, 166);
        Window.Theme.VANILLA.paintPanel(fromRules, Window.Box.of(176, 166));

        // Only the frame ring - the drawn arm paints a plain interior where the art carries slots.
        int differingInFrame = 0;
        for (int y = 0; y < 166; y++)
            for (int x = 0; x < 176; x++) {
                boolean frame = x < 4 || x >= 172 || y < 4 || y >= 162;
                if (frame && fromArt.getPixel(x, y) != fromRules.getPixel(x, y)) differingInFrame++;
            }

        assertThat(differingInFrame, is(equalTo(0)));
    }

    @Test
    @DisplayName("a cell sliced from the shipped slot sprite is what the drawn arm draws")
    void aSlicedCellMatchesTheDrawnCell() {
        Window window = Window.Sliced.of(PixelBuffer.create(8, 8), Optional.of(resolve(SLOT)), Optional.empty());

        PixelBuffer fromArt = PixelBuffer.create(18, 18);
        window.paintCell(fromArt, Window.Box.of(18, 18));

        PixelBuffer fromRules = PixelBuffer.create(18, 18);
        Window.Theme.VANILLA.paintCell(fromRules, Window.Box.of(18, 18));

        assertThat(differing(fromArt, fromRules), is(equalTo(0)));
    }

    @Test
    @DisplayName("a panel with no cell art paints no cell")
    void aPanelWithNoCellArtPaintsNoCell() {
        Window window = Window.Sliced.of(PixelBuffer.create(8, 8), Optional.empty(), Optional.empty());

        PixelBuffer painted = PixelBuffer.create(18, 18);
        window.paintCell(painted, Window.Box.of(18, 18));

        int written = 0;
        for (int y = 0; y < 18; y++)
            for (int x = 0; x < 18; x++)
                if (painted.getPixel(x, y) != 0) written++;

        assertThat(written, is(equalTo(0)));
    }

    @Test
    @DisplayName("the sliced arm replicates at scale rather than resampling")
    void theSlicedArmReplicatesAtScale() {
        Window window = named(shipped(HOPPER), true);

        PixelBuffer one = PixelBuffer.create(176, 133);
        window.paintPanel(one, Window.Box.of(176, 133));

        PixelBuffer two = PixelBuffer.create(352, 266);
        window.paintPanel(two, new Window.Box(0, 0, 176, 133, 2));

        int differing = 0;
        for (int y = 0; y < 266; y++)
            for (int x = 0; x < 352; x++)
                if (two.getPixel(x, y) != one.getPixel(x / 2, y / 2)) differing++;

        assertThat(differing, is(equalTo(0)));
    }

    @Test
    @DisplayName("a panel's sidecar is not read, and a cell takes the border its sidecar declares and none from a gui section the client's codec refuses")
    void aPanelsSidecarIsNotReadAndACellTakesTheBorderItsSidecarDeclares() {
        // A 4-texel frame round a flat interior, so the depth derived from the art is not the declared 3.
        PixelBuffer art = PixelBuffer.create(20, 20);
        art.fill(0xFF101010);
        for (int y = 4; y < 16; y++)
            for (int x = 4; x < 16; x++)
                art.setPixel(x, y, 0xFF808080);
        ResourceId id = ResourceId.parse("minecraft:gui/sprites/container/test_panel");
        MCMeta loaded = MCMetaParser.parse(
            "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":20,\"height\":20,\"border\":3}}}", id);
        // An object border missing its bottom side, which the client's codec refuses.
        MCMeta refused = MCMetaParser.parse(
            "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":20,\"height\":20,\"border\":{\"left\":3,\"top\":3,\"right\":3}}}}", id);

        // The same art named as both panel and cell, on a screen the client ships no sheet for.
        ScreenMetrics grid = ScreenMetrics.grid(1, 1);
        MenuLayout layout = MenuLayout.of(grid, false);
        Window.Sliced declared = Window.Sliced.resolve(
            stubContext(Map.of(id.id(), art), Map.of(id.id(), loaded)), id, Optional.of(id), grid, layout);
        Window.Sliced fromRefused = Window.Sliced.resolve(
            stubContext(Map.of(id.id(), art), Map.of(id.id(), refused)), id, Optional.of(id), grid, layout);
        ChromeDecomposition derived = ChromeSlicer.decompose(art, Optional.empty(), true);

        assertThat("the depths the art itself gives are not the declared ones", depths(derived), is(not(List.of(3, 3, 3, 3))));
        assertThat("the panel's depths are its own pixels' under a loaded section", depths(declared.panel()),
            is(depths(ChromeSlicer.decompose(declared.panelArt(), Optional.empty(), true))));
        assertThat("and are not the declared ones", depths(declared.panel()), is(not(List.of(3, 3, 3, 3))));
        assertThat("a cell's loaded section's border is the band depths", depths(declared.cell().orElseThrow()),
            is(depths(ChromeSlicer.decompose(art, Optional.of(new ChromeDecomposition.Border(3, 3, 3, 3)), true))));
        assertThat("a refused section declares none, so the cell's depths are derived",
            depths(fromRefused.cell().orElseThrow()), is(depths(derived)));
    }

    /**
     * Answers a decomposition's four band depths, left, top, right and bottom - the part of it a declared
     * border decides.
     *
     * @param decomposition the decomposition
     * @return its band depths
     */
    private static List<Integer> depths(ChromeDecomposition decomposition) {
        return List.of(decomposition.left(), decomposition.top(), decomposition.right(), decomposition.bottom());
    }

    @Test
    @DisplayName("the sliced arm's floor is where its own bands meet")
    void theSlicedArmFloorIsWhereItsBandsMeet() {
        ScreenMetrics doubleChest = ScreenMetrics.chest(6);
        Window.Sliced window = Window.Sliced.resolve(textures, ResourceId.parse(GENERIC_54), Optional.empty(),
            doubleChest, MenuLayout.of(doubleChest, true));

        assertThat(window.minimum().width(), is(equalTo(window.panel().left() + window.panel().right())));
        assertThat(window.minimum().height(), is(equalTo(window.panel().top() + window.panel().bottom())));
    }

}
