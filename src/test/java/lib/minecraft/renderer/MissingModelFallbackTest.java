package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.texture.MissingTexture;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.parity.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.tensor.EulerRotation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of the five render entry points an id neither index carries falls back at, and of the
 * presentation each one answers with.
 * <p>
 * The sharpest pin here is a colour count. A flat square carries the checkerboard's own two colours; a
 * cube seen at the isometric pose shows three faces at three shades and carries four. That is what
 * separates a slot's picture from a posed one, and a {@code GUI_ICON} answering four has been routed
 * through the isometric projection.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class
 * where nothing has extracted the client yet.
 */
@DisplayName("Missing-model fallback at the five render entry points")
@ExtendWith(ClientAssetsExtension.class)
class MissingModelFallbackTest {

    private static final int SIZE = 64;
    private static final String UNKNOWN = "minecraft:definitely_not_a_real_id";

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
        assertThat(colours, hasItems(MissingTexture.BLACK_ARGB, MissingTexture.MAGENTA_ARGB));
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
            is(Set.of(MissingTexture.BLACK_ARGB, MissingTexture.MAGENTA_ARGB)));
    }

    @Test
    @DisplayName("an unknown item's flat icon renders the flat square")
    void gui2DRendersTheFlatSquare() {
        assertThat(distinctOpaque(itemRenderer.render(item(UNKNOWN, ItemOptions.Type.GUI_2D, EulerRotation.NONE))),
            is(Set.of(MissingTexture.BLACK_ARGB, MissingTexture.MAGENTA_ARGB)));
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
        int shaded = Math.round(SIDE_SHADE * (MissingTexture.MAGENTA_ARGB >>> 16 & 0xFF));
        int shadedMagenta = 0xFF000000 | shaded << 16 | shaded;

        Set<Integer> colours = distinctOpaque(itemRenderer.render(item(UNKNOWN, ItemOptions.Type.HELD_3D, EulerRotation.NONE)));

        assertThat(colours, is(Set.of(MissingTexture.BLACK_ARGB, shadedMagenta)));
    }

    @Test
    @DisplayName("an unknown item's inventory icon renders the flat square, not the hexagon")
    void guiIconRendersTheFlatSquare() {
        Set<Integer> icon = distinctOpaque(itemRenderer.render(item(UNKNOWN, ItemOptions.Type.GUI_ICON, EulerRotation.NONE)));
        Set<Integer> isometric = distinctOpaque(blockRenderer.render(block(UNKNOWN, EulerRotation.NONE)));

        assertThat("the slot shows one face square-on", icon.size(), is(2));
        assertThat("the posed cube shows three", isometric.size(), is(4));
        assertThat(icon, is(Set.of(MissingTexture.BLACK_ARGB, MissingTexture.MAGENTA_ARGB)));
    }

    @Test
    @DisplayName("the isometric cube turns with the caller's rotation")
    void isometricHonoursTheCallersRotation() {
        // The one of the three posed types that reads a rotation at all. The missing subject is posed
        // exactly where a resolving one would have been - only the subject is substituted.
        int[] straight = RenderDigest.firstFramePixels(blockRenderer.render(block(UNKNOWN, EulerRotation.NONE)));
        int[] turned = RenderDigest.firstFramePixels(blockRenderer.render(block(UNKNOWN, new EulerRotation(15f, 40f, 0f))));

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

        int[] flatStraight = RenderDigest.firstFramePixels(blockRenderer.render(face(EulerRotation.NONE)));
        int[] flatTurned = RenderDigest.firstFramePixels(blockRenderer.render(face(new EulerRotation(15f, 40f, 0f))));
        assertThat("flat face", flatTurned, is(flatStraight));
    }

    /**
     * Collects the distinct fully-opaque colours a render's first frame carries.
     *
     * @param image the rendered image
     * @return every opaque colour present, without duplicates
     */
    private static Set<Integer> distinctOpaque(@NotNull ImageData image) {
        Set<Integer> colours = new HashSet<>();
        for (int pixel : RenderDigest.firstFramePixels(image))
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
        return itemRenderer.render(item(UNKNOWN, ItemOptions.Type.HELD_3D, rotation));
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
