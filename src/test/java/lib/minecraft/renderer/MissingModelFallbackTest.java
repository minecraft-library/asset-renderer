package lib.minecraft.renderer;

import com.google.gson.JsonParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.ImageData;
import dev.simplified.util.Possible;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.call.request.BlockOptions;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of the five render entry points an id neither index carries falls back at, and of the
 * presentation each one answers with - and of the same pictures where an item definition's walk draws
 * them for an id both indexes carry.
 * <p>
 * The sharpest pin here is a colour count. A flat square carries the checkerboard's own two colours; a
 * cube seen at the isometric pose shows three faces at three shades and carries four. That is what
 * separates a slot's picture from a posed one, and a {@code GUI_ICON} answering four has been routed
 * through the isometric projection.
 * <p>
 * The walk's own stand-ins are what vanilla draws. A leaf naming a model no pack ships draws the
 * missing model - the square in a slot, the cube held - reported once per model id, and it keeps the
 * stack's glint. A definition the loader refused draws vanilla's missing item model, the same picture
 * with no glint, for a block-backed id too. An empty branch draws nothing beneath the slot's
 * decorations. A block-backed id whose stack chooses a
 * flat model draws it in every type, and so does one choosing a block model, its slot drawing that
 * model as the block it belongs to draws its icon. A stack that chooses nothing renders byte-identical
 * to no stack. Each definition is
 * parsed from JSON through the real deserializer and answered for one id over the client context, as a
 * pack shadowing that id would answer it.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class
 * where nothing has extracted the client yet.
 */
@DisplayName("Missing-model fallback at the five render entry points")
@ExtendWith(ClientAssetsExtension.class)
class MissingModelFallbackTest {

    private static final int SIZE = 64;
    private static final String UNKNOWN = "minecraft:definitely_not_a_real_id";

    /** An item-index id whose definition the walk rows shadow. */
    private static final String SWORD = "minecraft:diamond_sword";

    /** A block-backed id the item index does not carry, whose definition the routing rows shadow. */
    private static final String STONE = "minecraft:stone";

    /** The diffuse factor a cube's side face carries, which is the only face an identity pose shows. */
    private static final float SIDE_SHADE = 0.6f;

    private static RendererContext context;
    private static ItemRenderer itemRenderer;
    private static BlockRenderer blockRenderer;

    @BeforeAll
    static void bootstrapPipeline() {
        context = ClientAssetsExtension.context();
        itemRenderer = new ItemRenderer(context);
        blockRenderer = new BlockRenderer(context);

        assertThat("the probe id must be absent from both indexes",
            context.findItem(UNKNOWN).isPresent() || context.findBlock(UNKNOWN).isPresent(), is(false));
    }

    @Test
    @DisplayName("an unknown block renders the posed cube, four opaque colours")
    void isometricRendersThePosedCube() {
        Set<Integer> colours = distinctOpaque(blockRenderer.render(block(UNKNOWN, EulerRotation.NONE)));

        assertThat("three shaded faces plus the unshadeable black", colours.size(), is(4));
        assertThat(colours, hasItems(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB));
    }

    @Test
    @DisplayName("an unknown block's single face renders the flat square")
    void blockFaceRendersTheFlatSquare() {
        BlockOptions options = BlockOptions.builder()
            .blockId(UNKNOWN)
            .type(BlockOptions.Type.BLOCK_FACE_2D)
            .output(OutputOptions.builder().canvasSize(SIZE).build())
            .build();

        assertThat(distinctOpaque(blockRenderer.render(options)),
            is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
    }

    @Test
    @DisplayName("an unknown item's flat icon renders the flat square")
    void gui2DRendersTheFlatSquare() {
        assertThat(distinctOpaque(itemRenderer.render(item(UNKNOWN, ItemOptions.Type.GUI_2D, EulerRotation.NONE))),
            is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
    }

    @Test
    @DisplayName("an unknown item held renders the cube square-on, carrying the sprite's two colours")
    void held3DRendersTheCube() {
        // The held camera is an identity pose - the held view's rotation lives in the model's display
        // transform, and a missing model has no display slot, so the transform is the identity one an
        // absent slot already resolves to. Exactly one face of the cube is therefore seen square-on,
        // and it is a side face carrying the cube's own side shade. Magenta's green is zero and the
        // shade scales red and blue alike, so the expected value is derived rather than observed.
        // Four colours here would mean the guard built a pose the resolving path does not build.
        int shaded = Math.round(SIDE_SHADE * (MissingSprite.MAGENTA_ARGB >>> 16 & 0xFF));
        int shadedMagenta = 0xFF000000 | shaded << 16 | shaded;

        Set<Integer> colours = distinctOpaque(itemRenderer.render(item(UNKNOWN, ItemOptions.Type.HELD_3D, EulerRotation.NONE)));

        assertThat(colours, is(Set.of(MissingSprite.BLACK_ARGB, shadedMagenta)));
    }

    @Test
    @DisplayName("an unknown item's inventory icon renders the flat square, not the hexagon")
    void guiIconRendersTheFlatSquare() {
        Set<Integer> icon = distinctOpaque(itemRenderer.render(item(UNKNOWN, ItemOptions.Type.GUI_ICON, EulerRotation.NONE)));
        Set<Integer> isometric = distinctOpaque(blockRenderer.render(block(UNKNOWN, EulerRotation.NONE)));

        assertThat("the slot shows one face square-on", icon.size(), is(2));
        assertThat("the posed cube shows three", isometric.size(), is(4));
        assertThat(icon, is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
    }

    @Test
    @DisplayName("the isometric cube turns with the caller's rotation")
    void isometricHonoursTheCallersRotation() {
        // The one of the three posed types that reads a rotation at all. The missing subject is posed
        // exactly where a resolving one would have been - only the subject is substituted.
        int[] straight = RenderDigest.firstFramePixels(blockRenderer.render(block(UNKNOWN, EulerRotation.NONE)).image());
        int[] turned = RenderDigest.firstFramePixels(blockRenderer.render(block(UNKNOWN, new EulerRotation(15f, 40f, 0f))).image());

        assertThat(turned, is(not(straight)));
    }

    @Test
    @DisplayName("the held cube and the flat face are invariant under rotation, as a resolving subject is")
    void heldAndFlatAreInvariantUnderRotation() {
        // Invariance rather than difference: the held path hard-codes an identity rotation into its
        // camera because the pose lives in the model's display transform, and a flat face reads no
        // rotation at all. A missing render that DID move under a rotation would mean the guard built
        // a pose the resolving path does not build.
        int[] heldStraight = RenderDigest.firstFramePixels(item3D(EulerRotation.NONE));
        int[] heldTurned = RenderDigest.firstFramePixels(item3D(new EulerRotation(15f, 40f, 0f)));
        assertThat("held", heldTurned, is(heldStraight));

        int[] flatStraight = RenderDigest.firstFramePixels(blockRenderer.render(face(EulerRotation.NONE)).image());
        int[] flatTurned = RenderDigest.firstFramePixels(blockRenderer.render(face(new EulerRotation(15f, 40f, 0f))).image());
        assertThat("flat face", flatTurned, is(flatStraight));
    }

    @Test
    @DisplayName("a leaf naming a model no pack ships draws the square in a slot and the cube held")
    void aLeafMissDrawsTheMissingModel() {
        ItemRenderer renderer = new ItemRenderer(withTree(SWORD, steeredTo(SWORD, leaf("minecraft:item/missing_model_fallback_test_drawn"))));

        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON))
            assertThat(type + " draws the square", distinctOpaque(renderer.render(steered(SWORD, type).build())),
                is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
        assertThat("held draws the cube square-on", distinctOpaque(renderer.render(steered(SWORD, ItemOptions.Type.HELD_3D).build())),
            is(Set.of(MissingSprite.BLACK_ARGB, shadedMagenta())));
    }

    @Test
    @DisplayName("a leaf miss is reported once per model id, whichever type draws it")
    void aLeafMissIsReportedOnce() {
        String model = "minecraft:item/missing_model_fallback_test_reported";
        ItemRenderer renderer = new ItemRenderer(withTree(SWORD, steeredTo(SWORD, leaf(model))));

        String first = errDuring(() -> renderer.render(steered(SWORD, ItemOptions.Type.GUI_2D).build()));
        assertThat(first, containsString("Missing model '" + model + "' named by item '" + SWORD + "'"));

        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_ICON, ItemOptions.Type.HELD_3D))
            assertThat(type.name(), errDuring(() -> renderer.render(steered(SWORD, type).build())), not(containsString(model)));
    }

    @Test
    @DisplayName("a leaf miss on an enchanted stack keeps the glint, which vanilla's missing item model drops")
    void aLeafMissKeepsTheGlint() {
        ItemRenderer missing = new ItemRenderer(withTree(SWORD, steeredTo(SWORD, leaf("minecraft:item/missing_model_fallback_test_glint"))));
        ItemRenderer refused = new ItemRenderer(withTree(SWORD, ItemModelTree.rejected(ResourceId.parse(SWORD))));

        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.HELD_3D)) {
            assertThat(type + ": the missing model glints", RenderDigest.firstFramePixels(missing.render(enchanted(steered(SWORD, type))).image()),
                is(not(RenderDigest.firstFramePixels(missing.render(steered(SWORD, type).build()).image()))));
            assertThat(type + ": the missing item model does not", RenderDigest.firstFramePixels(refused.render(enchanted(steered(SWORD, type))).image()),
                is(RenderDigest.firstFramePixels(refused.render(steered(SWORD, type).build()).image())));
        }
    }

    @Test
    @DisplayName("an empty branch draws nothing, and the stack count and damage bar still draw over it")
    void anEmptyBranchDrawsNothingButTheDecorations() {
        ItemRenderer empty = new ItemRenderer(withTree(SWORD, steeredTo(SWORD, "{\"type\":\"minecraft:empty\"}")));

        assertThat("a plain slot is transparent", opaque(empty.render(steered(SWORD, ItemOptions.Type.GUI_2D).build())), is(0));
        assertThat("held draws nothing", opaque(empty.render(steered(SWORD, ItemOptions.Type.HELD_3D).build())), is(0));

        ItemOptions decorated = steered(SWORD, ItemOptions.Type.GUI_2D).context(ItemContext.ofStack(stack(SWORD, 5, 300))).build();
        int decorations = opaque(empty.render(decorated));
        assertThat("the count and the bar draw", decorations, is(greaterThan(0)));
        assertThat("and nothing of the sword", decorations, is(lessThan(opaque(itemRenderer.render(decorated)))));
    }

    @Test
    @DisplayName("a refused definition draws vanilla's missing item model in every type, block-backed included")
    void aRefusedDefinitionDrawsTheMissingItemModel() {
        for (String id : List.of(SWORD, STONE)) {
            ItemRenderer refused = new ItemRenderer(withTree(id, ItemModelTree.rejected(ResourceId.parse(id))));
            for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON))
                assertThat(id + " " + type, distinctOpaque(refused.render(item(id, type, EulerRotation.NONE))),
                    is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
            assertThat(id + " held", distinctOpaque(refused.render(item(id, ItemOptions.Type.HELD_3D, EulerRotation.NONE))),
                is(Set.of(MissingSprite.BLACK_ARGB, shadedMagenta())));
        }
    }

    /**
     * Pins the refusal through a real pack load: the pack's file is the only one read for its id, so
     * vanilla's own definition beneath it never stands in, and the item draws the missing item model.
     */
    @Test
    @DisplayName("a pack's undecodable definition draws the missing item model, not the lower pack's model")
    void aPacksUndecodableDefinitionShadowsVanilla(@TempDir Path work) throws IOException {
        Path pack = work.resolve("refusedpack");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"refused fixture\"}}");
        write(pack.resolve("assets/minecraft/items/stone.json"), "{\"model\":{\"type\":\"minecraft:mystery_future_node\"}}");
        write(pack.resolve("assets/minecraft/items/diamond_sword.json"), "{\"model\":");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        RendererContext refused = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanillaRoot()));
        ItemRenderer renderer = new ItemRenderer(refused);

        for (String id : List.of(SWORD, STONE)) {
            assertThat(id + " is held refused", refused.findItemTree(id).map(ItemModelTree::isRejected).orElseThrow(), is(true));
            assertThat(id + " in a slot", distinctOpaque(renderer.render(item(id, ItemOptions.Type.GUI_ICON, EulerRotation.NONE))),
                is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
            assertThat(id + " held", distinctOpaque(renderer.render(item(id, ItemOptions.Type.HELD_3D, EulerRotation.NONE))),
                is(Set.of(MissingSprite.BLACK_ARGB, shadedMagenta())));
        }
    }

    @Test
    @DisplayName("a block-backed id whose stack chooses a flat model draws it in every type")
    void aBlockBackedFlatChoiceDrawsEverywhere() {
        ItemRenderer stone = new ItemRenderer(withTree(STONE, steeredTo(STONE, leaf("minecraft:item/diamond_sword"))));

        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON, ItemOptions.Type.HELD_3D))
            assertThat(type + " draws the sword", RenderDigest.firstFramePixels(stone.render(steered(STONE, type).build()).image()),
                is(RenderDigest.firstFramePixels(itemRenderer.render(item(SWORD, type, EulerRotation.NONE)).image())));
    }

    @Test
    @DisplayName("a block-backed id whose stack chooses a block model draws it in every type, in a slot as that block's own icon")
    void aBlockBackedElementChoiceDrawsEverywhere() {
        String model = "minecraft:block/deepslate";
        ItemRenderer stone = new ItemRenderer(withTree(STONE, steeredTo(STONE, leaf(model))));

        int[] held = RenderDigest.firstFramePixels(stone.render(steered(STONE, ItemOptions.Type.HELD_3D).build()).image());
        assertThat("held draws the chosen model", held,
            is(not(RenderDigest.firstFramePixels(itemRenderer.render(item(STONE, ItemOptions.Type.HELD_3D, EulerRotation.NONE)).image()))));

        int[] deepslate = RenderDigest.firstFramePixels(itemRenderer.render(item("minecraft:deepslate", ItemOptions.Type.GUI_ICON, EulerRotation.NONE)).image());
        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON))
            assertThat(type + " draws the chosen model as the block it belongs to draws its icon",
                RenderDigest.firstFramePixels(stone.render(steered(STONE, type).build()).image()), is(deepslate));

        // The chosen model takes its own branch's tints: a grass block steered to its own model with the
        // tint its definition names draws its own icon, and steered to that model with no tint does not.
        String grass = "minecraft:grass_block";
        String grassLeaf = "{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/grass_block\","
            + "\"tints\":[{\"type\":\"minecraft:grass\",\"downfall\":1.0,\"temperature\":0.5}]}";
        int[] grassIcon = RenderDigest.firstFramePixels(itemRenderer.render(item(grass, ItemOptions.Type.GUI_ICON, EulerRotation.NONE)).image());
        ItemRenderer tinted = new ItemRenderer(withTree(grass, steeredTo(grass, grassLeaf, leaf("minecraft:block/stone"))));
        ItemRenderer untinted = new ItemRenderer(withTree(grass, steeredTo(grass, leaf("minecraft:block/grass_block"), grassLeaf)));
        assertThat("the branch's tint colours the chosen model",
            RenderDigest.firstFramePixels(tinted.render(steered(grass, ItemOptions.Type.GUI_ICON).build()).image()), is(grassIcon));
        assertThat("a branch naming no tint leaves it uncoloured",
            RenderDigest.firstFramePixels(untinted.render(steered(grass, ItemOptions.Type.GUI_ICON).build()).image()), is(not(grassIcon)));
    }

    @Test
    @DisplayName("an indexed id whose walk lands on a block model draws it in a slot as that block's icon, and the index's own element row draws")
    void anIndexedElementLeafDrawsItsElements() {
        ItemRenderer sword = new ItemRenderer(withTree(SWORD, steeredTo(SWORD, leaf("minecraft:block/cobbled_deepslate"))));

        int[] cobbled = RenderDigest.firstFramePixels(itemRenderer.render(item("minecraft:cobbled_deepslate", ItemOptions.Type.GUI_ICON, EulerRotation.NONE)).image());
        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON))
            assertThat(type + " draws the chosen model as the block it belongs to draws its icon",
                RenderDigest.firstFramePixels(sword.render(steered(SWORD, type).build()).image()), is(cobbled));

        // big_dripleaf's own models/item row is an element model with no layer0, so its slot draws the
        // elements, while its inventory icon stays the block's own.
        assertThat("the index's element row draws in a slot",
            opaque(itemRenderer.render(item("minecraft:big_dripleaf", ItemOptions.Type.GUI_2D, EulerRotation.NONE))), is(greaterThan(0)));
    }

    @Test
    @DisplayName("a stack that selects nothing renders byte-identical to no stack")
    void aStackThatSelectsNothingRendersAsNoStack() {
        // The stack carries no dyed colour: the walk is what an unsteering stack leaves alone, and a
        // dyed_color would tint a dye source whatever the walk chose.
        for (String id : List.of("minecraft:leather_helmet", SWORD, STONE))
            for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON, ItemOptions.Type.HELD_3D)) {
                ItemOptions plain = item(id, type, EulerRotation.NONE);
                ItemOptions unsteered = plain.mutate().context(ItemContext.ofStack(stack(id, 1, 0))).build();
                assertThat(id + " " + type, RenderDigest.firstFramePixels(itemRenderer.render(unsteered).image()),
                    is(RenderDigest.firstFramePixels(itemRenderer.render(plain).image())));
            }
    }

    /**
     * The colour the identity-posed cube's side face carries: magenta scaled by the side shade, whose
     * green is zero, so red and blue scale alike.
     *
     * @return the shaded magenta
     */
    private static int shadedMagenta() {
        int shaded = Math.round(SIDE_SHADE * (MissingSprite.MAGENTA_ARGB >>> 16 & 0xFF));
        return 0xFF000000 | shaded << 16 | shaded;
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
     * Builds a {@code minecraft:model} leaf naming one model.
     *
     * @param model the model id
     * @return the leaf's JSON
     */
    private static @NotNull String leaf(@NotNull String model) {
        return "{\"type\":\"minecraft:model\",\"model\":\"" + model + "\"}";
    }

    /**
     * Builds a definition whose custom_data test takes a branch for a stack carrying {@code id: "X"},
     * and the id's own vanilla model otherwise - the shape a pack shadowing a vanilla item gives it.
     *
     * @param itemId the id the definition shadows, {@link #SWORD} or {@link #STONE}
     * @param branch the JSON of the branch the stack selects
     * @return the definition's tree
     */
    private static @NotNull ItemModelTree steeredTo(@NotNull String itemId, @NotNull String branch) {
        return steeredTo(itemId, branch, leaf(itemId.equals(STONE) ? "minecraft:block/stone" : "minecraft:item/diamond_sword"));
    }

    /**
     * Builds a definition whose custom_data test takes a branch for a stack carrying {@code id: "X"},
     * and a given branch otherwise.
     *
     * @param itemId the id the definition shadows
     * @param branch the JSON of the branch the stack selects
     * @param otherwise the JSON of the branch every other stack walks to
     * @return the definition's tree
     */
    private static @NotNull ItemModelTree steeredTo(@NotNull String itemId, @NotNull String branch, @NotNull String otherwise) {
        String model = "{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\","
            + "\"predicate\":\"minecraft:custom_data\",\"value\":{\"id\":\"X\"},"
            + "\"on_true\":" + branch + ",\"on_false\":" + otherwise + "}";
        return new ItemModelTree(ResourceId.parse(itemId),
            GsonSettings.defaults().create().fromJson(JsonParser.parseString(model), ItemModelNode.class));
    }

    /**
     * Wraps the client context so one id answers the given definition, as a pack shadowing that id
     * would.
     *
     * @param itemId the id the definition shadows
     * @param tree the definition
     * @return the shadowing context
     */
    private static @NotNull RendererContext withTree(@NotNull String itemId, @NotNull ItemModelTree tree) {
        return new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return context;
            }

            @Override
            public @NotNull Possible<ItemModelTree> findItemTree(@NotNull String id) {
                return id.equals(itemId) ? Possible.of(tree) : context.findItemTree(id);
            }

        };
    }

    /**
     * Builds a 26.1 stack whose custom data carries {@code id: "X"}, with no dyed colour.
     *
     * @param itemId the stack's item id
     * @param count the stack size
     * @param damage the stack's damage, {@code 0} for none
     * @return the stack
     */
    private static @NotNull CompoundTag stack(@NotNull String itemId, int count, int damage) {
        CompoundTag data = new CompoundTag();
        data.put("id", new StringTag("X"));
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_data", data);
        if (damage > 0)
            components.put("minecraft:damage", new IntTag(damage));
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(itemId));
        stack.put("count", new IntTag(count));
        stack.put("components", components);
        return stack;
    }

    /**
     * Starts the options for a render of one id at one type carrying the steering stack.
     *
     * @param id the id to render
     * @param type the render type
     * @return the options builder
     */
    private static @NotNull ItemOptions.Builder steered(@NotNull String id, ItemOptions.@NotNull Type type) {
        return item(id, type, EulerRotation.NONE).mutate().context(ItemContext.ofStack(stack(id, 1, 0)));
    }

    /**
     * Finishes options with an enchantment and a still glint.
     *
     * @param options the options builder
     * @return the enchanted options
     */
    private static @NotNull ItemOptions enchanted(@NotNull ItemOptions.Builder options) {
        return options.enchanted(true).animateGlint(false).build();
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

    /**
     * Collects the distinct fully-opaque colours a render's first frame carries.
     *
     * @param rendered the render
     * @return every opaque colour present, without duplicates
     */
    private static Set<Integer> distinctOpaque(@NotNull RenderResult rendered) {
        Set<Integer> colours = new HashSet<>();
        for (int pixel : RenderDigest.firstFramePixels(rendered.image()))
            if ((pixel >>> 24) == 0xFF) colours.add(pixel);

        return colours;
    }

    /**
     * Builds isometric block options at the shared test canvas and the given rotation.
     *
     * @param id the block id to render
     * @param rotation the model rotation the caller asks for
     * @return the block options
     */
    private static @NotNull BlockOptions block(@NotNull String id, @NotNull EulerRotation rotation) {
        return BlockOptions.builder()
            .blockId(id)
            .output(OutputOptions.builder().canvasSize(SIZE).rotation(rotation).build())
            .build();
    }

    /**
     * Builds single-face block options at the shared test canvas and the given rotation.
     *
     * @param rotation the model rotation the caller asks for
     * @return the block options
     */
    private static @NotNull BlockOptions face(@NotNull EulerRotation rotation) {
        return BlockOptions.builder()
            .blockId(UNKNOWN)
            .type(BlockOptions.Type.BLOCK_FACE_2D)
            .output(OutputOptions.builder().canvasSize(SIZE).rotation(rotation).build())
            .build();
    }

    /**
     * Renders the unknown id through the held path at the given rotation.
     *
     * @param rotation the model rotation the caller asks for
     * @return the rendered image
     */
    private static @NotNull ImageData item3D(@NotNull EulerRotation rotation) {
        return itemRenderer.render(item(UNKNOWN, ItemOptions.Type.HELD_3D, rotation)).image();
    }

    /**
     * Builds item options at the shared test canvas and the given rotation.
     *
     * @param id the item id to render
     * @param type the render mode to dispatch through
     * @param rotation the model rotation the caller asks for
     * @return the item options
     */
    private static @NotNull ItemOptions item(
        @NotNull String id, ItemOptions.@NotNull Type type, @NotNull EulerRotation rotation) {
        return ItemOptions.builder()
            .itemId(id)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).rotation(rotation).build())
            .build();
    }

}
