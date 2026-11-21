package lib.minecraft.renderer.asset.pack;

import lib.minecraft.renderer.exception.ContentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What a {@link PatternBudget} answers and what it refuses: a pattern matched through one answers as it
 * does matched directly, and a match the budget cannot pay for, a subject past the longest taken and a
 * match that runs out of stack each end in a {@link ContentException} rather than in the time or the
 * stack the pattern asks for.
 *
 * <p>The costly pattern stands in for any whose cost outgrows its subject. Every count here is far to
 * one side of the budget it is held against, so none turns on exactly how many characters a matcher
 * reads.
 */
@DisplayName("A pack's patterns are matched within a step budget")
class PatternBudgetTest {

    /** A pattern costing a few hundred thousand steps against {@link #SUBJECT}, inside one match's budget. */
    private static final Pattern AFFORDABLE = Pattern.compile("x*x*x*y");

    /** A pattern costing billions of steps against {@link #SUBJECT}, far past one match's budget. */
    private static final Pattern COSTLY = Pattern.compile("x*x*x*x*x*x*x*x*y");

    private static final String SUBJECT = "x".repeat(40);

    @Test
    @DisplayName("a pattern answers through a budget as it does matched directly")
    void answersAsTheMatcherDoes() {
        List<String> patterns = List.of("^blockstates/.*\\.json$", "stone", ".*", "", "(?i)STONE", "[a-z_]+/[a-z_]+", "a|b", "^$");
        List<String> subjects = List.of("blockstates/stone.json", "models/block/stone.json", "minecraft", "", "Stone", "a");

        for (String regex : patterns) {
            Pattern pattern = Pattern.compile(regex);
            for (String subject : subjects) {
                assertThat("find '" + regex + "' in '" + subject + "'",
                    PatternBudget.single().find(pattern, subject), is(pattern.matcher(subject).find()));
                assertThat("match '" + regex + "' against '" + subject + "'",
                    PatternBudget.single().matches(pattern, subject), is(pattern.matcher(subject).matches()));
            }
        }
    }

    @Test
    @DisplayName("a match the budget cannot pay for is refused")
    void anUnaffordableMatchIsRefused() {
        ContentException refusal = assertThrows(ContentException.class, () -> PatternBudget.single().find(COSTLY, SUBJECT));

        assertThat(refusal.getMessage(), containsString(COSTLY.pattern()));
        assertThat(refusal.getMessage(), containsString(Long.toString(PatternBudget.SINGLE_STEPS)));
    }

    @Test
    @DisplayName("every later match through a spent budget is refused, however cheap")
    void aSpentBudgetRefusesEveryLaterMatch() {
        PatternBudget budget = PatternBudget.single();
        assertThrows(ContentException.class, () -> budget.find(COSTLY, SUBJECT));

        assertThrows(ContentException.class, () -> budget.find(Pattern.compile("x"), "x"));
        assertThat("and a fresh budget still answers", PatternBudget.single().find(Pattern.compile("x"), "x"), is(true));
    }

    @Test
    @DisplayName("a pass's budget is spent by its matches together, each one affordable alone")
    void aPassIsBoundedAsAWhole() {
        assertThat("one such match fits one match's budget", PatternBudget.single().find(AFFORDABLE, SUBJECT), is(false));

        // Enough subjects that the pass's allowance has left the single-match floor behind.
        int subjects = 256;
        PatternBudget pass = PatternBudget.across(Collections.nCopies(subjects, SUBJECT).stream());
        assertThrows(ContentException.class, () -> {
            for (int subject = 0; subject < subjects; subject++)
                pass.find(AFFORDABLE, SUBJECT);
        });
    }

    @Test
    @DisplayName("a pass over cheap matches is paid for, a match that reads nothing included")
    void aPassOfCheapMatchesIsPaidFor() {
        List<String> subjects = IntStream.range(0, 4096).mapToObj(subject -> "blockstates/some_block_" + subject + ".json").toList();
        PatternBudget pass = PatternBudget.across(subjects.stream());
        Pattern anchored = Pattern.compile("^textures/.*\\.png$");
        Pattern empty = Pattern.compile("");

        for (String subject : subjects) {
            assertThat(pass.find(anchored, subject), is(false));
            assertThat(pass.find(empty, "minecraft"), is(true));
        }
    }

    @Test
    @DisplayName("a pass's budget grows with its subjects' lengths, up to the longest it pays for")
    void aPassIsSizedByItsSubjects() {
        Pattern careless = Pattern.compile(".*\\.nomatch$");
        List<String> shortPaths = Collections.nCopies(1024, "models/a.json");
        List<String> longPaths = Collections.nCopies(1024, "models/block/" + "a".repeat(100) + ".json");
        List<String> overlong = Collections.nCopies(64, "models/block/" + "a".repeat(1000) + ".json");

        PatternBudget shorter = PatternBudget.across(shortPaths.stream());
        shortPaths.forEach(path -> assertThat(shorter.find(careless, path), is(false)));
        PatternBudget longer = PatternBudget.across(longPaths.stream());
        longPaths.forEach(path -> assertThat(longer.find(careless, path), is(false)));

        assertThat("the short paths' budget does not pay for the long ones",
            assertThrows(ContentException.class, () -> {
                PatternBudget mismatched = PatternBudget.across(shortPaths.stream());
                longPaths.forEach(path -> mismatched.find(careless, path));
            }).getMessage(), containsString(careless.pattern()));
        assertThrows(ContentException.class, () -> {
            PatternBudget capped = PatternBudget.across(overlong.stream());
            overlong.forEach(path -> capped.find(careless, path));
        }, "a path past the longest paid for is paid for as one of that length");
    }

    @Test
    @DisplayName("a subject past the longest taken is refused before it is read")
    void anOverlongSubjectIsRefused() {
        Pattern any = Pattern.compile(".*");

        assertThat(PatternBudget.single().matches(any, "x".repeat(PatternBudget.MAX_SUBJECT_LENGTH)), is(true));
        assertThrows(ContentException.class, () -> PatternBudget.single().matches(any, "x".repeat(PatternBudget.MAX_SUBJECT_LENGTH + 1)));
    }

    @Test
    @DisplayName("a match that runs out of stack is refused, not passed on as an error")
    void aMatchOutOfStackIsRefused() throws InterruptedException {
        // The longest subject taken, matched on a thread with little stack to give it.
        Pattern nested = Pattern.compile("(xy|x)*");
        String subject = "x".repeat(PatternBudget.MAX_SUBJECT_LENGTH);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread thread = new Thread(null, () -> {
            try {
                PatternBudget.single().matches(nested, subject);
            } catch (Throwable ex) {
                thrown.set(ex);
            }
        }, "pattern-budget-small-stack", 128 * 1024);
        thread.start();
        thread.join();

        assertThat(thrown.get(), is(instanceOf(ContentException.class)));
    }

}
