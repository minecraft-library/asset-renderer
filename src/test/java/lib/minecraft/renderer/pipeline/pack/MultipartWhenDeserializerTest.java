package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Block.Multipart.When;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The blockstate multipart {@code when} grammar as {@link MultipartWhenDeserializer} actually reads
 * it, driven through the runtime {@link GsonSettings#defaults()} so the test exercises the same
 * registration and the same {@code AND} / {@code OR} recursion the pipeline does.
 *
 * <p>The grammar is four arms decided by key presence alone - {@code AND} first, {@code OR} second,
 * anything else a property match - and the deciding branch is a pair of {@code has} calls with no
 * validation behind them. That shape makes a missing arm silent: a clause that should have narrowed
 * a part instead becomes a different arm that still evaluates, and the part draws or vanishes with
 * no parse error anywhere. The precedence tests are the point of this class - a {@code when} that
 * carries {@code AND} alongside a sibling {@code OR} or a plain property keeps only the {@code AND},
 * and the discarded siblings leave no trace.
 *
 * <p>The second half pins what the reader does with input it was not written for, because it does not
 * reject any of it. An unknown key is accepted as a property name, so the grammar keys are
 * case-sensitive in the strongest sense - a lower-case {@code "and"} parses as a property called
 * {@code and}. A non-array {@code AND} yields an empty {@link When.All}, which matches everything,
 * while a non-array {@code OR} yields an empty {@link When.Any}, which matches nothing: the same
 * malformed input lands on opposite answers. A non-primitive value collapses to the empty string,
 * which is exactly the value {@link When.Match} reads for a property the block never declared, so a
 * malformed clause matches a block missing that property. Only a non-object condition fails, and it
 * fails as a Gson syntax error rather than a checked one.
 */
@DisplayName("Multipart when grammar")
class MultipartWhenDeserializerTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    /** Reads one {@code when} clause through the registered deserializer. */
    private static When parse(String json) {
        return GSON.fromJson(json, When.class);
    }

    /** Reads a clause expected to land on the property-match arm. */
    private static When.Match asMatch(String json) {
        When when = parse(json);
        assertThat(when, is(instanceOf(When.Match.class)));
        return (When.Match) when;
    }

    /** Reads a clause expected to land on the {@code AND} arm. */
    private static When.All asAll(String json) {
        When when = parse(json);
        assertThat(when, is(instanceOf(When.All.class)));
        return (When.All) when;
    }

    /** Reads a clause expected to land on the {@code OR} arm. */
    private static When.Any asAny(String json) {
        When when = parse(json);
        assertThat(when, is(instanceOf(When.Any.class)));
        return (When.Any) when;
    }

    /** The property map a condition is evaluated against. */
    private static ConcurrentMap<String, String> properties(Map<String, String> entries) {
        return Concurrent.newMap(entries);
    }

    @Nested
    @DisplayName("grammar arms")
    class GrammarArms {

        /** Asserts a lone property becomes a match carrying that one property and one allowed value. */
        @Test
        @DisplayName("a single property becomes a match with one allowed value")
        void singlePropertyBecomesAMatchWithOneAllowedValue() {
            When.Match match = asMatch("{\"facing\":\"north\"}");
            assertThat(match.required().keySet(), contains("facing"));
            assertThat(match.required().get("facing"), is(equalTo(List.of("north"))));
            assertThat(match.matches(properties(Map.of("facing", "north"))), is(true));
            assertThat(match.matches(properties(Map.of("facing", "south"))), is(false));
        }

        /**
         * Asserts a {@code |} alternation is split at load into the allowed set, so the render-time
         * test is a list membership rather than a second parse.
         */
        @Test
        @DisplayName("a pipe-separated value becomes the allowed set")
        void pipeSeparatedValueBecomesTheAllowedSet() {
            When.Match match = asMatch("{\"north\":\"side|up\"}");
            assertThat(match.required().get("north"), is(equalTo(List.of("side", "up"))));
            assertThat(match.matches(properties(Map.of("north", "side"))), is(true));
            assertThat(match.matches(properties(Map.of("north", "up"))), is(true));
            assertThat(match.matches(properties(Map.of("north", "none"))), is(false));
        }

        /** Asserts an {@code AND} array becomes an all-of clause holding its terms in author order. */
        @Test
        @DisplayName("AND becomes an all-of clause in author order")
        void andBecomesAnAllOfClauseInAuthorOrder() {
            When.All all = asAll("{\"AND\":[{\"a\":\"1\"},{\"b\":\"2\"}]}");
            assertThat(all.terms().size(), is(2));
            assertThat(((When.Match) all.terms().getFirst()).required().keySet(), contains("a"));
            assertThat(((When.Match) all.terms().getLast()).required().keySet(), contains("b"));
            assertThat(all.matches(properties(Map.of("a", "1", "b", "2"))), is(true));
            assertThat(all.matches(properties(Map.of("a", "1"))), is(false));
        }

        /** Asserts an {@code OR} array becomes an any-of clause holding its terms in author order. */
        @Test
        @DisplayName("OR becomes an any-of clause in author order")
        void orBecomesAnAnyOfClauseInAuthorOrder() {
            When.Any any = asAny("{\"OR\":[{\"a\":\"1\"},{\"b\":\"2\"}]}");
            assertThat(any.terms().size(), is(2));
            assertThat(((When.Match) any.terms().getFirst()).required().keySet(), contains("a"));
            assertThat(((When.Match) any.terms().getLast()).required().keySet(), contains("b"));
            assertThat(any.matches(properties(Map.of("a", "1"))), is(true));
            assertThat(any.matches(properties(Map.of("c", "3"))), is(false));
        }

        /**
         * Asserts {@code AND} wins outright over a sibling {@code OR} and a sibling plain property,
         * which are dropped rather than folded in - the clause evaluates as if they were never
         * written.
         */
        @Test
        @DisplayName("AND outranks a sibling OR and a sibling plain property, both discarded")
        void andOutranksItsSiblings() {
            When.All all = asAll("{\"AND\":[{\"a\":\"1\"}],\"OR\":[{\"b\":\"2\"}],\"facing\":\"north\"}");
            assertThat(all.terms().size(), is(1));
            assertThat(((When.Match) all.terms().getFirst()).required().keySet(), contains("a"));
            // The dropped OR and the dropped facing leave nothing behind: a block satisfying only the
            // AND term still matches, which it could not do if either sibling had survived.
            assertThat(all.matches(properties(Map.of("a", "1"))), is(true));
        }

        /** Asserts {@code OR} outranks a sibling plain property, which is likewise discarded. */
        @Test
        @DisplayName("OR outranks a sibling plain property, which is discarded")
        void orOutranksASiblingPlainProperty() {
            When.Any any = asAny("{\"OR\":[{\"a\":\"1\"}],\"facing\":\"north\"}");
            assertThat(any.terms().size(), is(1));
            assertThat(any.matches(properties(Map.of("a", "1"))), is(true));
        }

        /**
         * Asserts a nested clause resolves through the deserialization context rather than a second
         * hand parse, so an {@code AND} inside an {@code OR} is a real all-of term.
         */
        @Test
        @DisplayName("a nested AND inside an OR recurses through the context")
        void nestedAndInsideAnOrRecursesThroughTheContext() {
            When.Any any = asAny("{\"OR\":[{\"AND\":[{\"a\":\"1\"},{\"b\":\"2\"}]},{\"c\":\"3\"}]}");
            assertThat(any.terms().size(), is(2));
            assertThat(any.terms().getFirst(), is(instanceOf(When.All.class)));
            assertThat(any.terms().getLast(), is(instanceOf(When.Match.class)));
            assertThat(((When.All) any.terms().getFirst()).terms().size(), is(2));
            assertThat(any.matches(properties(Map.of("a", "1", "b", "2"))), is(true));
            assertThat(any.matches(properties(Map.of("a", "1"))), is(false));
            assertThat(any.matches(properties(Map.of("c", "3"))), is(true));
        }

        /**
         * Asserts a match keeps its properties in document order - the reader accumulates into a
         * {@code LinkedHashMap} over a Gson object whose own iteration is insertion-ordered, so the
         * parsed clause reproduces the file rather than a sorted or hashed permutation of it.
         */
        @Test
        @DisplayName("match properties keep document order, not sorted or hashed order")
        void matchPropertiesKeepDocumentOrder() {
            When.Match match = asMatch("{\"zebra\":\"1\",\"apple\":\"2\",\"mango\":\"3\"}");
            assertThat(match.required().keySet(), contains("zebra", "apple", "mango"));
        }
    }

    @Nested
    @DisplayName("input the reader was not written for")
    class UnwrittenForInput {

        /**
         * Asserts the same malformed shape lands on opposite answers: a non-array {@code AND} yields
         * an empty all-of, which is vacuously true, and a non-array {@code OR} an empty any-of, which
         * is unconditionally false. Nothing is reported either way, so a hand-edited pack silently
         * turns one part unconditional and its neighbour undrawable.
         */
        @Test
        @DisplayName("a non-array AND is vacuously true where a non-array OR is always false")
        void aNonArrayAndIsTrueWhereANonArrayOrIsFalse() {
            When.All all = asAll("{\"AND\":\"nope\"}");
            assertThat(all.terms(), is(empty()));
            assertThat(all.matches(properties(Map.of("facing", "north"))), is(true));

            When.Any any = asAny("{\"OR\":5}");
            assertThat(any.terms(), is(empty()));
            assertThat(any.matches(properties(Map.of("facing", "north"))), is(false));
        }

        /**
         * Asserts a term that is not an object is dropped from the list rather than rejecting the
         * clause, so a strings-and-numbers array narrows to whichever entries happened to be objects.
         */
        @Test
        @DisplayName("non-object terms are dropped rather than rejected")
        void nonObjectTermsAreDroppedRatherThanRejected() {
            When.All all = asAll("{\"AND\":[{\"a\":\"1\"},\"junk\",7,null,[]]}");
            assertThat(all.terms().size(), is(1));
            assertThat(((When.Match) all.terms().getFirst()).required().keySet(), contains("a"));
        }

        /**
         * Asserts a non-primitive value collapses to the single allowed value {@code ""}, which is
         * also what a match reads for a property the block never declared - so the malformed clause
         * matches precisely the blocks that are missing the property and no others.
         */
        @Test
        @DisplayName("a non-primitive value collapses to the empty allowed value")
        void aNonPrimitiveValueCollapsesToTheEmptyAllowedValue() {
            When.Match match = asMatch("{\"a\":{\"b\":1}}");
            assertThat(match.required().get("a"), is(equalTo(List.of(""))));
            assertThat(match.matches(properties(Map.of())), is(true));
            assertThat(match.matches(properties(Map.of("a", "1"))), is(false));
        }

        /**
         * Asserts an unknown key is accepted as a property name rather than refused, which makes the
         * grammar keys case-sensitive in the strongest sense - a lower-case {@code "and"} parses as a
         * property called {@code and} whose array value collapses to the empty allowed value, so the
         * clause matches every block that does not declare one.
         */
        @Test
        @DisplayName("an unknown key is accepted as a property name, so AND and OR are case-sensitive")
        void anUnknownKeyIsAcceptedAsAPropertyName() {
            When.Match lowerCaseAnd = asMatch("{\"and\":[{\"a\":\"1\"}]}");
            assertThat(lowerCaseAnd.required().keySet(), contains("and"));
            assertThat(lowerCaseAnd.required().get("and"), is(equalTo(List.of(""))));
            assertThat(lowerCaseAnd.matches(properties(Map.of("a", "1"))), is(true));

            assertThat(asMatch("{\"nonsense\":\"x\"}").required().get("nonsense"), is(equalTo(List.of("x"))));
        }

        /**
         * Asserts a bare JSON number or boolean is stringified rather than refused, so an unquoted
         * value reads as the same property string vanilla writes quoted.
         */
        @Test
        @DisplayName("bare numbers and booleans are stringified")
        void bareNumbersAndBooleansAreStringified() {
            When.Match match = asMatch("{\"level\":3,\"lit\":true}");
            assertThat(match.required().get("level"), is(equalTo(List.of("3"))));
            assertThat(match.required().get("lit"), is(equalTo(List.of("true"))));
            assertThat(match.matches(properties(Map.of("level", "3", "lit", "true"))), is(true));
        }

        /**
         * Asserts the separator's own edges, which are the split's and not a rule of the grammar: a
         * trailing separator drops its empty alternative, a leading one keeps it, and a lone
         * separator leaves no allowed value at all, which no property value can ever satisfy.
         */
        @Test
        @DisplayName("a trailing separator drops its empty alternative and a lone separator leaves none")
        void separatorEdgesFollowTheSplit() {
            assertThat(asMatch("{\"north\":\"side|\"}").required().get("north"), is(equalTo(List.of("side"))));
            assertThat(asMatch("{\"north\":\"|side\"}").required().get("north"), is(equalTo(List.of("", "side"))));

            When.Match lone = asMatch("{\"north\":\"|\"}");
            assertThat(lone.required().get("north"), is(empty()));
            assertThat(lone.matches(properties(Map.of("north", "side"))), is(false));
            assertThat(lone.matches(properties(Map.of())), is(false));
        }

        /** Asserts an empty condition object becomes a match with nothing required, matching every block. */
        @Test
        @DisplayName("an empty condition object matches every block")
        void anEmptyConditionObjectMatchesEveryBlock() {
            When.Match match = asMatch("{}");
            assertThat(match.required().keySet(), is(empty()));
            assertThat(match.matches(properties(Map.of("facing", "north"))), is(true));
        }

        /**
         * Asserts the one input that does fail is a condition that is not an object, and that it
         * fails as a Gson syntax error wrapping the raw {@code IllegalStateException} the object cast
         * throws - there is no checked message of the reader's own.
         */
        @Test
        @DisplayName("a non-object condition is a Gson syntax error, not a checked one")
        void aNonObjectConditionIsAGsonSyntaxError() {
            JsonSyntaxException thrown = assertThrows(JsonSyntaxException.class, () -> parse("\"north\""));
            assertThat(thrown.getCause(), is(instanceOf(IllegalStateException.class)));
        }
    }

}
