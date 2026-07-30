package lib.minecraft.renderer.option.spec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Pins the supersample clamp {@link OutputOptions} owns.
 * <p>
 * A sub-1 factor asks for a zero-pixel raster, so the accessor answers {@code 1} for anything below it.
 * The clamp used to be spelled {@code Math.max(1, ...)} by each of the six subject renderers reading it,
 * which put one rule in six places and left a seventh reader - the one that copies the factor into a
 * derived frame - without it. These cases hold the rule where the field is.
 */
class OutputOptionsTest {

    @Test
    @DisplayName("a sub-1 supersample factor answers 1")
    void supersample_belowOne_answersOne() {
        assertThat(OutputOptions.builder().supersample(0).build().getSupersample(), equalTo(1));
        assertThat(OutputOptions.builder().supersample(-4).build().getSupersample(), equalTo(1));
    }

    @Test
    @DisplayName("a supersample factor of 1 or more is answered unchanged")
    void supersample_oneOrMore_answersItself() {
        assertThat(OutputOptions.defaults().getSupersample(), equalTo(1));
        assertThat(OutputOptions.builder().supersample(1).build().getSupersample(), equalTo(1));
        assertThat(OutputOptions.builder().supersample(4).build().getSupersample(), equalTo(4));
    }

    @Test
    @DisplayName("the clamp survives a mutate round-trip, so a derived frame carries it too")
    void supersample_clampedThroughMutate() {
        OutputOptions derived = OutputOptions.defaults().mutate()
            .supersample(OutputOptions.builder().supersample(0).build().getSupersample())
            .build();
        assertThat(derived.getSupersample(), equalTo(1));
    }

}
