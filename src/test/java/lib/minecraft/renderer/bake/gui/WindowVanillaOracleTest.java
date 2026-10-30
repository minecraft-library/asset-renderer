package lib.minecraft.renderer.bake.gui;

import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.Resample;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.gui.Mark;
import lib.minecraft.renderer.vanilla.gui.ScreenMetrics;
import lib.minecraft.text.font.MinecraftFont;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Pins {@link Window.Theme#VANILLA} against the container backgrounds the client ships.
 * <p>
 * The shipped art is the oracle, so this needs no stored baseline and no promotion: a container
 * background is the frame, a solid interior and a set of slot cells, and the assertion is that
 * painting those from rules reproduces the texture with no differing pixel. Four of the six
 * containers reproduce whole on chrome alone. The crafting table keeps a craft arrow that is not
 * chrome, so its residual is pinned by count and bounding box instead - a model change that starts
 * or stops reproducing chrome moves that number either way.
 * <p>
 * The anvil is the one screen the art cannot fully answer for, so it is compared with its marks
 * drawn as well as its chrome. Everything the panel draws reproduces; what is left is the rectangle
 * of flat red the panel carries where its name field goes, which the client covers on every draw
 * and never once shows. That residual is the field being drawn from rules rather than read off art,
 * and a second test pins the red so the reason stays visible. Against the client's frame - the sheet,
 * the field sprite its first input picks and the cross where the client draws one - the theme paints
 * the anvil whole in every state its slots give, field and cross included.
 * <p>
 * The crafting table's recipe-book button is pinned beside them: the theme's bevel with the item it
 * carries is the client's button sprite.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("Window.Theme.VANILLA reproduces the shipped container backgrounds")
class WindowVanillaOracleTest {

    /** the nine columns every full-width slot row sits at */
    private static final int[] NINE = { 7, 25, 43, 61, 79, 97, 115, 133, 151 };

    /** the vanilla slot cell's side, in Minecraft pixels */
    private static final int SLOT = 18;

    /** the output pixels a Minecraft pixel is laid at on a side */
    private static final int S = MinecraftFont.MC_PIXEL_SCALE;

    private static final String ANVIL = "minecraft:gui/container/anvil";

    private static final String TEXT_FIELD = "minecraft:gui/sprites/container/anvil/text_field";

    private static final String TEXT_FIELD_DISABLED = "minecraft:gui/sprites/container/anvil/text_field_disabled";

    private static final String ERROR = "minecraft:gui/sprites/container/anvil/error";

    private static final String RECIPE_BUTTON = "minecraft:gui/sprites/recipe_book/button";

    private static final String KNOWLEDGE_BOOK = "minecraft:item/knowledge_book";

    private static RendererContext textures;

    @BeforeAll
    static void resolveTextures() {
        textures = ClientAssetsExtension.context();
    }

    /**
     * One container background and the layout it is drawn from.
     * <p>
     * Every screen the client blits whole is named by the screen the renderer lays it out as, so this
     * asserts the roster's own numbers against the art rather than against a second table beside it.
     * The chest sheet is the exception and carries its cells by hand: no screen is that shape, one
     * sheet serving each row count the client opens through a composition it takes two draws over.
     *
     * @param id the texture id, without the {@code textures/} prefix and the extension
     * @param width the drawn width in Minecraft pixels
     * @param height the drawn height in Minecraft pixels
     * @param cells every slot cell, as {@code x, y, side} triples
     */
    private record Container(String id, int width, int height, List<int[]> cells) {}

    private static List<int[]> grid(int[] xs, int[] ys) {
        return Arrays.stream(ys)
            .boxed()
            .flatMap(y -> Arrays.stream(xs).mapToObj(x -> new int[] { x, y, SLOT }))
            .toList();
    }

    @SafeVarargs
    private static List<int[]> cells(List<int[]>... parts) {
        return Arrays.stream(parts).flatMap(List::stream).toList();
    }

    /** The container the given screen is laid out as, with the player's section drawn. */
    private static Container of(String id, ScreenMetrics screen) {
        MenuLayout layout = MenuLayout.of(screen, true);
        return new Container(id, layout.width(), layout.height(),
            layout.cells().stream().map(cell -> new int[] { cell.x(), cell.y(), cell.size() }).toList());
    }

    private static Container shulkerBox() {
        return of("minecraft:gui/container/shulker_box", ScreenMetrics.shulkerBox());
    }

    private static Container genericFiftyFour() {
        return new Container("minecraft:gui/container/generic_54", 176, 222,
            cells(grid(NINE, new int[] { 17, 35, 53, 71, 89, 107 }), grid(NINE, new int[] { 139, 157, 175, 197 })));
    }

    private static Container dispenser() {
        return of("minecraft:gui/container/dispenser", ScreenMetrics.dispenser());
    }

    private static Container hopper() {
        return of("minecraft:gui/container/hopper", ScreenMetrics.hopper());
    }

    private static Container craftingTable() {
        return of("minecraft:gui/container/crafting_table", ScreenMetrics.craftingTable());
    }

    /** Paints a container's panel and every cell at the font's scale. */
    private static PixelBuffer paint(Container container) {
        PixelBuffer buffer = PixelBuffer.create(container.width() * S, container.height() * S);
        Window window = Window.Theme.VANILLA;

        window.paintPanel(buffer, new Window.Box(0, 0, container.width(), container.height()));
        for (int[] cell : container.cells())
            window.paintCell(buffer, new Window.Box(cell[0], cell[1], cell[2], cell[2]));

        return buffer;
    }

    /** Reads a buffer painted at the font's scale back in Minecraft pixels, the first output pixel of each block. */
    private static PixelBuffer readDown(PixelBuffer painted) {
        return painted.resize(painted.width() / S, painted.height() / S, Resample.NEAREST);
    }

    private static PixelBuffer shipped(Container container) {
        return texture(container.id());
    }

    /** Resolves a shipped texture at tick zero, asserting that it resolves. */
    private static PixelBuffer texture(String id) {
        Possible<PixelBuffer> resolved = Flipbook.atTick(textures.resolveTexture(id), textures.findFlipbook(id), 0);
        assertThat("shipped texture '" + id + "' resolves", resolved.isPresent(), is(true));
        return resolved.get();
    }

    /** Counts pixels where the painted panel and the shipped texture disagree, over the drawn rect. */
    private static int differingPixels(Container container) {
        PixelBuffer painted = readDown(paint(container));
        PixelBuffer art = shipped(container);
        int differing = 0;

        for (int y = 0; y < container.height(); y++)
            for (int x = 0; x < container.width(); x++)
                if (painted.getPixel(x, y) != art.getPixel(x, y)) differing++;

        return differing;
    }

    @Test
    @DisplayName("the shulker box reproduces whole")
    void shulkerBoxReproducesWhole() {
        assertThat(differingPixels(shulkerBox()), is(equalTo(0)));
    }

    @Test
    @DisplayName("the sheet every chest is composed out of reproduces whole")
    void theChestSheetReproducesWhole() {
        assertThat(differingPixels(genericFiftyFour()), is(equalTo(0)));
    }

    @Test
    @DisplayName("the dispenser reproduces whole, its own grid starting one pixel above the chest's")
    void dispenserReproducesWhole() {
        assertThat(differingPixels(dispenser()), is(equalTo(0)));
    }

    @Test
    @DisplayName("the hopper reproduces whole, its five-slot row inset from the nine")
    void hopperReproducesWhole() {
        assertThat(differingPixels(hopper()), is(equalTo(0)));
    }

    @Test
    @DisplayName("the crafting table leaves only its craft arrow")
    void craftingTableLeavesOnlyItsArrow() {
        Container container = craftingTable();
        PixelBuffer painted = readDown(paint(container));
        PixelBuffer art = shipped(container);

        int differing = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (int y = 0; y < container.height(); y++) {
            for (int x = 0; x < container.width(); x++) {
                if (painted.getPixel(x, y) == art.getPixel(x, y)) continue;
                differing++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }

        assertThat("residual pixel count", differing, is(equalTo(106)));
        assertThat("residual bounds", List.of(minX, minY, maxX, maxY), is(equalTo(List.of(90, 35, 111, 49))));
    }

    /**
     * Composes the chest sheet the way the client does - one draw of the container half, then a
     * second from a source row past the one the first ended on, which is what leaves the composed
     * panel a pixel shorter than the sheet.
     */
    private static PixelBuffer composedChest(int rows) {
        PixelBuffer sheet = shipped(genericFiftyFour());
        int split = rows * ScreenMetrics.CELL + 17;
        PixelBuffer out = PixelBuffer.create(176, split + 96);

        for (int y = 0; y < split; y++)
            for (int x = 0; x < 176; x++) out.setPixel(x, y, sheet.getPixel(x, y));

        for (int y = 0; y < 96; y++)
            for (int x = 0; x < 176; x++) out.setPixel(x, split + y, sheet.getPixel(x, 126 + y));

        return out;
    }

    @Test
    @DisplayName("each chest row count the client opens reproduces the panel the client composes for it")
    void everyChestRowCountReproducesItsComposedPanel() {
        for (int rows : new int[] { 1, 2, 3, 6 }) {
            MenuLayout layout = MenuLayout.of(ScreenMetrics.chest(rows), true);
            PixelBuffer chrome = PixelBuffer.create(layout.width() * S, layout.height() * S);
            Window window = Window.Theme.VANILLA;

            window.paintPanel(chrome, layout.box());
            for (ScreenMetrics.Cell cell : layout.cells())
                window.paintCell(chrome, Window.Box.of(cell));

            PixelBuffer painted = readDown(chrome);
            PixelBuffer art = composedChest(rows);
            assertThat("a chest of " + rows + " rows is as tall as its composition",
                layout.height(), is(equalTo(art.height())));

            int differing = 0;
            for (int y = 0; y < art.height(); y++)
                for (int x = 0; x < art.width(); x++)
                    if (painted.getPixel(x, y) != art.getPixel(x, y)) differing++;

            assertThat("a chest of " + rows + " rows reproduces whole", differing, is(equalTo(0)));
        }
    }

    @Test
    @DisplayName("the anvil leaves only the dead field its art carries and the client always covers")
    void theAnvilLeavesOnlyTheDeadFieldItsArtCarries() {
        MenuLayout layout = MenuLayout.of(ScreenMetrics.anvil(), true);
        PixelBuffer chrome = PixelBuffer.create(layout.width() * S, layout.height() * S);
        Window window = Window.Theme.VANILLA;

        window.paintPanel(chrome, layout.box());
        for (ScreenMetrics.Cell cell : layout.cells())
            window.paintCell(chrome, Window.Box.of(cell));
        for (Mark.Placement mark : layout.marks())
            window.paintDecoration(chrome, Window.Box.of(mark), mark.kind());

        PixelBuffer painted = readDown(chrome);
        Possible<PixelBuffer> resolved = Flipbook.atTick(textures.resolveTexture("minecraft:gui/container/anvil"), textures.findFlipbook("minecraft:gui/container/anvil"), 0);
        assertThat("the anvil texture resolves", resolved.isPresent(), is(true));
        PixelBuffer art = resolved.get();

        int differing = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (int y = 0; y < layout.height(); y++) {
            for (int x = 0; x < layout.width(); x++) {
                if (painted.getPixel(x, y) == art.getPixel(x, y)) continue;
                differing++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }

        // Everything the shipped panel draws reproduces - the frame, the interior, the three cells,
        // the hammer, the plus and the arrow. What is left is the panel's name field, which is a
        // rectangle of flat red the client covers with a blitted field on every draw and never once
        // shows, so the whole of it differs and that is the field being drawn rather than read.
        assertThat("residual pixel count", differing, is(equalTo(110 * 16)));
        assertThat("residual bounds", List.of(minX, minY, maxX, maxY), is(equalTo(List.of(59, 20, 168, 35))));
    }

    @Test
    @DisplayName("the dead field really is flat red, the hole the client covers with the field sprite")
    void theDeadFieldIsFlatRed() {
        Possible<PixelBuffer> resolved = Flipbook.atTick(textures.resolveTexture("minecraft:gui/container/anvil"), textures.findFlipbook("minecraft:gui/container/anvil"), 0);
        assertThat("the anvil texture resolves", resolved.isPresent(), is(true));
        PixelBuffer art = resolved.get();

        // This is the hole the client covers by blitting the field sprite over it on every draw, which
        // is why a sliced window draws that sprite there rather than showing the art.
        int red = 0;
        for (int y = 20; y < 36; y++)
            for (int x = 59; x < 169; x++)
                if (art.getPixel(x, y) == 0xFFFF0000) red++;

        assertThat("every pixel of the field's rectangle", red, is(equalTo(110 * 16)));
    }

    @Test
    @DisplayName("each field decoration is the shipped text-field sprite of its form")
    void theFieldDecorationIsTheShippedSprite() {
        Map<Mark, String> sprites = new EnumMap<>(Mark.class);
        sprites.put(Mark.FIELD, TEXT_FIELD);
        sprites.put(Mark.FIELD_DISABLED, TEXT_FIELD_DISABLED);
        Map<Mark, Integer> differing = new EnumMap<>(Mark.class);

        sprites.forEach((field, id) -> {
            PixelBuffer sprite = texture(id);
            int w = Window.Extent.of(field).width();
            int h = Window.Extent.of(field).height();

            // On a panel-filled buffer, because the well hands two corners over the way the frame's own
            // chamfers do - it leaves them for whatever it is painted over, and what the sprite has
            // there is the panel's own grey.
            PixelBuffer chrome = PixelBuffer.create(w * S, h * S);
            chrome.fill(Window.Palette.VANILLA.panel());
            Window.Theme.VANILLA.paintDecoration(chrome, Window.Box.of(field.at(0, 0)), field);
            PixelBuffer painted = readDown(chrome);

            int count = 0;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if (painted.getPixel(x, y) != sprite.getPixel(x, y)) count++;
            differing.put(field, count);
        });

        assertThat("Minecraft pixels from the sprite, each form", differing,
            is(equalTo(Map.of(Mark.FIELD, 0, Mark.FIELD_DISABLED, 0))));
    }

    @Test
    @DisplayName("the anvil in each state its slots give is the client's frame, its field and cross included")
    void theAnvilInEachStateItsSlotsGiveIsTheClientsFrame() {
        PixelBuffer sheet = texture(ANVIL);
        List<Integer> differing = new ArrayList<>();

        for (int state = 0; state < 8; state++) {
            int held = state;
            boolean first = (held & 1) != 0, second = (held & 2) != 0, result = (held & 4) != 0;
            MenuLayout layout = MenuLayout.of(ScreenMetrics.anvil(slot -> (held >> slot & 1) != 0), true);
            PixelBuffer chrome = PixelBuffer.create(layout.width() * S, layout.height() * S);
            Window window = Window.Theme.VANILLA;

            window.paintPanel(chrome, layout.box());
            for (ScreenMetrics.Cell cell : layout.cells())
                window.paintCell(chrome, Window.Box.of(cell));
            for (Mark.Placement mark : layout.marks())
                window.paintDecoration(chrome, Window.Box.of(mark), mark.kind());
            PixelBuffer painted = readDown(chrome);

            // The client's frame, from AnvilScreen's own draws: the sheet, then text_field at (59, 20)
            // while the first input holds an item and text_field_disabled otherwise, then error at
            // (99, 45) while either input holds one and the result holds none.
            PixelBuffer frame = PixelBuffer.create(176, 166);
            frame.blit(sheet.crop(0, 0, 176, 166), 0, 0, BlendMode.REPLACE);
            frame.blit(texture(first ? TEXT_FIELD : TEXT_FIELD_DISABLED), 59, 20, BlendMode.NORMAL);
            if ((first || second) && !result)
                frame.blit(texture(ERROR), 99, 45, BlendMode.NORMAL);

            int count = 0;
            for (int y = 0; y < frame.height(); y++)
                for (int x = 0; x < frame.width(); x++)
                    if (painted.getPixel(x, y) != frame.getPixel(x, y)) count++;
            differing.add(count);
        }

        assertThat("Minecraft pixels from the client's frame, slot states 0 to 7 by bit (first input, second, result)",
            differing, is(equalTo(Collections.nCopies(8, 0))));
    }

    @Test
    @DisplayName("the theme's recipe-book button with the knowledge book on its face is the client's sprite")
    void theThemesRecipeButtonIsTheClientsSprite() {
        Window.Extent extent = Window.Extent.of(Mark.BUTTON);
        int w = extent.width();
        int h = extent.height();

        // Both on a panel-filled buffer, because each leaves its chamfered corners to what it stands on.
        // The theme's bevel takes the item on its face as the menu lays an icon there.
        PixelBuffer chrome = PixelBuffer.create(w * S, h * S);
        chrome.fill(Window.Palette.VANILLA.panel());
        Window.Theme.VANILLA.paintDecoration(chrome, new Window.Box(0, 0, w, h), Mark.BUTTON);
        PixelBuffer theme = readDown(chrome);
        Mark.Inset face = Mark.BUTTON.iconInset().orElseThrow();
        theme.blit(texture(KNOWLEDGE_BOOK), face.x(), face.y(), BlendMode.NORMAL);

        PixelBuffer client = PixelBuffer.create(w, h);
        client.fill(Window.Palette.VANILLA.panel());
        client.blit(texture(RECIPE_BUTTON), 0, 0, BlendMode.NORMAL);

        Set<List<Integer>> differing = new HashSet<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (theme.getPixel(x, y) != client.getPixel(x, y)) differing.add(List.of(x, y));

        // The frame's two right-hand chamfers take the palette's ink for them, and the two face texels
        // closing the sprite's book are painted beneath the item, which leaves them clear.
        assertThat("the texels where the theme's button and its item are not the client's sprite", differing,
            is(empty()));
    }

    @Test
    @DisplayName("the anvil's arrow is the crafting table's, so one mark serves both")
    void theAnvilsArrowIsTheCraftingTables() {
        Possible<PixelBuffer> anvil = Flipbook.atTick(textures.resolveTexture("minecraft:gui/container/anvil"), textures.findFlipbook("minecraft:gui/container/anvil"), 0);
        Possible<PixelBuffer> crafting = Flipbook.atTick(textures.resolveTexture("minecraft:gui/container/crafting_table"), textures.findFlipbook("minecraft:gui/container/crafting_table"), 0);
        assertThat("both textures resolve", anvil.isPresent() && crafting.isPresent(), is(true));

        // The two screens place one kind at two positions, which is the whole reason a kind carries
        // its extent and a placement carries only where it sits.
        int differing = 0;
        for (int y = 0; y < 15; y++)
            for (int x = 0; x < 22; x++)
                if (anvil.get().getPixel(102 + x, 48 + y) != crafting.get().getPixel(90 + x, 35 + y)) differing++;

        assertThat(differing, is(equalTo(0)));
    }

    @Test
    @DisplayName("a cell at eighteen is the shipped slot sprite")
    void cellAtEighteenIsTheShippedSlotSprite() {
        Possible<PixelBuffer> resolved = Flipbook.atTick(textures.resolveTexture("minecraft:gui/sprites/container/slot"), textures.findFlipbook("minecraft:gui/sprites/container/slot"), 0);
        assertThat("slot sprite resolves", resolved.isPresent(), is(true));

        PixelBuffer sprite = resolved.get();
        PixelBuffer chrome = PixelBuffer.create(SLOT * S, SLOT * S);
        Window.Theme.VANILLA.paintCell(chrome, new Window.Box(0, 0, SLOT, SLOT));
        PixelBuffer painted = readDown(chrome);

        int differing = 0;
        for (int y = 0; y < SLOT; y++)
            for (int x = 0; x < SLOT; x++)
                if (painted.getPixel(x, y) != sprite.getPixel(x, y)) differing++;

        assertThat(differing, is(equalTo(0)));
    }

    @Test
    @DisplayName("painting at the font's scale lays each Minecraft pixel as a block")
    void paintingAtTheFontsScaleLaysEachMinecraftPixelAsABlock() {
        PixelBuffer painted = paint(shulkerBox());
        PixelBuffer blocks = readDown(painted).resize(painted.width(), painted.height(), Resample.NEAREST);

        int differing = 0;
        for (int y = 0; y < painted.height(); y++)
            for (int x = 0; x < painted.width(); x++)
                if (painted.getPixel(x, y) != blocks.getPixel(x, y)) differing++;

        assertThat(differing, is(equalTo(0)));
    }

    @Test
    @DisplayName("the minimum panel is the smallest one that paints a whole frame")
    void theMinimumPanelIsTheSmallestThatPaintsAWholeFrame() {
        Window.Extent minimum = Window.Theme.VANILLA.minimum();

        PixelBuffer chromeAtFloor = PixelBuffer.create(minimum.width() * S, minimum.height() * S);
        Window.Theme.VANILLA.paintPanel(chromeAtFloor, new Window.Box(0, 0, minimum.width(), minimum.height()));
        PixelBuffer atFloor = readDown(chromeAtFloor);

        int painted = 0;
        for (int y = 0; y < minimum.height(); y++)
            for (int x = 0; x < minimum.width(); x++)
                if (atFloor.getPixel(x, y) != 0) painted++;

        // The four corner blocks tile the whole panel at the floor, so the only untouched pixels are
        // the chamfers - and they come to 18 rather than a multiple of four, because the corners are
        // not rotations of one another: the top-left cuts 3 and the bottom-right 3, while the two
        // that hand the highlight over to the shadow cut 6 each.
        assertThat("painted at the floor", painted, is(equalTo(minimum.width() * minimum.height() - 18)));

        PixelBuffer chromeBelowFloor = PixelBuffer.create(minimum.width() * S, minimum.height() * S);
        Window.Theme.VANILLA.paintPanel(chromeBelowFloor, new Window.Box(0, 0, minimum.width() - 1, minimum.height()));
        PixelBuffer belowFloor = readDown(chromeBelowFloor);

        int under = 0;
        for (int y = 0; y < minimum.height(); y++)
            for (int x = 0; x < minimum.width(); x++)
                if (belowFloor.getPixel(x, y) != 0) under++;

        assertThat("a panel under the floor declines rather than painting a broken frame", under, is(equalTo(0)));
    }

}
