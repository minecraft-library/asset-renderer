package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.AnimatedImageData;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.StaticImageData;
import dev.simplified.image.frame.FrameBlend;
import dev.simplified.image.frame.FrameDisposal;
import dev.simplified.image.frame.ImageFrame;
import dev.simplified.image.pixel.Background;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.call.request.GridOptions;
import lib.minecraft.renderer.call.result.GridResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.call.slot.GridSlot;
import lib.minecraft.renderer.engine.frame.FrameCompositor;
import lib.minecraft.renderer.engine.frame.FrameLayer;
import lib.minecraft.renderer.engine.frame.FramePlacement;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.layer.LayerStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of a grid whose tiles are supplied: the grid asks each tile for its render as it draws it, and
 * the sheet holds the bytes a serial composition of the same renders holds.
 * <p>
 * The reference is written here rather than taken from the grid: a buffer filled with the background
 * and premultiplied, then each tile rescaled into its cell by {@link PixelBuffer#blitScaled} and laid
 * down with {@link BlendMode#PREMULTIPLIED}, one after another. Every tile is pixels built in memory,
 * mixing transparent, part-transparent and opaque texels, at the cell's size
 * and rescaled from a smaller and a larger one, over transparent, part-transparent and checkerboard
 * backgrounds, with and without separation, and at a cell large enough that a draw made off every
 * fork-join pool splits its rows across threads, while one made on a fork-join worker runs them in order
 * on that worker. Counting what an animated tile's content is asked shows which path drew the sheet: the
 * streamed pass asks it, finds it animated and hands the sheet to the held path, which asks it again,
 * while a grid held from the start asks it once.
 */
@DisplayName("A grid draws each supplied tile as it renders it")
class GridRendererSuppliedTileTest {

    private static final int COLUMNS = 3;

    /** Every background a sheet is drawn on: transparent, a transparent colour, part-transparent and two checkerboards. */
    private static final List<Background> BACKGROUNDS = List.of(
        Background.TRANSPARENT, Background.solid(0x00FF0000), Background.solid(0x80336699),
        Background.checkerboard(), Background.checkerboard(0xFF102030, 0x80405060, 3));

    private static final Substitution STONE = Substitution.texture("minecraft:block/stone", Possible.State.ABSENT);

    @Test
    @DisplayName("a streamed sheet holds the bytes of a serial composition, every tile asked once")
    void aStreamedSheetHoldsTheSerialComposition() {
        for (int cell : new int[] { 16, 64 })
            for (int separation : new int[] { 0, 4 })
                for (Background background : BACKGROUNDS) {
                    List<RenderResult> renders = renders(cell == 16 ? 8 : 24, cell);
                    AtomicIntegerArray asked = new AtomicIntegerArray(renders.size());
                    GridResult sheet = render(supplied(renders, asked), cell, separation, background, UnaryOperator.identity());

                    String label = "%d px cells, %d px apart, over %s".formatted(cell, separation, background);
                    assertArrayEquals(serial(renders, cell, separation, background), sheet.image().toPixelBuffer().data(), label + " pixels");
                    assertThat(label + " is static", sheet.image().isAnimated(), is(false));
                    assertAskedOnce(label, asked);
                }
    }

    @Test
    @DisplayName("each cell, in tile order, names the stand-ins its tile drew")
    void cellsNameTheirTilesStandIns() {
        List<RenderResult> renders = IntStream.range(0, 5)
            .mapToObj(i -> i % 2 == 0
                ? RenderResult.of(Timeline.still(pattern(16, 16, i)), List.of(STONE))
                : RenderResult.of(Timeline.still(pattern(16, 16, i))))
            .toList();
        GridResult sheet = render(supplied(renders, new AtomicIntegerArray(renders.size())), 16, 0, Background.TRANSPARENT, UnaryOperator.identity());

        assertThat("one cell per tile, in tile order", sheet.cells().stream().map(GridResult.Cell::index).toList(), contains(0, 1, 2, 3, 4));
        for (GridResult.Cell cell : sheet.cells())
            assertThat("cell " + cell.index(), cell.substitutions(), is(renders.get(cell.index()).substitutions()));
        assertThat("and the sheet names the stand-in once", sheet.substitutions(), contains(STONE));
    }

    @Test
    @DisplayName("a tile that answers animated has the whole sheet merged frame by frame, no tile asked more than twice")
    void anAnimatedTileMergesTheSheet() {
        List<RenderResult> renders = IntStream.range(0, 7)
            .mapToObj(i -> i == 4 ? RenderResult.of(animated(16, 3, 100, i)) : RenderResult.of(Timeline.still(pattern(16, 16, i))))
            .toList();
        AtomicIntegerArray asked = new AtomicIntegerArray(renders.size());
        Background background = Background.solid(0xFF203040);
        GridResult sheet = render(supplied(renders, asked), 16, 2, background, UnaryOperator.identity());

        ConcurrentList<FramePlacement> placements = Concurrent.newList();
        for (int i = 0; i < renders.size(); i++)
            placements.add(new FramePlacement(x(i, 16, 2), y(i, 16, 2), renders.get(i).image()).fittedTo(16, 16));
        ImageData merged = FrameCompositor.merge(placements, sheet.image().getWidth(), sheet.image().getHeight(), 30, background);

        assertThat("the sheet is animated", sheet.image().isAnimated(), is(true));
        assertThat("frame count", sheet.image().getFrames().size(), is(merged.getFrames().size()));
        for (int frame = 0; frame < merged.getFrames().size(); frame++)
            assertArrayEquals(merged.getFrames().get(frame).pixels().data(), sheet.image().getFrames().get(frame).pixels().data(), "frame " + frame);
        assertThat("the animated tile is asked by the streamed pass and again by the held path", asked.get(4), is(2));
        for (int i = 0; i < renders.size(); i++)
            assertThat("tile " + i + " asked", asked.get(i), is(lessThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("an animated tile on a decorated sheet is asked once, the grid holding every tile from the start")
    void anAnimatedTileOnADecoratedSheetIsAskedOnce() {
        List<RenderResult> renders = IntStream.range(0, 4)
            .mapToObj(i -> i == 2 ? RenderResult.of(animated(16, 2, 100, i)) : RenderResult.of(Timeline.still(pattern(16, 16, i))))
            .toList();
        AtomicIntegerArray asked = new AtomicIntegerArray(renders.size());
        GridResult sheet = render(supplied(renders, asked), 16, 0, Background.TRANSPARENT, stack -> stack.addAfter(GridSlot.CELL, sink -> {}));

        assertThat("the sheet is animated", sheet.image().isAnimated(), is(true));
        assertAskedOnce("decorated", asked);
    }

    @Test
    @DisplayName("two tiles at one cell are composed held, the later over the earlier, every tile asked once")
    void tilesSharingACellLandInListOrder() {
        List<RenderResult> renders = renders(4, 16);
        AtomicIntegerArray asked = new AtomicIntegerArray(renders.size());
        int[][] cells = { { 0, 0 }, { 1, 0 }, { 0, 0 }, { 2, 1 } };
        ConcurrentList<GridOptions.GridTile> tiles = Concurrent.newList();
        for (int i = 0; i < renders.size(); i++) {
            int index = i;
            tiles.add(new GridOptions.GridTile(cells[i][0], cells[i][1], () -> {
                asked.incrementAndGet(index);
                return renders.get(index);
            }));
        }
        GridResult sheet = render(tiles, 16, 0, Background.solid(0x80336699), UnaryOperator.identity());

        PixelBuffer expected = PixelBuffer.create(COLUMNS * 16, 3 * 16);
        Background.solid(0x80336699).fill(expected);
        expected.premultiplyAlpha();
        for (int i = 0; i < renders.size(); i++)
            expected.blitScaled(renders.get(i).image().toPixelBuffer(), cells[i][0] * 16, cells[i][1] * 16, 16, 16, BlendMode.PREMULTIPLIED);

        assertArrayEquals(expected.data(), sheet.image().toPixelBuffer().data(), "pixels");
        assertThat("one cell per tile, two at the same place", sheet.cells().stream().map(cell -> List.of(cell.col(), cell.row())).toList(),
            contains(List.of(0, 0), List.of(1, 0), List.of(0, 0), List.of(2, 1)));
        assertAskedOnce("shared cell", asked);
    }

    @Test
    @DisplayName("a decorated sheet is composed held, every tile asked once, the decorator's layer drawn with them")
    void aDecoratedSheetIsHeld() {
        List<RenderResult> renders = renders(5, 16);
        AtomicIntegerArray asked = new AtomicIntegerArray(renders.size());
        PixelBuffer badge = pattern(16, 16, 99);
        FrameLayer overlay = sink -> sink.add(new FramePlacement(x(5, 16, 0), y(5, 16, 0), Timeline.still(badge)));
        GridResult sheet = render(supplied(renders, asked), 16, 0, Background.TRANSPARENT, stack -> stack.addAfter(GridSlot.CELL, overlay));

        List<RenderResult> withBadge = new ArrayList<>(renders);
        withBadge.add(RenderResult.of(Timeline.still(badge)));
        assertArrayEquals(serial(withBadge, 16, 0, Background.TRANSPARENT), sheet.image().toPixelBuffer().data(), "pixels");
        assertAskedOnce("decorated", asked);
    }

    @Test
    @DisplayName("a tile the decorator drops still names its stand-ins, asked once")
    void aDroppedTileStillNamesItsStandIns() {
        List<RenderResult> renders = List.of(RenderResult.of(Timeline.still(pattern(16, 16, 1)), List.of(STONE)));
        AtomicIntegerArray asked = new AtomicIntegerArray(1);
        GridResult sheet = render(supplied(renders, asked), 16, 0, Background.solid(0xFF000000), stack -> new LayerStack<>());

        assertThat("the cell names its tile's stand-in", sheet.cells().getFirst().substitutions(), contains(STONE));
        assertThat("nothing is drawn", IntStream.of(sheet.image().toPixelBuffer().data()).allMatch(pixel -> pixel == 0xFF000000), is(true));
        assertAskedOnce("dropped", asked);
    }

    @Test
    @DisplayName("a sheet no buffer can hold is composed held, every tile asked before the allocation fails as it does today")
    void anUnallocatableSheetIsHeld() {
        List<RenderResult> renders = renders(3, 16);
        AtomicIntegerArray asked = new AtomicIntegerArray(renders.size());
        GridOptions options = GridOptions.builder()
            .tiles(supplied(renders, asked))
            .cellSize(16)
            .columns(COLUMNS)
            .rows(-1)
            .build();

        assertThrows(NegativeArraySizeException.class, () -> new GridRenderer().render(options));
        assertAskedOnce("unallocatable", asked);
    }

    @Test
    @DisplayName("a transparent background of any colour leaves the new buffer's transparent black")
    void aTransparentBackgroundIsLaidDownExactly() {
        for (int argb : new int[] { 0x00000000, 0x00FF0000 }) {
            GridResult sheet = render(Concurrent.newList(), 16, 0, Background.solid(argb), UnaryOperator.identity());
            assertThat("solid(0x%08X)".formatted(argb), IntStream.of(sheet.image().toPixelBuffer().data()).allMatch(pixel -> pixel == 0), is(true));
            assertThat(sheet.cells(), is(empty()));
        }
    }

    /**
     * Renders supplied tiles onto a grid of {@link #COLUMNS} columns and as many rows as they need.
     *
     * @param tiles the tiles
     * @param cell the cell size
     * @param separation the gap between cells
     * @param background the fill behind them
     * @param decorator the layer decorator
     * @return the sheet
     */
    private static @NotNull GridResult render(
        @NotNull ConcurrentList<GridOptions.GridTile> tiles,
        int cell,
        int separation,
        @NotNull Background background,
        @NotNull UnaryOperator<LayerStack<FrameLayer>> decorator
    ) {
        return new GridRenderer().render(GridOptions.builder()
            .tiles(tiles)
            .cellSize(cell)
            .columns(COLUMNS)
            .rows(Math.max(1, (tiles.size() + COLUMNS - 1) / COLUMNS + 1))
            .separation(separation)
            .background(background)
            .layerDecorator(decorator)
            .build());
    }

    /**
     * Builds tiles that answer the given renders, tile {@code i} at cell {@code i}, counting each time a
     * tile's content is asked.
     *
     * @param renders the renders
     * @param asked how many times each tile's content was asked, by tile index
     * @return the tiles
     */
    private static @NotNull ConcurrentList<GridOptions.GridTile> supplied(@NotNull List<RenderResult> renders, @NotNull AtomicIntegerArray asked) {
        ConcurrentList<GridOptions.GridTile> tiles = Concurrent.newList();
        for (int i = 0; i < renders.size(); i++) {
            int index = i;
            tiles.add(new GridOptions.GridTile(i % COLUMNS, i / COLUMNS, () -> {
                asked.incrementAndGet(index);
                return renders.get(index);
            }));
        }
        return tiles;
    }

    /**
     * Composes the reference: a buffer of the sheet's size filled with the background and premultiplied,
     * then each render rescaled into its cell, one after another.
     *
     * @param renders the renders, render {@code i} at cell {@code i}
     * @param cell the cell size
     * @param separation the gap between cells
     * @param background the fill behind them
     * @return the reference pixels
     */
    private static int @NotNull [] serial(@NotNull List<RenderResult> renders, int cell, int separation, @NotNull Background background) {
        int rows = Math.max(1, (renders.size() + COLUMNS - 1) / COLUMNS + 1);
        PixelBuffer sheet = PixelBuffer.create(COLUMNS * (cell + separation) + separation, rows * (cell + separation) + separation);
        background.fill(sheet);
        sheet.premultiplyAlpha();
        for (int i = 0; i < renders.size(); i++)
            sheet.blitScaled(renders.get(i).image().toPixelBuffer(), x(i, cell, separation), y(i, cell, separation), cell, cell, BlendMode.PREMULTIPLIED);
        return sheet.data();
    }

    private static int x(int index, int cell, int separation) {
        return (index % COLUMNS) * (cell + separation) + separation;
    }

    private static int y(int index, int cell, int separation) {
        return (index / COLUMNS) * (cell + separation) + separation;
    }

    /**
     * Asserts every tile's content was asked exactly once.
     *
     * @param label what was rendered
     * @param asked how many times each tile's content was asked
     */
    private static void assertAskedOnce(@NotNull String label, @NotNull AtomicIntegerArray asked) {
        for (int i = 0; i < asked.length(); i++)
            assertThat(label + " tile " + i + " asked", asked.get(i), is(1));
    }

    /**
     * Builds static renders: the cell's size, half of it and one and a half times it, and one flagged
     * opaque while holding transparent texels, in turn.
     *
     * @param count how many renders
     * @param cell the cell size
     * @return the renders
     */
    private static @NotNull List<RenderResult> renders(int count, int cell) {
        return IntStream.range(0, count)
            .mapToObj(i -> RenderResult.of(switch (i % 4) {
                case 0 -> Timeline.still(pattern(cell, cell, i));
                case 1 -> Timeline.still(pattern(cell / 2, cell / 2, i));
                case 2 -> Timeline.still(pattern(cell * 3 / 2, cell * 3 / 2, i));
                default -> StaticImageData.of(PixelBuffer.of(pattern(cell, cell, i).data(), cell, cell, false));
            }))
            .toList();
    }

    /**
     * Builds an animated image whose every frame is a distinct {@link #pattern}, each held for the same
     * delay and replacing the last whole.
     *
     * @param size the image's edge length
     * @param frameCount the number of frames
     * @param delayMs how long each frame is held
     * @param seed what sets this image's texels apart from another's
     * @return the animated image
     */
    private static @NotNull ImageData animated(int size, int frameCount, int delayMs, int seed) {
        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int frame = 0; frame < frameCount; frame++)
            builder.withFrame(ImageFrame.of(pattern(size, size, seed * 10 + frame), delayMs, 0, 0, FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE));
        return builder.build();
    }

    /**
     * Builds a buffer whose texels cycle through transparent, half-transparent and opaque, each a colour
     * hashed from its position and the seed.
     *
     * @param width the buffer width
     * @param height the buffer height
     * @param seed what sets one buffer's texels apart from another's
     * @return the buffer
     */
    private static @NotNull PixelBuffer pattern(int width, int height, int seed) {
        PixelBuffer buffer = PixelBuffer.create(width, height);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int mix = x * 31 + y * 17 + seed * 13;
                int alpha = switch (mix % 3) {
                    case 0 -> 0x00;
                    case 1 -> 0x80;
                    default -> 0xFF;
                };
                buffer.setPixel(x, y, (alpha << 24) | (((mix * 0x9E3779B1) >>> 8) & 0xFFFFFF));
            }
        return buffer;
    }

}
