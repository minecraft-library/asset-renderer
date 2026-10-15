package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.call.request.AtlasOptions;
import lib.minecraft.renderer.call.result.AtlasResult;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of where the atlas lays each tile: every kept subject's tile, drawn as that subject drawn
 * alone, in the cell its rank among the kept subjects gives it, row by row and densely, over the
 * background laid down for the whole sheet.
 * <p>
 * Two references stand in for the atlas's own layout. A tile is held to its subject rendered alone into
 * a one-cell sheet over the same background - on a checkerboard only where the tile size is a multiple of
 * the board's period, so the cell sees the board the lone sheet's origin sees. A sheet whose context
 * refuses subjects - listing ids its lookups answer absent, in the middle and at the head of either pass,
 * or failing a texture read - is held to the sheet a context without those subjects lays down, pixel for
 * pixel and row for row, on every background, a checkerboard whose period no tile size lines up with
 * among them.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where nothing
 * has extracted the client yet.
 */
@DisplayName("The atlas lays each kept tile where a dense sheet of the kept subjects puts it")
@ExtendWith(ClientAssetsExtension.class)
class AtlasRendererLayoutTest {

    private static final String ABSENT_BLOCK = "minecraft:atlas_layout_absent_block";
    private static final String ABSENT_ITEM = "minecraft:atlas_layout_absent_item";

    /** A checkerboard whose 3 px squares repeat every 6 px, which 16, 32 and 64 px tiles do not line up with. */
    private static final Background MISALIGNED = Background.checkerboard(0xFF102030, 0x80405060, 3);

    /** Every background a sheet is laid on: transparent, part-transparent, and two checkerboards. */
    private static final List<Background> BACKGROUNDS = List.of(
        Background.TRANSPARENT, Background.solid(0x80336699), Background.checkerboard(), MISALIGNED);

    private static RendererContext vanilla;

    @BeforeAll
    static void bootstrapPipeline() {
        vanilla = ClientAssetsExtension.context();
    }

    @Test
    @DisplayName("each tile of a sheet of many rows is its subject drawn alone, laid down row by row")
    void eachTileIsItsSubjectDrawnAlone() {
        AtlasRenderer renderer = new AtlasRenderer(vanilla);
        record Shape(int columns, int tileSize, Background background) {}
        for (Shape shape : List.of(
            new Shape(3, 24, Background.TRANSPARENT), new Shape(3, 24, Background.solid(0x80336699)),
            new Shape(7, 64, Background.TRANSPARENT), new Shape(7, 64, Background.checkerboard()),
            new Shape(5, 32, Background.checkerboard()))) {
            AtlasOptions options = oak(id -> false).columns(shape.columns()).tileSize(shape.tileSize()).background(shape.background()).build();
            AtlasResult atlas = renderer.render(options);
            String label = "%d columns of %d px over %s".formatted(shape.columns(), shape.tileSize(), shape.background());

            assertDense(label, atlas, options);
            assertThat(label + " has many rows", atlas.image().getHeight() > 2 * shape.tileSize(), is(true));
            for (AtlasResult.Tile tile : atlas.sidecar().tiles())
                assertArrayEquals(alone(renderer, tile.id(), options), crop(atlas, tile), label + " tile " + tile.id());
        }
    }

    @Test
    @DisplayName("refusals in the middle of either pass leave the sheet a context without them lays down, on every background")
    void refusalsInTheMiddleLeaveTheSheetWithoutThem() {
        for (int[] shape : new int[][] { { 3, 16 }, { 7, 32 } })
            for (Background background : BACKGROUNDS) {
                AtlasOptions options = oak(List.of(ABSENT_BLOCK, ABSENT_ITEM)::contains)
                    .columns(shape[0]).tileSize(shape[1]).background(background).build();
                AtlasResult refusing = new AtlasRenderer(listing(vanilla, 1)).render(options);
                String label = "%d columns of %d px over %s".formatted(shape[0], shape[1], background);

                assertThat(label + " refuses both absent ids", skippedIds(refusing), is(List.of(ABSENT_BLOCK, ABSENT_ITEM)));
                assertSameSheet(label, refusing, new AtlasRenderer(vanilla).render(options));
                assertDense(label, refusing, options);
            }
    }

    @Test
    @DisplayName("refusals at the head of each pass drop an emptied row, and a tile that draws nothing keeps its cell")
    void refusalsDropAnEmptiedRow() {
        // Five subjects and two refusals at the head of each pass make seven candidates over three rows of
        // three; the five kept fill two. Cave air and void air draw nothing through the block pass, and
        // air through the item pass.
        List<String> subjects = List.of("minecraft:oak_planks", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:stick", "minecraft:air", ABSENT_BLOCK, ABSENT_ITEM);
        for (Background background : BACKGROUNDS) {
            AtlasOptions options = AtlasOptions.builder()
                .filter(Optional.of(subjects::contains))
                .columns(3)
                .tileSize(16)
                .background(background)
                .progressLogging(false)
                .build();
            AtlasResult refusing = new AtlasRenderer(listing(vanilla, 0)).render(options);

            assertSameSheet("over " + background, refusing, new AtlasRenderer(vanilla).render(options));
            assertThat("five kept fill two rows", refusing.image().getHeight(), is(2 * 16));
            assertThat("the empty tiles keep their cells", refusing.sidecar().tiles().stream()
                .filter(tile -> tile.source() == AtlasResult.Tile.Source.EMPTY)
                .map(AtlasResult.Tile::id)
                .toList(), hasItems("minecraft:cave_air", "minecraft:void_air", "minecraft:air"));
        }
    }

    @Test
    @DisplayName("a subject whose render fails is refused, and the sheet is the one its context lays down without it")
    void aFailedRenderIsRefused() {
        String texture = "minecraft:block/oak_planks";
        RendererContext failing = new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return vanilla;
            }

            @Override
            public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
                if (textureId.equals(texture))
                    throw new RenderException("Texture '%s' failed to read", textureId);

                return vanilla.resolveTexture(textureId);
            }

        };
        for (Background background : List.of(Background.TRANSPARENT, MISALIGNED)) {
            AtlasOptions options = oak(id -> false).columns(3).tileSize(16).background(background).build();
            AtlasResult refusing = new AtlasRenderer(failing).render(options);
            List<String> refused = skippedIds(refusing);

            assertThat("the subjects drawing the texture are refused", refused, is(not(empty())));
            assertThat("for the read that failed", refusing.sidecar().skipped().getFirst().reason(), containsString("failed to read"));
            AtlasOptions without = oak(id -> false).filter(Optional.of(((Predicate<String>) refused::contains).negate().and(id -> id.contains("oak"))))
                .columns(3).tileSize(16).background(background).build();
            assertSameSheet("over " + background, refusing, new AtlasRenderer(vanilla).render(without));
        }
    }

    @Test
    @DisplayName("each pass alone lays down that pass's subjects, in the order the whole sheet has them")
    void eachPassAloneKeepsItsOrder() {
        AtlasRenderer renderer = new AtlasRenderer(vanilla);
        AtlasResult both = renderer.render(oak(id -> false).columns(5).tileSize(16).build());

        // The whole sheet is the block pass then the item pass, each in the order the context lists its ids.
        List<AtlasResult.Tile> tiles = both.sidecar().tiles();
        int firstItem = (int) tiles.stream().takeWhile(tile -> tile.kind() == AtlasResult.Tile.Kind.BLOCK).count();
        assertThat("the block pass comes first", tiles.stream().skip(firstItem).allMatch(tile -> tile.kind() == AtlasResult.Tile.Kind.ITEM), is(true));
        assertThat("both passes laid tiles", firstItem > 0 && firstItem < tiles.size(), is(true));
        assertListedOrder("the block pass", tiles.subList(0, firstItem), vanilla.knownBlockIds());
        assertListedOrder("the item pass", tiles.subList(firstItem, tiles.size()), vanilla.knownItemIds());

        for (AtlasResult.Tile.Kind kind : AtlasResult.Tile.Kind.values()) {
            AtlasOptions.Scope scope = kind == AtlasResult.Tile.Kind.BLOCK ? AtlasOptions.Scope.BLOCK : AtlasOptions.Scope.ITEM;
            AtlasOptions options = oak(id -> false).source(scope).columns(5).tileSize(16).build();
            AtlasResult alone = renderer.render(options);

            assertThat(scope + " alone", alone.sidecar().tiles().stream().map(AtlasResult.Tile::id).toList(),
                is(both.sidecar().tiles().stream().filter(tile -> tile.kind() == kind).map(AtlasResult.Tile::id).toList()));
            assertDense(scope + " alone", alone, options);
        }
    }

    @Test
    @DisplayName("a subject kept on one sheet and refused on the next is left out, the refusal replacing the keep")
    void aRefusalOnALaterSheetReplacesAKeep() {
        String target = "minecraft:oak_planks";
        AtlasOptions lone = AtlasOptions.builder().filter(Optional.of(target::equals)).tileSize(16).progressLogging(false).build();
        AtomicInteger perRender = new AtomicInteger();
        new AtlasRenderer(failingAfter(vanilla, target, perRender, Integer.MAX_VALUE)).render(lone);
        assertThat("one render of the target asks its lookup", perRender.get() > 0, is(true));

        // The listed absent id refuses on the first sheet, so a second is laid down, and the target's
        // lookup fails once it has served one render: kept on the first sheet, refused on the second.
        AtlasOptions options = oak(List.of(ABSENT_BLOCK)::contains).columns(3).tileSize(16).build();
        AtlasResult atlas = new AtlasRenderer(failingAfter(listing(vanilla, 1), target, new AtomicInteger(), perRender.get())).render(options);

        assertThat("both are refused", skippedIds(atlas), hasItems(ABSENT_BLOCK, target));
        assertThat("the target holds no cell", atlas.sidecar().tiles().stream().map(AtlasResult.Tile::id).toList().contains(target), is(false));
        AtlasOptions without = oak(id -> false).filter(Optional.of(id -> id.contains("oak") && !id.equals(target))).columns(3).tileSize(16).build();
        assertSameSheet("without the target", atlas, new AtlasRenderer(vanilla).render(without));
    }

    @Test
    @DisplayName("each warning and pass line prints once however many sheets a bake lays down")
    void eachLinePrintsOnce() {
        AtlasOptions options = oak(List.of(ABSENT_BLOCK, ABSENT_ITEM)::contains).columns(3).tileSize(16).progressLogging(true).build();
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            System.setOut(capture);
            System.setErr(capture);
            new AtlasRenderer(listing(vanilla, 1)).render(options);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        List<String> lines = captured.toString(StandardCharsets.UTF_8).lines().toList();
        for (String line : List.of("  skipped block '" + ABSENT_BLOCK + "'", "  skipped item '" + ABSENT_ITEM + "'", "Block render pass complete", "Item render pass complete"))
            assertThat(line, lines.stream().filter(printed -> printed.startsWith(line)).count(), is(1L));
    }

    @Test
    @DisplayName("a column count or tile size that is not positive is refused before any tile renders")
    void unplaceableGeometryIsRefused() {
        AtomicInteger asks = new AtomicInteger();
        AtlasRenderer renderer = new AtlasRenderer(failingAfter(vanilla, "minecraft:oak_planks", asks, Integer.MAX_VALUE));
        for (AtlasOptions options : List.of(oak(id -> false).columns(0).build(), oak(id -> false).columns(-3).build(), oak(id -> false).tileSize(0).build())) {
            RenderException failure = assertThrows(RenderException.class, () -> renderer.render(options));
            assertThat(failure.getMessage(), containsString("must be positive"));
        }
        assertThat("no tile rendered", asks.get(), is(0));
    }

    @Test
    @DisplayName("a sheet whose every subject is refused fails, naming that it has no tile")
    void anAllRefusedSheetFails() {
        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(List.of(ABSENT_BLOCK, ABSENT_ITEM)::contains))
            .tileSize(16)
            .progressLogging(false)
            .build();

        RenderException failure = assertThrows(RenderException.class, () -> new AtlasRenderer(listing(vanilla, 0)).render(options));
        assertThat(failure.getMessage(), containsString("zero tiles"));
    }

    @Test
    @DisplayName("a written sheet is the rendered sheet encoded, placed by the same sidecar, on every background and past refusals")
    void aWrittenSheetIsTheRenderedSheetEncoded(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("atlas.png");
        Files.writeString(file, "a file the write replaces");
        int rows = (oakCount() + 2) / 3;

        for (Background background : BACKGROUNDS) {
            for (int tileSize : new int[]{ 16, 12 }) {
                String label = "%d px over %s".formatted(tileSize, background);
                AtlasOptions options = oak(List.of(ABSENT_BLOCK, ABSENT_ITEM)::contains).columns(3).tileSize(tileSize).background(background).build();
                assertThat(label + " spans several bands", rows, is(greaterThan(2 * AtlasRenderer.bandRows(background, tileSize))));

                AtlasResult.Sidecar written = new AtlasRenderer(listing(vanilla, 1)).write(options, file);
                AtlasResult rendered = new AtlasRenderer(listing(vanilla, 1)).render(options);

                assertThat(label + " refuses both absent ids", skippedIds(rendered), is(List.of(ABSENT_BLOCK, ABSENT_ITEM)));
                assertThat(label, written, is(rendered.sidecar()));
                assertArrayEquals(new ImageFactory().toByteArray(rendered.image(), ImageFormat.PNG), Files.readAllBytes(file), label);
                assertThat(label + " leaves no partial file", Files.exists(directory.resolve("atlas.png.partial")), is(false));
            }
        }
    }

    @Test
    @DisplayName("a written sheet's bands start on a checkerboard row that starts a pair of cells, and are four tile rows otherwise")
    void bandsLineUpWithTheCheckerboard() {
        assertThat(AtlasRenderer.bandRows(Background.TRANSPARENT, 128), is(4));
        assertThat(AtlasRenderer.bandRows(Background.solid(0xFF000000), 7), is(4));
        assertThat("128 px rows over 8 px cells", AtlasRenderer.bandRows(Background.checkerboard(), 128), is(4));
        assertThat("16 px rows over 3 px cells", AtlasRenderer.bandRows(MISALIGNED, 16), is(6));
        assertThat("1 px rows over 8 px cells", AtlasRenderer.bandRows(Background.checkerboard(), 1), is(16));
    }

    /**
     * Counts the oak subjects both passes draw.
     *
     * @return the number of oak tiles a sheet of them holds
     */
    private static int oakCount() {
        return new AtlasRenderer(vanilla).render(oak(id -> false).tileSize(1).build()).sidecar().count();
    }

    /**
     * Starts the options of a sheet of every oak subject, which both passes draw, and whatever else a
     * predicate keeps.
     *
     * @param also what the sheet keeps beside the oak subjects
     * @return the builder, with logging off
     */
    private static @NotNull AtlasOptions.Builder oak(@NotNull Predicate<String> also) {
        Predicate<String> oak = id -> id.contains("oak");
        return AtlasOptions.builder().filter(Optional.of(oak.or(also))).progressLogging(false);
    }

    /**
     * Asserts a sheet lays its tiles down densely: tile {@code i} at column {@code i} modulo the column
     * count and row {@code i} over it, each cell the tile size, the sheet as many rows as the tiles fill,
     * and every cell past the last tile the background alone.
     *
     * @param label what is checked
     * @param atlas the atlas
     * @param options the options it was rendered with
     */
    private static void assertDense(@NotNull String label, @NotNull AtlasResult atlas, @NotNull AtlasOptions options) {
        int columns = options.getColumns();
        int size = options.getTileSize();
        List<AtlasResult.Tile> tiles = atlas.sidecar().tiles();
        int rows = (tiles.size() + columns - 1) / columns;

        assertThat(label + " count", atlas.sidecar().count(), is(tiles.size()));
        assertThat(label + " width", atlas.image().getWidth(), is(columns * size));
        assertThat(label + " height", atlas.image().getHeight(), is(rows * size));
        for (int i = 0; i < tiles.size(); i++) {
            AtlasResult.Tile tile = tiles.get(i);
            assertThat(label + " tile " + i + " cell", List.of(tile.col(), tile.row(), tile.x(), tile.y(), tile.width(), tile.height()),
                is(List.of(i % columns, i / columns, (i % columns) * size, (i / columns) * size, size, size)));
        }

        PixelBuffer background = PixelBuffer.create(columns * size, rows * size);
        options.getBackground().fill(background);
        PixelBuffer sheet = atlas.image().toPixelBuffer();
        for (int i = tiles.size(); i < rows * columns; i++) {
            int x = (i % columns) * size;
            int y = (i / columns) * size;
            assertArrayEquals(background.getPixels(x, y, size, size, null, 0, size), sheet.getPixels(x, y, size, size, null, 0, size),
                label + " empty cell " + i);
        }
    }

    /**
     * Asserts the tiles a context lists keep the order it lists them in.
     *
     * @param label which pass
     * @param tiles the pass's tiles, in sheet order
     * @param known the ids the context lists for the pass, in its order
     */
    private static void assertListedOrder(@NotNull String label, @NotNull List<AtlasResult.Tile> tiles, @NotNull List<String> known) {
        List<Integer> ranks = tiles.stream().map(tile -> known.indexOf(tile.id())).filter(rank -> rank >= 0).toList();
        assertThat(label + " lists most of its tiles", ranks.size() > tiles.size() / 2, is(true));
        for (int i = 1; i < ranks.size(); i++)
            assertThat(label + " keeps the listed order at " + i, ranks.get(i) > ranks.get(i - 1), is(true));
    }

    /**
     * Asserts two sheets hold the same image, byte for byte, and the same tiles.
     *
     * @param label what is compared
     * @param actual the sheet under test
     * @param expected the reference sheet
     */
    private static void assertSameSheet(@NotNull String label, @NotNull AtlasResult actual, @NotNull AtlasResult expected) {
        assertThat(label + " width", actual.image().getWidth(), is(expected.image().getWidth()));
        assertThat(label + " height", actual.image().getHeight(), is(expected.image().getHeight()));
        assertArrayEquals(expected.image().toPixelBuffer().data(), actual.image().toPixelBuffer().data(), label + " pixels");
        assertThat(label + " tiles", actual.sidecar().tiles(), is(expected.sidecar().tiles()));
    }

    /**
     * Renders one subject alone into a one-cell sheet at the options' tile size and background, and
     * reads its cell.
     *
     * @param renderer the renderer
     * @param id the subject
     * @param options the options of the sheet the subject is compared within
     * @return the lone tile's pixels, row by row
     */
    private static int @NotNull [] alone(@NotNull AtlasRenderer renderer, @NotNull String id, @NotNull AtlasOptions options) {
        AtlasResult lone = renderer.render(AtlasOptions.builder()
            .filter(Optional.of(id::equals))
            .columns(1)
            .tileSize(options.getTileSize())
            .background(options.getBackground())
            .progressLogging(false)
            .build());
        return crop(lone, lone.sidecar().tiles().getFirst());
    }

    /**
     * Reads one tile's pixels out of a sheet.
     *
     * @param atlas the sheet
     * @param tile the sidecar row placing the tile
     * @return the tile's pixels, row by row
     */
    private static int @NotNull [] crop(@NotNull AtlasResult atlas, @NotNull AtlasResult.Tile tile) {
        return atlas.image().toPixelBuffer().getPixels(tile.x(), tile.y(), tile.width(), tile.height(), null, 0, tile.width());
    }

    private static @NotNull List<String> skippedIds(@NotNull AtlasResult atlas) {
        return atlas.sidecar().skipped().stream().map(AtlasResult.Skipped::id).toList();
    }

    /**
     * Wraps a context so one block's lookup counts its calls and fails once it has answered the allowed
     * number, as a lookup that serves one render and then breaks would.
     *
     * @param over the context to wrap
     * @param blockId the block whose lookup is counted
     * @param asks the running count of the block's lookups
     * @param allowed how many lookups answer before the rest fail
     * @return the failing context
     */
    private static @NotNull RendererContext failingAfter(@NotNull RendererContext over, @NotNull String blockId, @NotNull AtomicInteger asks, int allowed) {
        return new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return over;
            }

            @Override
            public @NotNull Possible<Block> findBlock(@NotNull String id) {
                if (id.equals(blockId) && asks.incrementAndGet() > allowed)
                    throw new RenderException("Lookup of '%s' failed on a later sheet", id);

                return over.findBlock(id);
            }

        };
    }

    /**
     * Wraps a context so each pass's known ids also list an id its lookup answers absent - the block
     * pass {@link #ABSENT_BLOCK} and the item pass {@link #ABSENT_ITEM} - each placed right after the
     * given count of the pass's oak ids, or at the end of the pass where it has fewer.
     *
     * @param over the context to wrap
     * @param after how many of a pass's oak ids precede its refused one
     * @return the listing context
     */
    private static @NotNull RendererContext listing(@NotNull RendererContext over, int after) {
        return new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return over;
            }

            @Override
            public @NotNull ConcurrentList<String> knownBlockIds() {
                return withId(over.knownBlockIds(), ABSENT_BLOCK);
            }

            @Override
            public @NotNull ConcurrentList<String> knownItemIds() {
                return withId(over.knownItemIds(), ABSENT_ITEM);
            }

            private @NotNull ConcurrentList<String> withId(@NotNull List<String> known, @NotNull String id) {
                ConcurrentList<String> ids = Concurrent.newList(known);
                int seen = 0;
                int at = 0;
                while (at < ids.size() && seen < after)
                    if (ids.get(at++).contains("oak")) seen++;
                ids.add(at, id);
                return ids;
            }

        };
    }

}
