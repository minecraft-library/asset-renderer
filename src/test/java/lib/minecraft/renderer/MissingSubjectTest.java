package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import lib.minecraft.renderer.call.request.BlockOptions;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.store.diff.RenderDigest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of the five render entry points an id neither index carries falls back at: each draws the
 * missing picture and reports the id once, however often it is drawn.
 * <p>
 * The stub carries no block and no item, so every id is a subject miss and the picture is reached
 * without booting the asset pipeline. What each entry point draws is pinned to the colour against the
 * real indexes in {@link MissingModelFallbackTest}; what is pinned here is that all five reach the
 * missing picture rather than raising, and that each reports through the one place a subject miss is
 * reported. The reporting set is static and lives as long as the process, so every row names an id of
 * its own.
 */
@DisplayName("An id neither index carries draws the missing picture at all five entry points, reported once")
class MissingSubjectTest {

    private static final int SIZE = 16;

    private final @NotNull RendererContext context = RendererContext.builder().build();

    @Test
    @DisplayName("an isometric block draws the posed cube")
    void isometricDrawsTheCube() {
        assertDrawsAndReportsOnce("minecraft:missing_subject_test_isometric",
            id -> new BlockRenderer(this.context).render(block(id, BlockOptions.Type.ISOMETRIC_3D)), false);
    }

    @Test
    @DisplayName("a single block face draws the flat square")
    void blockFaceDrawsTheSquare() {
        assertDrawsAndReportsOnce("minecraft:missing_subject_test_face",
            id -> new BlockRenderer(this.context).render(block(id, BlockOptions.Type.BLOCK_FACE_2D)), true);
    }

    @Test
    @DisplayName("a flat item icon draws the flat square")
    void gui2DDrawsTheSquare() {
        assertDrawsAndReportsOnce("minecraft:missing_subject_test_gui2d",
            id -> new ItemRenderer(this.context).render(item(id, ItemOptions.Type.GUI_2D)), true);
    }

    @Test
    @DisplayName("a held item draws the cube")
    void held3DDrawsTheCube() {
        assertDrawsAndReportsOnce("minecraft:missing_subject_test_held",
            id -> new ItemRenderer(this.context).render(item(id, ItemOptions.Type.HELD_3D)), false);
    }

    @Test
    @DisplayName("the faithful icon draws the flat square")
    void guiIconDrawsTheSquare() {
        assertDrawsAndReportsOnce("minecraft:missing_subject_test_icon",
            id -> new ItemRenderer(this.context).render(item(id, ItemOptions.Type.GUI_ICON)), true);
    }

    /**
     * Renders one id twice, asserting the first render reports it and draws the missing picture, and
     * the second reports nothing.
     *
     * @param id the id neither index carries, unique to the row
     * @param render the render of an id through one entry point
     * @param square whether the entry point draws the flat square, the checkerboard's own two colours,
     *     rather than a shaded cube
     */
    private static void assertDrawsAndReportsOnce(
        @NotNull String id, @NotNull Function<String, ImageData> render, boolean square) {
        ImageData[] drawn = new ImageData[1];
        String first = errDuring(() -> drawn[0] = render.apply(id));
        String second = errDuring(() -> render.apply(id));

        Set<Integer> colours = distinctOpaque(drawn[0]);
        if (square)
            assertThat("the flat square carries the checkerboard's two colours",
                colours, is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
        else {
            assertThat("the cube carries the checkerboard's black", colours, hasItem(MissingSprite.BLACK_ARGB));
            assertThat("and its magenta under a shade",
                colours.stream().anyMatch(MissingSubjectTest::isShadedMagenta), is(true));
        }

        assertThat(first, containsString("Missing model for '" + id + "' - drawing the missing-model cube"));
        assertThat("the second render reports nothing", second, not(containsString(id)));
    }

    /**
     * Whether a colour is the checkerboard's magenta under some shade: no green, and red equal to blue,
     * which a shade scales alike.
     *
     * @param argb the opaque colour
     * @return whether it is shaded magenta
     */
    private static boolean isShadedMagenta(int argb) {
        int red = argb >>> 16 & 0xFF;
        int green = argb >>> 8 & 0xFF;
        int blue = argb & 0xFF;
        return green == 0 && red > 0 && red == blue;
    }

    /**
     * Collects the distinct fully-opaque colours a render's first frame carries.
     *
     * @param image the rendered image
     * @return every opaque colour present
     */
    private static @NotNull Set<Integer> distinctOpaque(@NotNull ImageData image) {
        Set<Integer> colours = new HashSet<>();
        for (int pixel : RenderDigest.firstFramePixels(image))
            if ((pixel >>> 24) == 0xFF) colours.add(pixel);

        return colours;
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
     * Builds block options for an id at the small test canvas.
     *
     * @param id the block id
     * @param type the render mode to dispatch through
     * @return the block options
     */
    private static @NotNull BlockOptions block(@NotNull String id, BlockOptions.@NotNull Type type) {
        return BlockOptions.builder()
            .blockId(id)
            .type(type)
            .output(OutputOptions.builder().canvasSize(SIZE).build())
            .build();
    }

    /**
     * Builds item options for an id at the small test canvas.
     *
     * @param id the item id
     * @param type the render mode to dispatch through
     * @return the item options
     */
    private static @NotNull ItemOptions item(@NotNull String id, ItemOptions.@NotNull Type type) {
        return ItemOptions.builder()
            .itemId(id)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build())
            .build();
    }

}
