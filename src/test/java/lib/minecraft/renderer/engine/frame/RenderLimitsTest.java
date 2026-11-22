package lib.minecraft.renderer.engine.frame;

import lib.minecraft.renderer.exception.RenderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ceilings a render request is drawn under, each at its edge: what sits on a ceiling is answered,
 * what sits one past it is refused with a {@link RenderException} naming the value and the ceiling, and
 * a value far past it is refused the same way rather than wrapping into one that passes.
 */
@DisplayName("A request past a render's ceilings is refused")
class RenderLimitsTest {

    @Test
    @DisplayName("an edge on the ceiling is answered, and one past it is refused by name")
    void anEdgePastTheCeilingIsRefused() {
        assertThat(RenderLimits.edge("Canvas size", RenderLimits.MAX_EDGE), is(RenderLimits.MAX_EDGE));
        assertThat("an edge below one is not this ceiling's to refuse", RenderLimits.edge("Canvas size", -4), is(-4));

        RenderException refusal = assertThrows(RenderException.class, () -> RenderLimits.edge("Canvas size", RenderLimits.MAX_EDGE + 1L));
        assertThat(refusal.getMessage(), containsString("Canvas size '16385'"));
        assertThat(refusal.getMessage(), containsString("'16384'"));
        assertThrows(RenderException.class, () -> RenderLimits.edge("Canvas size", 1L << 40));
    }

    @Test
    @DisplayName("a frame count on the ceiling is answered, and one past it is refused by name")
    void aFrameCountPastTheCeilingIsRefused() {
        assertThat(RenderLimits.frames("Frame count", RenderLimits.MAX_FRAMES), is(RenderLimits.MAX_FRAMES));

        RenderException refusal = assertThrows(RenderException.class, () -> RenderLimits.frames("Frame count", RenderLimits.MAX_FRAMES + 1L));
        assertThat(refusal.getMessage(), containsString("Frame count '4097'"));
        assertThat(refusal.getMessage(), containsString("'4096'"));
    }

    @Test
    @DisplayName("a render's frames together are held to the pixel ceiling, and each edge to its own")
    void aRenderIsHeldToThePixelCeiling() {
        assertDoesNotThrow(() -> RenderLimits.render("Raster", RenderLimits.MAX_EDGE, RenderLimits.MAX_EDGE, 1));
        assertDoesNotThrow(() -> RenderLimits.render("Raster", 256, 256, RenderLimits.MAX_FRAMES));
        assertDoesNotThrow(() -> RenderLimits.render("Raster", 0, 0, 1));

        assertThat("one frame more than fills the ceiling",
            assertThrows(RenderException.class, () -> RenderLimits.render("Raster", RenderLimits.MAX_EDGE, RenderLimits.MAX_EDGE, 2)).getMessage(),
            containsString("'268435456' pixels"));
        assertThrows(RenderException.class, () -> RenderLimits.render("Raster", 257, 256, RenderLimits.MAX_FRAMES));
        assertThat("an edge past its ceiling, on a raster of few pixels",
            assertThrows(RenderException.class, () -> RenderLimits.render("Raster", RenderLimits.MAX_EDGE + 1L, 1, 1)).getMessage(),
            containsString("'16384' pixels an edge"));
        assertThrows(RenderException.class, () -> RenderLimits.render("Raster", Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThat("an edge a size wrapped into",
            assertThrows(RenderException.class, () -> RenderLimits.render("Raster", Integer.MIN_VALUE, 16, 1)).getMessage(),
            containsString("negative edge"));
    }

    @Test
    @DisplayName("a canvas long one way is held to the pixel ceiling and to no edge of its own")
    void aLongCanvasIsHeldToThePixelCeilingAlone() {
        assertDoesNotThrow(() -> RenderLimits.pixels("Text canvas", 400, 200_000, 1));
        assertDoesNotThrow(() -> RenderLimits.pixels("Text canvas", RenderLimits.MAX_PIXELS, 1, 1));

        assertThrows(RenderException.class, () -> RenderLimits.pixels("Text canvas", RenderLimits.MAX_PIXELS + 1, 1, 1));
        assertThrows(RenderException.class, () -> RenderLimits.pixels("Text canvas", 400, 200_000, 4));
        assertThrows(RenderException.class, () -> RenderLimits.pixels("Text canvas", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(RenderException.class, () -> RenderLimits.pixels("Text canvas", 16, -1, 1));
    }

    @Test
    @DisplayName("a sheet is held to what one buffer holds")
    void aSheetIsHeldToOneBuffer() {
        assertDoesNotThrow(() -> RenderLimits.sheet("Grid canvas", 46_340, 46_340));
        assertDoesNotThrow(() -> RenderLimits.sheet("Grid canvas", Integer.MAX_VALUE, 1));
        assertDoesNotThrow(() -> RenderLimits.sheet("Grid canvas", 0, 0));

        assertThrows(RenderException.class, () -> RenderLimits.sheet("Grid canvas", 46_341, 46_341));
        assertThrows(RenderException.class, () -> RenderLimits.sheet("Grid canvas", Integer.MAX_VALUE + 1L, 1));
        assertThrows(RenderException.class, () -> RenderLimits.sheet("Grid canvas", 16, -1));
        assertThat(assertThrows(RenderException.class, () -> RenderLimits.sheet("Grid canvas", 1L << 40, 1L << 40)).getMessage(),
            containsString("Grid canvas"));
    }

}
