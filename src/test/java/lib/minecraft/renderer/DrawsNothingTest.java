package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.util.Possible;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.call.request.AnimationOptions;
import lib.minecraft.renderer.call.request.AtlasOptions;
import lib.minecraft.renderer.call.request.BlockOptions;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.result.AtlasResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.GlintPolicy;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.id.ResourceId;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Coverage of what draws nothing at the five render entry points: an id the game registers as a block
 * or an item whose model declares nothing to draw, an item definition rooted at {@code minecraft:empty},
 * and a model that declares nothing to draw where a definition's leaf or a CIT model override names it.
 * <p>
 * Each is known and holds nothing, so each draws an empty frame - the shape its missing picture would
 * have taken, minus the picture - and reports nothing: the missing picture would report a defect that is
 * not there. What tells it from a miss is the lookup's empty answer, so the fluid and portal stand-ins,
 * which the block renderer cannot draw and the index answers absent, and an id nothing knows still draw
 * the missing picture.
 * <p>
 * The context lists the registered ids that draw nothing beside the ids that draw, so a bulk walker
 * meets them too: the atlas draws each as one transparent tile labelled empty, or as the item sprite an
 * id whose block draws nothing carries. An id a context lists while its lookup answers it absent is a
 * context at odds with itself, and the atlas leaves that one subject out as a skipped row.
 * <p>
 * The registered subjects are read off the client context. The definitions and the blank model are a
 * pack laid over it and loaded through the real pipeline, so the empty answers the renderers act on are
 * the lookups' own. Reads the client assets through {@link ClientAssetsExtension}, which abandons the
 * class where nothing has extracted the client yet.
 */
@DisplayName("What draws nothing draws an empty frame")
@ExtendWith(ClientAssetsExtension.class)
class DrawsNothingTest {

    private static final int SIZE = 32;
    private static final String UNKNOWN = "minecraft:definitely_not_a_real_id";
    private static final String AIR = "minecraft:air";
    private static final String CAVE_AIR = "minecraft:cave_air";

    /** The blocks the 26.1 index knows as drawing nothing. */
    private static final List<String> BLOCKS_DRAWING_NOTHING = List.of(
        AIR, CAVE_AIR, "minecraft:void_air", "minecraft:barrier", "minecraft:structure_void",
        "minecraft:moving_piston", "minecraft:light");

    /**
     * Two fluid stand-ins, whose models are blank because another renderer draws them, and an id nothing
     * knows - each a miss the block renderer answers with the missing picture.
     */
    private static final List<String> MISSES = List.of("minecraft:water", "minecraft:bubble_column", UNKNOWN);

    /** An item-index id the fixture pack roots at {@code minecraft:empty}, over its same-named model. */
    private static final String SWORD = "minecraft:diamond_sword";

    /** A block-backed id the item index does not carry, which the fixture pack roots at {@code minecraft:empty}. */
    private static final String STONE = "minecraft:stone";

    /** An item-index id whose fixture definition's leaf names a model that declares nothing to draw. */
    private static final String BLANK_LEAF = "minecraft:iron_sword";

    /** The fixture model that declares nothing to draw. */
    private static final String BLANK_MODEL = "minecraft:item/draws_nothing_test_blank";

    /** An item no fixture file touches, whose own model a CIT override stands in for or is replaced by. */
    private static final String GOLD = "minecraft:golden_sword";

    private static final List<ItemOptions.Type> ITEM_TYPES = List.of(
        ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON, ItemOptions.Type.HELD_3D);

    @TempDir
    static Path work;

    private static RendererContext vanilla;
    private static RendererContext packed;

    @BeforeAll
    static void bootstrapPipeline() throws IOException {
        vanilla = ClientAssetsExtension.context();

        Path pack = work.resolve("drawsnothing");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"draws nothing fixture\"}}");
        write(pack.resolve("assets/minecraft/items/diamond_sword.json"), "{\"model\":{\"type\":\"minecraft:empty\"}}");
        write(pack.resolve("assets/minecraft/items/stone.json"), "{\"model\":{\"type\":\"minecraft:empty\"}}");
        write(pack.resolve("assets/minecraft/items/iron_sword.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + BLANK_MODEL + "\"}}");
        write(pack.resolve("assets/minecraft/models/item/draws_nothing_test_blank.json"), "{\"parent\":\"minecraft:item/generated\"}");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        packed = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanillaRoot()));
    }

    @Test
    @DisplayName("a block the index knows as drawing nothing draws empty frames through both block types")
    void aBlockDrawingNothingDrawsEmptyFrames() {
        for (String id : BLOCKS_DRAWING_NOTHING) {
            assertThat(id + " is known and draws nothing", vanilla.findBlock(id).getState(), is(Possible.State.EMPTY));

            for (BlockOptions.Type type : List.of(BlockOptions.Type.ISOMETRIC_3D, BlockOptions.Type.BLOCK_FACE_2D)) {
                BlockOptions options = block(id, type).build();
                assertDrawsNothing(id + " " + type, () -> new BlockRenderer(vanilla).render(options));
            }
        }
    }

    @Test
    @DisplayName("the context lists every block and item it knows, the ones drawing nothing included, and no stand-in or template")
    void theKnownIdsListWhatDrawsNothing() {
        List<String> blocks = vanilla.knownBlockIds();
        List<String> items = vanilla.knownItemIds();

        assertThat("the listed blocks that draw nothing",
            blocks.stream().filter(id -> vanilla.findBlock(id).getState() == Possible.State.EMPTY).collect(Collectors.toSet()),
            is(Set.copyOf(BLOCKS_DRAWING_NOTHING)));
        assertThat("the listed items that draw nothing",
            items.stream().filter(id -> vanilla.findItem(id).getState() == Possible.State.EMPTY).toList(), is(List.of(AIR)));

        for (String id : List.of("minecraft:water", "minecraft:bubble_column", "minecraft:slab"))
            assertThat(id + " is not a listed block", blocks, not(hasItem(id)));
        assertThat("a template is not a listed item", items, not(hasItem("minecraft:generated")));

        assertThat("every listed block is one findBlock knows",
            blocks.stream().filter(id -> vanilla.findBlock(id).isAbsent()).toList(), is(empty()));
        assertThat("every listed item is one findItem knows",
            items.stream().filter(id -> vanilla.findItem(id).isAbsent()).toList(), is(empty()));
        assertThat("each block is listed once", Set.copyOf(blocks).size(), is(blocks.size()));
        assertThat("each item is listed once", Set.copyOf(items).size(), is(items.size()));
    }

    @Test
    @DisplayName("the atlas draws each registered id that draws nothing as one transparent tile, air once")
    void theAtlasDrawsWhatDrawsNothingAsTransparentTiles() {
        // Barrier, light and structure_void draw nothing as blocks but carry an item sprite, so the item
        // pass draws them; air is an item that draws nothing, so the item pass takes it too. The other
        // three are no item at all and enter through the block pass.
        Map<String, AtlasResult.Tile.Kind> kinds = Map.of(
            AIR, AtlasResult.Tile.Kind.ITEM, "minecraft:barrier", AtlasResult.Tile.Kind.ITEM,
            "minecraft:light", AtlasResult.Tile.Kind.ITEM, "minecraft:structure_void", AtlasResult.Tile.Kind.ITEM,
            CAVE_AIR, AtlasResult.Tile.Kind.BLOCK, "minecraft:void_air", AtlasResult.Tile.Kind.BLOCK,
            "minecraft:moving_piston", AtlasResult.Tile.Kind.BLOCK);
        Set<String> drawn = Set.of("minecraft:barrier", "minecraft:light", "minecraft:structure_void");

        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(BLOCKS_DRAWING_NOTHING::contains))
            .tileSize(SIZE)
            .progressLogging(false)
            .build();
        AtlasResult atlas = new AtlasRenderer(vanilla).render(options);
        List<AtlasResult.Tile> tiles = atlas.sidecar().tiles();

        assertThat("one tile per id, air among them once", tiles.stream().map(AtlasResult.Tile::id).toList(),
            containsInAnyOrder(BLOCKS_DRAWING_NOTHING.toArray()));
        assertThat("no subject is skipped", atlas.sidecar().skipped(), is(empty()));

        ImageFrame sheet = atlas.image().getFrames().getFirst();
        for (AtlasResult.Tile tile : tiles) {
            String label = tile.id();
            assertThat(label + " enters through its pass", tile.kind(), is(kinds.get(tile.id())));

            // The source is what the pass's own lookup answers: an id it knows as drawing nothing is
            // labelled empty, and the three carrying an item sprite are items the index holds.
            int covered = opaque(tileOf(sheet, tile));
            if (drawn.contains(tile.id())) {
                assertThat(label + " draws its item sprite", covered, is(greaterThan(0)));
                assertThat(label + " is labelled an item model", tile.source(), is(AtlasResult.Tile.Source.ITEM_MODEL));
            } else {
                assertThat(label + " is transparent", covered, is(0));
                assertThat(label + " is labelled empty", tile.source(), is(AtlasResult.Tile.Source.EMPTY));
            }
        }
    }

    @Test
    @DisplayName("a block the context lists and its lookup answers absent is skipped, and the sheet completes")
    void aListedBlockItsLookupAnswersAbsentIsSkipped() {
        assertSkipsTheListedAbsentId(AtlasResult.Tile.Kind.BLOCK);
    }

    @Test
    @DisplayName("an item the context lists and its lookup answers absent is skipped, and the sheet completes")
    void aListedItemItsLookupAnswersAbsentIsSkipped() {
        assertSkipsTheListedAbsentId(AtlasResult.Tile.Kind.ITEM);
    }

    @Test
    @DisplayName("an isometric block drawing nothing keeps the frames the missing cube would have drawn on")
    void anIsometricBlockDrawingNothingKeepsTheMissingCubesFrames() {
        AnimationOptions strip = AnimationOptions.builder().frameCount(3).ticksPerFrame(2).build();
        ImageData air = new BlockRenderer(vanilla).render(block(AIR, BlockOptions.Type.ISOMETRIC_3D).animation(strip).build()).image();
        ImageData unknown = new BlockRenderer(vanilla).render(block(UNKNOWN, BlockOptions.Type.ISOMETRIC_3D).animation(strip).build()).image();

        assertThat("one frame per frame of the cube", air.getFrames().size(), is(unknown.getFrames().size()));
        assertThat(air.getFrames().size(), is(3));
        for (ImageFrame frame : air.getFrames()) {
            assertThat(frame.pixels().width(), is(SIZE));
            assertThat(opaque(frame.pixels().data()), is(0));
        }
    }

    @Test
    @DisplayName("a fluid or portal stand-in, and an id nothing knows, still draw the missing picture")
    void aStandInStillDrawsTheMissingPicture() {
        for (String id : MISSES) {
            assertThat(id + " is not one the index knows", vanilla.findBlock(id).getState(), is(Possible.State.ABSENT));

            BlockOptions posed = block(id, BlockOptions.Type.ISOMETRIC_3D).build();
            assertThat(id + " posed draws the missing cube",
                distinctOpaque(new BlockRenderer(vanilla).render(posed)), hasItem(MissingSprite.BLACK_ARGB));
            BlockOptions face = block(id, BlockOptions.Type.BLOCK_FACE_2D).build();
            assertThat(id + " as a face draws the flat square",
                distinctOpaque(new BlockRenderer(vanilla).render(face)),
                containsInAnyOrder(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB));

            ItemOptions held = item(id, ItemOptions.Type.HELD_3D).build();
            assertThat(id + " held draws the missing cube",
                distinctOpaque(new ItemRenderer(vanilla).render(held)), hasItem(MissingSprite.BLACK_ARGB));
        }
    }

    @Test
    @DisplayName("air draws an empty frame in every item type")
    void airDrawsAnEmptyFrameInEveryItemType() {
        assertThat("air is known and draws nothing", vanilla.findItem(AIR).getState(), is(Possible.State.EMPTY));

        for (ItemOptions.Type type : ITEM_TYPES) {
            ItemOptions options = item(AIR, type).build();
            assertDrawsNothing(type.name(), () -> new ItemRenderer(vanilla).render(options));
        }
    }

    @Test
    @DisplayName("a block drawing nothing with no item draws nothing held and as an icon, and the missing square as a flat icon")
    void aBlockWithNoItemDrawsNothingHeldAndAsAnIcon() {
        assertThat("cave_air is no item", vanilla.findItem(CAVE_AIR).getState(), is(Possible.State.ABSENT));

        for (ItemOptions.Type type : List.of(ItemOptions.Type.HELD_3D, ItemOptions.Type.GUI_ICON)) {
            ItemOptions options = item(CAVE_AIR, type).build();
            assertDrawsNothing(type.name(), () -> new ItemRenderer(vanilla).render(options));
        }

        // The flat icon looks in the item index alone, where cave_air is no item at all.
        ItemOptions flat = item(CAVE_AIR, ItemOptions.Type.GUI_2D).build();
        assertThat(distinctOpaque(new ItemRenderer(vanilla).render(flat)),
            containsInAnyOrder(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB));
    }

    @Test
    @DisplayName("a definition rooted at minecraft:empty draws nothing in every type, the indexed id's and a block-backed one's alike")
    void aRootEmptyDefinitionDrawsNothingInEveryType() {
        assertThat(packed.findItemTree(SWORD).getState(), is(Possible.State.EMPTY));
        assertThat("its same-named model keeps the row", packed.findItem(SWORD).getState(), is(Possible.State.PRESENT));
        assertThat(packed.findItemTree(STONE).getState(), is(Possible.State.EMPTY));
        assertThat("an item with no row of its own draws nothing", packed.findItem(STONE).getState(), is(Possible.State.EMPTY));
        assertThat(packed.findBlock(STONE).getState(), is(Possible.State.PRESENT));

        for (String id : List.of(SWORD, STONE))
            for (ItemOptions.Type type : ITEM_TYPES) {
                ItemOptions options = item(id, type).build();
                assertDrawsNothing(id + " " + type, () -> new ItemRenderer(packed).render(options));
            }
    }

    @Test
    @DisplayName("a slot drawing nothing still draws the stack count its request names, and only that")
    void aSlotDrawingNothingDrawsOnlyTheStackCount() {
        ItemOptions counted = item(AIR, ItemOptions.Type.GUI_2D).context(ItemContext.ofStack(stack(AIR, 5))).build();
        int[] air = RenderDigest.firstFramePixels(new ItemRenderer(vanilla).render(counted).image());
        assertThat("the count draws over the empty slot", opaque(air), is(greaterThan(0)));

        ItemOptions sword = item(SWORD, ItemOptions.Type.GUI_2D).context(ItemContext.ofStack(stack(SWORD, 5))).build();
        assertThat("a root-empty definition draws the same count and nothing else",
            RenderDigest.firstFramePixels(new ItemRenderer(packed).render(sword).image()), is(air));
    }

    @Test
    @DisplayName("a CIT model override over a definition rooted at minecraft:empty still draws its model")
    void aCitModelOutranksARootEmptyDefinition() {
        int[] slot = RenderDigest.firstFramePixels(
            new ItemRenderer(withCitModel(packed, "minecraft:item/golden_sword")).render(item(SWORD, ItemOptions.Type.GUI_2D).build()).image());
        assertThat("a slot draws the override", opaque(slot), is(greaterThan(0)));
        assertThat("as the item it belongs to draws it",
            slot, is(RenderDigest.firstFramePixels(new ItemRenderer(packed).render(item(GOLD, ItemOptions.Type.GUI_2D).build()).image())));

        // A flat sword held at its own display pose is seen edge-on, so the held row overrides with a
        // model built from elements, which shows its faces at the block display pose.
        int[] held = RenderDigest.firstFramePixels(
            new ItemRenderer(withCitModel(packed, "minecraft:block/deepslate")).render(item(SWORD, ItemOptions.Type.HELD_3D).build()).image());
        assertThat("held draws the override", opaque(held), is(greaterThan(0)));
    }

    @Test
    @DisplayName("a leaf or a CIT model override naming a model that declares nothing draws nothing, held included, rather than the missing model")
    void aBlankModelDrawsNothing() {
        assertThat(packed.findItemModel(BLANK_MODEL).getState(), is(Possible.State.EMPTY));

        ItemRenderer overridden = new ItemRenderer(withCitModel(packed, BLANK_MODEL));
        for (ItemOptions.Type type : ITEM_TYPES) {
            ItemOptions leaf = item(BLANK_LEAF, type).build();
            assertDrawsNothing("leaf " + type, () -> new ItemRenderer(packed).render(leaf));

            ItemOptions cit = item(GOLD, type).build();
            assertDrawsNothing("CIT " + type, () -> overridden.render(cit));
        }
    }

    /**
     * Renders once, asserting the render neither raises nor reports a missing subject, and that every
     * frame it answers is transparent at the canvas size.
     *
     * @param label what the row renders, for the failure message
     * @param render the render
     */
    private static void assertDrawsNothing(@NotNull String label, @NotNull Supplier<? extends RenderResult> render) {
        RenderResult[] rendered = new RenderResult[1];
        String err = errDuring(() -> rendered[0] = assertDoesNotThrow(render::get, label + " raised"));

        assertThat(label + " reports no missing subject", err, not(containsString("Missing model for")));
        assertThat(label + " answers a frame", rendered[0].image().getFrames().size(), is(greaterThan(0)));
        for (ImageFrame frame : rendered[0].image().getFrames()) {
            assertThat(label + " keeps the canvas", frame.pixels().width(), is(SIZE));
            assertThat(label + " draws nothing", opaque(frame.pixels().data()), is(0));
        }
    }

    /**
     * Renders an atlas over a context that lists {@link #UNKNOWN} among the ids one pass walks while its
     * lookup answers it absent, as a context whose list and lookups disagree would, asserting that the
     * pass leaves that one id out as a skipped row and draws every other tile.
     *
     * @param pass the pass whose known ids name the absent id
     */
    private static void assertSkipsTheListedAbsentId(AtlasResult.Tile.@NotNull Kind pass) {
        RendererContext disagreeing = listing(vanilla, UNKNOWN, pass);
        assertThat(UNKNOWN + " is one no lookup knows", vanilla.findBlock(UNKNOWN).isAbsent() && vanilla.findItem(UNKNOWN).isAbsent(), is(true));

        // Planks enter through the block pass and the stick through the item pass, so both passes draw.
        List<String> subjects = List.of("minecraft:oak_planks", "minecraft:stick", UNKNOWN);
        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(subjects::contains))
            .tileSize(SIZE)
            .progressLogging(false)
            .build();
        AtlasResult atlas = assertDoesNotThrow(() -> new AtlasRenderer(disagreeing).render(options), "the sheet completes");

        assertThat("every other subject draws its tile", atlas.sidecar().tiles().stream().map(AtlasResult.Tile::id).toList(),
            containsInAnyOrder("minecraft:oak_planks", "minecraft:stick"));
        assertThat("a skip holds no cell and is not counted", atlas.sidecar().count(), is(2));
        assertThat("the one skipped row", atlas.sidecar().skipped().stream().map(row -> row.id() + " " + row.kind()).toList(),
            is(List.of(UNKNOWN + " " + pass)));
        assertThat("its reason is the lookup's disagreement with the list", atlas.sidecar().skipped().getFirst().reason(),
            containsString("'" + UNKNOWN + "' is among the context's known ids"));
    }

    /**
     * Wraps a context so one pass's known ids also list an id, leaving every lookup as the wrapped
     * context answers it.
     *
     * @param over the context to wrap
     * @param id the id to list
     * @param pass whose known ids list it - the block or the item ids
     * @return the listing context
     */
    private static @NotNull RendererContext listing(@NotNull RendererContext over, @NotNull String id, AtlasResult.Tile.@NotNull Kind pass) {
        return new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return over;
            }

            @Override
            public @NotNull ConcurrentList<String> knownBlockIds() {
                return pass == AtlasResult.Tile.Kind.BLOCK ? withId(over.knownBlockIds()) : over.knownBlockIds();
            }

            @Override
            public @NotNull ConcurrentList<String> knownItemIds() {
                return pass == AtlasResult.Tile.Kind.ITEM ? withId(over.knownItemIds()) : over.knownItemIds();
            }

            private @NotNull ConcurrentList<String> withId(@NotNull List<String> known) {
                ConcurrentList<String> ids = Concurrent.newList(known);
                ids.add(id);
                return ids;
            }

        };
    }

    /**
     * Reads one tile's pixels out of a composed sheet.
     *
     * @param sheet the sheet's first frame
     * @param tile the sidecar row placing the tile
     * @return the tile's ARGB pixels, row by row
     */
    private static int @NotNull [] tileOf(@NotNull ImageFrame sheet, @NotNull AtlasResult.Tile tile) {
        int width = sheet.pixels().width();
        int[] data = sheet.pixels().data();
        int[] pixels = new int[tile.width() * tile.height()];
        for (int y = 0; y < tile.height(); y++)
            System.arraycopy(data, (tile.y() + y) * width + tile.x(), pixels, y * tile.width(), tile.width());
        return pixels;
    }

    /**
     * Wraps a context so every item render matches a CIT rule overriding its model with the given one.
     *
     * @param over the context to wrap
     * @param modelId the model id the override names
     * @return the overriding context
     */
    private static @NotNull RendererContext withCitModel(@NotNull RendererContext over, @NotNull String modelId) {
        CitResult override = new CitResult(Possible.empty(), Concurrent.newMap(),
            Possible.of(ResourceId.parse(modelId)), GlintPolicy.DEFAULT);
        return new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return over;
            }

            @Override
            public @NotNull CitResult resolveItemTextureOverride(@NotNull ItemContext context) {
                return override;
            }

        };
    }

    /**
     * Counts the pixels carrying any alpha.
     *
     * @param pixels the ARGB pixels
     * @return the non-transparent pixel count
     */
    private static int opaque(int @NotNull [] pixels) {
        int count = 0;
        for (int pixel : pixels)
            if ((pixel >>> 24) != 0) count++;
        return count;
    }

    /**
     * Collects the distinct fully-opaque colours a render's first frame carries.
     *
     * @param rendered the render
     * @return every opaque colour present
     */
    private static @NotNull List<Integer> distinctOpaque(@NotNull RenderResult rendered) {
        return Arrays.stream(RenderDigest.firstFramePixels(rendered.image()))
            .filter(pixel -> (pixel >>> 24) == 0xFF)
            .distinct()
            .boxed()
            .toList();
    }

    /**
     * Builds a 26.1 stack of the given size carrying no components.
     *
     * @param itemId the stack's item id
     * @param count the stack size
     * @return the stack
     */
    private static @NotNull CompoundTag stack(@NotNull String itemId, int count) {
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(itemId));
        stack.put("count", new IntTag(count));
        stack.put("components", new CompoundTag());
        return stack;
    }

    /**
     * Starts block options for one id at the shared canvas.
     *
     * @param id the block id
     * @param type the render mode
     * @return the options builder
     */
    private static @NotNull BlockOptions.Builder block(@NotNull String id, BlockOptions.@NotNull Type type) {
        return BlockOptions.builder()
            .blockId(id)
            .type(type)
            .output(OutputOptions.builder().canvasSize(SIZE).build());
    }

    /**
     * Starts item options for one id at the shared canvas.
     *
     * @param id the item id
     * @param type the render mode
     * @return the options builder
     */
    private static @NotNull ItemOptions.Builder item(@NotNull String id, ItemOptions.@NotNull Type type) {
        return ItemOptions.builder()
            .itemId(id)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build());
    }

    /**
     * Writes one fixture file, creating its parent directories.
     *
     * @param path the file to write
     * @param content the text it holds
     * @throws IOException if the file cannot be written
     */
    private static void write(@NotNull Path path, @NotNull String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    /**
     * Runs a body with {@code System.err} captured, restoring the real stream afterwards.
     *
     * @param body the call whose diagnostic output is being read
     * @return everything the body wrote to {@code System.err}
     */
    private static @NotNull String errDuring(@NotNull Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try {
            body.run();
        } finally {
            System.setErr(original);
        }

        return captured.toString(StandardCharsets.UTF_8);
    }

}
