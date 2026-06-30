package lib.minecraft.renderer.asset.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("IntRange parsing + containment")
class IntRangeTest {

    @Test
    @DisplayName("of() builds a single-value inclusive range")
    void single() {
        IntRange r = IntRange.of(5);
        assertThat(r, equalTo(new IntRange(5, 5)));
        assertThat(r.contains(5), is(true));
        assertThat(r.contains(4), is(false));
        assertThat(r.contains(6), is(false));
    }

    @Test
    @DisplayName("ANY contains every integer")
    void any() {
        assertThat(IntRange.ANY.contains(Integer.MIN_VALUE), is(true));
        assertThat(IntRange.ANY.contains(0), is(true));
        assertThat(IntRange.ANY.contains(Integer.MAX_VALUE), is(true));
    }

    @Test
    @DisplayName("parse handles single, closed, and open-ended forms")
    void parseForms() {
        assertThat(IntRange.parse("5"), equalTo(new IntRange(5, 5)));
        assertThat(IntRange.parse("5-10"), equalTo(new IntRange(5, 10)));
        assertThat(IntRange.parse("5-"), equalTo(new IntRange(5, Integer.MAX_VALUE)));
        assertThat(IntRange.parse(" 5-10 "), equalTo(new IntRange(5, 10)));
    }

    @Test
    @DisplayName("a leading dash is a negative sign, not an open lower bound")
    void leadingDashIsNegative() {
        // "-10" parses as the single value -10 (the leading dash is consumed as a sign);
        // a negative closed range is written with a second dash as the separator.
        assertThat(IntRange.parse("-10"), equalTo(new IntRange(-10, -10)));
        assertThat(IntRange.parse("-5-10"), equalTo(new IntRange(-5, 10)));
    }

    @Test
    @DisplayName("closed-range containment is inclusive on both ends")
    void containment() {
        IntRange r = IntRange.parse("5-10");
        assertThat(r.contains(5), is(true));
        assertThat(r.contains(10), is(true));
        assertThat(r.contains(7), is(true));
        assertThat(r.contains(4), is(false));
        assertThat(r.contains(11), is(false));
    }

    @Test
    @DisplayName("parse rejects non-numeric input")
    void parseRejectsGarbage() {
        assertThrows(NumberFormatException.class, () -> IntRange.parse("abc"));
    }

}
