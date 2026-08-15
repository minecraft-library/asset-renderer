package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
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
 * and a second test pins the red so the reason stays visible.
 */
@Tag("slow")
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("Window.Theme.VANILLA reproduces the shipped container backgrounds")
class WindowVanillaOracleTest {

    /** the nine columns every full-width slot row sits at */
    private static final int[] NINE = { 7, 25, 43, 61, 79, 97, 115, 133, 151 };

    /** the vanilla slot cell's side, in Minecraft pixels */
    private static final int SLOT = 18;

    private static Textures textures;

    @BeforeAll
    static void resolveTextures() {
        textures = new Textures(ClientAssetsExtension.context());
    }

    /**
     * One container background and the layout it is drawn from.
     * <p>
     * Every screen the client blits whole is named by the screen the renderer lays it out as, so this
     * asserts the roster's own numbers against the art rather than against a second table beside it.
     * The chest sheet is the exception and carries its cells by hand: no screen is that shape, one
     * sheet serving every row count through a composition the client takes two draws over.
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
    private static Container of(String id, MenuScreen screen) {
        MenuLayout layout = screen.layout(true);
        return new Container(id, layout.width(), layout.height(),
            layout.cells().stream().map(cell -> new int[] { cell.x(), cell.y(), cell.size() }).toList());
    }

    private static Container shulkerBox() {
        return of("minecraft:gui/container/shulker_box", MenuScreen.shulkerBox());
    }

    private static Container genericFiftyFour() {
        return new Container("minecraft:gui/container/generic_54", 176, 222,
            cells(grid(NINE, new int[] { 17, 35, 53, 71, 89, 107 }), grid(NINE, new int[] { 139, 157, 175, 197 })));
    }

    private static Container dispenser() {
        return of("minecraft:gui/container/dispenser", MenuScreen.dispenser());
    }

    private static Container hopper() {
        return of("minecraft:gui/container/hopper", MenuScreen.hopper());
    }

    private static Container craftingTable() {
        return of("minecraft:gui/container/crafting_table", MenuScreen.craftingTable());
    }

    private static PixelBuffer paint(Container container, int scale) {
        PixelBuffer buffer = PixelBuffer.create(container.width() * scale, container.height() * scale);
        Window window = Window.Theme.VANILLA;

        window.paintPanel(buffer, new Window.Box(0, 0, container.width(), container.height(), scale));
        for (int[] cell : container.cells())
            window.paintCell(buffer, new Window.Box(cell[0], cell[1], cell[2], cell[2], scale));

        return buffer;
    }

    private static PixelBuffer shipped(Container container) {
        Optional<PixelBuffer> resolved = textures.tryResolveTextureAtTick(container.id(), 0);
        assertThat("shipped texture '" + container.id() + "' resolves", resolved.isPresent(), is(true));
        return resolved.get();
    }

    /** Counts pixels where the painted panel and the shipped texture disagree, over the drawn rect. */
    private static int differingPixels(Container container) {
        PixelBuffer painted = paint(container, 1);
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
        PixelBuffer painted = paint(container, 1);
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
        int split = rows * MenuScreen.CELL + 17;
        PixelBuffer out = PixelBuffer.create(176, split + 96);

        for (int y = 0; y < split; y++)
            for (int x = 0; x < 176; x++) out.setPixel(x, y, sheet.getPixel(x, y));

        for (int y = 0; y < 96; y++)
            for (int x = 0; x < 176; x++) out.setPixel(x, split + y, sheet.getPixel(x, 126 + y));

        return out;
    }

    @Test
    @DisplayName("every chest row count reproduces the panel the client composes for it")
    void everyChestRowCountReproducesItsComposedPanel() {
        for (int rows : new int[] { 1, 2, 3, 6 }) {
            MenuLayout layout = MenuScreen.chest(rows).layout(true);
            PixelBuffer painted = PixelBuffer.create(layout.width(), layout.height());
            Window window = Window.Theme.VANILLA;

            window.paintPanel(painted, layout.box(1));
            for (MenuLayout.Cell cell : layout.cells())
                window.paintCell(painted, cell.box(1));

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
        MenuLayout layout = MenuScreen.anvil().layout(true);
        PixelBuffer painted = PixelBuffer.create(layout.width(), layout.height());
        Window window = Window.Theme.VANILLA;

        window.paintPanel(painted, layout.box(1));
        for (MenuLayout.Cell cell : layout.cells())
            window.paintCell(painted, cell.box(1));
        for (MenuLayout.Mark mark : layout.marks())
            window.paintDecoration(painted, mark.box(1), mark.kind());

        Optional<PixelBuffer> resolved = textures.tryResolveTextureAtTick("minecraft:gui/container/anvil", 0);
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
    @DisplayName("the dead field really is flat red, so slicing that art would draw it")
    void theDeadFieldIsFlatRed() {
        Optional<PixelBuffer> resolved = textures.tryResolveTextureAtTick("minecraft:gui/container/anvil", 0);
        assertThat("the anvil texture resolves", resolved.isPresent(), is(true));
        PixelBuffer art = resolved.get();

        // This is what makes the field a decoration rather than something a sliced window could
        // supply: a Window.Sliced over this texture paints the panel as authored, and the panel as
        // authored has a red hole in it.
        int red = 0;
        for (int y = 20; y < 36; y++)
            for (int x = 59; x < 169; x++)
                if (art.getPixel(x, y) == 0xFFFF0000) red++;

        assertThat("every pixel of the field's rectangle", red, is(equalTo(110 * 16)));
    }

    @Test
    @DisplayName("the field decoration is the shipped text-field sprite")
    void theFieldDecorationIsTheShippedSprite() {
        Optional<PixelBuffer> resolved =
            textures.tryResolveTextureAtTick("minecraft:gui/sprites/container/anvil/text_field_disabled", 0);
        assertThat("the field sprite resolves", resolved.isPresent(), is(true));
        PixelBuffer sprite = resolved.get();

        Decoration field = Decoration.FIELD;
        int w = field.extent().width();
        int h = field.extent().height();

        // On a panel-filled buffer, because the well hands two corners over the way the frame's own
        // chamfers do - it leaves them for whatever it is painted over, and what the sprite has
        // there is the panel's own grey.
        PixelBuffer painted = PixelBuffer.create(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                painted.setPixel(x, y, Window.Palette.VANILLA.panel());
        Window.Theme.VANILLA.paintDecoration(painted, field.at(0, 0).box(1), field);

        int differing = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (painted.getPixel(x, y) != sprite.getPixel(x, y)) differing++;

        assertThat(differing, is(equalTo(0)));
    }

    @Test
    @DisplayName("the anvil's arrow is the crafting table's, so one mark serves both")
    void theAnvilsArrowIsTheCraftingTables() {
        Optional<PixelBuffer> anvil = textures.tryResolveTextureAtTick("minecraft:gui/container/anvil", 0);
        Optional<PixelBuffer> crafting = textures.tryResolveTextureAtTick("minecraft:gui/container/crafting_table", 0);
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
        Optional<PixelBuffer> resolved = textures.tryResolveTextureAtTick("minecraft:gui/sprites/container/slot", 0);
        assertThat("slot sprite resolves", resolved.isPresent(), is(true));

        PixelBuffer sprite = resolved.get();
        PixelBuffer painted = PixelBuffer.create(SLOT, SLOT);
        Window.Theme.VANILLA.paintCell(painted, Window.Box.of(SLOT, SLOT));

        int differing = 0;
        for (int y = 0; y < SLOT; y++)
            for (int x = 0; x < SLOT; x++)
                if (painted.getPixel(x, y) != sprite.getPixel(x, y)) differing++;

        assertThat(differing, is(equalTo(0)));
    }

    @Test
    @DisplayName("painting at scale replicates rather than resamples")
    void paintingAtScaleReplicatesRatherThanResamples() {
        Container container = shulkerBox();
        PixelBuffer one = paint(container, 1);
        PixelBuffer three = paint(container, 3);

        int differing = 0;
        for (int y = 0; y < container.height() * 3; y++)
            for (int x = 0; x < container.width() * 3; x++)
                if (three.getPixel(x, y) != one.getPixel(x / 3, y / 3)) differing++;

        assertThat(differing, is(equalTo(0)));
    }

    @Test
    @DisplayName("the minimum panel is the smallest one that paints a whole frame")
    void theMinimumPanelIsTheSmallestThatPaintsAWholeFrame() {
        Window.Extent minimum = Window.Theme.VANILLA.minimum();

        PixelBuffer atFloor = PixelBuffer.create(minimum.width(), minimum.height());
        Window.Theme.VANILLA.paintPanel(atFloor, Window.Box.of(minimum.width(), minimum.height()));

        int painted = 0;
        for (int y = 0; y < minimum.height(); y++)
            for (int x = 0; x < minimum.width(); x++)
                if (atFloor.getPixel(x, y) != 0) painted++;

        // The four corner blocks tile the whole panel at the floor, so the only untouched pixels are
        // the chamfers - and they come to 18 rather than a multiple of four, because the corners are
        // not rotations of one another: the top-left cuts 3 and the bottom-right 3, while the two
        // that hand the highlight over to the shadow cut 6 each.
        assertThat("painted at the floor", painted, is(equalTo(minimum.width() * minimum.height() - 18)));

        PixelBuffer belowFloor = PixelBuffer.create(minimum.width(), minimum.height());
        Window.Theme.VANILLA.paintPanel(belowFloor, Window.Box.of(minimum.width() - 1, minimum.height()));

        int under = 0;
        for (int y = 0; y < minimum.height(); y++)
            for (int x = 0; x < minimum.width(); x++)
                if (belowFloor.getPixel(x, y) != 0) under++;

        assertThat("a panel under the floor declines rather than painting a broken frame", under, is(equalTo(0)));
    }

}
