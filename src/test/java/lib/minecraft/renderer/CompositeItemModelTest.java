package lib.minecraft.renderer;

import com.google.gson.JsonParser;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.util.Possible;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.ItemModelDispatch.FrameItem;
import lib.minecraft.renderer.content.index.ItemModelDispatch;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.mesh.MissingMesh;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of a {@code minecraft:composite} item definition drawn as vanilla draws one: every child
 * that draws, one over another in order.
 * <p>
 * What a frame draws is read through {@link ItemModelDispatch} - a composite of the frames its layers
 * draw, each with its own model and tints, with no stack as with one, and a later child a stack steers
 * steering the frame - and then the pixels: a slot stacks the layers' sprites in paint order, the held
 * view draws them in one depth pass so the order they are drawn in decides nothing, a block-backed id
 * whose definition composes draws its layers held and in a slot as the item-index id does, and a later
 * child naming a model no pack ships draws the missing model. A vanilla bed, whose definition composes
 * two special models, still draws once through its own path.
 * <p>
 * Each definition is parsed from JSON through the real deserializer and answered for one id over the
 * client, as a pack shadowing that id would. Reads the client assets through
 * {@link ClientAssetsExtension}, which abandons the class where nothing has extracted the client.
 */
@DisplayName("A composite item definition draws every child")
@ExtendWith(ClientAssetsExtension.class)
class CompositeItemModelTest {

    /** The id the composites are drawn for, an item-index id. */
    private static final @NotNull String SWORD = "minecraft:diamond_sword";

    /** The item whose sprite a composite draws over the sword's. */
    private static final @NotNull String STICK = "minecraft:stick";

    /** A block-backed id the item index does not carry. */
    private static final @NotNull String STONE = "minecraft:stone";

    /** The canvas every slot render here draws at. */
    private static final int SIZE = 32;

    /** The canvas the held rows draw at, wide enough that a held block covers more than a few pixels. */
    private static final int HELD_SIZE = 128;

    /**
     * A flower pot, which declares no display and so is held at the identity pose. Its walls stand
     * around the fence post's lower half, so the two pierce each other and share no face a camera
     * sees.
     */
    private static final @NotNull String POT = leaf("minecraft:block/flower_pot");

    /** A fence post, which declares no display either, standing through the flower pot. */
    private static final @NotNull String POST = leaf("minecraft:block/oak_fence_post");

    private static RendererContext context;

    @BeforeAll
    static void bootstrapPipeline() {
        context = ClientAssetsExtension.context();

        assertThat(SWORD + " is an item-index id", context.findItem(SWORD).isPresent(), is(true));
        assertThat(STONE + " is block-backed", context.findBlock(STONE).isPresent(), is(true));
        assertThat(STONE + " is no item-index id", context.findItem(STONE).isPresent(), is(false));
    }

    @Test
    @DisplayName("with no stack, a composite's frame holds every layer it draws, each with its own model and tints")
    void theFrameHoldsEveryLayer() {
        RendererContext composed = withTree(SWORD, composite(
            tinted("minecraft:item/diamond_sword", 0xFFFF0000), "{\"type\":\"minecraft:empty\"}", tinted("minecraft:item/stick", 0xFF0000FF)));

        FrameItem.Composite frame = composite(resolve(composed, options(SWORD, ItemOptions.Type.GUI_2D).build(), ItemOptions.Type.GUI_2D));
        assertThat(frame.item(), is(sameInstance(baked(SWORD))));
        assertThat(frame.layers().stream().map(layer -> drawn(layer).modelId().orElseThrow()).toList(),
            contains("minecraft:item/diamond_sword", "minecraft:item/stick"));
        assertThat(drawn(frame.layers().getFirst()).item().tints(), contains(new LayerTint.Constant(0xFFFF0000)));
        assertThat(drawn(frame.layers().getLast()).item().tints(), contains(new LayerTint.Constant(0xFF0000FF)));
        assertThat("every layer keeps the sword's decorations",
            frame.layers().stream().allMatch(layer -> layer.item().maxDurability() == baked(SWORD).maxDurability()), is(true));
        assertThat(frame.glints(), is(true));
    }

    @Test
    @DisplayName("a stack that steers a later child steers the frame, and a composite of one drawing child draws it alone")
    void aStackSteersALaterChild() {
        RendererContext composed = withTree(SWORD, composite(leaf("minecraft:item/diamond_sword"),
            "{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\",\"predicate\":\"minecraft:custom_data\","
                + "\"value\":{\"id\":\"X\"},\"on_true\":" + leaf("minecraft:item/stick") + ",\"on_false\":{\"type\":\"minecraft:empty\"}}"));

        ItemOptions steered = options(SWORD, ItemOptions.Type.GUI_2D).context(ItemContext.ofStack(stack("X"))).build();
        assertThat(composite(resolve(composed, steered, ItemOptions.Type.GUI_2D)).layers().stream().map(layer -> drawn(layer).modelId().orElseThrow()).toList(),
            contains("minecraft:item/diamond_sword", "minecraft:item/stick"));

        for (ItemOptions options : List.of(options(SWORD, ItemOptions.Type.GUI_2D).build(),
            options(SWORD, ItemOptions.Type.GUI_2D).context(ItemContext.ofStack(stack("OTHER"))).build())) {
            FrameItem frame = resolve(composed, options, ItemOptions.Type.GUI_2D);
            assertThat(frame, is(instanceOf(FrameItem.Drawn.class)));
            assertThat("the walk's own leaf, not the baked item", drawn(frame).modelId(), is(Optional.of("minecraft:item/diamond_sword")));
        }
    }

    @Test
    @DisplayName("a slot stacks every layer's sprite in paint order, a later one over the ones before it")
    void aSlotStacksTheLayers() {
        RendererContext composed = withTree(SWORD, composite(leaf("minecraft:item/diamond_sword"), leaf("minecraft:item/stick")));
        int[] drawn = pixels(new ItemRenderer(composed).render(options(SWORD, ItemOptions.Type.GUI_2D).build()));
        int[] sword = pixels(new ItemRenderer(context).render(options(SWORD, ItemOptions.Type.GUI_2D).build()));
        int[] stick = pixels(new ItemRenderer(context).render(options(STICK, ItemOptions.Type.GUI_2D).build()));

        int compared = 0;
        int over = 0;
        for (int index = 0; index < drawn.length; index++) {
            int top = ColorMath.alpha(stick[index]);
            if (top != 0 && top != 255) continue;

            compared++;
            assertThat("pixel " + index, drawn[index], is(top == 255 ? stick[index] : sword[index]));
            if (top == 255 && ColorMath.alpha(sword[index]) == 255 && stick[index] != sword[index]) over++;
        }
        assertThat(compared, is(greaterThan(0)));
        assertThat("pixels where the stick draws over the sword", over, is(greaterThan(0)));
    }

    @Test
    @DisplayName("the held view draws every layer in one depth pass, so the order they are drawn in decides nothing")
    void theHeldViewDrawsOneDepthPass() {
        int[] potFirst = held(SWORD, composite(POT, POST));
        int[] postFirst = held(SWORD, composite(POST, POT));
        int[] potAlone = held(SWORD, POT);
        int[] postAlone = held(SWORD, POST);

        // Where both cover a pixel in different colours, drawing them one over the other would give
        // the later one's colour, and so would differ between the two orders.
        int contested = 0;
        for (int index = 0; index < potAlone.length; index++)
            if (ColorMath.alpha(potAlone[index]) != 0 && ColorMath.alpha(postAlone[index]) != 0 && potAlone[index] != postAlone[index]) contested++;
        assertThat("pixels both layers cover in different colours", contested, is(greaterThan(0)));

        assertThat(potFirst, is(postFirst));
        assertThat(potFirst, is(not(potAlone)));
        assertThat(potFirst, is(not(postAlone)));
    }

    @Test
    @DisplayName("a block-backed id whose definition composes draws its layers held and in a slot, as the item-index id does")
    void aBlockBackedCompositeDraws() {
        RendererContext composed = withTree(STONE, composite(POT, POST));
        ItemOptions options = options(STONE, ItemOptions.Type.HELD_3D).build();

        FrameItem chosen = ItemModelDispatch.definitionItem(composed, options, options.itemModelAt(ItemOptions.Type.HELD_3D)).orElseThrow();
        assertThat(composite(chosen).layers().stream().map(layer -> drawn(layer).modelId().orElseThrow()).toList(),
            contains("minecraft:block/flower_pot", "minecraft:block/oak_fence_post"));

        assertThat("the block-backed id draws the layers the item-index id does",
            held(STONE, composite(POT, POST)), is(held(SWORD, composite(POT, POST))));
        int[] slot = pixels(new ItemRenderer(composed).render(options(STONE, ItemOptions.Type.GUI_ICON).build()));
        assertThat("its slot draws them as the item-index id's slot does", slot,
            is(pixels(new ItemRenderer(withTree(SWORD, composite(POT, POST))).render(options(SWORD, ItemOptions.Type.GUI_2D).build()))));
        assertThat("and not the block icon", slot,
            is(not(pixels(new ItemRenderer(context).render(options(STONE, ItemOptions.Type.GUI_ICON).build())))));
    }

    @Test
    @DisplayName("a later child naming a model no pack ships draws the missing model over the layers before it, in a slot and held")
    void aLaterChildMissesAsALeafDoes() {
        String sword = leaf("minecraft:item/diamond_sword");
        String missing = composite(sword, leaf("minecraft:item/composite_item_model_test_nothing"));
        RendererContext composed = withTree(SWORD, missing);

        int[] drawn = pixels(new ItemRenderer(composed).render(options(SWORD, ItemOptions.Type.GUI_2D).build()));
        assertThat(drawn, is(MissingMesh.icon(SIZE).data()));
        assertThat("held, the missing cube joins the sword in the one depth pass",
            held(SWORD, missing), is(not(held(SWORD, composite(sword)))));
    }

    @Test
    @DisplayName("a vanilla bed, whose definition composes two special models, draws once through its own path")
    void aBedDrawsThroughItsOwnPath() {
        String bed = "minecraft:red_bed";
        ItemModelTree tree = context.findItemTree(bed).orElseThrow();
        assertThat(options(bed, ItemOptions.Type.GUI_2D).build().itemModelAt(ItemOptions.Type.GUI_2D).resolve(tree).composed(), is(true));

        ItemOptions options = options(bed, ItemOptions.Type.GUI_ICON).build();
        assertThat(ItemModelDispatch.definitionItem(context, options, options.itemModelAt(ItemOptions.Type.GUI_ICON)), is(Optional.empty()));
        Possible<Item> indexed = context.findItem(bed);
        if (indexed.isPresent())
            assertThat(drawn(resolve(context, options(bed, ItemOptions.Type.GUI_2D).build(), ItemOptions.Type.GUI_2D)).item(), is(sameInstance(indexed.get())));
    }

    /**
     * Starts the options for one row at the canvas every render here draws at.
     *
     * @param itemId the item id to render
     * @param type the render type
     * @return the options builder
     */
    private static @NotNull ItemOptions.Builder options(@NotNull String itemId, ItemOptions.@NotNull Type type) {
        return ItemOptions.builder()
            .itemId(itemId)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build());
    }

    /**
     * The pipeline-baked item an id resolves to.
     *
     * @param itemId the item id
     * @return the indexed item
     */
    private static @NotNull Item baked(@NotNull String itemId) {
        return context.findItem(itemId).orElseThrow();
    }

    /**
     * Resolves what a frame of the given options draws against a context, at the evaluation context
     * the drawing type resolves for them, with no CIT override.
     *
     * @param over the renderer context the walk resolves against
     * @param options the row's options
     * @param type the render type whose context an absent one takes
     * @return what the frame draws
     */
    private static @NotNull FrameItem resolve(@NotNull RendererContext over, @NotNull ItemOptions options, ItemOptions.@NotNull Type type) {
        return ItemModelDispatch.resolveRenderItem(over, options, CitResult.NONE, options.itemModelAt(type), baked(options.getItemId()));
    }

    /**
     * Unwraps a frame that draws a composite, failing the row where it draws anything else.
     *
     * @param frame the frame
     * @return the composite frame
     */
    private static @NotNull FrameItem.Composite composite(@NotNull FrameItem frame) {
        assertThat(frame, is(instanceOf(FrameItem.Composite.class)));
        return (FrameItem.Composite) frame;
    }

    /**
     * Unwraps a frame that draws a model, failing the row where it draws anything else.
     *
     * @param frame the frame
     * @return the drawn frame
     */
    private static @NotNull FrameItem.Drawn drawn(@NotNull FrameItem frame) {
        assertThat(frame, is(instanceOf(FrameItem.Drawn.class)));
        return (FrameItem.Drawn) frame;
    }

    /**
     * Draws an id held, its definition answered by the given node, at the held rows' canvas.
     *
     * @param itemId the id
     * @param model the definition's {@code model} object
     * @return the first frame's pixels
     */
    private static int @NotNull [] held(@NotNull String itemId, @NotNull String model) {
        return pixels(new ItemRenderer(withTree(itemId, model)).render(options(itemId, ItemOptions.Type.HELD_3D)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(HELD_SIZE).build())
            .build()));
    }

    /**
     * Reads a render's first frame.
     *
     * @param image the render
     * @return the first frame's pixels
     */
    private static int @NotNull [] pixels(@NotNull ImageData image) {
        return RenderDigest.firstFramePixels(image);
    }

    /**
     * Wraps the client context so one id answers a definition parsed from JSON through the real
     * deserializer, as a pack shadowing that id would.
     *
     * @param itemId the id the definition shadows
     * @param model the definition's {@code model} object
     * @return the shadowing context
     */
    private static @NotNull RendererContext withTree(@NotNull String itemId, @NotNull String model) {
        ItemModelNode root = GsonSettings.defaults().create().fromJson(JsonParser.parseString(model), ItemModelNode.class);
        ItemModelTree tree = new ItemModelTree(ResourceId.parse(itemId), root);
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
     * A {@code minecraft:composite} node over the given child nodes.
     *
     * @param children the child nodes' JSON, in paint order
     * @return the node's JSON
     */
    private static @NotNull String composite(@NotNull String... children) {
        return "{\"type\":\"minecraft:composite\",\"models\":[" + String.join(",", children) + "]}";
    }

    /**
     * A {@code minecraft:model} leaf.
     *
     * @param model the model id
     * @return the leaf's JSON
     */
    private static @NotNull String leaf(@NotNull String model) {
        return "{\"type\":\"minecraft:model\",\"model\":\"" + model + "\"}";
    }

    /**
     * A {@code minecraft:model} leaf whose {@code layer0} takes one constant tint.
     *
     * @param model the model id
     * @param argb the tint
     * @return the leaf's JSON
     */
    private static @NotNull String tinted(@NotNull String model, int argb) {
        return "{\"type\":\"minecraft:model\",\"model\":\"" + model + "\",\"tints\":[{\"type\":\"minecraft:constant\",\"value\":" + argb + "}]}";
    }

    /**
     * Builds a 26.1 diamond sword stack whose custom data carries one id.
     *
     * @param id the custom data's {@code id}
     * @return the stack
     */
    private static @NotNull CompoundTag stack(@NotNull String id) {
        CompoundTag data = new CompoundTag();
        data.put("id", new StringTag(id));
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_data", data);
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(SWORD));
        stack.put("count", new IntTag(1));
        stack.put("components", components);
        return stack;
    }

}
