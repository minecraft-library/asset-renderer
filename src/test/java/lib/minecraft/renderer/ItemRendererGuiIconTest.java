package lib.minecraft.renderer;

import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.parity.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Equivalence coverage for the {@link ItemOptions.Type#GUI_ICON} faithful-inventory-icon dispatch.
 * Asserts that the mode is a pure router with no new rendering of its own:
 * <ul>
 * <li>a flat-sprite item (in the item index) is byte-identical to its {@link ItemOptions.Type#GUI_2D}
 * render;</li>
 * <li>a plain block with no flat item icon (not in the item index) is byte-identical to the
 * isometric {@link BlockRenderer} render at the same output frame;</li>
 * <li>a block-entity id (bed) likewise routes to the isometric block path;</li>
 * <li>an id backing neither an item nor a block raises {@link RenderException}.</li>
 * </ul>
 * Tagged {@code slow} because it boots the full asset pipeline; run with
 * {@code ./gradlew slowTest}.
 */
@Tag("slow")
@DisplayName("ItemRenderer GUI_ICON faithful-icon dispatch")
@ExtendWith(ClientAssetsExtension.class)
class ItemRendererGuiIconTest {

    private static final int SIZE = 64;

    private static RendererContext context;
    private static ItemRenderer itemRenderer;
    private static BlockRenderer blockRenderer;

    @BeforeAll
    static void bootstrapPipeline() {
        context = ClientAssetsExtension.context();
        itemRenderer = new ItemRenderer(context);
        blockRenderer = new BlockRenderer(context);
    }

    @Test
    @DisplayName("flat item/generated block-item: GUI_ICON == GUI_2D byte-for-byte")
    void guiIconMatchesGui2DForFlatItem() {
        // small_amethyst_bud ships a real models/item/*.json flat sprite, so it lives in the item
        // index and GUI_ICON routes it through the GUI_2D path unchanged.
        String id = "minecraft:small_amethyst_bud";
        assertThat(context.findItem(id).isPresent(), is(true));

        int[] icon = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_ICON)));
        int[] flat = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_2D)));
        assertThat(icon, is(flat));
    }

    @Test
    @DisplayName("plain block with no flat item icon: GUI_ICON == isometric BlockRenderer")
    void guiIconMatchesBlockIsoForPlainBlock() {
        // stone has no models/item/stone.json (its item definition points at block/stone), so it is
        // absent from the item index and GUI_ICON falls back to the isometric block render.
        String id = "minecraft:stone";
        assertThat(context.findItem(id).isPresent(), is(false));

        int[] icon = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_ICON)));
        int[] block = RenderDigest.firstFramePixels(blockRenderer.render(block(id)));
        assertThat(icon, is(block));
    }

    @Test
    @DisplayName("block-entity id (bed): GUI_ICON routes to the isometric block-entity render")
    void guiIconMatchesBlockIsoForBlockEntity() {
        // red_bed is filtered out of the item index (empty item model), so GUI_ICON falls back to
        // the block path, which renders it via the baked BlockEntityRenderer geometry.
        String id = "minecraft:red_bed";
        assertThat(context.findItem(id).isPresent(), is(false));

        int[] icon = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_ICON)));
        int[] block = RenderDigest.firstFramePixels(blockRenderer.render(block(id)));
        assertThat(icon, is(block));
    }

    @Test
    @DisplayName("id backing neither an item nor a block raises RenderException")
    void guiIconThrowsForUnknownId() {
        assertThrows(RenderException.class,
            () -> itemRenderer.render(item("minecraft:definitely_not_a_real_id", ItemOptions.Type.GUI_ICON)));
    }

    /**
     * Builds item options for {@code id} in the given render {@code type}, fixed to the small test
     * canvas so the slow render stays cheap.
     *
     * @param id the item id to render
     * @param type the render mode to dispatch through
     * @return the item options
     */
    private static ItemOptions item(String id, ItemOptions.Type type) {
        return ItemOptions.builder()
            .itemId(id)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build())
            .build();
    }

    /**
     * Builds the isometric block options the {@link ItemOptions.Type#GUI_ICON} block branch adapts
     * to: the default iso output frame at the shared test canvas size.
     *
     * @param id the block id to render
     * @return the block options
     */
    private static BlockOptions block(String id) {
        return BlockOptions.builder()
            .blockId(id)
            .output(OutputOptions.builder().canvasSize(SIZE).build())
            .build();
    }

}
