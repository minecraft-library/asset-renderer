package lib.minecraft.renderer;

import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.support.StubRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of the caller's opt-out at the five render entry points an id neither index carries falls
 * back at: left alone each draws the missing-model picture, turned off each refuses.
 * <p>
 * The stub carries no block and no item, so every id is a subject miss and the guard is reached
 * without booting the asset pipeline. What each entry point DRAWS on the substituting arm is pinned
 * elsewhere, against the real indexes; what is pinned here is that the flag reaches all five, and that
 * the refusal each raises names what it looked for. The noun is the part a shared helper could flatten
 * without failing anything else - the faithful icon looks in both indexes where the other four look in
 * one, and it is the only one that says so.
 */
@DisplayName("Turning the missing-subject substitution off refuses at all five entry points")
class MissingSubjectRefusalTest {

    private static final int SIZE = 16;
    private static final String UNKNOWN = "minecraft:definitely_not_a_real_id";

    private final @NotNull StubRendererContext context = StubRendererContext.builder().build();

    @Test
    @DisplayName("an isometric block draws the cube, or refuses as a block")
    void isometricRefuses() {
        assertDrawsOrRefuses(block(BlockOptions.Type.ISOMETRIC_3D), "No block registered for id '" + UNKNOWN + "'");
    }

    @Test
    @DisplayName("a single block face draws the square, or refuses as a block")
    void blockFaceRefuses() {
        assertDrawsOrRefuses(block(BlockOptions.Type.BLOCK_FACE_2D), "No block registered for id '" + UNKNOWN + "'");
    }

    @Test
    @DisplayName("a flat item icon draws the square, or refuses as an item")
    void gui2DRefuses() {
        assertDrawsOrRefuses(item(ItemOptions.Type.GUI_2D), "No item registered for id '" + UNKNOWN + "'");
    }

    @Test
    @DisplayName("a held item draws the cube, or refuses as an item")
    void held3DRefuses() {
        assertDrawsOrRefuses(item(ItemOptions.Type.HELD_3D), "No item registered for id '" + UNKNOWN + "'");
    }

    @Test
    @DisplayName("the faithful icon draws the square, or refuses as an item or block")
    void guiIconRefuses() {
        // The one entry point that looked in both indexes, and the only one whose refusal says so.
        assertDrawsOrRefuses(item(ItemOptions.Type.GUI_ICON), "No item or block registered for id '" + UNKNOWN + "'");
    }

    @Test
    @DisplayName("the flag defaults to on, so a caller who sets nothing still draws")
    void theDefaultIsOn() {
        // Every row above turns the flag on explicitly. This is the one that reads the default, which
        // is what a caller who never heard of the flag gets.
        assertThat(BlockOptions.defaults().isSubstituteMissing(), is(true));
        assertThat(ItemOptions.defaults().isSubstituteMissing(), is(true));
    }

    /**
     * Renders once with the substitution on and once with it off, asserting the first draws and the
     * second raises with the given message.
     *
     * @param options the caller's options, carrying the substitution flag
     * @param refusal the message the off arm must raise with
     */
    private void assertDrawsOrRefuses(@NotNull BlockOptions options, @NotNull String refusal) {
        assertThat("the substituting arm draws rather than refusing",
            assertDoesNotThrow(() -> new BlockRenderer(this.context).render(options)), notNullValue());

        RenderException raised = assertThrows(RenderException.class,
            () -> new BlockRenderer(this.context).render(options.mutate().substituteMissing(false).build()),
            "turning the substitution off must refuse rather than draw");
        assertThat(raised.getMessage(), is(refusal));
    }

    /**
     * Renders once with the substitution on and once with it off, asserting the first draws and the
     * second raises with the given message.
     *
     * @param options the caller's options, carrying the substitution flag
     * @param refusal the message the off arm must raise with
     */
    private void assertDrawsOrRefuses(@NotNull ItemOptions options, @NotNull String refusal) {
        assertThat("the substituting arm draws rather than refusing",
            assertDoesNotThrow(() -> new ItemRenderer(this.context).render(options)), notNullValue());

        RenderException raised = assertThrows(RenderException.class,
            () -> new ItemRenderer(this.context).render(options.mutate().substituteMissing(false).build()),
            "turning the substitution off must refuse rather than draw");
        assertThat(raised.getMessage(), is(refusal));
    }

    /**
     * Builds block options for the unknown id at the small test canvas, substitution on.
     *
     * @param type the render mode to dispatch through
     * @return the block options
     */
    private static @NotNull BlockOptions block(BlockOptions.@NotNull Type type) {
        return BlockOptions.builder()
            .blockId(UNKNOWN)
            .type(type)
            .output(OutputOptions.builder().canvasSize(SIZE).build())
            .build();
    }

    /**
     * Builds item options for the unknown id at the small test canvas, substitution on.
     *
     * @param type the render mode to dispatch through
     * @return the item options
     */
    private static @NotNull ItemOptions item(ItemOptions.@NotNull Type type) {
        return ItemOptions.builder()
            .itemId(UNKNOWN)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build())
            .build();
    }

}
