package lib.minecraft.renderer.pipeline.pack.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link IntRange#parse} across the OptiFine range grammar, especially the bracketed-negative
 * forms ({@code (-25)}, {@code (-10)-10}).
 */
class IntRangeTest {

    @Test
    @DisplayName("single values, bare and negative")
    void singles() {
        assertBounds(IntRange.parse("5"), 5, 5);
        assertBounds(IntRange.parse("-5"), -5, -5);
    }

    @Test
    @DisplayName("closed and open-max ranges")
    void ranges() {
        assertBounds(IntRange.parse("5-10"), 5, 10);
        assertBounds(IntRange.parse("5-"), 5, Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("a bare negative-low range is unambiguous and preserved")
    void bareNegativeLowRange() {
        assertBounds(IntRange.parse("-10-5"), -10, 5);
    }

    @Test
    @DisplayName("a parenthesised negative single value")
    void bracketedSingle() {
        assertBounds(IntRange.parse("(-25)"), -25, -25);
        assertThat(IntRange.parse("(-25)").contains(-25), is(true));
    }

    @Test
    @DisplayName("a parenthesised negative low bound")
    void bracketedLowBound() {
        IntRange range = IntRange.parse("(-10)-10");
        assertBounds(range, -10, 10);
        assertThat(range.contains(-10), is(true));
        assertThat(range.contains(0), is(true));
        assertThat(range.contains(10), is(true));
        assertThat(range.contains(-11), is(false));
        assertThat(range.contains(11), is(false));
    }

    @Test
    @DisplayName("both bounds parenthesised negative")
    void bothBracketed() {
        IntRange range = IntRange.parse("(-10)-(-5)");
        assertBounds(range, -10, -5);
        assertThat(range.contains(-7), is(true));
        assertThat(range.contains(-4), is(false));
    }

    private static void assertBounds(IntRange range, int min, int max) {
        assertThat(range.min(), equalTo(min));
        assertThat(range.max(), equalTo(max));
    }

}
