package lib.minecraft.renderer.option;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * The supersample clamp {@link OutputOptions} owns.
 * <p>
 * A sub-1 factor asks for a zero-pixel raster, so the accessor answers {@code 1} for anything below it
 * and answers a factor of one or more unchanged. The rule sits on the field rather than in each
 * renderer that reads it, so a frame derived through {@code mutate()} carries it too.
 */
@DisplayName("OutputOptions supersample clamp")
class OutputOptionsTest {

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

    @Test
    @DisplayName("the clamp survives a mutate round-trip, so a derived frame carries it too")
    void supersampleClampedThroughMutate() {
        OutputOptions derived = OutputOptions.defaults().mutate()
            .supersample(OutputOptions.builder().supersample(0).build().getSupersample())
            .build();
        assertThat(derived.getSupersample(), equalTo(1));
    }

}
