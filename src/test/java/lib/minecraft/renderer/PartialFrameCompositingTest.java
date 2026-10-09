package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.FrameBlend;
import dev.simplified.image.data.FrameDisposal;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.call.request.GridOptions;
import lib.minecraft.renderer.call.request.LayoutOptions;
import lib.minecraft.renderer.call.result.GridResult;
import lib.minecraft.renderer.call.result.LayoutResult;
import lib.minecraft.renderer.engine.frame.FrameCompositor;
import lib.minecraft.renderer.engine.frame.FramePlacement;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of how a layout and a grid draw an animated image whose frames are not all whole pictures.
 * <p>
 * A decoded GIF keeps each later frame as the rectangle that changed, at its offset. Such an image is
 * placed as the picture a viewer of it shows: at each output frame, its frames up to the one showing,
 * drawn in order onto its canvas and each disposed of before the next is drawn. Every picture expected
 * here is written out by hand, for frames built in memory and for the same frames decoded from GIF
 * bytes, under each disposal; a grid cell of another size rescales that picture as it rescales any
 * tile.
 * <p>
 * An image whose every frame covers its canvas is drawn exactly as it was before pictures were
 * composed - the frame showing, as it is - whatever its frames' disposal and blend say.
 * <p>
 * Each expected frame is spelled one character a texel: {@code .} for the background (and, in a
 * frame, for a transparent texel), and a letter for each colour in {@link #PALETTE}.
 */
@DisplayName("An animated image of partial frames is placed as the picture its frames build")
class PartialFrameCompositingTest {

    /** The colour of every texel nothing is drawn over. */
    private static final int BACKGROUND = 0xFF203040;

    /** How long each frame of the partial-frame image is held. */
    private static final int DELAY_MS = 100;

    /** The output frame rate, one output frame per {@link #DELAY_MS}. */
    private static final int FRAMES_PER_SECOND = 10;

    /** The colour each letter spells; {@code P} is what {@code h} makes over {@code Y}. */
    private static final Map<Character, Integer> PALETTE = Map.of(
        'R', 0xFFFF0000, 'G', 0xFF00FF00, 'B', 0xFF0000FF, 'Y', 0xFFFFFF00,
        'W', 0xFFFFFFFF, 'K', 0xFF000000, 'h', 0x80FFFFFF, 'P', 0xFFFFFF80);

    /** The first frame: the whole 4x4 canvas, a red ring around a yellow centre. */
    private static final List<String> RING = List.of("RRRR", "RYYR", "RYYR", "RRRR");

    /** The second frame: green over the centre, a 2x2 at (1, 1). */
    private static final List<String> PATCH = List.of("GG", "GG");

    /** The third frame: blue on one diagonal of a 2x2 at (2, 2), transparent on the other. */
    private static final List<String> CORNER = List.of("B.", ".B");

    /** The picture while the second frame shows. */
    private static final List<String> PATCHED = List.of("RRRR", "RGGR", "RGGR", "RRRR");

    /** The size of a grid cell, twice the partial-frame image's. */
    private static final int CELL = 8;

    /** The colours a GIF frame indexes, {@code .} at index 0 marked transparent. */
    private static final List<Character> GIF_PALETTE = List.of('.', 'R', 'G', 'B', 'Y');

    @TestFactory
    @DisplayName("a layout draws the picture the frames build, under each disposal")
    Stream<DynamicTest> aLayoutDrawsThePictureTheFramesBuild() {
        return Arrays.stream(FrameDisposal.values()).map(disposal -> DynamicTest.dynamicTest(disposal.name(),
            () -> assertLayoutShows(partialFrames(disposal), List.of(RING, PATCHED, cornered(disposal)))));
    }

    @TestFactory
    @DisplayName("a grid cell of another size rescales the picture the frames build, under each disposal")
    Stream<DynamicTest> aGridCellRescalesThePictureTheFramesBuild() {
        return Arrays.stream(FrameDisposal.values()).map(disposal -> DynamicTest.dynamicTest(disposal.name(),
            () -> assertGridShows(partialFrames(disposal), List.of(RING, PATCHED, cornered(disposal)))));
    }

    @TestFactory
    @DisplayName("a decoded GIF keeps its partial frames, and a layout and a grid draw the picture they build")
    Stream<DynamicTest> aDecodedGifDrawsThePictureItsFramesBuild() {
        return Arrays.stream(FrameDisposal.values()).map(disposal -> DynamicTest.dynamicTest(disposal.name(), () -> {
            ImageData decoded = new ImageFactory().fromByteArray(gif(disposal));
            assertThat(decoded, is(instanceOf(AnimatedImageData.class)));

            ConcurrentList<ImageFrame> frames = decoded.getFrames();
            assertThat("the reader keeps each frame at its own offset and size",
                frames.stream().map(frame -> List.of(frame.offsetX(), frame.offsetY(), frame.width(), frame.height())).toList(),
                is(List.of(List.of(0, 0, 4, 4), List.of(1, 1, 2, 2), List.of(2, 2, 2, 2))));
            assertThat("and the patch's disposal", frames.get(1).disposal(), is(disposal));

            List<List<String>> pictures = List.of(RING, PATCHED, cornered(disposal));
            assertLayoutShows(decoded, pictures);
            assertGridShows(decoded, pictures);
        }));
    }

    @Test
    @DisplayName("a part-transparent texel of a partial frame blends over the picture beneath it")
    void aPartTransparentTexelBlendsOverThePicture() {
        AnimatedImageData image = AnimatedImageData.builder()
            .withFrame(ImageFrame.of(texels(RING), DELAY_MS, 0, 0, FrameDisposal.NONE, FrameBlend.SOURCE))
            .withFrame(ImageFrame.of(texels(List.of("hh")), DELAY_MS, 1, 1, FrameDisposal.NONE, FrameBlend.SOURCE))
            .build();

        // Half-transparent white over opaque yellow: alpha 128 + 255 * 127 / 255 = 255, red and green
        // stay 255, and blue is (255 * 128 + 0) / 255 = 128. Opaque, so the layout copies it as it is.
        assertLayoutShows(image, List.of(RING, List.of("RRRR", "RPPR", "RYYR", "RRRR")));
    }

    @TestFactory
    @DisplayName("an image whose every frame covers its canvas is drawn frame by frame as it is, whatever its disposal and blend")
    Stream<DynamicTest> wholeFramesAreDrawnAsTheyAre() {
        return Arrays.stream(FrameDisposal.values()).flatMap(disposal -> Arrays.stream(FrameBlend.values()).map(blend ->
            DynamicTest.dynamicTest(disposal.name() + " " + blend.name(), () -> {
                AnimatedImageData image = wholeFrames(disposal, blend);
                ConcurrentList<FramePlacement> placements = Concurrent.newList();
                placements.add(new FramePlacement(1, 1, image));
                placements.add(new FramePlacement(7, 1, image).fittedTo(CELL, CELL));
                ImageData merged = FrameCompositor.merge(placements, 16, 10, FRAMES_PER_SECOND, Background.solid(BACKGROUND));
                assertThat(merged.getFrames().size(), is(3));

                // What the compositor draws for each output frame is the frame showing then, as it is:
                // blitted at the plain placement and rescaled into the fitted one, over the background.
                long playbackMs = 0;
                for (ImageFrame frame : merged.getFrames()) {
                    PixelBuffer showing = image.getFrameAtTime(playbackMs, false).frame().pixels();
                    PixelBuffer expected = PixelBuffer.create(16, 10);
                    Background.solid(BACKGROUND).fill(expected);
                    expected.blit(showing, 1, 1);
                    expected.blitScaled(showing, 7, 1, CELL, CELL);
                    assertThat("the merge at %d ms".formatted(playbackMs), frame.pixels().data(), is(expected.data()));
                    playbackMs += frame.delayMs();
                }
            })));
    }

    // --- assertions ---

    /**
     * Lays out an image beside the {@link #clock()} and checks every output frame against the
     * pictures the image should show in turn, one every {@link #DELAY_MS}.
     *
     * @param image the image to lay out
     * @param pictures the pictures it should show, row by row
     */
    private static void assertLayoutShows(@NotNull ImageData image, @NotNull List<List<String>> pictures) {
        ConcurrentList<LayoutOptions.Layout.Custom.Position> positions = Concurrent.newList();
        positions.add(new LayoutOptions.Layout.Custom.Position(3, 2));
        positions.add(new LayoutOptions.Layout.Custom.Position(0, 0));
        LayoutResult layout = new LayoutRenderer().render(LayoutOptions.builder()
            .layout(new LayoutOptions.Layout.Custom(1, LayoutOptions.Layout.Alignment.START, positions))
            .child(image)
            .child(clock())
            .framesPerSecond(FRAMES_PER_SECOND)
            .background(Background.solid(BACKGROUND))
            .build());

        LayoutResult.Child placed = layout.children().getFirst();
        LayoutResult.Child ticking = layout.children().getLast();
        ConcurrentList<ImageFrame> frames = layout.image().getFrames();
        assertThat("the clock's 600 ms loop plays the image twice, a frame every 100 ms", frames.size(), is(6));

        long playbackMs = 0;
        for (ImageFrame frame : frames) {
            List<String> picture = pictures.get((int) (playbackMs / DELAY_MS % pictures.size()));
            assertThat("the layout at %d ms".formatted(playbackMs), spell(frame.pixels()),
                is(frameOf(layout.image().getWidth(), layout.image().getHeight(),
                    new Stamp(placed.x(), placed.y(), picture),
                    new Stamp(ticking.x(), ticking.y(), List.of(String.valueOf(clockAt(playbackMs)))))));
            playbackMs += frame.delayMs();
        }
    }

    /**
     * Places an image and the {@link #clock()} in two {@link #CELL} cells of a grid - each a size the
     * cell rescales - and checks every output frame against the pictures the image should show in
     * turn, one every {@link #DELAY_MS}, each scaled to its cell.
     *
     * @param image the image to place, at half the cell's size
     * @param pictures the pictures it should show, row by row
     */
    private static void assertGridShows(@NotNull ImageData image, @NotNull List<List<String>> pictures) {
        ConcurrentList<GridOptions.GridTile> tiles = Concurrent.newList();
        tiles.add(new GridOptions.GridTile(0, 0, image));
        tiles.add(new GridOptions.GridTile(1, 0, clock()));
        GridResult sheet = new GridRenderer().render(GridOptions.builder()
            .tiles(tiles)
            .cellSize(CELL)
            .columns(2)
            .rows(1)
            .separation(2)
            .background(Background.solid(BACKGROUND))
            .framesPerSecond(FRAMES_PER_SECOND)
            .build());

        GridResult.Cell placed = sheet.cells().getFirst();
        GridResult.Cell ticking = sheet.cells().getLast();
        ConcurrentList<ImageFrame> frames = sheet.image().getFrames();
        assertThat("the clock's 600 ms loop plays the image twice, a frame every 100 ms", frames.size(), is(6));

        long playbackMs = 0;
        for (ImageFrame frame : frames) {
            List<String> picture = pictures.get((int) (playbackMs / DELAY_MS % pictures.size()));
            assertThat("the grid at %d ms".formatted(playbackMs), spell(frame.pixels()),
                is(frameOf(sheet.image().getWidth(), sheet.image().getHeight(),
                    new Stamp(placed.x(), placed.y(), scaled(picture, CELL / image.getWidth())),
                    new Stamp(ticking.x(), ticking.y(), scaled(List.of(String.valueOf(clockAt(playbackMs))), CELL)))));
            playbackMs += frame.delayMs();
        }
    }

    // --- fixtures ---

    /**
     * Returns the picture while the third frame shows, which depends on what the second frame's
     * disposal left it drawn onto.
     *
     * @param patchDisposal the second frame's disposal
     * @return the picture, row by row
     */
    private static @NotNull List<String> cornered(@NotNull FrameDisposal patchDisposal) {
        return switch (patchDisposal) {
            // the patch stays; the corner's transparent texels leave the ring and the patch beneath them
            case NONE, DO_NOT_DISPOSE -> List.of("RRRR", "RGGR", "RGBR", "RRRB");
            // the patch's rectangle is cleared, so the centre the ring framed is gone
            case RESTORE_TO_BACKGROUND -> List.of("RRRR", "R..R", "R.BR", "RRRB");
            // the canvas goes back to the ring as it stood before the patch
            case RESTORE_TO_PREVIOUS -> List.of("RRRR", "RYYR", "RYBR", "RRRB");
        };
    }

    /**
     * Builds the three-frame image in memory, as the GIF reader keeps one: the ring covering the
     * canvas, then the patch and the corner as partial frames at their offsets, every frame marked
     * {@link FrameBlend#SOURCE}.
     *
     * @param patchDisposal the second frame's disposal
     * @return the image
     */
    private static @NotNull AnimatedImageData partialFrames(@NotNull FrameDisposal patchDisposal) {
        return AnimatedImageData.builder()
            .withFrame(ImageFrame.of(texels(RING), DELAY_MS, 0, 0, FrameDisposal.NONE, FrameBlend.SOURCE))
            .withFrame(ImageFrame.of(texels(PATCH), DELAY_MS, 1, 1, patchDisposal, FrameBlend.SOURCE))
            .withFrame(ImageFrame.of(texels(CORNER), DELAY_MS, 2, 2, FrameDisposal.NONE, FrameBlend.SOURCE))
            .build();
    }

    /**
     * Builds a one-texel image that is white for the first {@code 3 * DELAY_MS} and black for the next,
     * whose 600 ms loop makes every merge here play the partial-frame image twice over.
     *
     * @return the image
     */
    private static @NotNull AnimatedImageData clock() {
        return AnimatedImageData.builder()
            .withFrame(ImageFrame.of(texels(List.of("W")), 3 * DELAY_MS, 0, 0, FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE))
            .withFrame(ImageFrame.of(texels(List.of("K")), 3 * DELAY_MS, 0, 0, FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE))
            .build();
    }

    /**
     * Spells the clock's texel at a playback instant.
     *
     * @param playbackMs the playback instant
     * @return {@code W} or {@code K}
     */
    private static char clockAt(long playbackMs) {
        return playbackMs % (6 * DELAY_MS) < 3 * DELAY_MS ? 'W' : 'K';
    }

    /**
     * Builds three frames that each cover the whole 4x4 canvas, every one transparent or
     * part-transparent somewhere an earlier one is opaque, so a frame drawn over the last instead of
     * in place of it shows.
     *
     * @param disposal every frame's disposal
     * @param blend every frame's blend
     * @return the image
     */
    private static @NotNull AnimatedImageData wholeFrames(@NotNull FrameDisposal disposal, @NotNull FrameBlend blend) {
        return AnimatedImageData.builder()
            .withFrame(ImageFrame.of(texels(List.of("RRRR", "RhhR", "R..R", "RRRR")), DELAY_MS, 0, 0, disposal, blend))
            .withFrame(ImageFrame.of(texels(List.of(".GG.", "G..G", "GhhG", ".GG.")), DELAY_MS, 0, 0, disposal, blend))
            .withFrame(ImageFrame.of(texels(List.of("B..B", ".hh.", ".BB.", "B..B")), DELAY_MS, 0, 0, disposal, blend))
            .build();
    }

    /**
     * Builds a buffer from rows of spelled texels, {@code .} transparent.
     *
     * @param rows the texels, row by row
     * @return the buffer
     */
    private static @NotNull PixelBuffer texels(@NotNull List<String> rows) {
        PixelBuffer buffer = PixelBuffer.create(rows.getFirst().length(), rows.size());
        for (int y = 0; y < rows.size(); y++)
            for (int x = 0; x < rows.get(y).length(); x++)
                buffer.setPixel(x, y, argb(rows.get(y).charAt(x)));
        return buffer;
    }

    /**
     * Returns the colour a texel's letter spells, {@code .} transparent.
     *
     * @param texel the letter
     * @return the colour
     */
    private static int argb(char texel) {
        return texel == '.' ? 0 : PALETTE.get(texel);
    }

    /**
     * Spells every texel of an output frame, {@code .} for the background and {@code ?} for a colour
     * the palette does not name.
     *
     * @param frame the output frame
     * @return the frame, row by row
     */
    private static @NotNull List<String> spell(@NotNull PixelBuffer frame) {
        ConcurrentList<String> rows = Concurrent.newList();
        for (int y = 0; y < frame.height(); y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < frame.width(); x++) {
                int pixel = frame.getPixel(x, y);
                row.append(pixel == BACKGROUND ? '.' : PALETTE.entrySet().stream()
                    .filter(entry -> entry.getValue() == pixel)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse('?'));
            }
            rows.add(row.toString());
        }
        return rows;
    }

    /**
     * Spells a background-filled frame with pictures stamped onto it, each stamp's {@code .} leaving
     * the background.
     *
     * @param width the frame width
     * @param height the frame height
     * @param stamps each picture with the column and row of its corner
     * @return the frame, row by row
     */
    private static @NotNull List<String> frameOf(int width, int height, @NotNull Stamp @NotNull ... stamps) {
        char[][] texels = new char[height][width];
        for (char[] row : texels)
            Arrays.fill(row, '.');

        for (Stamp stamp : stamps)
            for (int y = 0; y < stamp.rows().size(); y++)
                for (int x = 0; x < stamp.rows().get(y).length(); x++)
                    if (stamp.rows().get(y).charAt(x) != '.')
                        texels[stamp.y() + y][stamp.x() + x] = stamp.rows().get(y).charAt(x);

        return Arrays.stream(texels).map(String::new).toList();
    }

    /**
     * Rescales a spelled picture by a whole factor, each texel becoming a {@code factor} square - what
     * nearest-neighbour sampling makes of it.
     *
     * @param rows the picture, row by row
     * @param factor the scale
     * @return the rescaled picture, row by row
     */
    private static @NotNull List<String> scaled(@NotNull List<String> rows, int factor) {
        ConcurrentList<String> out = Concurrent.newList();
        for (String row : rows) {
            StringBuilder wide = new StringBuilder();
            for (char texel : row.toCharArray())
                wide.append(String.valueOf(texel).repeat(factor));
            for (int copy = 0; copy < factor; copy++)
                out.add(wide.toString());
        }
        return out;
    }

    /**
     * Encodes the three-frame partial image as GIF bytes through the JDK's GIF writer - the codec the
     * image library's GIF reader decodes through - each frame at its own size and offset, palette
     * index 0 transparent.
     *
     * @param patchDisposal the second frame's disposal
     * @return the GIF bytes
     * @throws IOException if the writer fails
     */
    private static byte @NotNull [] gif(@NotNull FrameDisposal patchDisposal) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            writeGifFrame(writer, RING, 0, 0, FrameDisposal.NONE);
            writeGifFrame(writer, PATCH, 1, 1, patchDisposal);
            writeGifFrame(writer, CORNER, 2, 2, FrameDisposal.NONE);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }

        return bytes.toByteArray();
    }

    /**
     * Appends one frame to a GIF sequence, declaring its offset, its disposal, its delay and the
     * transparent palette index.
     *
     * @param writer the GIF writer, mid-sequence
     * @param rows the frame's texels, row by row
     * @param x the frame's column on the canvas
     * @param y the frame's row on the canvas
     * @param disposal the frame's disposal
     * @throws IOException if the writer fails
     */
    private static void writeGifFrame(@NotNull ImageWriter writer, @NotNull List<String> rows, int x, int y, @NotNull FrameDisposal disposal) throws IOException {
        BufferedImage image = indexed(rows);
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

        IIOMetadataNode descriptor = childNamed(root, "ImageDescriptor");
        descriptor.setAttribute("imageLeftPosition", Integer.toString(x));
        descriptor.setAttribute("imageTopPosition", Integer.toString(y));
        descriptor.setAttribute("interlaceFlag", "FALSE");

        // A colour table holds a power of two entries, so the palette is padded to eight with black.
        IIOMetadataNode table = childNamed(root, "LocalColorTable");
        while (table.hasChildNodes())
            table.removeChild(table.getFirstChild());
        table.setAttribute("sizeOfLocalColorTable", "8");
        table.setAttribute("sortFlag", "FALSE");
        for (int index = 0; index < 8; index++) {
            int argb = index < GIF_PALETTE.size() ? argb(GIF_PALETTE.get(index)) : 0;
            IIOMetadataNode entry = new IIOMetadataNode("ColorTableEntry");
            entry.setAttribute("index", Integer.toString(index));
            entry.setAttribute("red", Integer.toString((argb >> 16) & 0xFF));
            entry.setAttribute("green", Integer.toString((argb >> 8) & 0xFF));
            entry.setAttribute("blue", Integer.toString(argb & 0xFF));
            table.appendChild(entry);
        }

        IIOMetadataNode control = childNamed(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", disposal.getMethod());
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "TRUE");
        control.setAttribute("transparentColorIndex", "0");
        control.setAttribute("delayTime", Integer.toString(DELAY_MS / 10));

        metadata.setFromTree(format, root);
        writer.writeToSequence(new IIOImage(image, null, metadata), param);
    }

    /**
     * Returns a metadata node's child of a name, appending an empty one when it has none.
     *
     * @param root the parent node
     * @param name the child's name
     * @return the child
     */
    private static @NotNull IIOMetadataNode childNamed(@NotNull IIOMetadataNode root, @NotNull String name) {
        for (int index = 0; index < root.getLength(); index++)
            if (root.item(index).getNodeName().equals(name))
                return (IIOMetadataNode) root.item(index);

        IIOMetadataNode child = new IIOMetadataNode(name);
        root.appendChild(child);
        return child;
    }

    /**
     * Builds an indexed image from rows of spelled texels over {@link #GIF_PALETTE}.
     *
     * @param rows the texels, row by row
     * @return the indexed image
     */
    private static @NotNull BufferedImage indexed(@NotNull List<String> rows) {
        byte[] red = new byte[GIF_PALETTE.size()];
        byte[] green = new byte[GIF_PALETTE.size()];
        byte[] blue = new byte[GIF_PALETTE.size()];
        for (int index = 1; index < GIF_PALETTE.size(); index++) {
            int argb = argb(GIF_PALETTE.get(index));
            red[index] = (byte) (argb >> 16);
            green[index] = (byte) (argb >> 8);
            blue[index] = (byte) argb;
        }

        IndexColorModel palette = new IndexColorModel(8, GIF_PALETTE.size(), red, green, blue, 0);
        BufferedImage image = new BufferedImage(rows.getFirst().length(), rows.size(), BufferedImage.TYPE_BYTE_INDEXED, palette);
        for (int y = 0; y < rows.size(); y++)
            for (int x = 0; x < rows.get(y).length(); x++)
                image.getRaster().setSample(x, y, 0, GIF_PALETTE.indexOf(rows.get(y).charAt(x)));
        return image;
    }

    /**
     * A spelled picture and where its corner lands.
     *
     * @param x the column of the corner
     * @param y the row of the corner
     * @param rows the picture, row by row
     */
    private record Stamp(int x, int y, @NotNull List<String> rows) {}

}
