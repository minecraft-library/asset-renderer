package lib.minecraft.renderer.asset.pack.rule;

import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.nbt.tag.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link NbtRule#matches} against nbt-factory: the list / wildcard / count path walker, the
 * typed predicates, the three value-normalization gaps (scalar wrap, SNBT booleans, cross-type numeric
 * equality), and negation.
 */
@DisplayName("NbtRule.matches path walk, predicates and normalization")
class NbtRuleMatchTest {

    private static final CompoundTag ITEM = NbtFactory.fromSnbt(
        "{display:{Name:\"Excalibur Sword\",Lore:[\"line one\",\"line two\"]},"
            + "ench:[{id:16,lvl:5},{id:34,lvl:3}],"
            + "Unbreakable:1b,Damage:10,ExtraAttributes:{id:\"ASPECT_OF_THE_END\"}}");

    private static boolean match(String path, NbtPredicate predicate) {
        return new NbtRule(NbtPath.parse(path), predicate, false).matches(ITEM);
    }

    @Test
    @DisplayName("exact string match over a compound path")
    void exactString() {
        assertThat(match("ExtraAttributes.id", NbtPredicate.exact("ASPECT_OF_THE_END")), is(true));
        assertThat(match("ExtraAttributes.id", NbtPredicate.exact(" HYPERION")), is(false));
    }

    @Test
    @DisplayName("cross-type numeric equality: literal 1 matches a ByteTag(1)")
    void crossTypeNumeric() {
        assertThat(match("Unbreakable", NbtPredicate.exact("1")), is(true));
        assertThat(match("Damage", NbtPredicate.exact("10")), is(true));
    }

    @Test
    @DisplayName("SNBT boolean literal true normalizes to ByteTag(1)")
    void booleanNormalization() {
        assertThat(match("Unbreakable", NbtPredicate.exact("true")), is(true));
        assertThat(match("Unbreakable", NbtPredicate.exact("false")), is(false));
    }

    @Test
    @DisplayName("glob pattern over a stringified value, case-insensitive")
    void globPattern() {
        assertThat(match("display.Name", NbtPredicate.glob("*Sword*", false)), is(true));
        assertThat(match("display.Name", NbtPredicate.glob("*sword*", false)), is(false));
        assertThat(match("display.Name", NbtPredicate.glob("*sword*", true)), is(true));
    }

    @Test
    @DisplayName("regex over the whole stringified value")
    void regexPattern() {
        assertThat(match("display.Name", NbtPredicate.regex("Excalibur.*", false)), is(true));
        assertThat(match("display.Name", NbtPredicate.regex("Sword", false)), is(false));
    }

    @Test
    @DisplayName("range predicate over an integral leaf")
    void rangePredicate() {
        assertThat(match("Damage", NbtPredicate.range(IntRanges.parse("5-15"))), is(true));
        assertThat(match("Damage", NbtPredicate.range(IntRanges.parse("1,2,3"))), is(false));
    }

    @Test
    @DisplayName("list index and list-element wildcard fan-out")
    void listTraversal() {
        assertThat(match("ench.0.lvl", NbtPredicate.exact("5")), is(true));
        assertThat(match("ench.*.id", NbtPredicate.exact("34")), is(true));
        assertThat(match("ench.*.id", NbtPredicate.exact("99")), is(false));
        assertThat(match("display.Lore.1", NbtPredicate.glob("*two*", false)), is(true));
    }

    @Test
    @DisplayName("count pseudo-key resolves a list's size")
    void countPseudoKey() {
        assertThat(match("ench.count", NbtPredicate.exact("2")), is(true));
        assertThat(match("ench.count", NbtPredicate.range(IntRanges.parse("2-"))), is(true));
    }

    @Test
    @DisplayName("exists: presence over the reached leaf set; mid-path scalar reads as absent")
    void existsPredicate() {
        assertThat(match("ExtraAttributes.id", NbtPredicate.exists(true)), is(true));
        assertThat(match("Missing", NbtPredicate.exists(false)), is(true));
        assertThat(match("Missing", NbtPredicate.exists(true)), is(false));
        // display.Name is a scalar, so descending further reaches no leaf.
        assertThat(match("display.Name.deeper", NbtPredicate.exists(false)), is(true));
    }

    @Test
    @DisplayName("raw: stringifies a branch to SNBT then applies the inner predicate")
    void rawPredicate() {
        assertThat(match("display", NbtPredicate.raw(NbtPredicate.glob("*Excalibur*", false))), is(true));
        assertThat(match("display", NbtPredicate.raw(NbtPredicate.glob("*Nonexistent*", false))), is(false));
    }

    @Test
    @DisplayName("negation inverts the predicate result")
    void negation() {
        assertThat(new NbtRule(NbtPath.parse("Damage"), NbtPredicate.range(IntRanges.parse("5-15")), true).matches(ITEM), is(false));
        assertThat(new NbtRule(NbtPath.parse("Damage"), NbtPredicate.range(IntRanges.parse("100-200")), true).matches(ITEM), is(true));
    }

    @Test
    @DisplayName("escaped dots address a key containing a literal dot")
    void escapedDotKey() {
        CompoundTag dotted = NbtFactory.fromSnbt("{\"a.b\":42}");
        assertThat(new NbtRule(NbtPath.parse("a\\.b"), NbtPredicate.exact("42"), false).matches(dotted), is(true));
    }

}
