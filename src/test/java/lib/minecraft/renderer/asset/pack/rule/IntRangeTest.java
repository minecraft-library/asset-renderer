package lib.minecraft.renderer.asset.pack.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link IntRange#parse} across the OptiFine range grammar, especially the
 * bracketed-negative forms ({@code (-25)}, {@code (-10)-10}), and of the plural {@link IntRanges} form
 * that a comma-separated list parses into.
 */
class IntRangeTest {

    @Test
    @DisplayName("a single value parses bare and negative")
    void singles() {
        assertBounds(IntRange.parse("5"), 5, 5);
        assertBounds(IntRange.parse("-5"), -5, -5);
    }

    @Test
    @DisplayName("a closed range and an open-max range each parse to their bounds")
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
    @DisplayName("a parenthesised negative single value parses to itself")
    void bracketedSingle() {
        assertBounds(IntRange.parse("(-25)"), -25, -25);
        assertThat(IntRange.parse("(-25)").contains(-25), is(true));
    }

    @Test
    @DisplayName("a parenthesised negative low bound parses and contains both endpoints")
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
    @DisplayName("both bounds parenthesised negative parse to a wholly negative span")
    void bothBracketed() {
        IntRange range = IntRange.parse("(-10)-(-5)");
        assertBounds(range, -10, -5);
        assertThat(range.contains(-7), is(true));
        assertThat(range.contains(-4), is(false));
    }

    @Test
    @DisplayName("the plural form parses a comma-separated mix of single values and ranges")
    void intRanges() {
        IntRanges ranges = IntRanges.parse("1,3,5-7");
        assertThat(ranges.contains(1), is(true));
        assertThat(ranges.contains(6), is(true));
        assertThat(ranges.contains(4), is(false));
        assertThat(IntRanges.parse("10-").contains(9999), is(true));
        assertThat(ranges.entries().size(), equalTo(3));
    }

    private static void assertBounds(IntRange range, int min, int max) {
        assertThat(range.min(), equalTo(min));
        assertThat(range.max(), equalTo(max));
    }

}
