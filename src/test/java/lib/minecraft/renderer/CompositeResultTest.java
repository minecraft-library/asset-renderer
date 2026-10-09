package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.call.request.GridOptions;
import lib.minecraft.renderer.call.request.LayoutOptions;
import lib.minecraft.renderer.call.result.GridResult;
import lib.minecraft.renderer.call.result.LayoutResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.engine.frame.Timeline;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the placements a grid and a layout answer: each tile or child is named at the rect it was
 * drawn into, keeps its own result whole, and only the placement whose render drew a stand-in names it.
 * <p>
 * Neither composer reads a context, so every part here is pixels built in memory, paired with the
 * stand-ins a render would have drawn through {@link RenderResult#of(ImageData, Collection)}. The grid's
 * tiles are drawn at its cell size, where the cell a static sheet scales a tile into and the extent an
 * animated one draws it at are the same rect.
 */
@DisplayName("A grid and a layout name where each part was drawn, and what each part drew")
class CompositeResultTest {

    private static final @NotNull Substitution HIDDEN = Substitution.texture("minecraft:block/stone", Possible.State.ABSENT);
    private static final int GREEN = 0xFF00FF00;
    private static final int MAGENTA = 0xFFFF00FF;

    @Test
    @DisplayName("of two grid tiles, only the one that drew a stand-in names it, each at the cell it was placed in")
    void onlyTheTileThatDrewAStandInNamesIt() {
        int cellSize = 16;
        int separation = 4;
        RenderResult plain = RenderResult.of(solid(cellSize, cellSize, GREEN));
        RenderResult hidden = RenderResult.of(solid(cellSize, cellSize, MAGENTA), List.of(HIDDEN));
        ConcurrentList<GridOptions.GridTile> tiles = Concurrent.newList();
        tiles.add(new GridOptions.GridTile(0, 0, plain));
        tiles.add(new GridOptions.GridTile(2, 1, hidden));

        GridResult grid = new GridRenderer().render(GridOptions.builder()
            .tiles(tiles)
            .cellSize(cellSize)
            .columns(3)
            .rows(2)
            .separation(separation)
            .build());

        assertThat("one cell per tile", grid.cells().size(), is(2));
        GridResult.Cell first = grid.cells().getFirst();
        GridResult.Cell last = grid.cells().getLast();
        int pitch = cellSize + separation;
        assertThat("the first tile's cell", rect(first), is(List.of(0, 0, 0, separation, separation, cellSize, cellSize)));
        assertThat("the second's, a pitch per column and row past the separation", rect(last),
            is(List.of(1, 2, 1, 2 * pitch + separation, pitch + separation, cellSize, cellSize)));

        assertThat("each cell keeps its tile's own result", first.result(), is(sameInstance(plain)));
        assertThat(last.result(), is(sameInstance(hidden)));
        assertThat("the plain tile's cell names nothing", first.result().substitutions(), is(empty()));
        assertThat("the hidden tile's names its texture", last.result().substitutions(), contains(HIDDEN));
        assertThat("and the sheet names it once", grid.substitutions(), contains(HIDDEN));

        PixelBuffer sheet = grid.image().getFrames().getFirst().pixels();
        assertThat("the plain tile is drawn at its cell", sheet.getPixel(first.x(), first.y()), is(GREEN));
        assertThat("and the hidden one at its own", sheet.getPixel(last.x(), last.y()), is(MAGENTA));
    }

    @Test
    @DisplayName("a layout keeps its children in append order, a child that is a grid keeping the grid's own result")
    void aLayoutChildThatIsAGridKeepsItsResult() {
        ConcurrentList<GridOptions.GridTile> tiles = Concurrent.newList();
        tiles.add(new GridOptions.GridTile(0, 0, RenderResult.of(solid(16, 16, MAGENTA), List.of(HIDDEN))));
        GridResult nested = new GridRenderer().render(GridOptions.builder().tiles(tiles).cellSize(16).build());
        RenderResult before = RenderResult.of(solid(8, 8, GREEN));

        LayoutResult layout = new LayoutRenderer().render(LayoutOptions.builder()
            .layout(new LayoutOptions.Layout.Row(4, LayoutOptions.Layout.Alignment.START))
            .child(before)
            .child(() -> nested)
            .child(solid(8, 8, GREEN))
            .build());

        assertThat("one child per appended child, in append order",
            layout.children().stream().map(LayoutResult.Child::index).toList(), contains(0, 1, 2));
        assertThat("a row places each child a padding past the last", layout.children().stream().map(CompositeResultTest::rect).toList(),
            is(List.of(List.of(0, 4, 4, 8, 8), List.of(1, 16, 4, 16, 16), List.of(2, 36, 4, 8, 8))));
        assertThat("the first child keeps the result handed in", layout.children().getFirst().result(), is(sameInstance(before)));

        RenderResult middle = layout.children().get(1).result();
        assertThat("the grid child keeps the grid's own result", middle, is(sameInstance(nested)));
        assertThat(middle, is(instanceOf(GridResult.class)));
        assertThat("whose cell still names the stand-in its tile drew",
            ((GridResult) middle).cells().getFirst().result().substitutions(), contains(HIDDEN));
        assertThat("pixels placed as an image carry no stand-in",
            layout.children().getLast().result().substitutions(), is(empty()));
        assertThat("and the layout names the grid's stand-in once", layout.substitutions(), contains(HIDDEN));
    }

    @Test
    @DisplayName("a custom layout names each child at its own position, padded, and at the size it measured")
    void aCustomLayoutNamesItsPaddedPositions() {
        int padding = 3;
        ConcurrentList<LayoutOptions.Layout.Custom.Position> positions = Concurrent.newList();
        positions.add(new LayoutOptions.Layout.Custom.Position(0, 0));
        positions.add(new LayoutOptions.Layout.Custom.Position(20, 5));

        LayoutResult layout = new LayoutRenderer().render(LayoutOptions.builder()
            .layout(new LayoutOptions.Layout.Custom(padding, LayoutOptions.Layout.Alignment.START, positions))
            .child(RenderResult.of(solid(10, 6, GREEN)))
            .child(RenderResult.of(solid(4, 12, MAGENTA), List.of(HIDDEN)))
            .build());

        LayoutResult.Child first = layout.children().getFirst();
        LayoutResult.Child last = layout.children().getLast();
        assertThat(rect(first), is(List.of(0, padding, padding, 10, 6)));
        assertThat(rect(last), is(List.of(1, 20 + padding, 5 + padding, 4, 12)));
        assertThat("only the child that drew a stand-in names it", first.result().substitutions(), is(empty()));
        assertThat(last.result().substitutions(), contains(HIDDEN));

        PixelBuffer canvas = layout.image().getFrames().getFirst().pixels();
        assertThat("each child is drawn at the corner it is named at", canvas.getPixel(first.x(), first.y()), is(GREEN));
        assertThat(canvas.getPixel(last.x(), last.y()), is(MAGENTA));
    }

    /**
     * Spells a grid cell as its index, its column and row, and its pixel rect.
     *
     * @param cell the cell
     * @return the cell's index, column, row, x, y, width and height
     */
    private static @NotNull List<Integer> rect(@NotNull GridResult.Cell cell) {
        return List.of(cell.index(), cell.col(), cell.row(), cell.x(), cell.y(), cell.width(), cell.height());
    }

    /**
     * Spells a layout child as its index and its pixel rect.
     *
     * @param child the child
     * @return the child's index, x, y, width and height
     */
    private static @NotNull List<Integer> rect(@NotNull LayoutResult.Child child) {
        return List.of(child.index(), child.x(), child.y(), child.width(), child.height());
    }

    /**
     * Builds a still of one opaque colour.
     *
     * @param width the image width
     * @param height the image height
     * @param argb the colour every pixel holds
     * @return the still
     */
    private static @NotNull ImageData solid(int width, int height, int argb) {
        PixelBuffer buffer = PixelBuffer.create(width, height);
        buffer.fill(argb);
        return Timeline.still(buffer);
    }

}
