package lib.minecraft.renderer;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageData;
import dev.simplified.util.Possible;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemModelContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.ItemModelDispatch.FrameItem;
import lib.minecraft.renderer.content.index.ItemModelDispatch;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.pack.PackAcquisition;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.sweep.LeafStacks.Reach;
import lib.minecraft.renderer.sweep.LeafStacks;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coverage of what a caller's item stack reaches in Hypixel+ 0.23.4 for 1.21.8 stacked over the
 * client - a pack that shadows vanilla items with item definitions whose branches sit behind tests on
 * the stack's components, and names models under its own {@code hplus} namespace from them.
 * <p>
 * In order: every definition the pack ships decodes but {@code player_head}'s and {@code red_bed}'s;
 * the whole {@code models/} tree loads, and the read is timed; the block items the pack shadows keep
 * their block icons; every leaf a {@code custom_data} test, a custom name, a dyed colour or a display
 * context guards is reached by the 26.1 stack built from the pack's own values, and the model lookup
 * answers it; a stack choosing no branch renders as no stack; three chosen branches draw; every
 * leaf past a composite's first child draws in its frame, over the first child in a slot and held;
 * every element model a stack chooses at the slot draws there - each named drawing written under
 * {@code build/hypixel-plus-reach} for a look; and every id the pack shadows that the item index
 * carries keeps its indexed item in a plain slot.
 * <p>
 * The leaves are read from the pack's JSON by {@link LeafStacks} rather than from the decoded tree the
 * renderer walks. It gathers the steps on the path to each leaf and solves them for the one stack that
 * takes them, or finds that none can - a test an earlier sibling answers first, or two guards that want
 * different values of one component. A leaf behind any other guard - a held key, a cast rod, a use
 * duration, a lore - needs state an icon has no value for, and is counted rather than walked.
 * <p>
 * The pack is read by path from the texture-pack cache and nothing here reaches the network, so the
 * class runs in the fast suite. It reads the client assets through {@link ClientAssetsExtension}, which
 * abandons the class where nothing has extracted the client, and it abandons itself where the pack is
 * absent, so a green run alone is no evidence that any assertion here ran.
 */
@DisplayName("What a 26.1 item stack reaches in Hypixel+ stacked over the client")
@ExtendWith(ClientAssetsExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HypixelPlusReachTest {

    /** The pack, read in place from the texture-pack cache by path. */
    private static final @NotNull Path PACK = Path.of("cache/texturepacks/Hypixel+ 0.23.4 for 1.21.8.zip");

    /** Where the three chosen branches are drawn for a look. */
    private static final @NotNull Path LOOK_DIRECTORY = Path.of("build/hypixel-plus-reach");

    /** The archive directory the pack's item definitions sit in. */
    private static final @NotNull String ITEMS = "assets/minecraft/items/";

    /** The archive directory the pack's own models sit under, at any depth. */
    private static final @NotNull String PACK_MODELS = "assets/hplus/models/";

    /** The model namespace the pack's own models load under. */
    private static final @NotNull String PACK_NAMESPACE = "hplus:";

    /**
     * The definitions the pack ships that the loader refuses, as vanilla refuses them too, sorted:
     * {@code player_head}'s nests past the JSON reader's limit, and {@code red_bed}'s names a bed
     * special model with no {@code part}, which the bed's codec requires.
     */
    private static final @NotNull List<String> REFUSED = List.of("minecraft:player_head", "minecraft:red_bed");


    /** How many of the block items vanilla projects the pack shadows with a definition of its own. */
    private static final int SHADOWED_BLOCK_ITEMS = 87;

    /**
     * How many of the ids the item index carries the pack shadows with a definition of its own: 229
     * named by their {@code models/item} file, and the clock and the compass, which the index builds
     * from the model their definition's walk lands on.
     */
    private static final int SHADOWED_INDEXED_ITEMS = 231;

    /** A custom data id no test in the pack names, so a stack carrying it chooses no branch. */
    private static final @NotNull String UNMATCHED = "HYPIXEL_PLUS_REACH_TEST_UNMATCHED";

    /** The canvas every render here draws at. */
    private static final int SIZE = 128;

    /** The client with the pack stacked over it. */
    private static PackStack packs;

    /** The context loaded over that stack. */
    private static RendererContext stacked;

    /** The context loaded over the client alone. */
    private static RendererContext vanilla;

    /** The item renderer over the stacked context. */
    private static ItemRenderer renderer;

    /** Every item id the pack ships a definition for, sorted. */
    private static List<String> shipped;

    /** The {@code model} member of every definition that reads as JSON, keyed by item id. */
    private static Map<String, JsonElement> definitions;

    /** How many model files the pack ships under its own namespace. */
    private static int packModels;

    /** Every model leaf of the definitions the loader decoded, with what reaching it takes. */
    private static List<Reach> reaches;

    /**
     * Stacks the pack over the client, loads a context over the stack, and walks the pack's own JSON
     * for every leaf of a definition the loader decoded.
     *
     * @param cache the cache root the acquisition is pointed at, which nothing here downloads into
     * @throws IOException if the pack cannot be read
     */
    @BeforeAll
    static void stackThePack(@TempDir Path cache) throws IOException {
        assumeTrue(Files.isRegularFile(PACK), () -> "Hypixel+ is not at '" + PACK + "'");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(cache.toFile())
            .texturePacks(Concurrent.adoptList(List.of(PACK.toFile())))
            .build();
        ClientAssets assets = new ClientAssets(options, ClientAssetsExtension.vanilla());
        packs = PackAcquisition.acquire(assets);
        stacked = RendererContext.load(assets);
        vanilla = ClientAssetsExtension.context();
        renderer = new ItemRenderer(stacked);

        readThePack();
        List<LeafStacks.Leaf> leaves = new ArrayList<>();
        definitions.forEach((id, model) -> {
            if (stacked.findItemTree(id).filter(tree -> !tree.isRejected()).isPresent())
                leaves.addAll(LeafStacks.leaves(id, model));
        });
        reaches = leaves.stream().map(Reach::of).toList();
    }

    /**
     * Pins the decode as strict as vanilla's and no stricter, ahead of everything that reads the
     * decoded trees: of every definition the pack ships, the loader refuses exactly the two vanilla
     * refuses.
     */
    @Test
    @Order(1)
    @DisplayName("every item definition the pack ships decodes, but player_head's and red_bed's")
    void everyDefinitionButPlayerHeadDecodes() {
        List<String> refused = shipped.stream()
            .filter(id -> stacked.findItemTree(id).map(ItemModelTree::isRejected).orElse(true))
            .toList();

        assertThat("definitions the loader refused", refused, is(REFUSED));
        assertThat("red_bed is held as a refused definition, shadowing vanilla's",
            stacked.findItemTree("minecraft:red_bed").map(ItemModelTree::isRejected), is(Possible.of(true)));
    }

    @Test
    @Order(2)
    @DisplayName("the whole models/ tree of the client and the pack loads, every model the pack ships with it")
    void theWholeModelTreeLoads() {
        long started = System.nanoTime();
        ResolvedModels models = ResolvedModels.load(packs);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        long loaded = models.all().keySet().stream().filter(id -> id.startsWith(PACK_NAMESPACE)).count();
        System.out.printf("ResolvedModels.load over the client and Hypixel+: %d ms, %d models, %d of them %s%n",
            elapsed, models.all().size(), loaded, PACK_NAMESPACE);

        assertThat("every model file the pack ships under its own namespace loads", loaded, is((long) packModels));
    }

    @Test
    @Order(3)
    @DisplayName("every block item the pack shadows keeps the block icon vanilla's own definition gives it")
    void theShadowedBlockItemsKeepTheirIcons() {
        List<String> shadowed = shipped.stream()
            .filter(id -> vanilla.findItemTree(id).toOptional().flatMap(tree -> ItemModelContext.gui().resolve(tree).blockModel()).isPresent())
            .toList();
        assertThat("block items vanilla projects that the pack shadows", shadowed, hasSize(SHADOWED_BLOCK_ITEMS));

        List<String> plain = shadowed.stream()
            .filter(id -> stacked.findItemTree(id).map(ItemModelTree::root).filter(ItemModelNode.Model.class::isInstance).isPresent())
            .toList();
        assertThat("shadowing definitions rooted at a plain model rather than a component test", plain, is(empty()));

        List<String> lost = shadowed.stream()
            .filter(id -> !stacked.findBlock(id).map(Block::modelIcon).orElse(false))
            .toList();
        assertThat("shadowed block items without their block icon", lost, is(empty()));
    }

    /**
     * Walks every leaf a stack's components or a display context choose, each with the stack its
     * guards solve to, through the bridge an item render takes its walk context from. A leaf past the
     * first child of a {@code composite} is one of the layers the walk lands on, drawn over the ones
     * before it, so it is held to being one of them. Every leaf this class's walk enumerates must be
     * reached - a count is printed rather than pinned - and the model lookup must answer every model a
     * leaf names.
     */
    @Test
    @Order(4)
    @DisplayName("every leaf a stack's custom data, custom name, dyed colour or display context guards is reached by the stack built from the pack's own values")
    void everyGuardedLeafIsReached() {
        List<String> missed = new ArrayList<>();
        List<String> unanswered = new ArrayList<>();
        Map<Route, List<Reach>> routed = new EnumMap<>(Route.class);

        for (Reach reach : reaches) {
            if (!reach.walkable()) {
                routed.computeIfAbsent(Route.unwalked(reach), route -> new ArrayList<>()).add(reach);
                continue;
            }

            ItemModelNode.Resolution walked = walk(reach);
            boolean landed = walked.composed() == reach.composed() && (reach.later()
                ? walked.layers().stream().anyMatch(layer -> layer.modelId().equals(Optional.of(reach.leaf().model())))
                : walked.modelId().equals(Optional.of(reach.leaf().model())));
            if (!landed) missed.add(reach + " walked to " + walked);
            // A model the pack ships that declares nothing to draw is answered, empty, as it draws nothing.
            if (stacked.findItemModel(reach.leaf().model()).isAbsent()) unanswered.add(reach.leaf().model());
            routed.computeIfAbsent(routeOf(reach), route -> new ArrayList<>()).add(reach);
        }

        System.out.printf("%-40s %8s %8s%n", "Hypixel+ leaves by route", "leaves", "models");
        routed.forEach((route, members) -> System.out.printf("%-40s %8d %8d%n", route.label(), members.size(),
            members.stream().map(reach -> reach.leaf().model()).distinct().count()));

        assertThat("leaves the stack built for them does not reach", missed, is(empty()));
        assertThat("models a reached leaf names that the lookup does not answer", unanswered, is(empty()));
        assertThat("leaves a stack reaches and a frame draws",
            routed.entrySet().stream().filter(entry -> entry.getKey().drawn()).mapToInt(entry -> entry.getValue().size()).sum(),
            is(greaterThan(0)));
    }

    @Test
    @Order(5)
    @DisplayName("a stack whose custom data no test names renders byte-identical to no stack, in every type")
    void anUnsteeredStackRendersAsNoStack() {
        // The stack carries no dyed colour: the walk is what an unsteering stack leaves alone, and a
        // dyed_color would tint a dye source whatever the walk chose.
        CompoundTag components = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.put("id", new StringTag(UNMATCHED));
        components.put("minecraft:custom_data", data);

        for (String id : List.of("minecraft:diamond_sword", "minecraft:leather_helmet", "minecraft:anvil")) {
            ItemModelTree tree = stacked.findItemTree(id).orElseThrow(() -> new AssertionError(id + " is expected among the pack's definitions"));
            for (ItemOptions.Type type : ItemOptions.Type.values()) {
                ItemOptions plain = plain(id, type).build();
                ItemOptions unsteered = plain(id, type).context(ItemContext.ofStack(LeafStacks.itemStack(id, components))).build();

                assertThat(id + " " + type + " walks as no stack", unsteered.itemModelAt(type).resolve(tree),
                    is(plain.itemModelAt(type).resolve(tree)));
                assertThat(id + " " + type, RenderDigest.firstFramePixels(renderer.render(unsteered).image()),
                    is(RenderDigest.firstFramePixels(renderer.render(plain).image())));
            }
        }
    }

    /**
     * Draws three branches a stack chooses, each the first of its kind in the pack's own order, and
     * writes each under {@link #LOOK_DIRECTORY}: a flat sword model a {@code custom_data} test chooses
     * in a slot, a flat model a custom name chooses for a block-backed id as its inventory icon, and a
     * model whose shape is its elements held. Each must draw something, and something other than the
     * same item drawn with no stack.
     */
    @Test
    @Order(6)
    @DisplayName("a flat sword, a block-backed icon a custom name chooses and an element model held each draw the chosen branch")
    void threeChosenBranchesDraw() throws IOException {
        Path directory = Files.createDirectories(LOOK_DIRECTORY);

        draw(directory, "flat-sword-gui-2d", ItemOptions.Type.GUI_2D, chosen(ItemOptions.Type.GUI_2D,
            route -> route == Route.INDEXED_FLAT,
            reach -> reach.leaf().itemId().endsWith("_sword") && reach.components().containsKey("minecraft:custom_data")));
        draw(directory, "block-backed-custom-name-gui-icon", ItemOptions.Type.GUI_ICON, chosen(ItemOptions.Type.GUI_ICON,
            route -> route == Route.BLOCK_FLAT,
            reach -> reach.components().containsKey("minecraft:custom_name") && stacked.findBlock(reach.leaf().itemId()).isPresent()));
        draw(directory, "element-model-held-3d", ItemOptions.Type.HELD_3D, chosen(ItemOptions.Type.HELD_3D,
            route -> route == Route.INDEXED_ELEMENT || route == Route.BLOCK_ELEMENT,
            reach -> true));
    }

    /**
     * Holds every leaf past a composite's first child to the frame its stack draws: the frame the
     * dispatch resolves for the leaf's stack draws the leaf's model, as one layer of a composite frame
     * wherever another child draws beside it, in the type that draws at the leaf's display context.
     * The first such leaf on an id the item index carries is then drawn in a slot and held, written
     * under {@link #LOOK_DIRECTORY}, and must draw other than the same stack over the definition with
     * every composite cut to its first child.
     */
    @Test
    @Order(7)
    @DisplayName("every leaf past a composite's first child draws in the frame its stack resolves, over the first child in a slot and held")
    void everyLaterChildDraws() throws IOException {
        List<Reach> later = reaches.stream().filter(Reach::walkable).filter(Reach::later).toList();
        assertThat("leaves past a composite's first child a stack reaches", later, is(not(empty())));

        List<String> undrawn = new ArrayList<>();
        for (Reach reach : later) {
            ItemOptions.Type type = reach.displayContext().equals(ItemOptions.Type.HELD_3D.displayContext())
                ? ItemOptions.Type.HELD_3D
                : ItemOptions.Type.GUI_2D;
            FrameItem frame = frameOf(stacked, options(reach, type).build(), type);
            if (!drawsModel(frame, reach.leaf().model())) undrawn.add(reach + " drew " + frame);
        }
        assertThat("leaves past a composite's first child the frame their stack resolves does not draw", undrawn, is(empty()));

        Reach composed = later.stream()
            .filter(reach -> stacked.findItem(reach.leaf().itemId()).isPresent())
            .filter(reach -> frameOf(stacked, options(reach, ItemOptions.Type.GUI_2D).build(), ItemOptions.Type.GUI_2D) instanceof FrameItem.Composite)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no leaf past a composite's first child draws beside another on an indexed id"));
        RendererContext cut = firstChildrenOnly(composed.leaf().itemId());
        Path directory = Files.createDirectories(LOOK_DIRECTORY);

        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.HELD_3D)) {
            ItemOptions options = options(composed, type).build();
            ImageData drawn = renderer.render(options).image();
            ImageData first = new ItemRenderer(cut).render(options).image();
            Path file = directory.resolve("later-child-" + type.name().toLowerCase().replace('_', '-') + ".png");
            ImageIO.write(drawn.toBufferedImage(), "PNG", file.toFile());
            System.out.printf("%s draws %s%n", file, composed);

            assertThat(type + " draws", opaque(drawn), is(greaterThan(0)));
            assertThat(type + " draws other than its composite's first child alone",
                RenderDigest.firstFramePixels(drawn), is(not(RenderDigest.firstFramePixels(first))));
        }
    }

    /**
     * Draws every model built from elements a stack chooses at the slot, in both slot types, and holds
     * each to drawing something, the inventory icon being the slot's own picture - the item index's ids
     * and the block-backed ones alike, so neither the block's icon nor the missing square stands in for
     * the chosen model. The reforge anvil a custom name chooses for {@code minecraft:anvil} is then
     * written under {@link #LOOK_DIRECTORY} as its inventory icon, and must draw other than the anvil
     * with no stack.
     *
     * @throws IOException if the render cannot be written
     */
    @Test
    @Order(8)
    @DisplayName("every element model a stack chooses at the slot draws there, the reforge anvil among them")
    void everyChosenElementModelDrawsInASlot() throws IOException {
        List<Reach> elements = reaches.stream()
            .filter(Reach::walkable)
            .filter(reach -> reach.displayContext().equals(ItemOptions.Type.GUI_ICON.displayContext()))
            .filter(reach -> routeOf(reach) == Route.INDEXED_ELEMENT || routeOf(reach) == Route.BLOCK_ELEMENT)
            .filter(reach -> walk(reach).modelId().equals(Optional.of(reach.leaf().model())))
            .toList();
        assertThat("element models a stack chooses at the slot", elements, is(not(empty())));
        System.out.printf("%d element model leaves a stack chooses at the slot, %d models%n", elements.size(),
            elements.stream().map(reach -> reach.leaf().model()).distinct().count());

        List<String> blank = new ArrayList<>();
        List<String> parted = new ArrayList<>();
        for (Reach reach : elements) {
            ImageData slot = renderer.render(options(reach, ItemOptions.Type.GUI_2D).build()).image();
            ImageData icon = renderer.render(options(reach, ItemOptions.Type.GUI_ICON).build()).image();
            if (opaque(slot) == 0) blank.add(ItemOptions.Type.GUI_2D + " " + reach);
            if (opaque(icon) == 0) blank.add(ItemOptions.Type.GUI_ICON + " " + reach);
            if (!Arrays.equals(RenderDigest.firstFramePixels(slot), RenderDigest.firstFramePixels(icon))) parted.add(reach.toString());
        }
        assertThat("element models a slot draws blank", blank, is(empty()));
        assertThat("element models whose inventory icon is not the slot's picture", parted, is(empty()));

        draw(Files.createDirectories(LOOK_DIRECTORY), "reforge-anvil-gui-icon", ItemOptions.Type.GUI_ICON, elements.stream()
            .filter(reach -> reach.leaf().model().equals("hplus:skyblock/items/reforge_stones/reforge_anvil"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no stack reaches the reforge anvil at the slot")));
    }

    /**
     * Holds every id the pack's definitions shadow that the item index carries to its indexed item in
     * a plain slot, and every one of them to the route its inventory icon takes with no definition
     * deciding it: each plain branch the pack ships lands on the model the index built the id's item
     * from, or on a special, so a plain icon the walk draws is the one the index draws.
     */
    @Test
    @Order(9)
    @DisplayName("every id the pack shadows that the item index carries keeps its indexed item in a plain slot")
    void everyShadowedIndexedIconKeepsItsIndexedItem() {
        List<String> indexed = shipped.stream().filter(id -> stacked.findItem(id).isPresent()).toList();
        assertThat("ids the item index carries that the pack shadows", indexed, hasSize(SHADOWED_INDEXED_ITEMS));

        List<String> moved = new ArrayList<>();
        for (String id : indexed) {
            Item item = stacked.findItem(id).orElseThrow();
            FrameItem frame = frameOf(stacked, plain(id, ItemOptions.Type.GUI_2D).build(), ItemOptions.Type.GUI_2D);
            if (!frame.equals(FrameItem.Drawn.baked(item))) moved.add(id + " draws a " + frame.getClass().getSimpleName() + " frame of its walk");

            ItemOptions icon = plain(id, ItemOptions.Type.GUI_ICON).build();
            if (ItemModelDispatch.definitionItem(stacked, icon, icon.itemModelAt(ItemOptions.Type.GUI_ICON)).isPresent())
                moved.add(id + " has its inventory icon decided by its definition");
        }
        assertThat("shadowed ids whose plain icon leaves the indexed item", moved, is(empty()));
    }

    /**
     * Walks the leaf a reach solved its stack for, through the bridge an item render takes its walk
     * context from: the type that draws at the leaf's display context, else a context supplied at it,
     * with the stack's components read from the stack.
     *
     * @param reach the walkable reach
     * @return what the walk resolves to
     */
    private static @NotNull ItemModelNode.Resolution walk(@NotNull Reach reach) {
        ItemModelTree tree = stacked.findItemTree(reach.leaf().itemId()).orElseThrow();
        ItemOptions.Type type = reach.displayContext().equals(ItemOptions.Type.HELD_3D.displayContext())
            ? ItemOptions.Type.HELD_3D
            : ItemOptions.Type.GUI_2D;
        return options(reach, type).build().itemModelAt(type).resolve(tree);
    }

    /** Where a leaf lands, or why no stack walks to it. */
    private enum Route {

        /** A model with no elements on an id the item index carries, drawn in every type. */
        INDEXED_FLAT("an item-index id, a flat model", true),

        /** A model whose shape is its elements on an id the item index carries, drawn in every type. */
        INDEXED_ELEMENT("an item-index id, an element model", true),

        /** A model with no elements on a block-backed id, drawn in every type. */
        BLOCK_FLAT("a block-backed id, a flat model", true),

        /** A model whose shape is its elements on a block-backed id, drawn in every type. */
        BLOCK_ELEMENT("a block-backed id, an element model", true),

        /** A leaf past a composite's first child, drawn as a later layer over the ones before it. */
        LATER_CHILD("past a composite's first child", true),

        /** A leaf no stack reaches: an earlier sibling answers its test first, or two of its guards disagree. */
        CONTRADICTED("shadowed or contradictory", false),

        /** A leaf behind a guard no stack built here answers. */
        OTHER_STATE("behind state an icon has no value for", false),

        /** A leaf no guard a stack sets stands in front of, which the walk reaches without one. */
        NEUTRAL("behind no guard a stack sets", false);

        /** How the printed table names the route. */
        private final @NotNull String label;

        /** Whether a frame draws a leaf the route holds. */
        private final boolean drawn;

        Route(@NotNull String label, boolean drawn) {
            this.label = label;
            this.drawn = drawn;
        }

        @NotNull String label() {
            return this.label;
        }

        boolean drawn() {
            return this.drawn;
        }

        /**
         * Routes a leaf no stack walks to by why it does not.
         *
         * @param reach the unwalked reach
         * @return the route
         */
        static @NotNull Route unwalked(@NotNull Reach reach) {
            return switch (reach.unwalked().orElseThrow()) {
                case CONTRADICTED -> CONTRADICTED;
                case OTHER_STATE -> OTHER_STATE;
                case NEUTRAL -> NEUTRAL;
            };
        }

    }

    // HELPERS

    /**
     * Reads the archive itself: every item id it ships a definition for, the {@code model} member of
     * each definition that reads as JSON, and how many model files sit under the pack's namespace.
     *
     * @throws IOException if the archive cannot be read
     */
    private static void readThePack() throws IOException {
        List<String> ids = new ArrayList<>();
        Map<String, JsonElement> read = new TreeMap<>();
        int models = 0;

        try (ZipFile zip = new ZipFile(PACK.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (name.startsWith(PACK_MODELS) && name.endsWith(".json")) models++;
                if (!name.startsWith(ITEMS) || !name.endsWith(".json") || name.indexOf('/', ITEMS.length()) >= 0) continue;

                String id = "minecraft:" + name.substring(ITEMS.length(), name.length() - ".json".length());
                ids.add(id);
                try (InputStream in = zip.getInputStream(entry)) {
                    read.put(id, JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().get("model"));
                } catch (JsonParseException unreadable) {
                    // The reader refuses it as the loader does; the first test pins which one that is.
                }
            }
        }

        shipped = ids.stream().sorted().toList();
        definitions = read;
        packModels = models;
    }

    /**
     * Starts the options for a render of one id at one type, carrying no stack.
     *
     * @param itemId the id to render
     * @param type the render type
     * @return the options builder
     */
    private static @NotNull ItemOptions.Builder plain(@NotNull String itemId, ItemOptions.@NotNull Type type) {
        return ItemOptions.builder()
            .itemId(itemId)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build());
    }

    /**
     * Starts the options for a render of a reach's leaf at one type, carrying the stack its path solved
     * to, and a context at the leaf's display context where the type draws at another.
     *
     * @param reach the walkable reach
     * @param type the render type
     * @return the options builder
     */
    private static @NotNull ItemOptions.Builder options(@NotNull Reach reach, ItemOptions.@NotNull Type type) {
        ItemOptions.Builder options = plain(reach.leaf().itemId(), type)
            .context(ItemContext.ofStack(reach.stack()));
        if (type.displayContext().equals(reach.displayContext())) return options;
        return options.itemModel(ItemModelContext.gui().withDisplayContext(reach.displayContext()));
    }

    /**
     * Routes a reached leaf: past a composite's first child, else by whether the item index carries the
     * id and whether the model's shape is its elements.
     *
     * @param reach the walkable reach
     * @return the route
     */
    private static @NotNull Route routeOf(@NotNull Reach reach) {
        if (reach.later()) return Route.LATER_CHILD;
        boolean element = stacked.findItemModel(reach.leaf().model()).map(model -> !model.getElements().isEmpty()).orElse(false);
        if (stacked.findItem(reach.leaf().itemId()).isPresent()) return element ? Route.INDEXED_ELEMENT : Route.INDEXED_FLAT;
        return element ? Route.BLOCK_ELEMENT : Route.BLOCK_FLAT;
    }

    /**
     * Picks the first reached leaf of a kind, in the pack's own order, at the display context a type
     * draws at - or at any, for the held view, which is handed its leaf's.
     *
     * @param type the type the leaf is to be drawn at
     * @param routes the routes the leaf may take
     * @param kind what else the leaf must be
     * @return the leaf's reach
     */
    private static @NotNull Reach chosen(ItemOptions.@NotNull Type type, @NotNull Predicate<Route> routes, @NotNull Predicate<Reach> kind) {
        return reaches.stream()
            .filter(Reach::walkable)
            .filter(reach -> type == ItemOptions.Type.HELD_3D || reach.displayContext().equals(type.displayContext()))
            .filter(reach -> routes.test(routeOf(reach)))
            .filter(kind)
            .filter(reach -> walk(reach).modelId().equals(Optional.of(reach.leaf().model())))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no reached leaf of the kind drawn at " + type));
    }

    /**
     * Draws a reach's leaf at one type, writes it for a look, and holds that it draws, and draws other
     * than the same item with no stack.
     *
     * @param directory where the render is written
     * @param name the file's name, without its extension
     * @param type the render type
     * @param reach the leaf's reach
     * @throws IOException if the render cannot be written
     */
    private static void draw(@NotNull Path directory, @NotNull String name, ItemOptions.@NotNull Type type, @NotNull Reach reach) throws IOException {
        ImageData chosen = renderer.render(options(reach, type).build()).image();
        ImageData plain = renderer.render(plain(reach.leaf().itemId(), type).build()).image();
        Path file = directory.resolve(name + ".png");
        ImageIO.write(chosen.toBufferedImage(), "PNG", file.toFile());
        System.out.printf("%s draws %s%n", file, reach);

        assertThat(name + " draws", opaque(chosen), is(greaterThan(0)));
        assertThat(name + " draws other than the item with no stack",
            RenderDigest.firstFramePixels(chosen), is(not(RenderDigest.firstFramePixels(plain))));
    }

    /**
     * Resolves what the first frame of a render draws, through the dispatch the render takes: per
     * frame for an id the item index carries, else the frame its item definition chooses.
     *
     * @param context the renderer context the walk resolves against
     * @param options the render's options
     * @param type the render type whose display context an absent context takes
     * @return what the frame draws
     */
    private static @NotNull FrameItem frameOf(@NotNull RendererContext context, @NotNull ItemOptions options, ItemOptions.@NotNull Type type) {
        ItemModelContext walked = options.itemModelAt(type);
        Possible<Item> indexed = context.findItem(options.getItemId());
        if (indexed.isEmpty()) return ItemModelDispatch.definitionItem(context, options, walked).orElseThrow();
        return ItemModelDispatch.resolveRenderItem(context, options, context.resolveItemTextureOverride(options.getContext()), walked, indexed.get());
    }

    /**
     * Answers whether a frame draws a model: as the model it draws, or as one layer of a composite.
     *
     * @param frame the frame
     * @param model the model id
     * @return whether the frame draws the model
     */
    private static boolean drawsModel(@NotNull FrameItem frame, @NotNull String model) {
        return switch (frame) {
            case FrameItem.Drawn drawn -> drawn.modelId().equals(Optional.of(model));
            case FrameItem.Composite composite -> composite.layers().stream().anyMatch(layer -> drawsModel(layer, model));
            default -> false;
        };
    }

    /**
     * Wraps the stacked context so one id answers its definition with every composite cut to its
     * first child, which is all of each a frame would draw were the later children dropped.
     *
     * @param itemId the id whose definition is cut
     * @return the cutting context
     */
    private static @NotNull RendererContext firstChildrenOnly(@NotNull String itemId) {
        ItemModelTree tree = stacked.findItemTree(itemId).orElseThrow();
        ItemModelTree cut = new ItemModelTree(tree.id(), firstChildOnly(tree.root()));
        return new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return stacked;
            }

            @Override
            public @NotNull Possible<ItemModelTree> findItemTree(@NotNull String id) {
                return id.equals(itemId) ? Possible.of(cut) : stacked.findItemTree(id);
            }

        };
    }

    /**
     * Rebuilds a node with every composite below it cut to its first child.
     *
     * @param node the node
     * @return the cut node
     */
    private static @NotNull ItemModelNode firstChildOnly(@NotNull ItemModelNode node) {
        return switch (node) {
            case ItemModelNode.Composite composite ->
                new ItemModelNode.Composite(Concurrent.newUnmodifiableList(firstChildOnly(composite.models().getFirst())));
            case ItemModelNode.Condition condition -> new ItemModelNode.Condition(condition.property(), condition.component(),
                condition.ignoreDefault(), condition.predicate(), firstChildOnly(condition.onTrue()), firstChildOnly(condition.onFalse()));
            case ItemModelNode.Select select -> new ItemModelNode.Select(select.property(), select.blockStateProperty(), select.component(), select.decoded(),
                select.cases()
                    .stream()
                    .map(option -> new ItemModelNode.Select.Case(option.when(), firstChildOnly(option.model())))
                    .collect(Concurrent.toUnmodifiableList()),
                firstChildOnly(select.fallback()));
            case ItemModelNode.RangeDispatch range -> new ItemModelNode.RangeDispatch(range.property(), range.scale(), range.target(), range.index(),
                range.entries()
                    .stream()
                    .map(entry -> new ItemModelNode.RangeDispatch.Entry(entry.threshold(), firstChildOnly(entry.model())))
                    .collect(Concurrent.toUnmodifiableList()),
                firstChildOnly(range.fallback()));
            default -> node;
        };
    }

    /**
     * Counts the pixels a render's first frame carries with any alpha.
     *
     * @param image the rendered image
     * @return the non-transparent pixel count
     */
    private static int opaque(@NotNull ImageData image) {
        int count = 0;
        for (int pixel : RenderDigest.firstFramePixels(image))
            if ((pixel >>> 24) != 0) count++;
        return count;
    }

}
