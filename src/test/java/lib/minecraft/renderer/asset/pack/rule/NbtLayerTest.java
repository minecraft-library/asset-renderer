package lib.minecraft.renderer.asset.pack.rule;

import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.nbt.tag.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the NBT rule layer against nbt-factory: the list/wildcard/count path walker, the typed
 * predicates, the three value-normalization gaps (scalar wrap, SNBT booleans, cross-type numeric
 * equality), and negation.
 */
@DisplayName("NBT rule layer (path walk + predicates + normalization)")
class NbtLayerTest {

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
        assertTrue(match("ExtraAttributes.id", NbtPredicate.exact("ASPECT_OF_THE_END")));
        assertFalse(match("ExtraAttributes.id", NbtPredicate.exact(" HYPERION")));
    }

    @Test
    @DisplayName("cross-type numeric equality: literal 1 matches a ByteTag(1)")
    void crossTypeNumeric() {
        assertTrue(match("Unbreakable", NbtPredicate.exact("1")));
        assertTrue(match("Damage", NbtPredicate.exact("10")));
    }

    @Test
    @DisplayName("SNBT boolean literal true normalizes to ByteTag(1)")
    void booleanNormalization() {
        assertTrue(match("Unbreakable", NbtPredicate.exact("true")));
        assertFalse(match("Unbreakable", NbtPredicate.exact("false")));
    }

    @Test
    @DisplayName("glob pattern over a stringified value, case-insensitive")
    void globPattern() {
        assertTrue(match("display.Name", NbtPredicate.glob("*Sword*", false)));
        assertFalse(match("display.Name", NbtPredicate.glob("*sword*", false)));
        assertTrue(match("display.Name", NbtPredicate.glob("*sword*", true)));
    }

    @Test
    @DisplayName("regex over the whole stringified value")
    void regexPattern() {
        assertTrue(match("display.Name", NbtPredicate.regex("Excalibur.*", false)));
        assertFalse(match("display.Name", NbtPredicate.regex("Sword", false)));
    }

    @Test
    @DisplayName("range predicate over an integral leaf")
    void rangePredicate() {
        assertTrue(match("Damage", NbtPredicate.range(IntRanges.parse("5-15"))));
        assertFalse(match("Damage", NbtPredicate.range(IntRanges.parse("1,2,3"))));
    }

    @Test
    @DisplayName("list index and list-element wildcard fan-out")
    void listTraversal() {
        assertTrue(match("ench.0.lvl", NbtPredicate.exact("5")));
        assertTrue(match("ench.*.id", NbtPredicate.exact("34")));
        assertFalse(match("ench.*.id", NbtPredicate.exact("99")));
        assertTrue(match("display.Lore.1", NbtPredicate.glob("*two*", false)));
    }

    @Test
    @DisplayName("count pseudo-key resolves a list's size")
    void countPseudoKey() {
        assertTrue(match("ench.count", NbtPredicate.exact("2")));
        assertTrue(match("ench.count", NbtPredicate.range(IntRanges.parse("2-"))));
    }

    @Test
    @DisplayName("exists: presence over the reached leaf set; mid-path scalar reads as absent")
    void existsPredicate() {
        assertTrue(match("ExtraAttributes.id", NbtPredicate.exists(true)));
        assertTrue(match("Missing", NbtPredicate.exists(false)));
        assertFalse(match("Missing", NbtPredicate.exists(true)));
        // display.Name is a scalar, so descending further reaches no leaf.
        assertTrue(match("display.Name.deeper", NbtPredicate.exists(false)));
    }

    @Test
    @DisplayName("raw: stringifies a branch to SNBT then applies the inner predicate")
    void rawPredicate() {
        assertTrue(match("display", NbtPredicate.raw(NbtPredicate.glob("*Excalibur*", false))));
        assertFalse(match("display", NbtPredicate.raw(NbtPredicate.glob("*Nonexistent*", false))));
    }

    @Test
    @DisplayName("negation inverts the predicate result")
    void negation() {
        assertFalse(new NbtRule(NbtPath.parse("Damage"), NbtPredicate.range(IntRanges.parse("5-15")), true).matches(ITEM));
        assertTrue(new NbtRule(NbtPath.parse("Damage"), NbtPredicate.range(IntRanges.parse("100-200")), true).matches(ITEM));
    }

    @Test
    @DisplayName("escaped dots address a key containing a literal dot")
    void escapedDotKey() {
        CompoundTag dotted = NbtFactory.fromSnbt("{\"a.b\":42}");
        assertTrue(new NbtRule(NbtPath.parse("a\\.b"), NbtPredicate.exact("42"), false).matches(dotted));
    }

    @Test
    @DisplayName("IntRanges parses lists and open-ended ranges")
    void intRanges() {
        IntRanges ranges = IntRanges.parse("1,3,5-7");
        assertTrue(ranges.contains(1));
        assertTrue(ranges.contains(6));
        assertFalse(ranges.contains(4));
        assertTrue(IntRanges.parse("10-").contains(9999));
        assertEquals(3, ranges.entries().size());
    }

}
