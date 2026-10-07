package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.request.DecorationOptions;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of a block-backed id held: an id the item index does not carry, whose item definition
 * names its block's own model, draws that model from its elements at the model's
 * {@code thirdperson_righthand} pose, each tinted face coloured by the item definition's tint its
 * tintindex names. A definition rooted at a dispatch counts where its neutral branch names that
 * model - vanilla's beehive selects on a block state and falls back to one, and a pack may root a
 * block item at a component test whose {@code on_false} is one. A block entity, whose item definition
 * names a special model, keeps the missing cube. The big and small dripleaf are item-index ids whose
 * item models take their geometry from a block parent, and draw that geometry posed by their own
 * slots.
 * <p>
 * The draws run with the missing-subject substitution off, so the missing-model route and any missing
 * face texture both raise; completing is what says the block branch drew.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where
 * nothing has extracted the client yet.
 */
@DisplayName("A block-backed id held")
@ExtendWith(ClientAssetsExtension.class)
class HeldBlockItemTest {

    private static final int SIZE = 64;

    /** A plain block whose item definition names its own block model. */
    private static final @NotNull String STONE = "minecraft:stone";

    /** The block item a fixture pack shadows with a component-test definition. */
    private static final @NotNull String ANVIL = "minecraft:anvil";

    /** The two item models whose geometry comes from a block parent. */
    private static final @NotNull List<String> DRIPLEAFS = List.of("minecraft:big_dripleaf", "minecraft:small_dripleaf");

    private static RendererContext context;
    private static ItemRenderer itemRenderer;

    @BeforeAll
    static void bootstrapPipeline() {
        context = ClientAssetsExtension.context();
        itemRenderer = new ItemRenderer(context);

        assertThat("stone is not an item-index id", context.findItem(STONE).isPresent(), is(false));
        assertThat("stone draws its own block model", block(STONE).modelIcon(), is(true));
        for (String id : List.of("minecraft:big_dripleaf", "minecraft:chest"))
            assertThat(id + "'s item definition names a model other than its block's", block(id).modelIcon(), is(false));
    }

    @Test
    @DisplayName("stone draws its block model held")
    void stoneDrawsItsBlockModelHeld() {
        ImageData held = assertDoesNotThrow(() -> itemRenderer.render(held(STONE)));
        assertThat("the held stone draws", opaque(held), greaterThan(0));
    }

    @Test
    @DisplayName("oak stairs draw their block model held")
    void stairsDrawHeld() {
        ImageData held = assertDoesNotThrow(() -> itemRenderer.render(held("minecraft:oak_stairs")));
        assertThat("the held stairs draw", opaque(held), greaterThan(0));
    }

    @Test
    @DisplayName("oak leaves take their item definition's tint on their tinted faces")
    void oakLeavesTakeTheirTint() {
        // oak_leaves.png carries no green texel of its own and every face of block/leaves is
        // tintindex 0, so a green pixel is the tint's alone.
        boolean green = false;
        for (int pixel : RenderDigest.firstFramePixels(itemRenderer.render(held("minecraft:oak_leaves")))) {
            int r = pixel >>> 16 & 0xFF;
            int g = pixel >>> 8 & 0xFF;
            int b = pixel & 0xFF;
            if ((pixel >>> 24) != 0 && g > r + 16 && g > b + 16) green = true;
        }
        assertThat("the held leaves are green", green, is(true));
    }

    @Test
    @DisplayName("a held block-backed id calculates its item definition's tints, not its block's")
    void heldTintsAreTheDefinitions() {
        assertThat("mangrove leaves take their definition's constant, not the foliage colour",
            heldTints("minecraft:mangrove_leaves"), is(new int[]{ 0xFF92C648 }));
        assertThat("oak leaves' definition constant is the foliage colour",
            heldTints("minecraft:oak_leaves"), is(new int[]{ 0xFF48B518 }));
        assertThat("grass_block samples the grass colormap at its definition's climate point",
            heldTints("minecraft:grass_block"), is(new int[]{ 0xFF7CBD6B }));
        assertThat("stone's definition declares no tint",
            heldTints(STONE).length, is(0));
    }

    /**
     * Pins what a caller's custom colour paints on a held block: the tintindex-0 faces of a block
     * whose item definition declares no tint of its own, as it fills {@code layer0} of a flat sprite.
     * Cherry leaves have tintindex-0 faces and no definition tint, so the colour reaches them; stone
     * has no tintindex face, so the colour reaches nothing.
     */
    @Test
    @DisplayName("a caller's colour fills slot 0 of a held block whose definition declares no tint")
    void aCallerTintFillsAnUntintedDefinitionsSlotZero() {
        String cherry = "minecraft:cherry_leaves";
        assertThat("cherry leaves take the caller's colour",
            RenderDigest.firstFramePixels(itemRenderer.render(held(cherry, 0xFF3060C0))),
            is(not(RenderDigest.firstFramePixels(itemRenderer.render(held(cherry))))));
        assertThat("stone has no colourable face",
            RenderDigest.firstFramePixels(itemRenderer.render(held(STONE, 0xFF3060C0))),
            is(RenderDigest.firstFramePixels(itemRenderer.render(held(STONE)))));
    }

    @Test
    @DisplayName("both dripleafs draw held - their item models resolve their block parents' elements")
    void bothDripleafsDrawHeld() {
        for (String id : DRIPLEAFS) {
            assertThat(id + " is an item-index id", context.findItem(id).isPresent(), is(true));
            ImageData held = assertDoesNotThrow(() -> itemRenderer.render(held(id)), id);
            assertThat(id + " draws", opaque(held), greaterThan(0));
        }
    }

    @Test
    @DisplayName("both dripleafs hold their own third-person slot")
    void bothDripleafsHoldTheirOwnThirdPersonSlot() {
        assertSameDisplay(ItemRenderer.Held3D.displayMatrix(new ModelTransform(new EulerRotation(0f, 0f, 0f),
                new float[]{ 0f, 1f, 0f }, new float[]{ 0.55f, 0.55f, 0.55f })),
            ItemRenderer.Held3D.heldDisplay(item("minecraft:big_dripleaf").model()), "big dripleaf");
        assertSameDisplay(ItemRenderer.Held3D.displayMatrix(new ModelTransform(new EulerRotation(0f, 0f, 0f),
                new float[]{ 0f, 4f, 1f }, new float[]{ 0.55f, 0.55f, 0.55f })),
            ItemRenderer.Held3D.heldDisplay(item("minecraft:small_dripleaf").model()), "small dripleaf");
    }

    @Test
    @DisplayName("both dripleafs carry the eight display slots vanilla's parent walk finds")
    void bothDripleafsCarryTheEightSlotsVanillaFinds() {
        Set<String> slots = Set.of("gui", "fixed", "ground", "on_shelf", "thirdperson_righthand",
            "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand");
        for (String id : DRIPLEAFS)
            assertThat(id + "'s display slots", Set.copyOf(item(id).model().getDisplay().keySet()), is(slots));
    }

    @Test
    @DisplayName("a beehive draws the block model its select falls back to held")
    void beehiveDrawsItsFallbackBlockModelHeld() {
        assertThat("the beehive's neutral branch is its icon", block("minecraft:beehive").modelIcon(), is(true));
        assertThat("the icon poses through that model's display.gui", block("minecraft:beehive").iconGui().isPresent(), is(true));
        ImageData held = assertDoesNotThrow(() -> itemRenderer.render(held("minecraft:beehive")));
        assertThat("the held beehive draws", opaque(held), greaterThan(0));
    }

    /**
     * Pins a pack that roots a block item's definition at a component test, the shape Hypixel+ gives
     * the vanilla items it shadows. A stack with no custom data fails the test, so the neutral walk
     * lands on the anvil's own block model - the block keeps its icon, and the held render draws what
     * the unshadowed anvil draws rather than the missing cube.
     */
    @Test
    @DisplayName("an anvil a pack roots at a custom_data test keeps its block icon and draws it held")
    void aComponentRootedAnvilKeepsItsBlockIcon(@TempDir Path work) throws IOException {
        Path pack = work.resolve("anvilpack");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"anvil fixture\"}}");
        write(pack.resolve("assets/minecraft/items/anvil.json"),
            "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\","
                + "\"predicate\":\"minecraft:custom_data\",\"value\":{\"id\":\"FANCY_ANVIL\"},"
                + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/chipped_anvil\"},"
                + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/anvil\"}}}");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        RendererContext shadowed = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanillaRoot()));

        assertThat("the pack's definition is the one loaded",
            shadowed.findItemTree(ANVIL).map(ItemModelTree::root).orElseThrow(), instanceOf(ItemModelNode.Condition.class));
        assertThat("the anvil keeps its block icon",
            shadowed.findBlock(ANVIL).map(Block::modelIcon).orElseThrow(), is(true));
        ImageData held = assertDoesNotThrow(() -> new ItemRenderer(shadowed).render(held(ANVIL)));
        assertThat("the held anvil draws the unshadowed anvil's pixels",
            RenderDigest.firstFramePixels(held), is(RenderDigest.firstFramePixels(itemRenderer.render(held(ANVIL)))));
    }

    @Test
    @DisplayName("a chest keeps the missing model - a block entity is drawn by a special renderer")
    void blockEntityStaysOnTheMissingModel() {
        RenderException refused = assertThrows(RenderException.class,
            () -> itemRenderer.render(held("minecraft:chest")));
        assertEquals("No item registered for id 'minecraft:chest'", refused.getMessage());
    }

    @Test
    @DisplayName("stone holds block/block's third-person slot")
    void stoneHoldsTheBlockBlockSlot() {
        assertSameDisplay(ItemRenderer.Held3D.displayMatrix(new ModelTransform(new EulerRotation(75f, 45f, 0f),
                new float[]{ 0f, 2.5f, 0f }, new float[]{ 0.375f, 0.375f, 0.375f })),
            ItemRenderer.Held3D.heldDisplay(block(STONE).model()), "stone");
    }

    @Test
    @DisplayName("oak stairs hold block/block's third-person slot, which their own display leaves out")
    void stairsHoldTheBlockBlockSlot() {
        assertSameDisplay(ItemRenderer.Held3D.displayMatrix(new ModelTransform(new EulerRotation(75f, 45f, 0f),
                new float[]{ 0f, 2.5f, 0f }, new float[]{ 0.375f, 0.375f, 0.375f })),
            ItemRenderer.Held3D.heldDisplay(block("minecraft:oak_stairs").model()), "oak stairs");
    }

    @Test
    @DisplayName("the end rod holds its own third-person slot")
    void endRodHoldsItsOwnSlot() {
        assertSameDisplay(ItemRenderer.Held3D.displayMatrix(new ModelTransform(new EulerRotation(0f, 0f, 0f),
                new float[]{ 0f, 0f, 0f }, new float[]{ 0.375f, 0.375f, 0.375f })),
            ItemRenderer.Held3D.heldDisplay(block("minecraft:end_rod").model()), "end rod");
    }

    /**
     * Holds two display matrices to one another at the origin and a unit corner.
     *
     * @param expected the matrix the slot must compose to
     * @param actual the matrix the held path resolves
     * @param subject how a failure names the subject
     */
    private static void assertSameDisplay(@NotNull Matrix4f expected, @NotNull Matrix4f actual, @NotNull String subject) {
        for (Vector3f point : List.of(Vector3f.ZERO, new Vector3f(0.5f, 0.5f, 0.5f)))
            assertEquals(point.transform(expected), point.transform(actual), subject + " at " + point);
    }

    /**
     * The block one id resolves to, which the fixture expects to be indexed.
     *
     * @param id the block id
     * @return the indexed block
     */
    private static @NotNull Block block(@NotNull String id) {
        return context.findBlock(id).orElseThrow(() -> new AssertionError(id + " is expected in the block index"));
    }

    /**
     * The item one id resolves to, which the fixture expects to be indexed.
     *
     * @param id the item id
     * @return the indexed item
     */
    private static @NotNull Item item(@NotNull String id) {
        return context.findItem(id).orElseThrow(() -> new AssertionError(id + " is expected in the item index"));
    }

    /**
     * Calculates the tints a held render of one id takes.
     *
     * @param id the id to render
     * @return the calculated tints, indexed by tintindex
     */
    private static int @NotNull [] heldTints(@NotNull String id) {
        return ItemRenderer.definitionTints(context, held(id), ItemOptions.Type.HELD_3D);
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

    /**
     * Builds held options for one id at the shared test canvas, the substitution off.
     *
     * @param id the id to render
     * @return the item options
     */
    private static @NotNull ItemOptions held(@NotNull String id) {
        return ItemOptions.builder()
            .itemId(id)
            .type(ItemOptions.Type.HELD_3D)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build())
            .substituteMissing(false)
            .build();
    }

    /**
     * Builds held options for one id given a caller's custom colour.
     *
     * @param id the id to render
     * @param tintColor the caller's colour
     * @return the item options
     */
    private static @NotNull ItemOptions held(@NotNull String id, int tintColor) {
        return held(id).mutate()
            .decoration(DecorationOptions.builder().tintColor(tintColor).build())
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

}
