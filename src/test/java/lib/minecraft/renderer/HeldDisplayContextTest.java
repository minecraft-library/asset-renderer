package lib.minecraft.renderer;

import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.ItemModelDispatch;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the display context a render walks an item-definition tree at: a held render the caller
 * supplies no context for resolves a {@code display_context} select at {@code thirdperson_righthand},
 * the flat types resolve it at {@code gui}, and a context the caller supplies is used as given.
 * <p>
 * Each row resolves the item a frame draws through {@link ItemModelDispatch#resolveRenderItem} and
 * rasterizes nothing, so it reads which model the tree answered rather than the pixels drawn from it.
 * One row reads the model lookup that resolution materialises a leaf from, which answers a block model
 * as vanilla's one model map does.
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
            ItemModelContext resolved = ItemRenderer.itemModelOf(options(IRON_SPEAR, type).build(), type);
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
     * Resolves the item a frame of the given options draws, at the evaluation context the drawing type
     * resolves for them, with no CIT override.
     *
     * @param options the row's options
     * @param drawn the render type whose context an absent one takes
     * @return the item a frame draws
     */
    private static @NotNull Item resolve(@NotNull ItemOptions options, ItemOptions.@NotNull Type drawn) {
        Item baked = context.findItem(options.getItemId()).orElseThrow();
        return ItemModelDispatch.resolveRenderItem(
            context, options, CitResult.NONE, ItemRenderer.itemModelOf(options, drawn), baked);
    }

}
