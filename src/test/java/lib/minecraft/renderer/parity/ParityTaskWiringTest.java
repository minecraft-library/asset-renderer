package lib.minecraft.renderer.parity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Pins the build-file wiring the parity refusals rest on, none of which any run of the suite reaches.
 *
 * <p>Every statement read here is one a Gradle build has no unit to fail in, and every one of them
 * restores a defect end to end when it goes: an ordering edge gone and a promotion certifies itself
 * against the store it has just been written into; a value-blind flag reader and
 * {@code -Pbootstrap=false} turns bootstrap on; the task-name matcher gone and an abbreviation runs
 * the whole capture with every configuration-time refusal and every dependency edge silently
 * skipped; the override forwarding gone and the one answer to the dirty-tree refusal is unreachable
 * from a command line; the run-count forwarding gone and an operator's claim below the determinism
 * floor is replaced by the floor that refusal exists to enforce. Each left both suites green.
 *
 * <p>So the statements are read out of the build file and asserted. That is what is available -
 * exercising them needs a Gradle build, and the alternative is the state these landed in, where the
 * only thing that ever saw them was a hand-typed {@code --dry-run}. The build file is declared as
 * an input to this suite, so an edit to it re-runs these rather than leaving them UP-TO-DATE.
 *
 * <p>Each function is asserted <b>whole</b>, whitespace collapsed. A predicate that still reads the
 * start parameter and then answers differently is the exact shape of every defect above, and a
 * containment test on the part that did not change passes on all of them. Where a statement sits
 * inside a body too large to assert whole, what is read is the enclosing declaration rather than
 * the file: the same statement in the wrong body is a forwarding that reaches nothing.
 */
@DisplayName("the build file wires the parity refusals it says it wires")
final class ParityTaskWiringTest {

    /** The boolean-option reader, whose presence-only form turned {@code -Pname=false} into ON. */
    private static final String READS_A_BOOLEAN_OPTION =
        "fun parityFlag(name: String): Boolean = "
            + "gradle.startParameter.projectProperties.containsKey(name) "
            + "&& parityProperty(name) != \"false\"";

    /** The requested-task predicate, which has to decide a token the way Gradle itself will. */
    private static final String RESOLVES_THROUGH_GRADLES_MATCHER =
        "fun parityTaskRequested(name: String): Boolean = "
            + "gradle.startParameter.taskNames.any { typed -> "
            + "val token = typed.substringAfterLast(':') "
            + "typed == name || typed.endsWith(\":$name\") || "
            + "(token.isNotEmpty() && "
            + "org.gradle.util.internal.NameMatcher().find(token, listOf(name)) != null) }";

    /** The one guarded spelling of the run-count forwarding, which every site has to be. */
    private static final String FORWARDS_THE_RUN_COUNT = "runs?.let { add(\"--runs\"); add(it) }";

    /** What an unguarded forwarding would still have to contain, so the two can be counted apart. */
    private static final String NAMES_THE_RUN_COUNT = "add(\"--runs\")";

    /** The capture step's registration, which is the body that argv reaches a capture through. */
    private static final String REGISTERS_A_CAPTURE_STEP =
        "fun TaskContainer.registerParityCapture(";

    @Test
    @DisplayName("a boolean option is off when it is given the value false")
    void aBooleanOptionIsValueAware() {
        assertThat("both the skill and the runbook spell these `-Pname=true`, which is the only "
                + "spelling that makes `-Pname=false` look meaningful - and a presence test reads "
                + "that as ON, so the documented way to turn one off turns it on",
            collapsed(function("parityFlag")), equalTo(READS_A_BOOLEAN_OPTION));
    }

    @Test
    @DisplayName("a requested task is resolved through Gradle's own name matcher")
    void aRequestedTaskIsMatchedTheWayGradleWill() {
        assertThat("the start parameter holds what was TYPED and Gradle expands an abbreviation "
                + "later, so a literal comparison answers no for `parityCapt` while the build goes "
                + "on to run parityCapture: it erased the working root, ran no producer, wrote a "
                + "valid index over nothing and reported success. A hand-rolled matcher is the one "
                + "replacement that must not pass here - disagreeing with Gradle about what a token "
                + "names is the defect itself",
            collapsed(function("parityTaskRequested")), equalTo(RESOLVES_THROUGH_GRADLES_MATCHER));
    }

    @Test
    @DisplayName("a compare in the same invocation is ordered after the capture it reads")
    void theCompareIsOrderedAfterTheCapture() {
        assertThat("nothing but command-line order kept `parityCapture parityCompare` from "
                + "comparing a root the capture had not written yet",
            taskBlock("parityCompare"), containsString("mustRunAfter(\"parityCapture\")"));
    }

    @Test
    @DisplayName("a promotion in the same invocation is ordered after the compare it applies")
    void thePromotionIsOrderedAfterTheCompare() {
        assertThat("`parityPromote parityCompare` promoted the root into the store and then diffed "
                + "it against the store it had just been written into - necessarily zero movers, "
                + "and a verdict saying this exact tree was gated",
            taskBlock("parityPromote"), containsString("mustRunAfter(\"parityCompare\")"));
    }

    @Test
    @DisplayName("the dirty-tree override is read as a value and forwarded to the toolkit")
    void theDirtyTreeOverrideReachesTheToolkit() {
        String promote = taskBlock("parityPromote");
        assertThat("read through the value-aware reader like every other option here",
            promote, containsString("val allowDirty = parityFlag(\"allowDirty\")"));
        assertThat("a refusal whose only override is unreachable from a command line is a refusal "
                + "nobody can answer, and the runbook documents this one as the answer",
            promote, containsString("if (allowDirty) add(\"--allow-dirty\")"));
    }

    @Test
    @DisplayName("the run count reaches the capture of each artifact, and the index over them")
    void theRunCountReachesBothSidesOfACapture() {
        String step = collapsed(declaration(REGISTERS_A_CAPTURE_STEP));

        assertThat("read out of the body that builds a capture step's argv, so the statement is "
            + "pinned where it reaches an artifact rather than anywhere in the file",
            step, containsString("add(\"capture-normalize\")"));
        assertThat("the forwarding that carries the number into an ARTIFACT's provenance, which is "
                + "the value the promotion compares against the determinism floor. Dropped, every "
                + "capture is stamped with the floor instead - so a claim BELOW it, the one thing "
                + "the floor refusal exists to catch, is silently replaced by a passing one",
            step, containsString(FORWARDS_THE_RUN_COUNT));
        assertThat("and the one that records the same claim once for the capture as a whole, in "
                + "the index rather than under each row",
            taskBlock("parityCapture"), containsString(FORWARDS_THE_RUN_COUNT));
    }

    @Test
    @DisplayName("no forwarding of the run count is unguarded")
    void theRunCountIsForwardedOnlyWhenItWasMeasured() {
        String build = collapsed(buildFile());
        int named = occurrences(build, NAMES_THE_RUN_COUNT);

        assertThat("the build file forwards --runs nowhere at all, which would leave the "
            + "comparison below vacuous", named, is(greaterThan(0)));
        assertThat("absent, `--runs` is the artifact's declared floor and the toolkit stamps it - "
                + "which is a property of the artifact rather than of an invocation. A site "
                + "forwarding one unconditionally puts a build-side number under every capture and "
                + "makes that default unreachable, so the guarded spelling is the only one",
            occurrences(build, FORWARDS_THE_RUN_COUNT), is(equalTo(named)));
    }

    /**
     * Returns the body of a top-level build-file function, declaration line included.
     *
     * <p>Bounded by the blank line after it rather than by a brace, because both of these are
     * expression-bodied and the second's expression carries braces of its own.
     *
     * @param name the function name
     * @return its declaration and body, verbatim
     */
    private static String function(String name) {
        String build = buildFile();
        int start = build.indexOf("fun " + name + "(");
        assertThat("build.gradle.kts declares no " + name, start, is(not(-1)));
        int end = build.indexOf("\n\n", start);
        assertThat(name + " runs to the end of the build file", end, is(not(-1)));
        return build.substring(start, end);
    }

    /**
     * Returns a block-bodied top-level declaration, from its opening line to its closing brace.
     *
     * <p>Bounded by a brace in the first column, which is where a top-level declaration in this
     * file closes and where no declaration nested in one does.
     *
     * @param opening the declaration's opening text, verbatim
     * @return the declaration and its body
     */
    private static String declaration(String opening) {
        String build = buildFile();
        int start = build.indexOf(opening);
        assertThat("build.gradle.kts declares no " + opening, start, is(not(-1)));
        int end = build.indexOf("\n}", start);
        assertThat(opening + " has no line-initial closing brace", end, is(not(-1)));
        String body = build.substring(start, end);
        assertThat(opening + "'s slice ran into the declaration after it",
            body, not(containsString("\nfun ")));
        return body;
    }

    /**
     * Returns a registered task's body, from its opening brace to the next registration.
     *
     * <p>Sanity-checked here rather than beside a caller, so that every slice taken is checked
     * whatever asks for one: a slice running past its own registration would satisfy a containment
     * test out of a neighbour's body, and one stopping short of the body would satisfy none of them
     * and read as the wiring being gone.
     *
     * @param name the task name
     * @return the body, verbatim
     */
    private static String taskBlock(String name) {
        String build = buildFile();
        Matcher opens = Pattern.compile("register<[^>]+>\\(\"" + name + "\"\\) \\{").matcher(build);
        assertThat("build.gradle.kts registers no " + name, opens.find(), is(true));
        Matcher next = Pattern.compile("\n {4}register<").matcher(build);
        String block =
            build.substring(opens.end(), next.find(opens.end()) ? next.start() : build.length());
        assertThat(name + "'s slice ran into the registration after it",
            block, not(containsString("    register<")));
        assertThat(name + "'s slice stopped before its own body", block, containsString("description ="));
        assertThat(name + " must be registered in the parity group it is counted in",
            block, containsString("group = \"parity\""));
        return block;
    }

    /** Every run of whitespace as one space, so an assertion is about the statement and not its wrap. */
    private static String collapsed(String source) {
        return source.replaceAll("\\s+", " ").trim();
    }

    /**
     * Counts non-overlapping occurrences of a literal.
     *
     * @param haystack the text to search
     * @param needle the literal to count
     * @return how many times it occurs
     */
    private static int occurrences(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length()))
            found++;
        return found;
    }

    private static String buildFile() {
        try {
            return Files.readString(Path.of("build.gradle.kts"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
