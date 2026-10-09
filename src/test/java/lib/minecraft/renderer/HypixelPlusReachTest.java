package lib.minecraft.renderer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageData;
import dev.simplified.util.Possible;
import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.nbt.tag.ByteTag;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.DoubleTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.LongTag;
import lib.minecraft.nbt.tag.ShortTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
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
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;
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
 * The leaves are read from the pack's JSON by a walk of this class's own rather than from the decoded
 * tree the renderer walks. It gathers the steps on the path to each leaf and solves them for the one
 * stack that takes them, or finds that none can - a test an earlier sibling answers first, or two
 * guards that want different values of one component. A leaf behind any other guard - a held key, a
 * cast rod, a use duration, a lore - needs state an icon has no value for, and is counted rather than
 * walked.
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

    /**
     * Vanilla's display contexts, the slot first, which is the order a leaf a display context leaves
     * open is walked at.
     */
    private static final @NotNull List<JsonElement> DISPLAY_CONTEXTS = Stream.of(
            "gui", "thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand",
            "head", "ground", "fixed", "on_shelf", "none")
        .<JsonElement>map(JsonPrimitive::new)
        .toList();

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
        ClientAssets assets = new ClientAssets(options, ClientAssetsExtension.vanillaRoot());
        packs = PackAcquisition.acquire(assets);
        stacked = RendererContext.load(assets);
        vanilla = ClientAssetsExtension.context();
        renderer = new ItemRenderer(stacked);

        readThePack();
        List<Leaf> leaves = new ArrayList<>();
        definitions.forEach((id, model) -> {
            if (stacked.findItemTree(id).filter(tree -> !tree.isRejected()).isPresent())
                walk(id, model, List.of(), leaves);
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
                routed.computeIfAbsent(reach.route(), route -> new ArrayList<>()).add(reach);
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
                ItemOptions unsteered = plain(id, type).context(ItemContext.ofStack(itemStack(id, components))).build();

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
     * Walks one node of a definition's raw JSON, collecting every {@code minecraft:model} leaf below it
     * with the steps the path to it takes. Node types and dispatch properties are read namespace-exact,
     * so a mod's node draws nothing and a mod's property is a guard no stack answers.
     *
     * @param itemId the item the definition draws
     * @param element the node, or {@code null} where the definition declares none
     * @param path the steps taken to reach the node
     * @param out the leaves collected so far
     */
    private static void walk(@NotNull String itemId, @Nullable JsonElement element, @NotNull List<Step> path, @NotNull List<Leaf> out) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject node = element.getAsJsonObject();

        switch (vocabulary(node, "type")) {
            case "model" -> out.add(new Leaf(itemId, ResourceId.parse(string(node, "model")).id(), path));
            case "condition" -> condition(itemId, node, path, out);
            case "select" -> select(itemId, node, path, out);
            case "range_dispatch" -> range(itemId, node, path, out);
            case "composite" -> {
                JsonArray models = array(node, "models");
                for (int index = 0; index < models.size(); index++)
                    walk(itemId, models.get(index), then(path, new Step.Child(index)), out);
            }
            default -> { }
        }
    }

    /**
     * Walks both branches of a condition. A {@code custom_data} test is wanted on one branch and refused
     * on the other; a presence test, of a component or a {@code has_component}, asks on its false branch
     * that the stack not hold the component; and every true branch other than a {@code custom_data}
     * test's needs state an icon has no value for.
     *
     * @param itemId the item the definition draws
     * @param node the condition
     * @param path the steps taken to reach the node
     * @param out the leaves collected so far
     */
    private static void condition(@NotNull String itemId, @NotNull JsonObject node, @NotNull List<Step> path, @NotNull List<Leaf> out) {
        String property = vocabulary(node, "property");
        if (property.equals("component") && vocabulary(node, "predicate").equals("custom_data")) {
            CompoundTag data = customData(node.get("value"));
            walk(itemId, node.get("on_true"), then(path, new Step.Wants(data)), out);
            walk(itemId, node.get("on_false"), then(path, new Step.Refuses(data)), out);
            return;
        }

        Optional<String> presence = switch (property) {
            case "component" -> Optional.of(ResourceId.parse(string(node, "predicate")).id());
            case "has_component" -> Optional.of(ResourceId.parse(string(node, "component")).id());
            default -> Optional.empty();
        };
        walk(itemId, node.get("on_true"), then(path, new Step.Other()), out);
        walk(itemId, node.get("on_false"), presence.map(id -> then(path, new Step.Lacks(id))).orElse(path), out);
    }

    /**
     * Walks every case and the fallback of a select. A custom-name, dyed-colour or display-context case
     * takes its values and the fallback refuses every case's; the overworld case of a dimension select
     * is the one an icon is drawn in, so it takes nothing and the fallback beside it needs other state;
     * and any other case needs state an icon has no value for, while its fallback is where the walk
     * goes without it.
     *
     * @param itemId the item the definition draws
     * @param node the select
     * @param path the steps taken to reach the node
     * @param out the leaves collected so far
     */
    private static void select(@NotNull String itemId, @NotNull JsonObject node, @NotNull List<Step> path, @NotNull List<Leaf> out) {
        String property = vocabulary(node, "property");
        Optional<Selected> selected = switch (property) {
            case "display_context" -> Optional.of(Selected.DISPLAY);
            case "component" -> Selected.of(ResourceId.parse(string(node, "component")).id());
            default -> Optional.empty();
        };
        boolean dimension = property.equals("context_dimension");

        List<JsonElement> every = new ArrayList<>();
        for (JsonElement option : array(node, "cases")) {
            JsonObject branch = option.getAsJsonObject();
            List<JsonElement> values = values(branch.get("when"));
            every.addAll(values);
            if (selected.isPresent())
                walk(itemId, branch.get("model"), then(path, new Step.Takes(selected.get(), values)), out);
            else
                walk(itemId, branch.get("model"), dimension && overworld(values) ? path : then(path, new Step.Other()), out);
        }

        if (selected.isPresent())
            walk(itemId, node.get("fallback"), then(path, new Step.Skips(selected.get(), every)), out);
        else
            walk(itemId, node.get("fallback"), dimension && overworld(every) ? then(path, new Step.Other()) : path, out);
    }

    /**
     * Walks every entry and the fallback of a range dispatch. Every range input an icon renders at is
     * zero, so the entry it lands on is the highest threshold at or below zero, else the fallback, and
     * every other branch needs state an icon has no value for.
     *
     * @param itemId the item the definition draws
     * @param node the range dispatch
     * @param path the steps taken to reach the node
     * @param out the leaves collected so far
     */
    private static void range(@NotNull String itemId, @NotNull JsonObject node, @NotNull List<Step> path, @NotNull List<Leaf> out) {
        JsonArray entries = array(node, "entries");
        int neutral = -1;
        float best = 0f;
        for (int index = 0; index < entries.size(); index++) {
            float threshold = entries.get(index).getAsJsonObject().get("threshold").getAsFloat();
            if (threshold <= 0f && (neutral < 0 || threshold > best)) {
                neutral = index;
                best = threshold;
            }
        }

        for (int index = 0; index < entries.size(); index++)
            walk(itemId, entries.get(index).getAsJsonObject().get("model"), index == neutral ? path : then(path, new Step.Other()), out);
        walk(itemId, node.get("fallback"), neutral < 0 ? path : then(path, new Step.Other()), out);
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

    /** One step on the path from a definition's root to a leaf. */
    private sealed interface Step {

        /**
         * A {@code custom_data} test passed.
         *
         * @param data the custom data the test names
         */
        record Wants(@NotNull CompoundTag data) implements Step {}

        /**
         * A {@code custom_data} test failed.
         *
         * @param data the custom data the test names
         */
        record Refuses(@NotNull CompoundTag data) implements Step {}

        /**
         * A case of a select a stack or a display context answers taken.
         *
         * @param selected what the select keys on
         * @param values the values the case matches
         */
        record Takes(@NotNull Selected selected, @NotNull List<JsonElement> values) implements Step {}

        /**
         * The fallback of a select a stack or a display context answers taken.
         *
         * @param selected what the select keys on
         * @param values every value the select's cases match
         */
        record Skips(@NotNull Selected selected, @NotNull List<JsonElement> values) implements Step {}

        /**
         * A presence test failed.
         *
         * @param component the qualified id of the component the stack must not hold
         */
        record Lacks(@NotNull String component) implements Step {}

        /** A branch only state an icon has no value for takes. */
        record Other() implements Step {}

        /**
         * A child of a {@code composite} entered.
         *
         * @param index the child's index among the composite's models
         */
        record Child(int index) implements Step {}

    }

    /** What a select a stack or a display context answers keys on. */
    private enum Selected {

        /** The {@code minecraft:custom_name} component. */
        NAME,

        /** The {@code minecraft:dyed_color} component. */
        DYE,

        /** The display context. */
        DISPLAY;

        /**
         * Finds the component select a stack answers.
         *
         * @param component the qualified component id the select keys on
         * @return the select, or empty for a component the stack built here does not carry
         */
        static @NotNull Optional<Selected> of(@NotNull String component) {
            return switch (component) {
                case "minecraft:custom_name" -> Optional.of(NAME);
                case "minecraft:dyed_color" -> Optional.of(DYE);
                default -> Optional.empty();
            };
        }

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

    }

    /**
     * One model leaf of a definition and the path to it.
     *
     * @param itemId the item the definition draws
     * @param model the qualified model id the leaf names
     * @param path the steps from the definition's root to the leaf
     */
    private record Leaf(@NotNull String itemId, @NotNull String model, @NotNull List<Step> path) {}

    /**
     * The values a select may answer on a path - those the cases it takes leave, less those a fallback
     * it takes refuses.
     *
     * @param allowed the values every case taken matches, empty when the path takes no case
     * @param excluded the values a fallback taken refuses
     */
    private record Choice(@NotNull Optional<List<JsonElement>> allowed, @NotNull List<JsonElement> excluded) {

        /** No case and no fallback taken. */
        static final @NotNull Choice OPEN = new Choice(Optional.empty(), List.of());

        /**
         * Narrows this choice to the values a case taken matches.
         *
         * @param values the case's values
         * @return the narrowed choice
         */
        @NotNull Choice taking(@NotNull List<JsonElement> values) {
            return new Choice(Optional.of(this.allowed.map(held -> held.stream().filter(values::contains).toList()).orElse(values)), this.excluded);
        }

        /**
         * Adds the values a fallback taken refuses.
         *
         * @param values the values the select's cases match
         * @return the refusing choice
         */
        @NotNull Choice skipping(@NotNull List<JsonElement> values) {
            return new Choice(this.allowed, Stream.concat(this.excluded.stream(), values.stream()).toList());
        }

        /**
         * Picks the first value the path leaves.
         *
         * @param otherwise the values to pick from where the path takes no case
         * @return the value, or empty where every one is refused
         */
        @NotNull Optional<JsonElement> pick(@NotNull List<JsonElement> otherwise) {
            return this.allowed.orElse(otherwise).stream().filter(value -> !this.excluded.contains(value)).findFirst();
        }

    }

    /**
     * A leaf and the stack its path solves to: the components the path's guards ask for and the display
     * context its walk proceeds at, or the route that says why no stack walks to it.
     *
     * @param leaf the leaf
     * @param unwalked why no stack walks to the leaf, empty where one does
     * @param components the component patch of the stack that reaches the leaf, empty where none does
     * @param displayContext the display context the walk proceeds at
     * @param composed whether the path passes through a {@code composite}
     * @param later whether the path passes through a {@code composite} child past its first
     */
    private record Reach(
        @NotNull Leaf leaf, @NotNull Optional<Route> unwalked, @NotNull CompoundTag components,
        @NotNull String displayContext, boolean composed, boolean later
    ) {

        /**
         * Solves a leaf's path for the one stack that reaches it.
         * <p>
         * The custom data is every passed test's compound merged, so it is the least a stack can carry
         * and pass them all; a key two tests give different values contradicts. A failed test whose
         * compound that custom data matches is one every stack reaching the passed tests matches too,
         * an earlier sibling answering first, so the leaf is shadowed. A custom name, a dyed colour and
         * a display context are each the first value the path's cases leave and its fallbacks do not
         * refuse, the display context defaulting to the slot.
         *
         * @param leaf the leaf
         * @return the solved reach
         */
        static @NotNull Reach of(@NotNull Leaf leaf) {
            List<CompoundTag> wanted = new ArrayList<>();
            List<CompoundTag> refused = new ArrayList<>();
            Map<Selected, Choice> choices = new EnumMap<>(Selected.class);
            Set<String> lacking = new HashSet<>();
            boolean guarded = false;
            boolean other = false;
            boolean composed = false;
            boolean later = false;

            for (Step step : leaf.path()) {
                switch (step) {
                    case Step.Wants wants -> {
                        wanted.add(wants.data());
                        guarded = true;
                    }
                    case Step.Refuses refuses -> refused.add(refuses.data());
                    case Step.Takes takes -> {
                        choices.put(takes.selected(), choices.getOrDefault(takes.selected(), Choice.OPEN).taking(takes.values()));
                        guarded = true;
                    }
                    case Step.Skips skips -> choices.put(skips.selected(), choices.getOrDefault(skips.selected(), Choice.OPEN).skipping(skips.values()));
                    case Step.Lacks lacks -> lacking.add(lacks.component());
                    case Step.Other ignored -> other = true;
                    case Step.Child child -> {
                        composed = true;
                        later |= child.index() > 0;
                    }
                }
            }

            if (other) return unwalked(leaf, Route.OTHER_STATE);
            if (!guarded) return unwalked(leaf, Route.NEUTRAL);

            Optional<CompoundTag> merged = Optional.of(new CompoundTag());
            for (CompoundTag compound : wanted)
                merged = merged.flatMap(held -> merge(held, compound));
            if (merged.isEmpty()) return unwalked(leaf, Route.CONTRADICTED);
            CompoundTag data = merged.get();
            if (refused.stream().anyMatch(compound -> matches(compound, data))) return unwalked(leaf, Route.CONTRADICTED);

            Choice name = choices.getOrDefault(Selected.NAME, Choice.OPEN);
            Choice dye = choices.getOrDefault(Selected.DYE, Choice.OPEN);
            Optional<JsonElement> named = name.pick(List.of());
            Optional<JsonElement> dyed = dye.pick(List.of());
            Optional<JsonElement> display = choices.getOrDefault(Selected.DISPLAY, Choice.OPEN).pick(DISPLAY_CONTEXTS);
            if (name.allowed().isPresent() && named.isEmpty() || dye.allowed().isPresent() && dyed.isEmpty() || display.isEmpty())
                return unwalked(leaf, Route.CONTRADICTED);

            CompoundTag components = new CompoundTag();
            if (!wanted.isEmpty()) components.put("minecraft:custom_data", data);
            named.ifPresent(value -> components.put("minecraft:custom_name", tag(value, false)));
            dyed.ifPresent(value -> components.put("minecraft:dyed_color", dyedColor(value)));
            if (lacking.stream().anyMatch(components::containsKey)) return unwalked(leaf, Route.CONTRADICTED);

            return new Reach(leaf, Optional.empty(), components, display.get().getAsString(), composed, later);
        }

        /**
         * Builds the reach of a leaf no stack walks to.
         *
         * @param leaf the leaf
         * @param route why no stack walks to it
         * @return the reach
         */
        private static @NotNull Reach unwalked(@NotNull Leaf leaf, @NotNull Route route) {
            return new Reach(leaf, Optional.of(route), new CompoundTag(), "", false, false);
        }

        /**
         * Whether a stack walks to the leaf.
         *
         * @return whether the path solved
         */
        boolean walkable() {
            return this.unwalked.isEmpty();
        }

        /**
         * Why no stack walks to the leaf.
         *
         * @return the route of an unwalked leaf
         */
        @NotNull Route route() {
            return this.unwalked.orElseThrow();
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String toString() {
            return this.leaf.itemId() + " -> " + this.leaf.model() + " at " + this.displayContext + " with " + NbtFactory.toSnbt(this.components);
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
     * Decodes a {@code custom_data} test's value: an SNBT string as SNBT, an object as vanilla's
     * {@code JsonOps.convertTo} converts one.
     *
     * @param value the test's {@code value}
     * @return the compound the test names
     */
    private static @NotNull CompoundTag customData(@NotNull JsonElement value) {
        if (value.isJsonPrimitive()) return NbtFactory.fromSnbt(value.getAsString());
        return (CompoundTag) tag(value, true);
    }

    /**
     * Converts one JSON value to the 26.1 NBT tag it is written as: a string to a string tag, a boolean
     * to a byte, a whole number to an int - or, converted as {@code JsonOps.convertTo} converts a
     * {@code custom_data} object, to the narrowest of byte, short, int and long that holds it - any
     * other number to a float where that is exact and a double otherwise, an object to a compound, and
     * an array to a list, each element of a list whose element types differ wrapped as the one entry of
     * a compound keyed by the empty string.
     *
     * @param json the value
     * @param narrowest whether a whole number takes the narrowest type holding it
     * @return the tag
     */
    private static @NotNull Tag<?> tag(@NotNull JsonElement json, boolean narrowest) {
        if (json.isJsonObject()) {
            CompoundTag compound = new CompoundTag();
            json.getAsJsonObject().entrySet().forEach(entry -> compound.put(entry.getKey(), tag(entry.getValue(), narrowest)));
            return compound;
        }
        if (json.isJsonArray()) {
            List<Tag<?>> elements = json.getAsJsonArray().asList().stream().<Tag<?>>map(element -> tag(element, narrowest)).toList();
            boolean mixed = elements.stream().map(Tag::getId).distinct().count() > 1;
            ListTag<Tag<?>> list = new ListTag<>(elements.size());
            elements.forEach(element -> list.add(mixed ? wrapped(element) : element));
            return list;
        }

        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isString()) return new StringTag(primitive.getAsString());
        if (primitive.isBoolean()) return new ByteTag((byte) (primitive.getAsBoolean() ? 1 : 0));
        BigDecimal value = primitive.getAsBigDecimal();
        try {
            long whole = value.longValueExact();
            if (narrowest && (byte) whole == whole) return new ByteTag((byte) whole);
            if (narrowest && (short) whole == whole) return new ShortTag((short) whole);
            if ((int) whole == whole) return new IntTag((int) whole);
            return new LongTag(whole);
        } catch (ArithmeticException fractional) {
            double real = value.doubleValue();
            return (float) real == real ? new FloatTag((float) real) : new DoubleTag(real);
        }
    }

    /**
     * Wraps one element of a list whose element types differ, as vanilla's binary form writes it: a
     * compound that is not itself a wrapper as it is, anything else as the one entry of a compound
     * keyed by the empty string.
     *
     * @param element the element
     * @return the element as the list holds it
     */
    private static @NotNull Tag<?> wrapped(@NotNull Tag<?> element) {
        if (element instanceof CompoundTag compound && !(compound.size() == 1 && compound.containsKey(""))) return compound;
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("", element);
        return wrapper;
    }

    /**
     * Converts a dyed-colour case value to the {@code minecraft:dyed_color} a stack carries: a number as
     * the int vanilla writes, three floats as the list they are.
     *
     * @param value the case value
     * @return the component's tag
     */
    private static @NotNull Tag<?> dyedColor(@NotNull JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return new IntTag(value.getAsInt());
        return tag(value, false);
    }

    /**
     * Answers whether a custom data test passes a stack's custom data, as vanilla's partial
     * {@code NbtUtils.compareNbt} answers it: a compound when every expected key passes, a list when
     * every expected element passes against some actual one - an empty one only against an empty one -
     * and any other tag on equality, the tag type included.
     *
     * @param expected the custom data the test names
     * @param actual the stack's value at the same place, or {@code null} where it has none
     * @return whether the test passes
     */
    private static boolean matches(@NotNull Tag<?> expected, @Nullable Tag<?> actual) {
        if (actual == null || expected.getId() != actual.getId()) return false;
        if (expected instanceof CompoundTag wanted)
            return wanted.entrySet().stream().allMatch(entry -> matches(entry.getValue(), ((CompoundTag) actual).get(entry.getKey())));
        if (expected instanceof ListTag<?> wanted) {
            ListTag<?> held = (ListTag<?>) actual;
            if (wanted.isEmpty()) return held.isEmpty();
            return wanted.stream().allMatch(element -> held.stream().anyMatch(candidate -> matches(element, candidate)));
        }
        return expected.equals(actual);
    }

    /**
     * Merges two custom data compounds into the least one both pass against.
     *
     * @param left one compound
     * @param right the other
     * @return the merged compound, or empty where a key holds two different values
     */
    private static @NotNull Optional<CompoundTag> merge(@NotNull CompoundTag left, @NotNull CompoundTag right) {
        CompoundTag merged = new CompoundTag();
        left.entrySet().forEach(entry -> merged.put(entry.getKey(), entry.getValue()));
        for (Map.Entry<String, Tag<?>> entry : right.entrySet()) {
            Tag<?> held = merged.get(entry.getKey());
            if (held == null) {
                merged.put(entry.getKey(), entry.getValue());
                continue;
            }

            Optional<? extends Tag<?>> both = held instanceof CompoundTag heldCompound && entry.getValue() instanceof CompoundTag added
                ? merge(heldCompound, added)
                : Optional.of(held).filter(entry.getValue()::equals);
            if (both.isEmpty()) return Optional.empty();
            merged.put(entry.getKey(), both.get());
        }
        return Optional.of(merged);
    }

    /**
     * Reads a case's {@code when} as the values it matches: an array as one value per element, which
     * is how vanilla reads one whose every element decodes, and anything else as one value.
     *
     * @param when the case's {@code when}
     * @return the values
     */
    private static @NotNull List<JsonElement> values(@NotNull JsonElement when) {
        return when.isJsonArray() ? when.getAsJsonArray().asList() : List.of(when);
    }

    /**
     * Answers whether a dimension select's values name the overworld, the dimension an icon is drawn in.
     *
     * @param values the values
     * @return whether one of them is the overworld
     */
    private static boolean overworld(@NotNull List<JsonElement> values) {
        return values.stream().anyMatch(value -> ResourceId.parse(value.getAsString()).id().equals(ItemModelContext.DIMENSION_OVERWORLD));
    }

    /**
     * Reads a node type or a dispatch property the way vanilla parses an identifier.
     *
     * @param node the node
     * @param key the member naming the id
     * @return the id's path in vanilla's namespace, or {@code ""} for a mod's
     */
    private static @NotNull String vocabulary(@NotNull JsonObject node, @NotNull String key) {
        return ResourceId.vanillaPath(string(node, key)).orElse("");
    }

    /**
     * Reads a string member.
     *
     * @param node the node
     * @param key the member
     * @return the string, or {@code ""} where the member is absent
     */
    private static @NotNull String string(@NotNull JsonObject node, @NotNull String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /**
     * Reads an array member.
     *
     * @param node the node
     * @param key the member
     * @return the array, empty where the member is absent
     */
    private static @NotNull JsonArray array(@NotNull JsonObject node, @NotNull String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    /**
     * Extends a path by one step.
     *
     * @param path the steps so far
     * @param step the next step
     * @return the longer path
     */
    private static @NotNull List<Step> then(@NotNull List<Step> path, @NotNull Step step) {
        return Stream.concat(path.stream(), Stream.of(step)).toList();
    }

    /**
     * Builds a 26.1 item stack of one item.
     *
     * @param itemId the item id
     * @param components the stack's component patch
     * @return the stack
     */
    private static @NotNull CompoundTag itemStack(@NotNull String itemId, @NotNull CompoundTag components) {
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(itemId));
        stack.put("count", new IntTag(1));
        stack.put("components", components);
        return stack;
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
            .context(ItemContext.ofStack(itemStack(reach.leaf().itemId(), reach.components())));
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
