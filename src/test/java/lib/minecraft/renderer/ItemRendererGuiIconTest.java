package lib.minecraft.renderer;

import lib.minecraft.renderer.call.request.BlockOptions;
import lib.minecraft.renderer.call.request.DecorationOptions;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Equivalence coverage for the {@link ItemOptions.Type#GUI_ICON} faithful-inventory-icon dispatch.
 * Asserts that the mode routes to an existing renderer:
 * <ul>
 * <li>a flat-sprite item (in the item index) is byte-identical to its {@link ItemOptions.Type#GUI_2D}
 * render;</li>
 * <li>a plain block with no flat item icon (not in the item index) is byte-identical to the
 * isometric {@link BlockRenderer} render at the same output frame, where its item definition's tints
 * equal the block's own;</li>
 * <li>a tinted block icon takes its item definition's tints rather than the options' biome, and a
 * caller's custom colour only at tintindex 0 where the definition declares none;</li>
 * <li>an item model whose geometry comes from a block parent keeps the block icon;</li>
 * <li>a block-entity id (bed) likewise routes to the isometric block path;</li>
 * <li>an id backing neither an item nor a block draws the missing-model square.</li>
 * </ul>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class
 * where nothing has extracted the client yet.
 */
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

        int[] icon = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_ICON)).image());
        int[] flat = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_2D)).image());
        assertThat(icon, is(flat));
    }

    @Test
    @DisplayName("plain block with no flat item icon: GUI_ICON == isometric BlockRenderer")
    void guiIconMatchesBlockIsoForPlainBlock() {
        // stone has no models/item/stone.json (its item definition points at block/stone), so it is
        // absent from the item index and GUI_ICON falls back to the isometric block render.
        String id = "minecraft:stone";
        assertThat(context.findItem(id).isPresent(), is(false));

        int[] icon = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_ICON)).image());
        int[] block = RenderDigest.firstFramePixels(blockRenderer.render(block(id)).image());
        assertThat(icon, is(block));
    }

    /**
     * Pins that a slot icon takes its item definition's tints. Oak leaves' definition names the
     * constant {@code 0xFF48B518}, which is {@link Biome#INVENTORY_DEFAULT}'s foliage colour, and every
     * face of {@code block/leaves} is tintindex 0, so the icon is the block render at that biome - and
     * not the render at the default plains biome, whose foliage sample is {@code 0xFF77AB2F}.
     */
    @Test
    @DisplayName("a tinted block icon takes its item definition's tints, not its options' biome")
    void aTintedBlockIconTakesItsDefinition() {
        String id = "minecraft:oak_leaves";
        assertThat(context.findItem(id).isPresent(), is(false));

        int[] icon = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_ICON)).image());
        assertThat("the icon is the definition's colour",
            icon, is(RenderDigest.firstFramePixels(blockRenderer.render(block(id, Biome.INVENTORY_DEFAULT)).image())));
        assertThat("the icon is not the plains render",
            icon, is(not(RenderDigest.firstFramePixels(blockRenderer.render(block(id)).image()))));
        assertThat("mangrove leaves' slot tint is their definition's constant",
            ItemRenderer.definitionTints(context, item("minecraft:mangrove_leaves", ItemOptions.Type.GUI_ICON),
                ItemOptions.Type.GUI_ICON), is(new int[]{ 0xFF92C648 }));
    }

    /**
     * Pins what a caller's custom colour paints on a block-backed slot icon: the tintindex-0 faces of
     * a block whose item definition declares no tint of its own, as it fills {@code layer0} of a flat
     * sprite and the same faces of the held block. Cherry leaves have tintindex-0 faces and no
     * definition tint, so the colour reaches them; stone has no tintindex face, so it reaches nothing.
     */
    @Test
    @DisplayName("a caller's colour fills slot 0 of a block icon whose definition declares no tint")
    void aCallerColourFillsAnUntintedBlockIconsSlotZero() {
        String cherry = "minecraft:cherry_leaves";
        assertThat(context.findItem(cherry).isPresent(), is(false));

        assertThat("cherry leaves take the caller's colour",
            RenderDigest.firstFramePixels(itemRenderer.render(tinted(cherry, 0xFF3060C0)).image()),
            is(not(RenderDigest.firstFramePixels(itemRenderer.render(item(cherry, ItemOptions.Type.GUI_ICON)).image()))));
        assertThat("stone has no colourable face",
            RenderDigest.firstFramePixels(itemRenderer.render(tinted("minecraft:stone", 0xFF3060C0)).image()),
            is(RenderDigest.firstFramePixels(itemRenderer.render(item("minecraft:stone", ItemOptions.Type.GUI_ICON)).image())));
    }

    /**
     * Pins the routing of an item-index id whose model declares elements and which a block backs: the
     * dripleafs' item models take their geometry from a block parent, and their inventory icon is the
     * block's own, where {@link ItemOptions.Type#GUI_2D} draws the item model's elements. Both keep the
     * block render.
     */
    @Test
    @DisplayName("an element-model item a block backs keeps the block icon")
    void anElementModelItemABlockBacksKeepsTheBlockIcon() {
        for (String id : new String[]{ "minecraft:big_dripleaf", "minecraft:small_dripleaf" }) {
            assertThat(id + " is an item-index id", context.findItem(id).isPresent(), is(true));

            int[] icon = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_ICON)).image());
            int[] block = RenderDigest.firstFramePixels(blockRenderer.render(block(id)).image());
            assertThat(id, icon, is(block));
        }
    }

    @Test
    @DisplayName("block-entity id (bed): GUI_ICON routes to the isometric block-entity render")
    void guiIconMatchesBlockIsoForBlockEntity() {
        // red_bed is filtered out of the item index (empty item model), so GUI_ICON falls back to
        // the block path, which renders it via the baked BlockEntityRenderer geometry.
        String id = "minecraft:red_bed";
        assertThat(context.findItem(id).isPresent(), is(false));

        int[] icon = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_ICON)).image());
        int[] block = RenderDigest.firstFramePixels(blockRenderer.render(block(id)).image());
        assertThat(icon, is(block));
    }

    @Test
    @DisplayName("id backing neither an item nor a block draws the missing-model square")
    void guiIconDrawsMissingModelForUnknownId() {
        String id = "minecraft:definitely_not_a_real_id";
        assertThat("absent from the item index", context.findItem(id).isPresent(), is(false));
        assertThat("absent from the block index", context.findBlock(id).isPresent(), is(false));

        int[] pixels = RenderDigest.firstFramePixels(itemRenderer.render(item(id, ItemOptions.Type.GUI_ICON)).image());

        // Exactly two opaque colours, which is what separates the slot's flat square from a posed
        // cube: three visible faces at three shades would answer four. A GUI_ICON that came back with
        // four has been routed through the isometric projection, a picture no slot shows for an id
        // nothing resolved for.
        assertThat(distinctOpaque(pixels), is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
    }

    /**
     * Collects the distinct fully-opaque colours a rendered frame carries.
     *
     * @param pixels the frame's ARGB texels
     * @return every opaque colour present, without duplicates
     */
    private static Set<Integer> distinctOpaque(int[] pixels) {
        Set<Integer> colours = new HashSet<>();
        for (int pixel : pixels)
            if ((pixel >>> 24) == 0xFF) colours.add(pixel);

        return colours;
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
     * Builds {@link ItemOptions.Type#GUI_ICON} options for {@code id} given a caller's custom colour.
     *
     * @param id the item id to render
     * @param tintColor the caller's colour
     * @return the item options
     */
    private static ItemOptions tinted(String id, int tintColor) {
        return item(id, ItemOptions.Type.GUI_ICON).mutate()
            .decoration(DecorationOptions.builder().tintColor(tintColor).build())
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

    /**
     * Builds the isometric block options at an explicit biome.
     *
     * @param id the block id to render
     * @param biome the biome the block's tint resolves at
     * @return the block options
     */
    private static BlockOptions block(String id, Biome biome) {
        return block(id).mutate().biome(biome).build();
    }

}
