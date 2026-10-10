package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.call.request.ItemModelContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.ItemModelDispatch.FrameItem;
import lib.minecraft.renderer.content.index.ItemModelDispatch;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.store.diff.RenderDigest;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of what a plain item icon draws - one with no stack, or a stack that chooses nothing - for
 * an id the item index carries. Vanilla draws what the item definition's walk lands on, and the item
 * index builds an id's item from its {@code models/item} file, so the indexed item stands in only
 * where the walk lands on that same model or on a special.
 * <p>
 * Every id of the client's item index keeps its indexed item in a plain slot, and no definition
 * decides its inventory icon, so one a block backs whose model is built from elements keeps the
 * block's icon and no vanilla icon moves. A pack whose definitions point an id's plain branch past
 * its {@code models/item} file draws what the branch names in both slot types: another model, the
 * missing model for one no pack ships, vanilla's missing item model, or nothing. A block item whose
 * indexed model is built from elements draws the branch as its inventory icon rather than the block's.
 * An id only a definition names holds the model its plain branch names, a block model or one outside
 * both folders included, unless a block backs it, when it draws as its block does.
 * <p>
 * The pack is written to a temporary directory and stacked over the client, which it reads through
 * the shared client-assets extension rather than acquiring one of its own.
 */
@DisplayName("A plain item icon draws what its definition's walk lands on")
@ExtendWith(ClientAssetsExtension.class)
class PlainItemIconTest {

    /** The canvas every slot render is drawn at. */
    private static final int SIZE = 32;

    /** How many ids of the client's item index hold a definition whose plain walk lands on the indexed model. */
    private static final int OWN_MODEL = 695;

    /** The fixture pack's namespace. */
    private static final String NAMESPACE = "menu";

    /** The colour of every model the item index builds an item from in the fixture pack. */
    private static final int INDEXED = 0xFF00FF00;

    /** The colour of the model a repointed plain branch names, outside {@code models/item}. */
    private static final int NAMED = 0xFF0000FF;

    /** An id whose index row is its own {@code models/item} file and whose definition names another model. */
    private static final String LOCKED = NAMESPACE + ":locked";

    /** The model the definition of {@link #LOCKED} names. */
    private static final String LOCKED_ICON = NAMESPACE + ":icons/locked";

    /** An id whose definition names a model no pack ships. */
    private static final String ABSENT = NAMESPACE + ":absent";

    /** The model the definition of {@link #ABSENT} names. */
    private static final String ABSENT_ICON = NAMESPACE + ":icons/absent";

    /** An id whose definition is an empty branch. */
    private static final String HIDDEN = NAMESPACE + ":hidden";

    /** An id whose definition is a select the slot matches no case of, with no fallback. */
    private static final String UNMATCHED = NAMESPACE + ":unmatched";

    /** An id whose definition names its own {@code models/item} file. */
    private static final String KEPT = NAMESPACE + ":kept";

    /** A block item whose indexed model is built from elements, which the fixture pack repoints. */
    private static final String BIG_DRIPLEAF = "minecraft:big_dripleaf";

    /** An id only a definition names, whose definition names {@link #LOCKED_ICON}, outside {@code models/item}. */
    private static final String OUTSIDE = NAMESPACE + ":outside";

    /** An id only a definition names, whose definition names {@link #STONE_MODEL}. */
    private static final String BLOCK_ICON = NAMESPACE + ":block_icon";

    /** A block's id, whose vanilla definition names {@link #STONE_MODEL}. */
    private static final String STONE = "minecraft:stone";

    /** The vanilla block model {@link #BLOCK_ICON}'s and {@link #STONE}'s definitions name. */
    private static final String STONE_MODEL = "minecraft:block/stone";

    /** The client with the fixture pack stacked over it. */
    private static RendererContext stacked;

    /** The item renderer over the stacked context. */
    private static ItemRenderer renderer;

    /**
     * Writes the fixture pack and stacks it over the client.
     *
     * @param work the directory the pack and the cache root are written under
     * @throws IOException if a fixture file cannot be written
     */
    @BeforeAll
    static void stackThePack(@TempDir Path work) throws IOException {
        Path pack = work.resolve("plainpack");
        Path assets = pack.resolve("assets").resolve(NAMESPACE);
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"plain icon fixture\"}}");

        flatModel(assets, "item/locked", INDEXED);
        flatModel(assets, "icons/locked", NAMED);
        definition(assets, "locked", leaf(LOCKED_ICON));
        flatModel(assets, "item/absent", INDEXED);
        definition(assets, "absent", leaf(ABSENT_ICON));
        flatModel(assets, "item/hidden", INDEXED);
        definition(assets, "hidden", "{\"type\":\"minecraft:empty\"}");
        flatModel(assets, "item/unmatched", INDEXED);
        definition(assets, "unmatched", "{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
            + "\"cases\":[{\"when\":\"head\",\"model\":" + leaf(NAMESPACE + ":item/unmatched") + "}]}");
        flatModel(assets, "item/kept", INDEXED);
        definition(assets, "kept", leaf(NAMESPACE + ":item/kept"));
        definition(assets, "outside", leaf(LOCKED_ICON));
        definition(assets, "block_icon", leaf(STONE_MODEL));
        definition(pack.resolve("assets/minecraft"), "big_dripleaf", leaf(LOCKED_ICON));

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        stacked = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanilla()));
        renderer = new ItemRenderer(stacked);
    }

    @Test
    @DisplayName("every id of the client's item index keeps its indexed item in a plain slot, and its inventory icon's route")
    void everyVanillaIconKeepsItsIndexedItem() {
        RendererContext vanilla = ClientAssetsExtension.context();
        List<String> moved = new ArrayList<>();
        List<String> special = new ArrayList<>();
        int ownModel = 0;
        int undefined = 0;

        for (String id : vanilla.knownItemIds()) {
            // An item that draws nothing, such as air, is listed but holds no indexed item to keep.
            Possible<Item> found = vanilla.findItem(id);
            if (found.getState() == Possible.State.EMPTY) continue;

            Item indexed = found.orElseThrow();
            FrameItem frame = frameOf(vanilla, slot(id, ItemOptions.Type.GUI_2D), indexed);
            if (!frame.equals(FrameItem.Drawn.baked(indexed))) moved.add(id + " draws " + described(frame));

            ItemOptions icon = slot(id, ItemOptions.Type.GUI_ICON);
            Optional<FrameItem> chosen = ItemModelDispatch.definitionItem(vanilla, icon, icon.itemModelAt(ItemOptions.Type.GUI_ICON));
            if (chosen.isPresent()) moved.add(id + " leaves its block icon for " + described(chosen.get()));

            Possible<ItemModelTree> tree = vanilla.findItemTree(id);
            if (tree.isEmpty()) undefined++;
            else if (ItemModelContext.gui().resolve(tree.get()).layers().stream().anyMatch(layer -> layer.special().isPresent())) special.add(id);
            else ownModel++;
        }
        System.out.printf("Plain vanilla icons: %d land on their indexed model, %d on a special, %d have no definition%n",
            ownModel, special.size(), undefined);

        assertThat("vanilla ids whose plain icon leaves the indexed item", moved, is(empty()));
        assertThat("indexed ids whose plain walk lands on their own model", ownModel, is(OWN_MODEL));
        assertThat("indexed ids whose plain walk lands on a special", special, is(List.of("minecraft:shield")));
    }

    @Test
    @DisplayName("a plain branch a pack points at another model draws that model in both slot types, not the id's own models/item file")
    void aRepointedPlainBranchDrawsTheModelItNames() {
        Item indexed = stacked.findItem(LOCKED).orElseThrow();
        assertThat("the index builds the id from its own file", indexed.textures().get("layer0"), is(NAMESPACE + ":item/locked"));

        FrameItem frame = frameOf(stacked, slot(LOCKED, ItemOptions.Type.GUI_2D), indexed);
        assertThat(described(frame), is("Drawn " + LOCKED_ICON));
        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON))
            assertThat(type + " draws the named model", distinctOpaque(renderer.render(slot(LOCKED, type))), is(Set.of(NAMED)));
    }

    @Test
    @DisplayName("a plain branch naming a model no pack ships draws the missing square")
    void aPlainBranchNamingNoShippedModelDrawsTheMissingModel() {
        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON)) {
            RenderResult square = renderer.render(slot(ABSENT, type));
            assertThat(type + " draws the square", distinctOpaque(square), is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
            assertThat(type + " names the model the branch named", square.substitutions(),
                contains(Substitution.leafModel(ABSENT_ICON, ABSENT)));
        }
    }

    @Test
    @DisplayName("a plain branch on vanilla's missing item model draws it, and an empty one draws nothing")
    void aPlainBranchOnTheMissingItemModelOrNothingDrawsIt() {
        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON)) {
            RenderResult unmatched = renderer.render(slot(UNMATCHED, type));
            assertThat(type + " draws the missing item model", distinctOpaque(unmatched),
                is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
            assertThat(type + " names the item whose select fell back to nothing", unmatched.substitutions(),
                contains(Substitution.itemModel(UNMATCHED, Possible.State.ABSENT)));
            assertThat(type + " draws nothing", opaque(renderer.render(slot(HIDDEN, type))), is(0));
        }
    }

    @Test
    @DisplayName("a plain branch naming the id's own models/item file keeps the indexed item")
    void aPlainBranchOnTheIndexedModelKeepsTheIndexedItem() {
        Item indexed = stacked.findItem(KEPT).orElseThrow();

        assertThat(frameOf(stacked, slot(KEPT, ItemOptions.Type.GUI_2D), indexed), is(FrameItem.Drawn.baked(indexed)));
        assertThat(distinctOpaque(renderer.render(slot(KEPT, ItemOptions.Type.GUI_2D))), is(Set.of(INDEXED)));
    }

    @Test
    @DisplayName("a block item whose indexed model is built from elements draws its repointed plain branch as its inventory icon")
    void aBlockItemWithAnElementRowDrawsItsPlainBranch() {
        assertThat("the indexed model is built from elements",
            stacked.findItem(BIG_DRIPLEAF).orElseThrow().model().getElements(), is(not(empty())));
        assertThat("a block backs the id", stacked.findBlock(BIG_DRIPLEAF).isPresent(), is(true));

        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON))
            assertThat(type + " draws the named model", distinctOpaque(renderer.render(slot(BIG_DRIPLEAF, type))), is(Set.of(NAMED)));
    }

    @Test
    @DisplayName("an id only a definition names holds the model outside models/item its plain branch names, and a block's id keeps its block")
    void aDefinitionOffModelsItemHoldsTheModelItNames() {
        Item outside = stacked.findItem(OUTSIDE).orElseThrow();
        assertThat("the row holds the model the definition names", outside.model(),
            is(sameInstance(stacked.findItemModel(LOCKED_ICON).orElseThrow())));
        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON))
            assertThat(type + " draws the named model", distinctOpaque(renderer.render(slot(OUTSIDE, type))), is(Set.of(NAMED)));

        assertThat("a block model backs an id no block backs", stacked.findItem(BLOCK_ICON).orElseThrow().model(),
            is(sameInstance(stacked.findItemModel(STONE_MODEL).orElseThrow())));
        assertThat("a block's id holds no item row", stacked.findItem(STONE).isAbsent(), is(true));
    }

    /**
     * Resolves what the first frame of a plain slot render of an indexed id draws, through the walk the
     * render takes.
     *
     * @param context the renderer context the walk resolves against
     * @param options the render's options
     * @param indexed the item the item index holds for the id
     * @return what the frame draws
     */
    private static @NotNull FrameItem frameOf(@NotNull RendererContext context, @NotNull ItemOptions options, @NotNull Item indexed) {
        return ItemModelDispatch.resolveRenderItem(context, options, CitResult.NONE,
            options.itemModelAt(options.getType()), indexed);
    }

    /**
     * Describes a frame by its kind and the model it names, short enough for a failure list.
     *
     * @param frame the frame
     * @return the description
     */
    private static @NotNull String described(@NotNull FrameItem frame) {
        return switch (frame) {
            case FrameItem.Drawn drawn -> "Drawn " + drawn.modelId().orElse("(indexed)");
            case FrameItem.MissingModel missing -> "MissingModel " + missing.modelId();
            default -> frame.getClass().getSimpleName();
        };
    }

    /**
     * Builds a {@code minecraft:model} leaf naming one model.
     *
     * @param model the model id
     * @return the leaf's JSON
     */
    private static @NotNull String leaf(@NotNull String model) {
        return "{\"type\":\"minecraft:model\",\"model\":\"" + model + "\"}";
    }

    /**
     * Writes a flat model and its one-colour {@code layer0} texture, the texture under
     * {@code textures/item} at the model's own path.
     *
     * @param assets the namespace's assets root
     * @param path the model's path under {@code models}
     * @param argb the texture's one colour
     * @throws IOException if a file cannot be written
     */
    private static void flatModel(@NotNull Path assets, @NotNull String path, int argb) throws IOException {
        String texture = path.startsWith("item/") ? path : "item/" + path;
        write(assets.resolve("models/" + path + ".json"),
            "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + NAMESPACE + ":" + texture + "\"}}");

        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                image.setRGB(x, y, argb);
        Path png = assets.resolve("textures/" + texture + ".png");
        Files.createDirectories(png.getParent());
        ImageIO.write(image, "PNG", png.toFile());
    }

    /**
     * Writes an item definition.
     *
     * @param assets the namespace's assets root
     * @param name the definition's path under {@code items}
     * @param model the definition's {@code model} object
     * @throws IOException if the file cannot be written
     */
    private static void definition(@NotNull Path assets, @NotNull String name, @NotNull String model) throws IOException {
        write(assets.resolve("items/" + name + ".json"), "{\"model\":" + model + "}");
    }

    /**
     * Builds the options for a slot render of one id, with no stack.
     *
     * @param id the item id to render
     * @param type the slot type
     * @return the slot options
     */
    private static @NotNull ItemOptions slot(@NotNull String id, ItemOptions.@NotNull Type type) {
        return ItemOptions.builder()
            .itemId(id)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build())
            .build();
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
     * Counts the pixels a render's first frame carries with any alpha.
     *
     * @param rendered the render
     * @return the non-transparent pixel count
     */
    private static int opaque(@NotNull RenderResult rendered) {
        int count = 0;
        for (int pixel : RenderDigest.firstFramePixels(rendered.image()))
            if ((pixel >>> 24) != 0) count++;
        return count;
    }

    /**
     * Collects the distinct fully-opaque colours a render's first frame carries.
     *
     * @param rendered the render
     * @return every opaque colour present, without duplicates
     */
    private static @NotNull Set<Integer> distinctOpaque(@NotNull RenderResult rendered) {
        Set<Integer> colours = new HashSet<>();
        for (int pixel : RenderDigest.firstFramePixels(rendered.image()))
            if ((pixel >>> 24) == 0xFF) colours.add(pixel);

        return colours;
    }

}
