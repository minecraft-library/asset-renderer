package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.Background;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.call.request.GridOptions;
import lib.minecraft.renderer.call.result.GridResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.engine.frame.RasterPass;
import lib.minecraft.renderer.engine.frame.Timeline;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Holds a grid that recycles its tiles to the grid that allocates each. Every tile is rasterized through
 * a {@link RasterPass}, which is what draws into the buffer a recycling grid lends, and leaves some of its
 * pixels transparent and the rest part-transparent - so a lent buffer handed over still holding another
 * tile's pixels, or taken back before its tile is drawn, shows in the sheet. The arrays the tiles drew
 * into are counted, so a recycling grid that never managed to lend one fails too.
 */
@DisplayName("A grid that recycles its tiles draws what one that does not draws")
class GridRendererRecycledTileTest {

    /** The columns of the sheet. */
    private static final int COLUMNS = 6;

    /** The tiles on the sheet, enough that every worker draws several. */
    private static final int TILES = 60;

    /** The cell edge, which every tile is drawn at. */
    private static final int CELL = 16;

    @Test
    @DisplayName("a recycling sheet of rasterized tiles holds the bytes of one that allocates every tile, drawing into fewer arrays")
    void aRecyclingSheetDrawsWhatAnAllocatingOneDraws() {
        Set<Integer> recycledArrays = ConcurrentHashMap.newKeySet();
        Set<Integer> allocatedArrays = ConcurrentHashMap.newKeySet();

        GridResult recycled = sheet(true, recycledArrays);
        GridResult allocated = sheet(false, allocatedArrays);

        assertArrayEquals(allocated.image().toPixelBuffer().data(), recycled.image().toPixelBuffer().data());
        assertThat("every allocated tile drew into an array of its own", allocatedArrays.size(), is(TILES));
        assertThat("recycled tiles drew into fewer arrays than there are tiles", recycledArrays.size(), is(lessThan(TILES)));
    }

    /**
     * Renders the sheet of every tile over a part-transparent background.
     *
     * @param recycle whether the grid recycles its tiles
     * @param arrays collects the identity of every array a tile drew into
     * @return the sheet
     */
    private static @NotNull GridResult sheet(boolean recycle, @NotNull Set<Integer> arrays) {
        ConcurrentList<GridOptions.GridTile> tiles = Concurrent.newList();
        for (int i = 0; i < TILES; i++) {
            int index = i;
            tiles.add(new GridOptions.GridTile(i % COLUMNS, i / COLUMNS, () -> tile(index, arrays)));
        }

        return new GridRenderer().render(GridOptions.builder()
            .tiles(tiles)
            .cellSize(CELL)
            .columns(COLUMNS)
            .rows(TILES / COLUMNS)
            .background(Background.solid(0x80204060))
            .recycleTiles(recycle)
            .build());
    }

    /**
     * Rasterizes one tile - a pattern of its own, a third of its pixels left transparent - and notes the
     * array it drew into.
     *
     * @param index the tile's index, which sets its pattern
     * @param arrays collects the identity of the array the tile drew into
     * @return the tile's render
     */
    private static @NotNull RenderResult tile(int index, @NotNull Set<Integer> arrays) {
        ImageData image = new Timeline.Static(0).bake(RasterPass.of(CELL, CELL, 1, false, (target, tick) -> {
            for (int y = 0; y < CELL; y++)
                for (int x = 0; x < CELL; x++)
                    if ((x + y + index) % 3 != 0)
                        target.setPixel(x, y, ColorMath.pack(64 + index * 37 % 192, index * 53 % 256, x * 16, y * 16));
        }));
        arrays.add(System.identityHashCode(image.toPixelBuffer().data()));
        return RenderResult.of(image);
    }

}
