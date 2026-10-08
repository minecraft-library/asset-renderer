package lib.minecraft.renderer;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of a model built from elements in a GUI slot: the slot draws its elements, whether the item
 * index carries the model or a stack's branch chooses it, and a {@code layer0} bound beside them draws
 * nothing of its own. The elements are posed by the model's {@code display.gui}, and a model declaring
 * none is drawn unturned, so its south face fills the slot. They are lit as the model's
 * {@code gui_light} says - a face toward the viewer at full strength for {@code front} and shaded for
 * {@code side} - and a composite lights every layer as its first layer's light says.
 * <p>
 * The fixture is synthetic: solid one-colour textures served from memory, and models and definitions
 * parsed through the real deserializers, answered over an in-memory context. Shading scales the three
 * channels together, so a pixel textured blue stays a pixel with no red and no green.
 */
@DisplayName("A model built from elements draws in a GUI slot")
class SlotElementModelTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The item every row renders. */
    private static final @NotNull String ITEM = "test:widget";

    private static final @NotNull String WHITE = "test:block/white";

    private static final @NotNull String RED = "test:block/red";

    private static final @NotNull String BLUE = "test:block/blue";

    /** The block icon's {@code display.gui}: the iso turn at vanilla's block scale. */
    private static final @NotNull String ISO_GUI =
        "\"display\":{\"gui\":{\"rotation\":[30,225,0],\"translation\":[0,0,0],\"scale\":[0.625,0.625,0.625]}}";

    private static final int SIZE = 64;

    @Test
    @DisplayName("a model built from elements that the item index carries draws them, in either slot type")
    void anIndexedElementModelDraws() {
        RendererContext context = context(cube("side", true, ""), Map.of(), Optional.empty());

        int[] slot = render(context, ItemOptions.Type.GUI_2D, false);
        assertThat("the slot draws the cube", opaque(slot), is(greaterThan(0)));
        assertThat("the inventory icon is the same picture", render(context, ItemOptions.Type.GUI_ICON, false), is(slot));
    }

    @Test
    @DisplayName("a model built from elements a stack's branch chooses draws them, as the item index's own would")
    void aChosenElementModelDraws() {
        String chosen = "test:item/cube";
        RendererContext context = context(flat("front"), Map.of(chosen, cube("side", true, ""), "test:item/flat", flat("front")),
            Optional.of(steeredTo(chosen)));
        int[] indexed = render(context(cube("side", true, ""), Map.of(), Optional.empty()), ItemOptions.Type.GUI_2D, false);

        for (ItemOptions.Type type : new ItemOptions.Type[]{ ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON }) {
            int[] steered = render(context, type, true);
            assertThat(type + " draws the chosen cube", steered, is(indexed));
            assertThat(type + " draws other than the sprite the stack passes over", steered, is(not(render(context, type, false))));
        }
    }

    @Test
    @DisplayName("front and side light the same cube differently, over the same silhouette")
    void frontAndSideLightDiffer() {
        int[] side = render(context(cube("side", true, ""), Map.of(), Optional.empty()), ItemOptions.Type.GUI_2D, false);
        int[] front = render(context(cube("front", true, ""), Map.of(), Optional.empty()), ItemOptions.Type.GUI_2D, false);

        assertThat("the same pixels are covered", alphaMask(front), is(alphaMask(side)));
        assertThat("the shades differ", front, is(not(side)));
    }

    @Test
    @DisplayName("a model declaring no display.gui is drawn unturned, its south face filling the slot")
    void aModelWithNoGuiDisplayDrawsUnturned() {
        int[] unturned = render(context(cube("side", false, ""), Map.of(), Optional.empty()), ItemOptions.Type.GUI_2D, false);

        assertThat("every pixel is covered", opaque(unturned), is(SIZE * SIZE));
        assertThat("every pixel is the blue south face", Arrays.stream(unturned)
            .allMatch(pixel -> (pixel >>> 16 & 0xFF) == 0 && (pixel >>> 8 & 0xFF) == 0 && (pixel & 0xFF) > 0), is(true));
    }

    @Test
    @DisplayName("a face toward the viewer takes full light lit front and is shaded lit side, as the two entries light it")
    void eachLightShadesTheFacingFaceAsItsEntryDoes() {
        int[] front = render(context(cube("front", false, ""), Map.of(), Optional.empty()), ItemOptions.Type.GUI_2D, false);
        int[] side = render(context(cube("side", false, ""), Map.of(), Optional.empty()), ItemOptions.Type.GUI_2D, false);

        assertThat("ITEMS_FLAT lights the south face at full strength", Arrays.stream(front).allMatch(pixel -> pixel == 0xFF0000FF), is(true));
        assertThat("ITEMS_3D shades it", Arrays.stream(side).allMatch(pixel -> pixel != 0xFF0000FF && (pixel & 0xFF) > 0), is(true));
    }

    @Test
    @DisplayName("a layer0 bound beside the elements draws nothing of its own")
    void aLayerBesideTheElementsDrawsNothing() {
        int[] beside = render(context(cube("front", true, "\"layer0\":\"" + RED + "\","), Map.of(), Optional.empty()),
            ItemOptions.Type.GUI_2D, false);

        assertThat(beside, is(render(context(cube("front", true, ""), Map.of(), Optional.empty()), ItemOptions.Type.GUI_2D, false)));
    }

    @Test
    @DisplayName("a composite lights its element layer as its first layer's light says")
    void aCompositeLightsEveryLayerByItsFirst() {
        Map<String, ModelData> models = Map.of(
            "test:item/flat_front", flat("front"), "test:item/flat_side", flat("side"),
            "test:item/cube_front", cube("front", true, ""), "test:item/cube_side", cube("side", true, ""));

        int[] frontFirst = render(context(flat("front"), models,
            Optional.of(composite("test:item/flat_front", "test:item/cube_side"))), ItemOptions.Type.GUI_2D, false);
        int[] allFront = render(context(flat("front"), models,
            Optional.of(composite("test:item/flat_front", "test:item/cube_front"))), ItemOptions.Type.GUI_2D, false);
        int[] sideFirst = render(context(flat("front"), models,
            Optional.of(composite("test:item/flat_side", "test:item/cube_side"))), ItemOptions.Type.GUI_2D, false);

        assertThat("a side cube behind a front sprite is lit front", frontFirst, is(allFront));
        assertThat("and lit side behind a side sprite", sideFirst, is(not(frontFirst)));
    }

    /**
     * Builds the in-memory context a row renders over: the solid textures, the item index holding
     * {@link #ITEM} at the given model, and the models and definition a walk reads.
     *
     * @param indexed the model the item index carries {@link #ITEM} at
     * @param models the models a definition's leaves name, keyed by model id
     * @param tree the definition {@link #ITEM} answers, empty for none
     * @return the context
     */
    private static @NotNull RendererContext context(
        @NotNull ModelData indexed, @NotNull Map<String, ModelData> models, @NotNull Optional<ItemModelTree> tree) {
        RendererContext base = RendererContext.builder()
            .textures(Map.of(WHITE, solid(0xFFFFFFFF), RED, solid(0xFFFF0000), BLUE, solid(0xFF0000FF)))
            .items(Map.of(ITEM, item(indexed)))
            .build();

        return new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return base;
            }

            @Override
            public @NotNull Optional<ItemModelTree> findItemTree(@NotNull String id) {
                return id.equals(ITEM) ? tree : base.findItemTree(id);
            }

            @Override
            public @NotNull Optional<ModelData> findItemModel(@NotNull String modelId) {
                return Optional.ofNullable(models.get(modelId)).or(() -> base.findItemModel(modelId));
            }

        };
    }

    /**
     * Builds the indexed item a model backs, with no durability, tint or foil.
     *
     * @param model the item's model
     * @return the item
     */
    private static @NotNull Item item(@NotNull ModelData model) {
        ConcurrentMap<String, String> sprites = Concurrent.newMap();
        model.getTextures().forEach((slot, texture) -> sprites.put(slot, texture.sprite()));
        return new Item(ResourceId.parse(ITEM), model, sprites, 0, Concurrent.newUnmodifiableList(), false);
    }

    /**
     * Parses one full cube whose south face wears blue, its north face red and its other four white.
     *
     * @param light the {@code gui_light} the model names
     * @param posed whether the model declares the block icon's {@code display.gui}
     * @param texture a texture binding to add beside the cube's own, comma terminated, or {@code ""}
     * @return the model
     */
    private static @NotNull ModelData cube(@NotNull String light, boolean posed, @NotNull String texture) {
        return GSON.fromJson("{\"gui_light\":\"" + light + "\",\"textures\":{" + texture
            + "\"s\":\"" + BLUE + "\",\"n\":\"" + RED + "\",\"t\":\"" + WHITE + "\"},"
            + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
            + "\"south\":{\"texture\":\"#s\"},\"north\":{\"texture\":\"#n\"},\"east\":{\"texture\":\"#t\"},"
            + "\"west\":{\"texture\":\"#t\"},\"up\":{\"texture\":\"#t\"},\"down\":{\"texture\":\"#t\"}}}]"
            + (posed ? "," + ISO_GUI : "") + "}", ModelData.class);
    }

    /**
     * Parses a flat sprite model wearing the red texture as its {@code layer0}.
     *
     * @param light the {@code gui_light} the model names
     * @return the model
     */
    private static @NotNull ModelData flat(@NotNull String light) {
        return GSON.fromJson("{\"gui_light\":\"" + light + "\",\"textures\":{\"layer0\":\"" + RED + "\"}}", ModelData.class);
    }

    /**
     * Builds a definition whose custom_data test draws a model for a stack carrying {@code id: "X"},
     * and the flat sprite otherwise.
     *
     * @param model the model the stack's branch names
     * @return the definition
     */
    private static @NotNull ItemModelTree steeredTo(@NotNull String model) {
        return tree("{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\","
            + "\"predicate\":\"minecraft:custom_data\",\"value\":{\"id\":\"X\"},"
            + "\"on_true\":" + leaf(model) + ",\"on_false\":" + leaf("test:item/flat") + "}");
    }

    /**
     * Builds a definition rooted at a composite of two models, in paint order.
     *
     * @param first the first child's model
     * @param second the second child's model
     * @return the definition
     */
    private static @NotNull ItemModelTree composite(@NotNull String first, @NotNull String second) {
        return tree("{\"type\":\"minecraft:composite\",\"models\":[" + leaf(first) + "," + leaf(second) + "]}");
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
     * Parses a definition's {@code model} object for {@link #ITEM} through the real deserializer.
     *
     * @param model the {@code model} object
     * @return the definition
     */
    private static @NotNull ItemModelTree tree(@NotNull String model) {
        return new ItemModelTree(ResourceId.parse(ITEM), GSON.fromJson(JsonParser.parseString(model), ItemModelNode.class));
    }

    /**
     * Renders {@link #ITEM} at the test canvas, with or without the stack whose custom data carries
     * {@code id: "X"}.
     *
     * @param context the context the render resolves against
     * @param type the render type
     * @param steered whether the stack rides the render
     * @return the first frame's ARGB texels
     */
    private static int @NotNull [] render(@NotNull RendererContext context, ItemOptions.@NotNull Type type, boolean steered) {
        ItemOptions.Builder options = ItemOptions.builder()
            .itemId(ITEM)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build())
            .substituteMissing(false);
        if (steered)
            options.context(ItemContext.ofStack(stack()));

        return RenderDigest.firstFramePixels(new ItemRenderer(context).render(options.build()));
    }

    /**
     * Builds a 26.1 stack of {@link #ITEM} whose custom data carries {@code id: "X"}.
     *
     * @return the stack
     */
    private static @NotNull CompoundTag stack() {
        CompoundTag data = new CompoundTag();
        data.put("id", new StringTag("X"));
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_data", data);
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(ITEM));
        stack.put("count", new IntTag(1));
        stack.put("components", components);
        return stack;
    }

    /**
     * Builds a 16x16 texture of one colour.
     *
     * @param argb the colour
     * @return the texture
     */
    private static @NotNull PixelBuffer solid(int argb) {
        int[] pixels = new int[16 * 16];
        Arrays.fill(pixels, argb);
        return PixelBuffer.of(pixels, 16, 16);
    }

    /**
     * Counts the pixels a frame covers fully.
     *
     * @param pixels the frame's ARGB texels
     * @return the opaque pixel count
     */
    private static int opaque(int @NotNull [] pixels) {
        return (int) Arrays.stream(pixels).filter(pixel -> (pixel >>> 24) == 0xFF).count();
    }

    /**
     * Reads which pixels a frame covers at all.
     *
     * @param pixels the frame's ARGB texels
     * @return one flag per pixel, set where its alpha is non-zero
     */
    private static boolean @NotNull [] alphaMask(int @NotNull [] pixels) {
        boolean[] mask = new boolean[pixels.length];
        for (int i = 0; i < pixels.length; i++)
            mask[i] = (pixels[i] >>> 24) != 0;
        return mask;
    }

}
