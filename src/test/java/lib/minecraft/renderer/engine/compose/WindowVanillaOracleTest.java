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
 * Pins {@link Window.Vanilla#DRAW} against the container backgrounds the client ships.
 * <p>
 * The shipped art is the oracle, so this needs no stored baseline and no promotion: a container
 * background is the frame, a solid interior and a set of slot cells, and the assertion is that
 * painting those from rules reproduces the texture with no differing pixel. Four of the five
 * containers reproduce whole. The crafting table keeps a craft arrow that is not chrome, so its
 * residual is pinned by count and bounding box instead - a model change that starts or stops
 * reproducing chrome moves that number either way.
 */
@Tag("slow")
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("Window.Vanilla.DRAW reproduces the shipped container backgrounds")
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
     * One container background and the cells it carries.
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

    private static Container shulkerBox() {
        return new Container("minecraft:gui/container/shulker_box", 176, 166,
            cells(grid(NINE, new int[] { 17, 35, 53 }), grid(NINE, new int[] { 83, 101, 119, 141 })));
    }

    private static Container genericFiftyFour() {
        return new Container("minecraft:gui/container/generic_54", 176, 222,
            cells(grid(NINE, new int[] { 17, 35, 53, 71, 89, 107 }), grid(NINE, new int[] { 139, 157, 175, 197 })));
    }

    private static Container dispenser() {
        return new Container("minecraft:gui/container/dispenser", 176, 166,
            cells(grid(new int[] { 61, 79, 97 }, new int[] { 16, 34, 52 }), grid(NINE, new int[] { 83, 101, 119, 141 })));
    }

    private static Container hopper() {
        return new Container("minecraft:gui/container/hopper", 176, 133,
            cells(grid(new int[] { 43, 61, 79, 97, 115 }, new int[] { 19 }), grid(NINE, new int[] { 50, 68, 86, 108 })));
    }

    private static Container craftingTable() {
        return new Container("minecraft:gui/container/crafting_table", 176, 166,
            cells(grid(new int[] { 29, 47, 65 }, new int[] { 16, 34, 52 }),
                List.of(new int[] { 119, 30, 26 }),
                grid(NINE, new int[] { 83, 101, 119, 141 })));
    }

    private static PixelBuffer paint(Container container, int scale) {
        PixelBuffer buffer = PixelBuffer.create(container.width() * scale, container.height() * scale);
        Window window = Window.Vanilla.DRAW;

        window.paintPanel(buffer, new Window.Box(0, 0, container.width(), container.height(), scale), Window.Palette.VANILLA);
        for (int[] cell : container.cells())
            window.paintCell(buffer, new Window.Box(cell[0], cell[1], cell[2], cell[2], scale), Window.Palette.VANILLA);

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
    @DisplayName("the six-row chest reproduces whole")
    void sixRowChestReproducesWhole() {
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

    @Test
    @DisplayName("a cell at eighteen is the shipped slot sprite")
    void cellAtEighteenIsTheShippedSlotSprite() {
        Optional<PixelBuffer> resolved = textures.tryResolveTextureAtTick("minecraft:gui/sprites/container/slot", 0);
        assertThat("slot sprite resolves", resolved.isPresent(), is(true));

        PixelBuffer sprite = resolved.get();
        PixelBuffer painted = PixelBuffer.create(SLOT, SLOT);
        Window.Vanilla.DRAW.paintCell(painted, Window.Box.of(SLOT, SLOT), Window.Palette.VANILLA);

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
        Window.Extent minimum = Window.Vanilla.DRAW.minimum();

        PixelBuffer atFloor = PixelBuffer.create(minimum.width(), minimum.height());
        Window.Vanilla.DRAW.paintPanel(atFloor, Window.Box.of(minimum.width(), minimum.height()), Window.Palette.VANILLA);

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
        Window.Vanilla.DRAW.paintPanel(belowFloor, Window.Box.of(minimum.width() - 1, minimum.height()), Window.Palette.VANILLA);

        int under = 0;
        for (int y = 0; y < minimum.height(); y++)
            for (int x = 0; x < minimum.width(); x++)
                if (belowFloor.getPixel(x, y) != 0) under++;

        assertThat("a panel under the floor declines rather than painting a broken frame", under, is(equalTo(0)));
    }

}
