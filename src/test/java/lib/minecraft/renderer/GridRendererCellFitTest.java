package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.FrameBlend;
import dev.simplified.image.data.FrameDisposal;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.call.request.GridOptions;
import lib.minecraft.renderer.call.result.GridResult;
import lib.minecraft.renderer.engine.frame.FrameCompositor;
import lib.minecraft.renderer.engine.frame.FramePlacement;
import lib.minecraft.renderer.engine.frame.Timeline;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of how a grid fills its cells: every tile is drawn into its cell at the cell's size, on an
 * animated sheet exactly as on a static one.
 * <p>
 * The static sheet is the reference. For each frame of an animated sheet, the frame every tile shows
 * at that frame's playback instant is laid out again as a still through the same renderer, and the two
 * sheets must agree on every cell and on every gutter pixel. A tile that already has the cell's size is
 * drawn as it is, so an animated sheet of such tiles keeps the bytes it drew before tiles were fitted
 * to their cells.
 * <p>
 * Neither sheet reads a context, so every tile is pixels built in memory, mixing transparent, opaque
 * and part-transparent texels so the fit is checked under compositing and not only on solid fills.
 */
@DisplayName("A grid draws every tile into its cell, animated or static")
class GridRendererCellFitTest {

    private static final int CELL = 8;
    private static final int SEPARATION = 2;
    private static final int BACKGROUND = 0xFF203040;
    private static final int FRAMES_PER_SECOND = 20;

    @Test
    @DisplayName("an animated sheet scales a tile larger or smaller than its cell into the cell, as the static sheet does")
    void offSizeTilesFillTheirCellsOnAnAnimatedSheet() {
        ConcurrentList<GridOptions.GridTile> tiles = Concurrent.newList();
        tiles.add(new GridOptions.GridTile(0, 0, animated(12, 10, 3, 100, 1)));      // larger than the cell
        tiles.add(new GridOptions.GridTile(1, 0, animated(5, 6, 2, 150, 2)));        // smaller than the cell
        tiles.add(new GridOptions.GridTile(0, 1, animated(CELL, CELL, 4, 75, 3)));   // the cell's size
        tiles.add(new GridOptions.GridTile(1, 1, Timeline.still(pattern(CELL, CELL, 0, 4))));

        GridResult sheet = render(tiles);
        ConcurrentList<ImageFrame> frames = sheet.image().getFrames();
        // Every tile loops in 300 ms, sampled every 50 ms at 20 fps.
        assertThat("the sheet is animated", sheet.image().isAnimated(), is(true));
        assertThat(frames.size(), is(6));

        long playbackMs = 0;
        for (ImageFrame frame : frames) {
            PixelBuffer expected = render(stillsAt(tiles, playbackMs)).image().toPixelBuffer();

            for (GridResult.Cell cell : sheet.cells())
                assertThat("cell %d at %d ms".formatted(cell.index(), playbackMs),
                    region(frame.pixels(), cell), is(region(expected, cell)));

            assertThat("the gutters at %d ms".formatted(playbackMs),
                gutters(frame.pixels(), sheet.cells()), everyItem(is(BACKGROUND)));
            playbackMs += frame.delayMs();
        }
    }

    @Test
    @DisplayName("an animated sheet draws a tile of the cell's size as it is, byte for byte")
    void cellSizedTilesKeepTheirBytes() {
        ConcurrentList<GridOptions.GridTile> tiles = Concurrent.newList();
        tiles.add(new GridOptions.GridTile(0, 0, animated(CELL, CELL, 3, 100, 5)));
        tiles.add(new GridOptions.GridTile(1, 0, Timeline.still(pattern(CELL, CELL, 0, 6))));
        tiles.add(new GridOptions.GridTile(0, 1, animated(CELL, CELL, 2, 150, 7)));
        // Flagged opaque while holding transparent and part-transparent texels: the one input a plain
        // blit copies verbatim and a rescale at the same size composites instead, so it tells a tile
        // drawn as it is from one rescaled to the size it already has.
        tiles.add(new GridOptions.GridTile(1, 1, StaticImageData.of(PixelBuffer.of(pattern(CELL, CELL, 0, 8).data(), CELL, CELL, false))));

        GridResult sheet = render(tiles);
        ConcurrentList<FramePlacement> asTheyAre = Concurrent.newList();
        for (GridResult.Cell cell : sheet.cells())
            asTheyAre.add(new FramePlacement(cell.x(), cell.y(), cell.result().image()));
        ImageData merged = FrameCompositor.merge(asTheyAre, sheet.image().getWidth(), sheet.image().getHeight(),
            FRAMES_PER_SECOND, Background.solid(BACKGROUND));

        assertThat("the sheet is the merge of its tiles at their own size", digest(sheet.image()), is(digest(merged)));
        assertThat("and the bytes the sheet drew before tiles were fitted to their cells", digest(sheet.image()),
            is("6e8cec41c0276599f3dec50429f0b0a0a9808cecb93029ee91193ea8a3d54277"));
    }

    /**
     * Renders the tiles onto a two-by-two sheet on a solid background.
     *
     * @param tiles the tiles to place
     * @return the sheet
     */
    private static @NotNull GridResult render(@NotNull ConcurrentList<GridOptions.GridTile> tiles) {
        return new GridRenderer().render(GridOptions.builder()
            .tiles(tiles)
            .cellSize(CELL)
            .columns(2)
            .rows(2)
            .separation(SEPARATION)
            .background(Background.solid(BACKGROUND))
            .framesPerSecond(FRAMES_PER_SECOND)
            .build());
    }

    /**
     * Replaces every tile with a still of the frame it shows at a playback instant, sampled the way
     * the compositor samples it.
     *
     * @param tiles the tiles to sample
     * @param playbackMs the playback instant
     * @return the tiles as stills, at the same cells
     */
    private static @NotNull ConcurrentList<GridOptions.GridTile> stillsAt(@NotNull ConcurrentList<GridOptions.GridTile> tiles, long playbackMs) {
        ConcurrentList<GridOptions.GridTile> stills = Concurrent.newList();
        for (GridOptions.GridTile tile : tiles) {
            ImageData image = tile.result().image();
            PixelBuffer frame = image instanceof AnimatedImageData animated
                ? animated.getFrameAtTime(playbackMs, false).frame().pixels()
                : image.toPixelBuffer();
            stills.add(new GridOptions.GridTile(tile.col(), tile.row(), Timeline.still(frame)));
        }
        return stills;
    }

    /**
     * Reads the pixels a cell covers, row by row.
     *
     * @param pixels the sheet frame
     * @param cell the cell
     * @return the cell's pixels
     */
    private static int @NotNull [] region(@NotNull PixelBuffer pixels, @NotNull GridResult.Cell cell) {
        return pixels.getPixels(cell.x(), cell.y(), cell.width(), cell.height(), null, 0, cell.width());
    }

    /**
     * Reads every pixel of a sheet frame no cell covers - the gutters and the outer margin.
     *
     * @param pixels the sheet frame
     * @param cells the sheet's cells
     * @return the uncovered pixels
     */
    private static @NotNull List<Integer> gutters(@NotNull PixelBuffer pixels, @NotNull ConcurrentList<GridResult.Cell> cells) {
        ConcurrentList<Integer> gutters = Concurrent.newList();
        for (int y = 0; y < pixels.height(); y++)
            for (int x = 0; x < pixels.width(); x++)
                if (!covered(cells, x, y))
                    gutters.add(pixels.getPixel(x, y));
        return gutters;
    }

    /**
     * Tests whether a pixel lies inside any cell.
     *
     * @param cells the sheet's cells
     * @param x the pixel's column
     * @param y the pixel's row
     * @return whether a cell covers it
     */
    private static boolean covered(@NotNull ConcurrentList<GridResult.Cell> cells, int x, int y) {
        return cells.stream().anyMatch(cell ->
            x >= cell.x() && x < cell.x() + cell.width() && y >= cell.y() && y < cell.y() + cell.height());
    }

    /**
     * Builds an animated tile whose every frame is a distinct {@link #pattern}, each held for the same
     * delay and replacing the last whole.
     *
     * @param width the tile width
     * @param height the tile height
     * @param frameCount the number of frames
     * @param delayMs how long each frame is held
     * @param seed what sets this tile's texels apart from another's
     * @return the animated tile
     */
    private static @NotNull ImageData animated(int width, int height, int frameCount, int delayMs, int seed) {
        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int frame = 0; frame < frameCount; frame++)
            builder.withFrame(ImageFrame.of(pattern(width, height, frame, seed), delayMs, 0, 0,
                FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE));
        return builder.build();
    }

    /**
     * Builds a buffer whose texels cycle through transparent, half-transparent and opaque, each a
     * colour hashed from its position, the frame and the seed.
     *
     * @param width the buffer width
     * @param height the buffer height
     * @param frame the frame index
     * @param seed what sets one tile's texels apart from another's
     * @return the buffer
     */
    private static @NotNull PixelBuffer pattern(int width, int height, int frame, int seed) {
        PixelBuffer buffer = PixelBuffer.create(width, height);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int mix = x * 31 + y * 17 + frame * 7 + seed * 13;
                int alpha = switch (mix % 3) {
                    case 0 -> 0x00;
                    case 1 -> 0x80;
                    default -> 0xFF;
                };
                buffer.setPixel(x, y, (alpha << 24) | (((mix * 0x9E3779B1) >>> 8) & 0xFFFFFF));
            }
        return buffer;
    }

    /**
     * Digests an image: its frame count, then each frame's delay, size and pixels in order.
     *
     * @param image the image
     * @return the SHA-256 digest in hex
     */
    private static @NotNull String digest(@NotNull ImageData image) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            ConcurrentList<ImageFrame> frames = image.getFrames();
            sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(frames.size()).array());
            for (ImageFrame frame : frames) {
                PixelBuffer pixels = frame.pixels();
                ByteBuffer bytes = ByteBuffer.allocate(Integer.BYTES * (3 + pixels.data().length));
                bytes.putInt(frame.delayMs()).putInt(pixels.width()).putInt(pixels.height());
                for (int argb : pixels.data())
                    bytes.putInt(argb);
                sha.update(bytes.array());
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
