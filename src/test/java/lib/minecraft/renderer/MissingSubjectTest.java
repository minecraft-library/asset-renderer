package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.call.request.BlockOptions;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.light.Shading;
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
import java.util.function.Consumer;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of the five render entry points an id neither index carries falls back at: each draws the
 * missing picture, pinned to the colour, and reports the id once, however often it is drawn.
 * <p>
 * The stub carries no block and no item, so every id is a subject miss and the picture is reached
 * without booting the asset pipeline. The sharpest pin is a colour count. A flat square carries the
 * checkerboard's own two colours - the sprite's magenta on a single face, lit as a slot lights it on an
 * icon. A cube seen at the isometric pose shows three faces at three shades and carries four, its top
 * unshaded; held, it is seen square-on and carries two, the black and its side face's shaded magenta.
 * That count is what separates a slot's picture from a posed one: a {@code GUI_ICON} answering four
 * has been routed through the isometric projection, and a held cube answering four has been posed where
 * the held path poses nothing.
 * <p>
 * Each entry point reports through the one place a subject miss is reported. The reporting set is
 * static and lives as long as the process, so every row names an id of its own.
 */
@DisplayName("An id neither index carries draws the missing picture at all five entry points, reported once")
class MissingSubjectTest {

    private static final int SIZE = 64;

    /** The magenta a slot's square carries: the missing model's facing side lit by the slot's {@code ITEMS_3D} light. */
    private static final int SLOT_MAGENTA = Shading.apply(MissingSprite.MAGENTA_ARGB, ColorMath.WHITE, Shading.ITEMS_3D_FACING);

    /** The diffuse factor a cube's side face carries, which is the only face an identity pose shows. */
    private static final float SIDE_SHADE = 0.6f;

    private final @NotNull RendererContext context = RendererContext.builder().build();

    @Test
    @DisplayName("an isometric block draws the posed cube")
    void isometricDrawsTheCube() {
        assertDrawsAndReportsOnce("minecraft:missing_subject_test_isometric",
            id -> new BlockRenderer(this.context).render(block(id, BlockOptions.Type.ISOMETRIC_3D)),
            colours -> {
                assertThat("three shaded faces plus the unshadeable black", colours.size(), is(4));
                assertThat("the black and the top face's unshaded magenta", colours,
                    hasItems(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB));
            });
    }

    @Test
    @DisplayName("a single block face draws the flat square")
    void blockFaceDrawsTheSquare() {
        assertDrawsAndReportsOnce("minecraft:missing_subject_test_face",
            id -> new BlockRenderer(this.context).render(block(id, BlockOptions.Type.BLOCK_FACE_2D)), exactly(MissingSprite.MAGENTA_ARGB));
    }

    @Test
    @DisplayName("a flat item icon draws the flat square")
    void gui2DDrawsTheSquare() {
        assertDrawsAndReportsOnce("minecraft:missing_subject_test_gui2d",
            id -> new ItemRenderer(this.context).render(item(id, ItemOptions.Type.GUI_2D)), exactly(SLOT_MAGENTA));
    }

    @Test
    @DisplayName("a held item draws the cube square-on")
    void held3DDrawsTheCube() {
        // The held camera is an identity pose - the held view's rotation lives in the model's display
        // transform, and a missing model has no display slot, so the transform is the identity one an
        // absent slot already resolves to. Exactly one face of the cube is therefore seen square-on,
        // and it is a side face carrying the cube's own side shade. Four colours here would mean the
        // guard built a pose the resolving path does not build.
        assertDrawsAndReportsOnce("minecraft:missing_subject_test_held",
            id -> new ItemRenderer(this.context).render(item(id, ItemOptions.Type.HELD_3D)), exactly(sideMagenta()));
    }

    @Test
    @DisplayName("the faithful icon draws the flat square")
    void guiIconDrawsTheSquare() {
        assertDrawsAndReportsOnce("minecraft:missing_subject_test_icon",
            id -> new ItemRenderer(this.context).render(item(id, ItemOptions.Type.GUI_ICON)), exactly(SLOT_MAGENTA));
    }

    /**
     * Renders one id twice, asserting the first render draws the missing picture and prints the id's
     * line, the second prints nothing, and each render's result names the id as its one stand-in.
     *
     * @param id the id neither index carries, unique to the row
     * @param render the render of an id through one entry point
     * @param picture the assertions the distinct opaque colours of the first render are held to
     */
    private static void assertDrawsAndReportsOnce(
        @NotNull String id, @NotNull Function<String, ? extends RenderResult> render, @NotNull Consumer<Set<Integer>> picture) {
        RenderResult[] drawn = new RenderResult[1];
        RenderResult[] again = new RenderResult[1];
        String first = errDuring(() -> drawn[0] = render.apply(id));
        String second = errDuring(() -> again[0] = render.apply(id));

        picture.accept(distinctOpaque(drawn[0].image()));
        assertThat("the first render's result names the id", drawn[0].substitutions(), contains(Substitution.subject(id)));
        assertThat(first, containsString("No block or item '" + id + "' - drawing the missing model"));
        assertThat("the second render reports nothing", second, not(containsString(id)));
        assertThat("the second render's result still names the id", again[0].substitutions(),
            contains(Substitution.subject(id)));
    }

    /**
     * Holds a picture to exactly the checkerboard's black and one magenta.
     *
     * @param magenta the magenta the picture carries beside the black - the sprite's own on a single
     *     face, lit as a slot lights it on an icon, the side face's shaded one on the held cube
     * @return the assertion on the picture's distinct opaque colours
     */
    private static @NotNull Consumer<Set<Integer>> exactly(int magenta) {
        return colours -> assertThat("the picture carries the checkerboard's black and its magenta, and no other colour",
            colours, is(Set.of(MissingSprite.BLACK_ARGB, magenta)));
    }

    /**
     * The colour the identity-posed cube's side face carries: magenta scaled by the side shade, whose
     * green is zero, so red and blue scale alike.
     *
     * @return the shaded magenta
     */
    private static int sideMagenta() {
        int shaded = Math.round(SIDE_SHADE * (MissingSprite.MAGENTA_ARGB >>> 16 & 0xFF));
        return 0xFF000000 | shaded << 16 | shaded;
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
     * Builds block options for an id at the test canvas.
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
     * Builds item options for an id at the test canvas.
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
