package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.Background;
import dev.simplified.util.Possible;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.call.request.AtlasOptions;
import lib.minecraft.renderer.call.request.GridOptions;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.result.AtlasResult;
import lib.minecraft.renderer.call.result.GridResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of the atlas's {@code custom_model_data} branch tiles: each distinct branch of an id's item
 * definition is a tile of its own, the slot icon of the stack that picks it, keyed by that stack and
 * filtered by the model it lands on.
 * <p>
 * A fixture pack stacked over the client is shaped like an ItemsAdder export. It rewrites
 * {@code minecraft:paper} as a {@code range_dispatch} on {@code custom_model_data}, written with bare
 * type ids as such a pack writes them and its entries out of threshold order, whose thresholds name a
 * model outside {@code models/item/}, a model in a folder below it - neither of which names an item -
 * the paper's own model, which draws what the plain icon draws, the outside model again, and an
 * {@code empty} node, which lands on no model. It wraps vanilla's player head special in a one-entry
 * dispatch whose entry names another base, which the block pass lists. It dispatches
 * {@code minecraft:stick} at index 1, below the fallback of a {@code range_dispatch} on damage and a
 * {@code select} on the display context, under a scale no float divides into its threshold exactly,
 * and falls back to a dispatch at index 2 under that scale negated. No vanilla definition names
 * {@code custom_model_data}, so a vanilla sheet holds no branch tile and these rows are the only
 * witness.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where nothing
 * has extracted the client yet.
 */
@DisplayName("The atlas draws each custom_model_data branch of a definition as a tile of its own")
@ExtendWith(ClientAssetsExtension.class)
class AtlasRendererBranchTest {

    /** The item the fixture pack rewrites as a dispatch over its custom models. */
    private static final String PAPER = "minecraft:paper";

    /** The block-backed item the fixture pack wraps in a one-entry dispatch. */
    private static final String HEAD = "minecraft:player_head";

    /** The item the fixture pack dispatches at indices 1 and 2, under a scale and its negation, below two dispatch nodes. */
    private static final String STICK = "minecraft:stick";

    /** The fixture model outside {@code models/item/}, flat over a red texture. */
    private static final String RED_MODEL = "fixture:orb/red";

    /** The fixture model in a folder below {@code models/item/}, flat over {@link #GREEN_TEXTURE}. */
    private static final String GREEN_MODEL = "fixture:item/ia_auto/green";

    /** The texture only {@link #GREEN_MODEL} draws. */
    private static final String GREEN_TEXTURE = "fixture:orb/green";

    /** The vanilla model the stick's branch at index 1 lands on. */
    private static final String APPLE_MODEL = "minecraft:item/apple";

    /** The vanilla model the stick's branch at index 2 lands on. */
    private static final String BREAD_MODEL = "minecraft:item/bread";

    /** The paper's own model, which its third threshold names again. */
    private static final String PAPER_MODEL = "minecraft:item/paper";

    /** The paper branch landing on {@link #RED_MODEL}. */
    private static final String PAPER_RED = "minecraft:paper[custom_model_data={floats:[10000.0f]}]";

    /** The paper branch landing on {@link #GREEN_MODEL}. */
    private static final String PAPER_GREEN = "minecraft:paper[custom_model_data={floats:[10001.0f]}]";

    /** The paper threshold naming the paper's own model, which draws the plain icon and so is no tile. */
    private static final String PAPER_AGAIN = "minecraft:paper[custom_model_data={floats:[10002.0f]}]";

    /** The paper threshold naming {@link #RED_MODEL} again, which draws what {@link #PAPER_RED} draws and so is no tile. */
    private static final String PAPER_RED_AGAIN = "minecraft:paper[custom_model_data={floats:[10003.0f]}]";

    /** The paper branch landing on an {@code empty} node, which names no model and so is filtered by its item id. */
    private static final String PAPER_EMPTY = "minecraft:paper[custom_model_data={floats:[10004.0f]}]";

    /** The player head branch, a special whose base is {@link #RED_MODEL}. */
    private static final String HEAD_RED = "minecraft:player_head[custom_model_data={floats:[20000.0f]}]";

    /**
     * The stick branch at index 1: threshold 15 under scale 0.3, which the quotient {@code 49.999996}
     * falls short of and {@code 50.0}, the next float up, reaches.
     */
    private static final String STICK_APPLE = "minecraft:stick[custom_model_data={floats:[0.0f,50.0f]}]";

    /**
     * The stick branch at index 2: threshold 15 under scale -0.3, which the quotient {@code -49.999996}
     * falls short of and {@code -50.0}, the next float down, reaches.
     */
    private static final String STICK_BREAD = "minecraft:stick[custom_model_data={floats:[0.0f,0.0f,-50.0f]}]";

    /** A filter on a pack's own namespaces, keeping what lies outside the {@code minecraft:} namespace. */
    private static final Predicate<String> OUTSIDE_MINECRAFT = id -> !id.startsWith("minecraft:");

    /** A filter keeping the three rewritten ids and every model their branches land on, the paper's own among them. */
    private static final Predicate<String> FIXTURE_SUBJECTS =
        Set.of(PAPER, HEAD, STICK, RED_MODEL, GREEN_MODEL, APPLE_MODEL, BREAD_MODEL, PAPER_MODEL)::contains;

    /**
     * A filter keeping what a model id spells and no block or item id does - a path - so it keeps a branch
     * tile landing on a model, tested by that model, and never a plain tile or a branch landing on no
     * model, each tested by its id.
     */
    private static final Predicate<String> MODEL_IDS = key -> key.contains("/");

    /** The client with the fixture pack stacked over it. */
    private static RendererContext stacked;

    /**
     * Writes the fixture pack and stacks it over the client.
     *
     * @param work the directory the pack and the cache root are written under
     * @throws IOException if a fixture file cannot be written
     */
    @BeforeAll
    static void stackThePack(@TempDir Path work) throws IOException {
        Path pack = work.resolve("branchpack");
        Path fixture = pack.resolve("assets/fixture");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"atlas branch fixture\"}}");

        png(fixture.resolve("textures/orb/red.png"), 0xFFFF0000);
        png(fixture.resolve("textures/orb/green.png"), 0xFF00FF00);
        write(fixture.resolve("models/orb/red.json"),
            "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"fixture:orb/red\"}}");
        write(fixture.resolve("models/item/ia_auto/green.json"),
            "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"fixture:orb/green\"}}");

        Path items = pack.resolve("assets/minecraft/items");
        write(items.resolve("paper.json"), "{\"model\":{\"type\":\"range_dispatch\",\"property\":\"custom_model_data\",\"index\":0,"
            + "\"fallback\":" + model(PAPER_MODEL) + ",\"entries\":["
            + entry(10001, model(GREEN_MODEL)) + ","
            + entry(10000, model(RED_MODEL)) + ","
            + entry(10002, model(PAPER_MODEL)) + ","
            + entry(10003, model(RED_MODEL)) + ","
            + entry(10004, "{\"type\":\"empty\"}") + "]}}");
        write(items.resolve("player_head.json"), "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\","
            + "\"fallback\":" + head("minecraft:item/template_skull") + ",\"entries\":[" + entry(20000, head(RED_MODEL)) + "]}}");
        write(items.resolve("stick.json"), "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:damage\","
            + "\"entries\":[{\"threshold\":0.5,\"model\":" + model("minecraft:item/stick") + "}],"
            + "\"fallback\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\",\"cases\":[{\"when\":\"gui\","
            + "\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\",\"index\":1,\"scale\":0.3,"
            + "\"fallback\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\",\"index\":2,\"scale\":-0.3,"
            + "\"fallback\":" + model("minecraft:item/stick") + ",\"entries\":[" + entry(15, model(BREAD_MODEL)) + "]},"
            + "\"entries\":[" + entry(15, model(APPLE_MODEL)) + "]}}],"
            + "\"fallback\":" + model("minecraft:item/stick") + "}}}");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        stacked = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanilla()));
    }

    @Test
    @DisplayName("a filter on the pack's own namespaces keeps the branches landing on its models, block pass first, and no plain minecraft: tile")
    void aNamespaceFilterKeepsThePacksBranches() {
        AtlasResult atlas = new AtlasRenderer(stacked).render(options(OUTSIDE_MINECRAFT).tileSize(16).build());

        assertThat(ids(atlas), is(List.of(HEAD_RED, PAPER_RED, PAPER_GREEN)));
        assertThat(atlas.sidecar().tiles().stream().map(AtlasResult.Tile::kind).toList(),
            is(List.of(AtlasResult.Tile.Kind.BLOCK, AtlasResult.Tile.Kind.ITEM, AtlasResult.Tile.Kind.ITEM)));
        assertThat("no tile draws a stand-in", atlas.substitutions(), is(empty()));
    }

    @Test
    @DisplayName("each branch tile follows its id's plain tile, ascending, and a threshold drawing the plain icon or an earlier tile adds none")
    void eachBranchFollowsItsIdsPlainTile() {
        AtlasResult atlas = new AtlasRenderer(stacked).render(options(FIXTURE_SUBJECTS).tileSize(16).build());
        Map<String, List<String>> groups = Map.of(PAPER, List.of(PAPER, PAPER_RED, PAPER_GREEN, PAPER_EMPTY), STICK, List.of(STICK, STICK_APPLE, STICK_BREAD));
        List<String> expected = new ArrayList<>(List.of(HEAD, HEAD_RED));
        Stream.of(PAPER, STICK)
            .sorted(Comparator.comparingInt(stacked.knownItemIds()::indexOf))
            .forEach(id -> expected.addAll(groups.get(id)));

        assertThat(ids(atlas), is(expected));
        assertThat("the threshold naming the paper's own model is no tile", ids(atlas).contains(PAPER_AGAIN), is(false));
        assertThat("the threshold naming an earlier threshold's model is no tile", ids(atlas).contains(PAPER_RED_AGAIN), is(false));
    }

    @Test
    @DisplayName("each branch tile is its stack drawn alone as the slot icon, and not the plain icon")
    void eachBranchTileIsItsStackDrawnAlone() {
        AtlasOptions options = options(FIXTURE_SUBJECTS).tileSize(32).background(Background.checkerboard()).build();
        AtlasResult atlas = new AtlasRenderer(stacked).render(options);
        Map<String, CompoundTag> stacks = Map.of(
            HEAD_RED, stack(HEAD, 0, 20000f),
            PAPER_RED, stack(PAPER, 0, 10000f),
            PAPER_GREEN, stack(PAPER, 0, 10001f),
            PAPER_EMPTY, stack(PAPER, 0, 10004f),
            STICK_APPLE, stack(STICK, 1, 50f),
            STICK_BREAD, stack(STICK, 2, -50f));
        List<AtlasResult.Tile> branches = atlas.sidecar().tiles().stream().filter(tile -> stacks.containsKey(tile.id())).toList();

        assertThat("every branch has a tile", branches.size(), is(stacks.size()));
        for (AtlasResult.Tile tile : branches)
            assertArrayEquals(alone(tile.kind(), stacks.get(tile.id()), options), crop(atlas, tile), tile.id());

        int[] plain = crop(atlas, tile(atlas, PAPER));
        for (String branch : List.of(PAPER_RED, PAPER_GREEN))
            assertThat(branch + " is not the plain paper", Arrays.equals(crop(atlas, tile(atlas, branch)), plain), is(false));
    }

    @Test
    @DisplayName("a branch row names the model its walk lands on, a special's base for a special, and a plain row and a branch landing on no model name none")
    void aBranchRowNamesItsModel() {
        AtlasResult atlas = new AtlasRenderer(stacked).render(options(FIXTURE_SUBJECTS).tileSize(16).build());

        assertThat(tile(atlas, PAPER_RED).model(), is(Optional.of(RED_MODEL)));
        assertThat(tile(atlas, PAPER_GREEN).model(), is(Optional.of(GREEN_MODEL)));
        assertThat(tile(atlas, HEAD_RED).model(), is(Optional.of(RED_MODEL)));
        assertThat(tile(atlas, STICK_APPLE).model(), is(Optional.of(APPLE_MODEL)));
        assertThat(tile(atlas, STICK_BREAD).model(), is(Optional.of(BREAD_MODEL)));
        assertThat(tile(atlas, PAPER_EMPTY).model(), is(Optional.empty()));
        for (String plain : List.of(PAPER, HEAD, STICK))
            assertThat(plain, tile(atlas, plain).model(), is(Optional.empty()));
    }

    @Test
    @DisplayName("a filter keeping only model ids keeps every branch landing on a model and no plain tile, a branch landing on none tested by its item id")
    void aModelFilterKeepsEveryBranchLandingOnAModel() {
        AtlasResult atlas = new AtlasRenderer(stacked).render(options(MODEL_IDS).tileSize(16).build());

        assertThat(ids(atlas), is(List.of(HEAD_RED, PAPER_RED, PAPER_GREEN, STICK_APPLE, STICK_BREAD)));
    }

    @Test
    @DisplayName("over vanilla alone a filter keeping only model ids keeps nothing, as no definition names custom_model_data")
    void vanillaHasNoBranch() {
        AtlasOptions options = options(MODEL_IDS).tileSize(16).build();

        RenderException failure = assertThrows(RenderException.class, () -> new AtlasRenderer(ClientAssetsExtension.context()).render(options));
        assertThat(failure.getMessage(), containsString("zero tiles"));
    }

    @Test
    @DisplayName("a branch whose render fails is a skipped row carrying its stack, and the sheet is the one laid down without it")
    void aRefusedBranchIsSkippedByItsStack() {
        RendererContext failing = new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return stacked;
            }

            @Override
            public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
                if (textureId.equals(GREEN_TEXTURE))
                    throw new RenderException("Texture '%s' failed to read", textureId);

                return stacked.resolveTexture(textureId);
            }

        };
        AtlasResult refusing = new AtlasRenderer(failing).render(options(OUTSIDE_MINECRAFT).tileSize(16).build());

        assertThat(refusing.sidecar().skipped().stream().map(AtlasResult.Skipped::id).toList(), is(List.of(PAPER_GREEN)));
        assertThat(refusing.sidecar().skipped().getFirst().kind(), is(AtlasResult.Tile.Kind.ITEM));
        assertThat(refusing.sidecar().skipped().getFirst().reason(), containsString("failed to read"));
        AtlasResult without = new AtlasRenderer(stacked).render(options(RED_MODEL::equals).tileSize(16).build());
        assertArrayEquals(without.image().toPixelBuffer().data(), refusing.image().toPixelBuffer().data(), "the sheet without the refused branch");
        assertThat(refusing.sidecar().tiles(), is(without.sidecar().tiles()));
    }

    /**
     * Starts the options of a sheet keeping what a filter keeps, eight columns wide, with logging off.
     *
     * @param filter what the sheet keeps
     * @return the builder
     */
    private static @NotNull AtlasOptions.Builder options(@NotNull Predicate<String> filter) {
        return AtlasOptions.builder().filter(Optional.of(filter)).columns(8).progressLogging(false);
    }

    /**
     * Draws a stack alone as the atlas draws a tile of the given pass, into a one-cell sheet over the
     * options' background, and reads its cell.
     *
     * @param kind the pass the tile was laid down by
     * @param stack the stack
     * @param options the options of the sheet the tile is compared within
     * @return the lone tile's pixels, row by row
     */
    private static int @NotNull [] alone(@NotNull AtlasResult.Tile.Kind kind, @NotNull CompoundTag stack, @NotNull AtlasOptions options) {
        int size = options.getTileSize();
        ItemOptions.Builder icon = ItemOptions.builder()
            .itemId(((StringTag) stack.get("id")).getValue())
            .type(ItemOptions.Type.GUI_ICON)
            .context(ItemContext.ofStack(stack));
        if (kind == AtlasResult.Tile.Kind.BLOCK)
            icon.output(OutputOptions.builder().canvasSize(size).build());
        else
            icon.output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(size).build()).animateGlint(false);

        RenderResult drawn = new ItemRenderer(stacked).render(icon.build());
        GridResult sheet = new GridRenderer().render(GridOptions.builder()
            .tiles(Concurrent.newList(List.of(new GridOptions.GridTile(0, 0, drawn))))
            .cellSize(size)
            .columns(1)
            .rows(1)
            .background(options.getBackground())
            .build());
        return sheet.image().toPixelBuffer().getPixels(0, 0, size, size, null, 0, size);
    }

    /**
     * Builds a stack whose {@code custom_model_data} carries one float at an index, zeros before it.
     *
     * @param itemId the stack's item id
     * @param index the float's index
     * @param value the float
     * @return the stack
     */
    private static @NotNull CompoundTag stack(@NotNull String itemId, int index, float value) {
        ListTag<FloatTag> floats = new ListTag<>();
        for (int i = 0; i < index; i++)
            floats.add(new FloatTag(0f));
        floats.add(new FloatTag(value));
        CompoundTag data = new CompoundTag();
        data.put("floats", floats);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_model_data", data);
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(itemId));
        stack.put("components", components);
        return stack;
    }

    /**
     * Lists a sheet's tile ids, in the order its tiles were laid down.
     *
     * @param atlas the sheet
     * @return the ids
     */
    private static @NotNull List<String> ids(@NotNull AtlasResult atlas) {
        return atlas.sidecar().tiles().stream().map(AtlasResult.Tile::id).toList();
    }

    /**
     * Finds the row of one tile.
     *
     * @param atlas the sheet
     * @param id the tile's id
     * @return the row
     */
    private static @NotNull AtlasResult.Tile tile(@NotNull AtlasResult atlas, @NotNull String id) {
        return atlas.sidecar().tiles().stream().filter(tile -> tile.id().equals(id)).findFirst().orElseThrow();
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

    /**
     * Writes a {@code model} node, its type id bare as the pack writes it.
     *
     * @param model the model id
     * @return the node
     */
    private static @NotNull String model(@NotNull String model) {
        return "{\"type\":\"model\",\"model\":\"" + model + "\"}";
    }

    /**
     * Writes a range dispatch entry.
     *
     * @param threshold the entry's threshold
     * @param node the node it picks
     * @return the entry object
     */
    private static @NotNull String entry(int threshold, @NotNull String node) {
        return "{\"threshold\":" + threshold + ",\"model\":" + node + "}";
    }

    /**
     * Writes vanilla's player head special with the given base - its kind and its transformation.
     *
     * @param base the special's base model id
     * @return the special node
     */
    private static @NotNull String head(@NotNull String base) {
        return "{\"type\":\"minecraft:special\",\"base\":\"" + base + "\",\"model\":{\"type\":\"minecraft:player_head\"},"
            + "\"transformation\":{\"left_rotation\":[1.0,0.0,0.0,-0.0],\"right_rotation\":[0.0,0.0,0.0,1.0],"
            + "\"scale\":[1.0,1.0,1.0],\"translation\":[0.5,0.0,0.5]}}";
    }

    /**
     * Writes a solid 16x16 texture.
     *
     * @param png the file to write
     * @param argb its one colour
     * @throws IOException if the file cannot be written
     */
    private static void png(@NotNull Path png, int argb) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                image.setRGB(x, y, argb);

        Files.createDirectories(png.getParent());
        ImageIO.write(image, "PNG", png.toFile());
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

}
