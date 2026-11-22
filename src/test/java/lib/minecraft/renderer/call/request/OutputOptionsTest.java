package lib.minecraft.renderer.call.request;

import lib.minecraft.renderer.engine.frame.RenderLimits;
import lib.minecraft.renderer.exception.RenderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The supersample clamp and the size ceilings {@link OutputOptions} owns.
 * <p>
 * A sub-1 factor asks for a zero-pixel raster, so the accessor answers {@code 1} for anything below it
 * and answers a factor of one or more unchanged. The rule sits on the field rather than in each
 * renderer that reads it, so a frame derived through {@code mutate()} carries it too.
 * <p>
 * The ceilings sit there for the same reason: a canvas over {@link RenderLimits#MAX_EDGE}, or a factor
 * that takes the raster over it, is refused by the accessor a renderer reads it through. An oversize
 * frame still builds, and is refused by whatever reads it, a copy through {@code mutate()} included.
 */
@DisplayName("OutputOptions supersample clamp and size ceilings")
class OutputOptionsTest {

    @Test
    @DisplayName("a canvas on the edge ceiling is answered, and one past it is refused where it is read")
    void aCanvasPastTheCeilingIsRefusedWhereItIsRead() {
        assertThat(OutputOptions.builder().canvasSize(RenderLimits.MAX_EDGE).build().getCanvasSize(), equalTo(RenderLimits.MAX_EDGE));

        OutputOptions oversize = OutputOptions.builder().canvasSize(RenderLimits.MAX_EDGE + 1).build();
        assertThat(assertThrows(RenderException.class, oversize::getCanvasSize).getMessage(), containsString("Canvas size '16385'"));
        assertThat("and reading its supersample refuses the canvas the same way",
            assertThrows(RenderException.class, oversize::getSupersample).getMessage(), containsString("Canvas size '16385'"));
        assertThat("and so does deriving a frame from it, which reads it",
            assertThrows(RenderException.class, oversize::mutate).getMessage(), containsString("Canvas size '16385'"));
    }

    @Test
    @DisplayName("a supersample factor is held to the raster edge it takes the canvas to")
    void aSupersampleFactorIsHeldToTheRasterEdge() {
        assertThat(OutputOptions.builder().canvasSize(4096).supersample(4).build().getSupersample(), equalTo(4));
        assertThat(OutputOptions.builder().canvasSize(1).supersample(RenderLimits.MAX_EDGE).build().getSupersample(), equalTo(RenderLimits.MAX_EDGE));

        RenderException refusal = assertThrows(RenderException.class,
            () -> OutputOptions.builder().canvasSize(4096).supersample(5).build().getSupersample());
        assertThat(refusal.getMessage(), containsString("Supersample '5'"));
        assertThat(refusal.getMessage(), containsString("'20480'"));
        assertThrows(RenderException.class,
            () -> OutputOptions.builder().canvasSize(RenderLimits.MAX_EDGE).supersample(Integer.MAX_VALUE).build().getSupersample());
    }

    @Test
    @DisplayName("a sub-1 supersample factor answers 1")
    void supersampleBelowOneAnswersOne() {
        assertThat(OutputOptions.builder().supersample(0).build().getSupersample(), equalTo(1));
        assertThat(OutputOptions.builder().supersample(-4).build().getSupersample(), equalTo(1));
    }

    @Test
    @DisplayName("a supersample factor of 1 or more is answered unchanged")
    void supersampleOneOrMoreAnswersItself() {
        assertThat(OutputOptions.defaults().getSupersample(), equalTo(1));
        assertThat(OutputOptions.builder().supersample(1).build().getSupersample(), equalTo(1));
        assertThat(OutputOptions.builder().supersample(4).build().getSupersample(), equalTo(4));
    }

}
