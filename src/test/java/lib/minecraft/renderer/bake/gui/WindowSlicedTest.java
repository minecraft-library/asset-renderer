package lib.minecraft.renderer.bake.gui;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.Resample;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition;
import lib.minecraft.renderer.bake.gui.chrome.ChromeSlicer;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.index.SubstitutionCollector;
import lib.minecraft.renderer.content.pack.MCMetaParser;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.gui.Mark;
import lib.minecraft.renderer.vanilla.gui.ScreenMetrics;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.font.MinecraftFont;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static lib.minecraft.renderer.fixture.TooltipFixtures.stubContext;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link Window.Sliced} - the arm that paints a panel from authored container art rather than by
 * drawing rules - against the client's blits of the sheets it ships at each screen's own size, holds
 * it to the drawn arm on the frame, and pins the GUI sprites it draws through {@link NineSliceKit}.
 * <p>
 * The client's blits are written here from its own draws rather than read off
 * {@link ScreenMetrics#regions()}, so a composition that agrees with them agrees with the client and
 * not with itself. Where the client draws over the sheet, the anvil is held to the client's frame in
 * every state its slots give - the sheet blit, the field sprite its first input picks and the cross
 * where the client draws one - and the crafting table to its sheet blit and the recipe-book button the
 * client draws over it.
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

    private static final String TEXT_FIELD = "minecraft:gui/sprites/container/anvil/text_field";

    private static final String TEXT_FIELD_DISABLED = "minecraft:gui/sprites/container/anvil/text_field_disabled";

    private static final String ERROR = "minecraft:gui/sprites/container/anvil/error";

    private static final String RECIPE_BUTTON = "minecraft:gui/sprites/recipe_book/button";

    /** the rows the client's margin takes below the last cell, which close a panel cut to fit */
    private static final int MARGIN_ROWS = 7;

    /** the output pixels a Minecraft pixel is laid at on a side */
    private static final int S = MinecraftFont.MC_PIXEL_SCALE;

    private static RendererContext textures;

    @BeforeAll
    static void resolveTextures() {
        textures = ClientAssetsExtension.context();
    }

    /**
     * One screen the client ships, the sheet it draws it from, the regions of that sheet it blits and the
     * GUI sprites it draws over them with every input empty.
     *
     * @param name what the assertions call it
     * @param sheet the sheet's texture id
     * @param screen the screen it is laid out as
     * @param blits the regions the client's {@code extractBackground} blits, each laid under the one before
     * @param sprites the GUI sprites the client draws over the sheet with every input empty and nothing
     *     hovered or focused - its {@code extractBackground}'s and its widgets' - each at its own size and
     *     keyed to where it opens on the panel
     * @param ownEnd the row the screen's own cells end on, which is where a panel without the player's
     *     section is cut
     */
    private record Shipped(String name, String sheet, ScreenMetrics screen, List<Rectangle> blits,
                           Map<String, Point> sprites, int ownEnd) {}

    /**
     * Lists every screen the client ships a sheet for, a chest at each row count it opens with.
     *
     * @return the shipped screens, each with the client's blits of its sheet
     */
    private static List<Shipped> shipped() {
        List<Shipped> out = new ArrayList<>();
        for (int rows = 1; rows <= 6; rows++) {
            out.add(new Shipped("a chest of " + rows + " rows", GENERIC_54, ScreenMetrics.chest(rows),
                List.of(new Rectangle(0, 0, 176, rows * 18 + 17), new Rectangle(0, 126, 176, 96)), Map.of(),
                rows * 18 + 17));
        }

        out.add(new Shipped("the shulker box", SHULKER_BOX, ScreenMetrics.shulkerBox(),
            List.of(new Rectangle(0, 0, 176, 167)), Map.of(), 71));
        out.add(new Shipped("the hopper", HOPPER, ScreenMetrics.hopper(),
            List.of(new Rectangle(0, 0, 176, 133)), Map.of(), 37));
        out.add(new Shipped("the dispenser", "minecraft:gui/container/dispenser", ScreenMetrics.dispenser(),
            List.of(new Rectangle(0, 0, 176, 166)), Map.of(), 70));
        // CraftingScreen's ImageButton blits recipe_book/button 20x18 at (leftPos + 5, height / 2 - 49),
        // (5, 34) on the panel, while neither hovered nor focused.
        out.add(new Shipped("the crafting table, against its sheet blit and the recipe-book button the client draws over it",
            CRAFTING_TABLE, ScreenMetrics.craftingTable(), List.of(new Rectangle(0, 0, 176, 166)),
            Map.of(RECIPE_BUTTON, new Point(5, 34)), 70));
        // AnvilScreen blits text_field_disabled at (59, 20), 110x16, while its first input is empty.
        out.add(new Shipped("the anvil, against its sheet blit and the field the client blits over it", ANVIL,
            ScreenMetrics.anvil(), List.of(new Rectangle(0, 0, 176, 166)), Map.of(TEXT_FIELD_DISABLED, new Point(59, 20)), 64));
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
        PixelBuffer out = PixelBuffer.straight(drawn.width(), drawn.height());

        int y = 0;
        for (Rectangle blit : shipped.blits()) {
            out.blit(sheet.crop(blit.x, blit.y, blit.width, blit.height), 0, y, BlendMode.REPLACE);
            y += blit.height;
        }

        return out;
    }

    /**
     * Lays the GUI sprites the client draws over a screen's sheet with every input empty and nothing
     * hovered or focused - its {@code extractBackground}'s and its widgets' - over its sheet blits, each at
     * its own size and as the client lays a GUI texel - the client's frame in that state.
     *
     * @param shipped the screen, the client's blits of its sheet and the sprites it blits over them
     * @return the panel the client draws with every input empty
     */
    private static PixelBuffer clientFrame(Shipped shipped) {
        PixelBuffer frame = clientBlits(shipped);
        shipped.sprites().forEach((sprite, at) -> frame.blit(resolve(sprite), at.x, at.y, BlendMode.NORMAL));
        return frame;
    }

    /** Resolves a shipped screen's sheet with no cell named, for the panel with or without the player's section. */
    private static Window.Sliced named(Shipped shipped, boolean playerSection) {
        return Window.Sliced.resolve(textures, ResourceId.parse(shipped.sheet()), Optional.empty(),
            shipped.screen(), MenuLayout.of(shipped.screen(), playerSection));
    }

    /**
     * Paints a window's panel, every cell and every mark of a layout at the font's scale, in the order a
     * menu paints its chrome, and reads the result back in Minecraft pixels.
     */
    private static PixelBuffer paint(Window window, MenuLayout layout) {
        PixelBuffer buffer = PixelBuffer.canvas(layout.width() * S, layout.height() * S);

        window.paintPanel(buffer, layout.box());
        for (ScreenMetrics.Cell cell : layout.cells())
            window.paintCell(buffer, Window.Box.of(cell));
        for (Mark.Placement mark : layout.marks())
            window.paintDecoration(buffer, Window.Box.of(mark), mark.kind());

        return readDown(buffer);
    }

    /** Reads a buffer painted at the font's scale back in Minecraft pixels, the first output pixel of each block. */
    private static PixelBuffer readDown(PixelBuffer painted) {
        return painted.resize(painted.width() / S, painted.height() / S, Resample.NEAREST);
    }

    /**
     * Counts the Minecraft pixels of the anvil's name-field rect that hold the sheet's flat red.
     *
     * @param panel a panel read in Minecraft pixels
     * @return how many of the rect's 110 x 16 pixels are {@code FFFF0000}
     */
    private static int flatRed(PixelBuffer panel) {
        int red = 0;
        for (int y = 20; y < 36; y++)
            for (int x = 59; x < 169; x++)
                if (panel.getPixel(x, y) == 0xFFFF0000) red++;

        return red;
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
    @DisplayName("named vanilla art at its screen's own size is the client's frame with every input empty, with no cell drawn over it")
    void namedArtAtItsOwnSizeIsTheClientsBlits() {
        for (Shipped shipped : shipped()) {
            MenuLayout layout = MenuLayout.of(shipped.screen(), true);
            Window.Sliced window = named(shipped, true);
            PixelBuffer painted = paint(window, layout);

            assertThat(shipped.name() + " reads no cell sprite", window.cellArt().isPresent(), is(false));
            assertThat(shipped.name() + " clears its own floor",
                window.minimum().width() <= layout.width() && window.minimum().height() <= layout.height(), is(true));
            assertThat(shipped.name() + " paints what the client draws",
                differing(painted, clientFrame(shipped)), is(equalTo(0)));
            if (shipped.sheet().equals(ANVIL))
                assertThat(shipped.name() + " shows none of the sheet's flat red", flatRed(painted), is(equalTo(0)));
        }

        // The shulker box blits a row more than its canvas holds, and its art leaves that row clear.
        PixelBuffer shulker = resolve(SHULKER_BOX);
        int drawn = 0;
        for (int x = 0; x < 176; x++)
            if (shulker.getPixel(x, 166) >>> 24 != 0) drawn++;

        assertThat("the shulker box's last blitted row draws nothing", drawn, is(equalTo(0)));
    }

    @Test
    @DisplayName("named anvil art in each state its slots give is the client's frame, reading only the sprites it draws")
    void namedAnvilArtInEachStateIsTheClientsFrame() {
        Shipped anvil = shipped(ANVIL);
        List<Integer> differing = new ArrayList<>();
        List<Set<Mark>> drawn = new ArrayList<>();
        List<Set<Mark>> read = new ArrayList<>();

        for (int state = 0; state < 8; state++) {
            int held = state;
            boolean first = (held & 1) != 0, second = (held & 2) != 0, result = (held & 4) != 0;
            ScreenMetrics screen = ScreenMetrics.anvil(slot -> (held >> slot & 1) != 0);
            MenuLayout layout = MenuLayout.of(screen, true);
            Window.Sliced window = Window.Sliced.resolve(textures, ResourceId.parse(ANVIL), Optional.empty(), screen, layout);

            // AnvilScreen blits text_field at (59, 20) while its first input holds an item and
            // text_field_disabled otherwise, and error at (99, 45) while either input holds one and the
            // result holds none.
            boolean cross = (first || second) && !result;
            PixelBuffer frame = clientBlits(anvil);
            frame.blit(resolve(first ? TEXT_FIELD : TEXT_FIELD_DISABLED), 59, 20, BlendMode.NORMAL);
            if (cross) frame.blit(resolve(ERROR), 99, 45, BlendMode.NORMAL);
            differing.add(differing(paint(window, layout), frame));

            Set<Mark> kinds = EnumSet.of(first ? Mark.FIELD : Mark.FIELD_DISABLED);
            if (cross) kinds.add(Mark.ERROR);
            drawn.add(kinds);
            read.add(EnumSet.copyOf(window.markArt().keySet()));
        }

        assertThat("Minecraft pixels from the client's frame, slot states 0 to 7 by bit (first input, second, result)",
            differing, is(equalTo(Collections.nCopies(8, 0))));
        assertThat("the marks a sprite is read for are the ones the client draws one at", read, is(equalTo(drawn)));
    }

    @Test
    @DisplayName("named crafting-table art reads the client's recipe-book button sprite at that mark alone, and its paint carries the button's icon")
    void namedCraftingTableArtReadsTheRecipeButtonSprite() {
        Window.Sliced window = named(shipped(CRAFTING_TABLE), true);

        assertThat("the button is the one mark a sprite is read for, the arrow being the art's",
            window.markArt().keySet(), is(equalTo(EnumSet.of(Mark.BUTTON))));
        assertThat("and it is the client's button as a screen opens, not its highlighted form",
            differing(window.markArt().get(Mark.BUTTON), resolve(RECIPE_BUTTON)), is(equalTo(0)));
        assertThat("drawn with the scaling the client gives a sidecar-less sprite",
            window.markScaling().get(Mark.BUTTON), is(equalTo(MCMeta.GuiScaling.DEFAULT)));
        assertThat("so the window's paint carries the button's icon", window.paintsIcon(Mark.BUTTON), is(true));
        assertThat("and carries none at a mark it draws no sprite at", window.paintsIcon(Mark.ARROW), is(false));
        assertThat("nor at the button where it draws no sprite there",
            Window.Sliced.of(window.panelArt(), Optional.empty(), Optional.empty(), Concurrent.newMap(), Concurrent.newMap())
                .paintsIcon(Mark.BUTTON), is(false));
        assertThat("while a theme's paint carries no icon",Window.Theme.VANILLA.paintsIcon(Mark.BUTTON), is(false));
    }

    @Test
    @DisplayName("named vanilla art without the player's section is the client's frame cut after the screen's own rows and closed by its last margin")
    void namedArtWithoutThePlayersSectionIsTheBlitsCutToFit() {
        for (Shipped shipped : shipped()) {
            MenuLayout layout = MenuLayout.of(shipped.screen(), false);
            assertThat(shipped.name() + "'s panel is its own rows and a margin",
                layout.height(), is(equalTo(shipped.ownEnd() + MARGIN_ROWS)));

            PixelBuffer frame = clientFrame(shipped);
            PixelBuffer cut = PixelBuffer.straight(frame.width(), layout.height());
            cut.blit(frame.crop(0, 0, frame.width(), shipped.ownEnd()), 0, 0, BlendMode.REPLACE);
            cut.blit(frame.crop(0, frame.height() - MARGIN_ROWS, frame.width(), MARGIN_ROWS), 0, shipped.ownEnd(), BlendMode.REPLACE);

            Window.Sliced window = named(shipped, false);
            PixelBuffer painted = paint(window, layout);
            assertThat(shipped.name() + " reads no cell sprite", window.cellArt().isPresent(), is(false));
            assertThat(shipped.name() + " paints the frame cut to fit", differing(painted, cut), is(equalTo(0)));
            if (shipped.sheet().equals(ANVIL))
                assertThat(shipped.name() + " shows none of the sheet's flat red", flatRed(painted), is(equalTo(0)));
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
        assertThat("and draws with the scaling the client gives a sidecar-less sprite",
            window.cellScaling(), is(equalTo(Optional.of(MCMeta.GuiScaling.DEFAULT))));
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
        assertThat("and draws with the scaling the client gives a sidecar-less sprite",
            resized.cellScaling(), is(equalTo(Optional.of(MCMeta.GuiScaling.DEFAULT))));
    }

    @Test
    @DisplayName("a cell sprite named over the crafting table's result cell is stretched as the client stretches a GUI sprite, at a size the client never draws it")
    void aNamedCellOverTheResultIsStretchedAsAGuiSprite() {
        ScreenMetrics screen = ScreenMetrics.craftingTable();
        MenuLayout layout = MenuLayout.of(screen, true);
        Window.Sliced window = Window.Sliced.resolve(textures, ResourceId.parse(CRAFTING_TABLE),
            Optional.of(ResourceId.parse(SLOT)), screen, layout);
        ScreenMetrics.Cell result = layout.cells().stream()
            .filter(cell -> cell.role() == ScreenMetrics.Role.RESULT)
            .findFirst()
            .orElseThrow();
        assertThat("the result cell is 26", result.size(), is(equalTo(26)));

        // A house choice with no client twin: the client draws container/slot on the mount screens
        // alone, and at 18, so no screen it shows holds a slot sprite at 26. Every GUI sprite takes the
        // one path, so the named slot is stretched over the cell as the client stretches a GUI sprite,
        // which is not the cell the theme draws there.
        Window.Box box = new Window.Box(0, 0, result.size(), result.size());
        PixelBuffer drawn = PixelBuffer.canvas(result.size() * S, result.size() * S);
        window.paintCell(drawn, box);

        PixelBuffer stretched = PixelBuffer.canvas(result.size() * S, result.size() * S);
        NineSliceKit.draw(stretched, resolve(SLOT), MCMeta.GuiScaling.DEFAULT, 0, 0, 26, 26, 1f);
        PixelBuffer theme = PixelBuffer.canvas(result.size() * S, result.size() * S);
        Window.Theme.VANILLA.paintCell(theme, box);

        assertThat("the cell is the slot stretched", differing(drawn, stretched), is(equalTo(0)));
        assertThat("output pixels from the theme's cell at 26", differing(drawn, theme), is(equalTo(194)));
    }

    @Test
    @DisplayName("a cell sprite authored at twice its size draws every texel")
    void aCellSpriteAuthoredAtTwiceItsSizeDrawsEveryTexel() {
        // Thirty-six texels a side, each a colour of its own, with no sidecar: a slot authored at twice
        // its size, which an output pixel a texel reproduces whole.
        PixelBuffer doubled = PixelBuffer.straight(36, 36);
        for (int y = 0; y < 36; y++)
            for (int x = 0; x < 36; x++)
                doubled.setPixel(x, y, 0xFF000000 | x << 16 | y << 8 | 0x80);
        String id = "minecraft:gui/sprites/container/doubled_slot";
        ScreenMetrics grid = ScreenMetrics.grid(1, 1);
        MenuLayout layout = MenuLayout.of(grid, false);
        Window.Sliced window = Window.Sliced.resolve(stubContext(Map.of(GENERIC_54, resolve(GENERIC_54), id, doubled), Map.of()),
            ResourceId.parse(GENERIC_54), Optional.of(ResourceId.parse(id)), grid, layout);
        ScreenMetrics.Cell cell = layout.cells().getFirst();
        assertThat("the cell is 18", cell.size(), is(equalTo(18)));

        PixelBuffer painted = PixelBuffer.canvas(cell.size() * S, cell.size() * S);
        window.paintCell(painted, new Window.Box(0, 0, cell.size(), cell.size()));

        assertThat(differing(painted, doubled), is(equalTo(0)));
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
        PixelBuffer small = PixelBuffer.straight(24, 24);
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
        PixelBuffer strip = PixelBuffer.straight(256, 512);
        strip.fill(first);
        PixelBuffer lower = PixelBuffer.straight(256, 256);
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
        Window.Box box = new Window.Box(0, 0, 176, 166);

        PixelBuffer fromArt = PixelBuffer.canvas(176 * S, 166 * S);
        sliced.paintPanel(fromArt, box);

        PixelBuffer fromRules = PixelBuffer.canvas(176 * S, 166 * S);
        Window.Theme.VANILLA.paintPanel(fromRules, box);

        // Only the frame ring - the drawn arm paints a plain interior where the art carries slots.
        int ring = 4 * S;
        int differingInFrame = 0;
        for (int y = 0; y < fromArt.height(); y++)
            for (int x = 0; x < fromArt.width(); x++) {
                boolean frame = x < ring || x >= fromArt.width() - ring || y < ring || y >= fromArt.height() - ring;
                if (frame && fromArt.getPixel(x, y) != fromRules.getPixel(x, y)) differingInFrame++;
            }

        assertThat(differingInFrame, is(equalTo(0)));
    }

    @Test
    @DisplayName("a cell drawn from the shipped slot sprite is what the drawn arm draws")
    void aSlicedCellMatchesTheDrawnCell() {
        Window window = Window.Sliced.of(PixelBuffer.straight(8, 8), Optional.of(resolve(SLOT)),
            Optional.of(MCMeta.GuiScaling.DEFAULT), Concurrent.newMap(), Concurrent.newMap());
        Window.Box box = new Window.Box(0, 0, 18, 18);

        PixelBuffer fromArt = PixelBuffer.canvas(18 * S, 18 * S);
        window.paintCell(fromArt, box);

        PixelBuffer fromRules = PixelBuffer.canvas(18 * S, 18 * S);
        Window.Theme.VANILLA.paintCell(fromRules, box);

        assertThat(differing(fromArt, fromRules), is(equalTo(0)));
    }

    @Test
    @DisplayName("a panel with no cell art paints no cell, and a GUI sprite is carried exactly with its scaling")
    void aPanelWithNoCellArtPaintsNoCell() {
        Window window = Window.Sliced.of(PixelBuffer.straight(8, 8), Optional.empty(), Optional.empty(),
            Concurrent.newMap(), Concurrent.newMap());

        PixelBuffer painted = PixelBuffer.canvas(18 * S, 18 * S);
        window.paintCell(painted, new Window.Box(0, 0, 18, 18));

        int written = 0;
        for (int y = 0; y < painted.height(); y++)
            for (int x = 0; x < painted.width(); x++)
                if (painted.getPixel(x, y) != 0) written++;

        assertThat(written, is(equalTo(0)));

        PixelBuffer slot = resolve(SLOT);
        assertThrows(IllegalArgumentException.class, () -> Window.Sliced.of(PixelBuffer.straight(8, 8),
            Optional.of(slot), Optional.empty(), Concurrent.newMap(), Concurrent.newMap()), "a cell sprite without its scaling");
        assertThrows(IllegalArgumentException.class, () -> Window.Sliced.of(PixelBuffer.straight(8, 8),
            Optional.empty(), Optional.empty(), Concurrent.newMap(Map.entry(Mark.FIELD, slot)), Concurrent.newMap()),
            "a field sprite without its scaling");

        // The pair is checked once, so the window keeps the maps it was built with rather than the
        // caller's, which could come apart after.
        ConcurrentMap<Mark, PixelBuffer> art = Concurrent.newMap(Map.entry(Mark.FIELD, slot));
        Window.Sliced held = Window.Sliced.of(PixelBuffer.straight(8, 8), Optional.empty(), Optional.empty(),
            art, Concurrent.newMap(Map.entry(Mark.FIELD, MCMeta.GuiScaling.DEFAULT)));
        art.remove(Mark.FIELD);
        assertThat("a mark's sprite stays with its scaling whatever the caller does with its map",
            held.markArt().keySet(), is(equalTo(held.markScaling().keySet())));
    }

    @Test
    @DisplayName("the sliced arm lays each Minecraft pixel of its art as a block of the font's scale")
    void theSlicedArmLaysEachMinecraftPixelAsABlock() {
        Shipped hopper = shipped(HOPPER);
        Window window = named(hopper, true);

        PixelBuffer painted = PixelBuffer.canvas(176 * S, 133 * S);
        window.paintPanel(painted, new Window.Box(0, 0, 176, 133));
        PixelBuffer read = readDown(painted);

        assertThat("every block is one Minecraft pixel",
            differing(painted, read.resize(painted.width(), painted.height(), Resample.NEAREST)), is(equalTo(0)));
        assertThat("and those are the client's blits", differing(read, clientBlits(hopper)), is(equalTo(0)));
    }

    @Test
    @DisplayName("a Minecraft pixel is laid as the client lays a GUI texel, by a box and by a panel cut from art")
    void aMinecraftPixelIsLaidAsTheClientLaysAGuiTexel() {
        // A translucent red, a clear texel carrying white, and an opaque green, over an opaque blue.
        int[] inks = { 0x80FF0000, 0x00FFFFFF, 0xFF00FF00 };
        int under = 0xFF0000FF;
        int[] laid = { ColorMath.blend(inks[0], under, BlendMode.NORMAL), under, inks[2] };
        Window.Box box = new Window.Box(0, 0, inks.length, 1);

        PixelBuffer byBox = PixelBuffer.canvas(inks.length * S, S);
        byBox.fill(under);
        for (int x = 0; x < inks.length; x++)
            box.put(byBox, x, 0, inks[x]);

        PixelBuffer art = PixelBuffer.straight(inks.length, 1);
        for (int x = 0; x < inks.length; x++)
            art.setPixel(x, 0, inks[x]);
        PixelBuffer byPanel = PixelBuffer.canvas(inks.length * S, S);
        byPanel.fill(under);
        Window.Sliced.of(art, Optional.empty(), Optional.empty(), Concurrent.newMap(), Concurrent.newMap())
            .paintPanel(byPanel, box);

        for (int y = 0; y < S; y++) {
            for (int x = 0; x < inks.length * S; x++) {
                assertThat("a box lays block " + x / S + " as the client does", byBox.getPixel(x, y), is(equalTo(laid[x / S])));
                assertThat("and so does a panel cut from art", byPanel.getPixel(x, y), is(equalTo(laid[x / S])));
            }
        }
    }

    @Test
    @DisplayName("a panel's sidecar is not read, a cell carries the gui.scaling its sidecar declares, and a cell whose gui section the client's codec refuses is the missing texture, reported unreadable")
    void aPanelsSidecarIsNotReadAndACellCarriesTheScalingItsSidecarDeclares() {
        // A 4-texel frame round a flat interior, so the depth derived from the art is not the declared 3.
        PixelBuffer art = PixelBuffer.straight(20, 20);
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
        SubstitutionCollector reported = new SubstitutionCollector();
        Window.Sliced fromRefused = Window.Sliced.resolve(
            stubContext(Map.of(id.id(), art), Map.of(id.id(), refused)).collecting(reported), id, Optional.of(id), grid, layout);
        ChromeDecomposition derived = ChromeSlicer.decompose(art, true);

        assertThat("the depths the art itself gives are not the declared ones", depths(derived), is(not(List.of(3, 3, 3, 3))));
        assertThat("the panel's depths are its own pixels' under a loaded section", depths(declared.panel()),
            is(depths(ChromeSlicer.decompose(declared.panelArt(), true))));
        assertThat("and are not the declared ones", depths(declared.panel()), is(not(List.of(3, 3, 3, 3))));

        // The cell is a GUI sprite, so the client's walk draws it by its whole scaling, the border taken
        // as the nine-slice declares it rather than as a hint to the slicer.
        assertThat("a cell carries its loaded section's scaling", declared.cellScaling(), is(equalTo(loaded.gui().toOptional())));
        Window.Box cellBox = new Window.Box(0, 0, 26, 26);
        PixelBuffer drawn = PixelBuffer.canvas(26 * S, 26 * S);
        declared.paintCell(drawn, cellBox);
        PixelBuffer nineSliced = PixelBuffer.canvas(26 * S, 26 * S);
        NineSliceKit.draw(nineSliced, art, loaded.gui().get(), 0, 0, 26, 26, 1f);
        PixelBuffer stretched = PixelBuffer.canvas(26 * S, 26 * S);
        NineSliceKit.draw(stretched, art, MCMeta.GuiScaling.DEFAULT, 0, 0, 26, 26, 1f);
        assertThat("and is drawn by it", differing(drawn, nineSliced), is(equalTo(0)));
        assertThat("which is not the art stretched", differing(drawn, stretched), is(not(equalTo(0))));

        // The client's GUI atlas holds no sprite whose gui section its codec refuses.
        assertThat("a cell whose section is refused is the missing texture",
            MissingSprite.isSprite(fromRefused.cellArt().orElseThrow()), is(true));
        assertThat("which stretches", fromRefused.cellScaling(), is(equalTo(Optional.of(MCMeta.GuiScaling.DEFAULT))));
        assertThat("and is reported unreadable, once, the panel read of the same id being served",
            reported.snapshot(), contains(Substitution.texture(id.id(), Possible.State.EMPTY)));
    }

    /**
     * Answers a decomposition's four band depths, left, top, right and bottom.
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
