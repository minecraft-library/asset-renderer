package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of a block-backed id held: an id the item index does not carry, whose item definition
 * names its block's own model, draws that model from its elements at the model's
 * {@code thirdperson_righthand} pose, with the block's no-world tint on its tinted faces. A block
 * whose item definition names another model - a block entity, the dripleaf pair - keeps the missing
 * cube.
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
    @DisplayName("oak leaves take the block's no-world tint on their tinted faces")
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
    @DisplayName("the big dripleaf keeps the missing model - its item model names a block model as its parent")
    void dripleafStaysOnTheMissingModel() {
        RenderException refused = assertThrows(RenderException.class,
            () -> itemRenderer.render(held("minecraft:big_dripleaf")));
        assertEquals("No item registered for id 'minecraft:big_dripleaf'", refused.getMessage());
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

}
