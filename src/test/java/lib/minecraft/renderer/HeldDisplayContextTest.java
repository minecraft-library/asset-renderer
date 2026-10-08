package lib.minecraft.renderer;

import com.google.gson.JsonParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.ImageData;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.GlintPolicy;
import lib.minecraft.renderer.content.index.ItemModelDispatch.FrameItem;
import lib.minecraft.renderer.content.index.ItemModelDispatch;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.request.AnimationOptions;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.request.OutputOptions;
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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the display context a render walks an item-definition tree at: a held render the caller
 * supplies no context for resolves a {@code display_context} select at {@code thirdperson_righthand},
 * the flat types resolve it at {@code gui}, and a context the caller supplies is used as given.
 * <p>
 * The same walk carries the caller's item stack. A 26.1 stack in {@link ItemOptions#getContext()}
 * reaches the walk whether or not the caller supplies a context, a context's own components winning,
 * and so does its item id, which an {@code item_model} select reads; a stack that chooses no branch
 * walks as no stack, keeping the baked fast path. A CIT model override naming no model renders the base
 * item. A derived animation counts its frames along the same walk, so a stack whose branch holds no
 * time table renders a still where vanilla's clock derives a day.
 * <p>
 * Each row resolves what a frame draws through {@link ItemModelDispatch#resolveRenderItem}, or the
 * timing a render bakes through {@link ItemModelDispatch#itemAnimation}, so it reads which model the
 * tree answered rather than the pixels drawn from it; the derived-animation row renders only to count
 * the frames a still holds.
 * The stack rows read a definition parsed from JSON through the real deserializer, standing in for a
 * pack's. One row reads the model lookup that resolution materialises a leaf from, which answers a
 * block model as vanilla's one model map does.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where
 * nothing has extracted the client yet.
 */
@DisplayName("The display context an item render resolves its tree at")
@ExtendWith(ClientAssetsExtension.class)
class HeldDisplayContextTest {

    /** A spear, whose tree names its inventory sprite at gui and its in-hand model at a held display. */
    private static final @NotNull String IRON_SPEAR = "minecraft:iron_spear";

    /** The spyglass, whose held case is the one item model declaring element boxes of its own. */
    private static final @NotNull String SPYGLASS = "minecraft:spyglass";

    /** The trident, whose held case is a special renderer rather than a model. */
    private static final @NotNull String TRIDENT = "minecraft:trident";

    /** The sword the steered rows inject a definition for. */
    private static final @NotNull String SWORD = "minecraft:diamond_sword";

    /** A custom_data test that draws the iron sword for a stack whose custom data carries {@code id: "X"}. */
    private static final @NotNull String CUSTOM_DATA_TREE = "{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\","
        + "\"predicate\":\"minecraft:custom_data\",\"value\":{\"id\":\"X\"},"
        + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/iron_sword\"},"
        + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/diamond_sword\"}}";

    /** An {@code item_model} select that draws the iron sword for a stack of golden swords, and the diamond sword for any other. */
    private static final @NotNull String ITEM_MODEL_TREE = "{\"type\":\"minecraft:select\",\"property\":\"minecraft:component\","
        + "\"component\":\"minecraft:item_model\",\"cases\":[{\"when\":\"minecraft:golden_sword\","
        + "\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/iron_sword\"}}],"
        + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/diamond_sword\"}}";

    /** The clock, whose definition dispatches its faces on world time. */
    private static final @NotNull String CLOCK = "minecraft:clock";

    /** A {@code custom_name} select that draws paper for a stack named {@code Calendar}, its fallback the empty node a row replaces with vanilla's clock tree. */
    private static final @NotNull String CALENDAR_SELECT = "{\"type\":\"minecraft:select\",\"property\":\"minecraft:component\","
        + "\"component\":\"minecraft:custom_name\",\"cases\":[{\"when\":\"Calendar\","
        + "\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/paper\"}}],"
        + "\"fallback\":{\"type\":\"minecraft:empty\"}}";

    private static RendererContext context;

    @BeforeAll
    static void bootstrapPipeline() {
        context = ClientAssetsExtension.context();

        for (String id : List.of(IRON_SPEAR, SPYGLASS, TRIDENT))
            assertThat(id + " is an item-index id", context.findItem(id).isPresent(), is(true));
    }

    @Test
    @DisplayName("a held spear resolves its in-hand model and that model's held pose")
    void heldSpearResolvesItsInHandModel() {
        Item resolved = resolve(options(IRON_SPEAR, ItemOptions.Type.HELD_3D).build(), ItemOptions.Type.HELD_3D);

        assertThat(resolved.textures().get("layer0"), is("minecraft:item/iron_spear_in_hand"));
        ModelTransform held = resolved.model().getDisplay().get(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND);
        assertThat(held.getScaleX(), is(1.7f));
        assertThat(held.getScaleY(), is(1.7f));
        assertThat(held.getScaleZ(), is(0.85f));
        assertThat(held.getRotation(), is(new EulerRotation(5f, 270f, -40f)));
    }

    @Test
    @DisplayName("a held spyglass resolves the element model its inventory sprite lacks")
    void heldSpyglassResolvesTheElementModel() {
        Item baked = context.findItem(SPYGLASS).orElseThrow();
        Item resolved = resolve(options(SPYGLASS, ItemOptions.Type.HELD_3D).build(), ItemOptions.Type.HELD_3D);

        assertThat("the indexed spyglass is the flat sprite", baked.model().getElements(), is(empty()));
        assertThat(resolved.model().getElements(), is(not(empty())));
    }

    @Test
    @DisplayName("a gui context the caller supplies wins over the held type's own")
    void anExplicitGuiContextWins() {
        Item baked = context.findItem(IRON_SPEAR).orElseThrow();
        ItemOptions options = options(IRON_SPEAR, ItemOptions.Type.HELD_3D).itemModel(ItemModelContext.gui()).build();
        Item resolved = resolve(options, ItemOptions.Type.HELD_3D);

        assertThat(resolved, is(sameInstance(baked)));
        assertThat(resolved.model().getDisplay().get(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND).getScaleX(),
            is(0.55f));
    }

    @Test
    @DisplayName("the flat types resolve at gui, the neutral context")
    void theFlatTypesResolveAtGui() {
        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.GUI_ICON)) {
            ItemModelContext resolved = options(IRON_SPEAR, type).build().itemModelAt(type);
            assertThat(type + " resolves at gui", resolved, is(ItemModelContext.gui()));
            assertThat(type + " keeps the fast path", resolved.isNeutral(), is(true));
        }
    }

    @Test
    @DisplayName("a held trident stays on its indexed model, its held case being a special renderer")
    void heldTridentStaysOnItsIndexedModel() {
        Item baked = context.findItem(TRIDENT).orElseThrow();
        Item resolved = resolve(options(TRIDENT, ItemOptions.Type.HELD_3D).build(), ItemOptions.Type.HELD_3D);

        assertThat(resolved, is(sameInstance(baked)));
    }

    @Test
    @DisplayName("the model a resolved leaf is materialised from may be a block model")
    void theModelLookupAnswersABlockModel() {
        ModelData stone = context.findItemModel("minecraft:block/stone").orElseThrow();

        assertThat(stone.getElements(), is(not(empty())));
        assertThat("a bare id reads as minecraft:", context.findItemModel("block/stone").orElseThrow(), is(sameInstance(stone)));
    }

    @Test
    @DisplayName("a 26.1 stack walks its definition to the branch its custom data selects")
    void aStackWalksToItsBranch() {
        RendererContext steered = withTree(SWORD, CUSTOM_DATA_TREE);
        ItemOptions options = options(SWORD, ItemOptions.Type.GUI_2D).context(ItemContext.ofStack(stack("X"))).build();

        ItemModelContext walked = options.itemModelAt(ItemOptions.Type.GUI_2D);
        assertThat("the stack's patch reaches the walk", walked.components(), is(options.getContext().components()));
        FrameItem.Drawn frame = drawn(resolve(steered, options, ItemOptions.Type.GUI_2D, CitResult.NONE));
        assertThat(frame.modelId(), is(Optional.of("minecraft:item/iron_sword")));
        assertThat(frame.item().textures().get("layer0"), is("minecraft:item/iron_sword"));
    }

    @Test
    @DisplayName("a context's own components win over the stack's")
    void aContextsOwnComponentsWin() {
        RendererContext steered = withTree(SWORD, CUSTOM_DATA_TREE);
        CompoundTag own = customData("Y");
        ItemOptions options = options(SWORD, ItemOptions.Type.GUI_2D)
            .context(ItemContext.ofStack(stack("X")))
            .itemModel(ItemModelContext.gui().withComponents(own))
            .build();

        assertThat(options.itemModelAt(ItemOptions.Type.GUI_2D).components(), is(Optional.of(own)));
        assertThat("Y selects nothing, so the walk keeps the baked sword",
            drawn(resolve(steered, options, ItemOptions.Type.GUI_2D, CitResult.NONE)).item(), is(sameInstance(baked(SWORD))));
    }

    @Test
    @DisplayName("a context with no components of its own takes the stack's, keeping its other inputs")
    void aContextWithoutComponentsTakesTheStacks() {
        RendererContext steered = withTree(SWORD, CUSTOM_DATA_TREE);
        ItemOptions options = options(SWORD, ItemOptions.Type.GUI_2D)
            .context(ItemContext.ofStack(stack("X")))
            .itemModel(ItemModelContext.gui().withDisplayContext(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND))
            .build();

        ItemModelContext walked = options.itemModelAt(ItemOptions.Type.GUI_2D);
        assertThat(walked.displayContext(), is(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND));
        assertThat(walked.components(), is(options.getContext().components()));
        assertThat(drawn(resolve(steered, options, ItemOptions.Type.GUI_2D, CitResult.NONE)).modelId(),
            is(Optional.of("minecraft:item/iron_sword")));
    }

    @Test
    @DisplayName("a stack that selects nothing walks as no stack, the baked fast path included")
    void aStackThatSelectsNothingKeepsTheFastPath() {
        RendererContext steered = withTree(SWORD, CUSTOM_DATA_TREE);
        ItemOptions options = options(SWORD, ItemOptions.Type.GUI_2D).context(ItemContext.ofStack(stack("OTHER"))).build();

        FrameItem.Drawn frame = drawn(resolve(steered, options, ItemOptions.Type.GUI_2D, CitResult.NONE));
        assertThat(frame.item(), is(sameInstance(baked(SWORD))));
        assertThat(frame.modelId(), is(Optional.empty()));
    }

    @Test
    @DisplayName("a stack's item id reaches the walk as its item model, a context's own item id winning")
    void aStacksItemIdReachesTheWalk() {
        RendererContext steered = withTree(SWORD, ITEM_MODEL_TREE);
        ItemOptions golden = options(SWORD, ItemOptions.Type.GUI_2D).context(ItemContext.ofItem("golden_sword")).build();

        assertThat(golden.itemModelAt(ItemOptions.Type.GUI_2D).itemId(), is(Optional.of("minecraft:golden_sword")));
        assertThat(drawn(resolve(steered, golden, ItemOptions.Type.GUI_2D, CitResult.NONE)).modelId(),
            is(Optional.of("minecraft:item/iron_sword")));

        ItemOptions own = options(SWORD, ItemOptions.Type.GUI_2D)
            .context(ItemContext.ofItem("minecraft:golden_sword"))
            .itemModel(ItemModelContext.gui().withItemId(SWORD))
            .build();
        assertThat(own.itemModelAt(ItemOptions.Type.GUI_2D).itemId(), is(Optional.of(SWORD)));
        assertThat("an item id that selects no case walks as no stack",
            drawn(resolve(steered, own, ItemOptions.Type.GUI_2D, CitResult.NONE)).item(), is(sameInstance(baked(SWORD))));

        assertThat(options(SWORD, ItemOptions.Type.GUI_2D).build().itemModelAt(ItemOptions.Type.GUI_2D).itemId(),
            is(Optional.empty()));
    }

    @Test
    @DisplayName("a derived animation counts the time table on the branch the stack walks")
    void aDerivedAnimationFollowsTheStacksBranch() {
        // Hypixel+'s shape: a custom_name select ahead of vanilla's own clock tree, whose named case
        // draws one still model.
        ItemModelNode.Select named = (ItemModelNode.Select) node(CALENDAR_SELECT);
        RendererContext calendar = withTree(CLOCK, new ItemModelNode.Select(named.property(), named.blockStateProperty(),
            named.component(), named.decoded(), named.cases(), context.findItemTree(CLOCK).orElseThrow().root()));
        AnimationOptions derived = AnimationOptions.builder().deriveTimeline(true).build();
        OutputOptions small = ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(16).build();

        for (ItemOptions.Type type : List.of(ItemOptions.Type.GUI_2D, ItemOptions.Type.HELD_3D)) {
            ItemOptions unnamed = options(CLOCK, type).animation(derived).build();
            AnimationOptions day = ItemModelDispatch.itemAnimation(calendar, unnamed, unnamed.itemModelAt(type));
            assertThat(type + " derives a day of the clock's faces", day.getFrameCount(), is(64));
            assertThat(day.getSchedule(), is(AnimationOptions.Schedule.GAME_TIME));

            ItemOptions stack = options(CLOCK, type).animation(derived).output(small)
                .context(ItemContext.ofStack(namedClock("Calendar")))
                .build();
            ImageData still = new ItemRenderer(calendar).render(stack);
            assertThat(type + " renders the branch the name picks as one still", still.getFrames().size(), is(1));
        }
    }

    @Test
    @DisplayName("a CIT model override naming no model renders the base item")
    void aCitOverrideMissRendersTheBaseItem() {
        CitResult override = new CitResult(Optional.empty(), Concurrent.newMap(),
            Optional.of(new ResourceId("minecraft", "optifine/cit/held_display_context_test_nothing")), GlintPolicy.DEFAULT);
        ItemOptions options = options(SWORD, ItemOptions.Type.GUI_2D).build();

        FrameItem.Drawn frame = drawn(resolve(context, options, ItemOptions.Type.GUI_2D, override));
        assertThat(frame.item(), is(sameInstance(baked(SWORD))));
    }

    /**
     * Starts the options for one row, leaving the item model context for the row to set or leave empty.
     *
     * @param itemId the item id to render
     * @param type the render type
     * @return the options builder
     */
    private static @NotNull ItemOptions.Builder options(@NotNull String itemId, ItemOptions.@NotNull Type type) {
        return ItemOptions.builder().itemId(itemId).type(type);
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
     * Resolves the item a frame of the given options draws, at the evaluation context the drawing type
     * resolves for them, with no CIT override.
     *
     * @param options the row's options
     * @param drawn the render type whose context an absent one takes
     * @return the item a frame draws
     */
    private static @NotNull Item resolve(@NotNull ItemOptions options, ItemOptions.@NotNull Type drawn) {
        return drawn(resolve(context, options, drawn, CitResult.NONE)).item();
    }

    /**
     * Resolves what a frame of the given options draws against a context, at the evaluation context
     * the drawing type resolves for them.
     *
     * @param over the renderer context the walk resolves against
     * @param options the row's options
     * @param drawn the render type whose context an absent one takes
     * @param cit the CIT result the frame resolves under
     * @return what the frame draws
     */
    private static @NotNull FrameItem resolve(
        @NotNull RendererContext over, @NotNull ItemOptions options, ItemOptions.@NotNull Type drawn, @NotNull CitResult cit) {
        return ItemModelDispatch.resolveRenderItem(over, options, cit, options.itemModelAt(drawn), baked(options.getItemId()));
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
     * Wraps the client context so one id answers a definition parsed from JSON through the real
     * deserializer, as a pack shadowing that id would.
     *
     * @param itemId the id the definition shadows
     * @param model the definition's {@code model} object
     * @return the shadowing context
     */
    private static @NotNull RendererContext withTree(@NotNull String itemId, @NotNull String model) {
        return withTree(itemId, node(model));
    }

    /**
     * Wraps the client context so one id answers a definition rooted at the given node, as a pack
     * shadowing that id would.
     *
     * @param itemId the id the definition shadows
     * @param root the definition's root node
     * @return the shadowing context
     */
    private static @NotNull RendererContext withTree(@NotNull String itemId, @NotNull ItemModelNode root) {
        ItemModelTree tree = new ItemModelTree(ResourceId.parse(itemId), root);
        return new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return context;
            }

            @Override
            public @NotNull Optional<ItemModelTree> findItemTree(@NotNull String id) {
                return id.equals(itemId) ? Optional.of(tree) : context.findItemTree(id);
            }

        };
    }

    /**
     * Parses a definition's {@code model} object through the real deserializer.
     *
     * @param model the {@code model} object
     * @return the parsed node
     */
    private static @NotNull ItemModelNode node(@NotNull String model) {
        return GsonSettings.defaults().create().fromJson(JsonParser.parseString(model), ItemModelNode.class);
    }

    /**
     * Builds a 26.1 clock stack carrying a custom name.
     *
     * @param name the name, a plain literal
     * @return the stack
     */
    private static @NotNull CompoundTag namedClock(@NotNull String name) {
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_name", new StringTag(name));
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(CLOCK));
        stack.put("count", new IntTag(1));
        stack.put("components", components);
        return stack;
    }

    /**
     * Builds a component patch whose custom data carries one id.
     *
     * @param id the custom data's {@code id}
     * @return the patch
     */
    private static @NotNull CompoundTag customData(@NotNull String id) {
        CompoundTag data = new CompoundTag();
        data.put("id", new StringTag(id));
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_data", data);
        return components;
    }

    /**
     * Builds a 26.1 diamond sword stack whose custom data carries one id, and no dyed colour.
     *
     * @param id the custom data's {@code id}
     * @return the stack
     */
    private static @NotNull CompoundTag stack(@NotNull String id) {
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(SWORD));
        stack.put("count", new IntTag(1));
        stack.put("components", customData(id));
        return stack;
    }

}
