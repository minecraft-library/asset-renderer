package lib.minecraft.renderer.parity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import lib.minecraft.renderer.support.BuildScripts;

/**
 * One sort direction across every sweep writer, asserted on their sources.
 *
 * <p>Nothing downstream can notice a table ranked the other way - the store re-keys every row by
 * subject and the fleet sum is order-independent - so what is at risk is a human reading the raw
 * table, where "worst first" and "best first" look the same.
 *
 * <p>Read off the sources rather than off a run, because a run of one of these boots the pipeline and
 * renders a corpus.
 */
@DisplayName("The sweep writers rank their tables the same way")
final class SweepSortDirectionTest {

    /** Where the writers live. */
    private static final Path HOME = Path.of("src/test/java/lib/minecraft/renderer/visual");

    /**
     * The writers, named rather than globbed and held below to the writers the sweep producers run.
     *
     * <p>Naming them buys a reader the set at a glance; what makes the naming safe is that
     * relation, because a set typed once is one every case here goes on holding over after a member
     * is dropped from it.
     */
    private static final List<String> WRITERS = List.of(
        "TestEntityParityVanilla.java", "TestBlockParityVanilla.java", "TestItemParityVanilla.java",
        "TestPlayerParityVanilla.java", "TestArmorParityVanilla.java", "TestGlintParityVanilla.java",
        "TestMenuParityVanilla.java");

    /** Where the build file registers the task that runs each writer. */

    /** The id prefix of the artifacts whose producers are the writers, which is what selects them. */
    private static final String SWEEP_ID_PREFIX = "sweep.";

    /** What a writer's source file is called, given the class its producer runs. */
    private static final String JAVA = ".java";

    /** How a registration names the class it runs, which is where a producer's writer is read from. */
    private static final Pattern RUNS_A_CLASS = Pattern.compile("mainClass\\.set\\(\"([^\"]+)\"\\)");

    /** The one spelling of the ranking, which is the shared comparator applied to the row's delta. */
    private static final String THE_SORT = "rows.sort(SweepReport.byDelta(Row::meanDelta));";

    /** What a console header promises, wherever a writer prints one. */
    private static final String WORST_FIRST = "worst first";

    /** The copy that promise is kept with, which is the table re-ranked the other way and not moved. */
    private static final String THE_DESCENDING_SORT =
        ".sorted((a, b) -> Double.compare(b.meanDelta(), a.meanDelta()))";

    /** How the listing under such a header reads it, whether it takes the whole list or a head of it. */
    private static final String READS_THE_DESCENDING_COPY = "for (Row r : worst";

    @Test
    @DisplayName("the writers named here are the ones the sweep producers run")
    void theNamedSetIsWhatTheSweepProducersRun() {
        ProducerWriters resolved = writersTheSweepProducersRun();
        List<String> run = resolved.writers();
        List<String> present = writerFilesInTheDirectory();

        assertThat("sweep producers whose writer the build file does not name",
            resolved.unreadable(), is(empty()));
        assertThat("the artifact roster registers no sweep at all", run, is(not(empty())));
        assertThat("the writers named here, against the writer each sweep producer actually runs",
            WRITERS.stream().sorted().toList(), equalTo(run));
        assertThat("and the directory holds no writer the producers do not run", present, equalTo(run));
    }

    /**
     * The writer file each sweep producer runs, beside the producers whose writer could not be read.
     *
     * @param writers the writer file names, sorted
     * @param unreadable the producers the build file registers no {@code JavaExec} for, or whose
     *     registration names no main class
     */
    private record ProducerWriters(List<String> writers, List<String> unreadable) {}

    /**
     * Resolves every sweep producer's writer off the artifact roster and the build file.
     *
     * @return the writers found, beside the producers whose writer could not be read
     */
    private static ProducerWriters writersTheSweepProducersRun() {
        String build = BuildScripts.all();
        List<String> found = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (ParityArtifacts.Registration registration : ParityArtifacts.ALL) {
            if (!registration.id().startsWith(SWEEP_ID_PREFIX)) continue;
            for (String producer : registration.producers()) {
                Optional<String> className = classRunBy(build, producer);
                if (className.isEmpty()) {
                    unreadable.add(producer);
                    continue;
                }
                String name = className.get();
                found.add(name.substring(name.lastIndexOf('.') + 1) + JAVA);
            }
        }
        return new ProducerWriters(found.stream().sorted().toList(), List.copyOf(unreadable));
    }

    /**
     * Reads the class one registered producer runs.
     *
     * <p>Sliced to that registration's own block, so a task registered without a main class reads
     * as one rather than borrowing the next registration's.
     *
     * @param build the build file's text
     * @param producer the task name
     * @return the fully-qualified class name, or empty when the build file registers no
     *     {@code JavaExec} by that name or the registration names no main class
     */
    private static Optional<String> classRunBy(String build, String producer) {
        String opening = "register<JavaExec>(\"" + producer + "\") {";
        int start = build.indexOf(opening);
        if (start < 0) return Optional.empty();
        int next = build.indexOf("\n    register<", start + opening.length());
        Matcher runs = RUNS_A_CLASS.matcher(
            next < 0 ? build.substring(start) : build.substring(start, next));
        return runs.find() ? Optional.of(runs.group(1)) : Optional.empty();
    }

    /**
     * Returns every sweep writer's file name the directory holds.
     *
     * @return the file names, sorted
     */
    private static List<String> writerFilesInTheDirectory() {
        try (Stream<Path> files = Files.list(HOME)) {
            return files.map(file -> file.getFileName().toString())
                .filter(name -> name.startsWith("Test") && name.endsWith("ParityVanilla" + JAVA))
                .sorted()
                .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    @DisplayName("each writer sorts its rows exactly once, through the shared comparator")
    void everyWriterRanksThroughTheOneComparator() {
        List<String> without = new ArrayList<>();
        List<String> repeated = new ArrayList<>();
        for (String writer : WRITERS) {
            long calls = source(writer).lines().filter(line -> line.trim().equals(THE_SORT)).count();
            if (calls == 0) without.add(writer);
            if (calls > 1) repeated.add(writer + " sorts " + calls + " times");
        }

        assertThat("sweep writers that do not rank their table through the shared comparator",
            without, is(empty()));
        assertThat("and sweep writers that sort more than once", repeated, is(empty()));
    }

    @Test
    @DisplayName("no writer ranks its table through a comparator of its own")
    void noWriterCarriesItsOwnTableComparator() {
        List<String> handRolled = new ArrayList<>();
        for (String writer : WRITERS)
            source(writer).lines()
                .filter(line -> line.contains("rows.sort(") && line.contains("Double.compare("))
                .forEach(line -> handRolled.add(writer + ": " + line.trim()));

        assertThat("hand-rolled comparators on the row list", handRolled, is(empty()));
    }

    @Test
    @DisplayName("a console listing headed worst first is read off a descending copy, not off the table")
    void everyWorstFirstHeaderIsFollowedByTheDescendingListing() {
        List<String> wrong = new ArrayList<>();
        for (String writer : WRITERS) {
            List<String> lines = source(writer).lines().map(String::trim).toList();
            for (int index = 0; index < lines.size(); index++) {
                if (!lines.get(index).contains(WORST_FIRST)) continue;
                String listing = index + 1 < lines.size() ? lines.get(index + 1) : "";
                if (!listing.startsWith(READS_THE_DESCENDING_COPY))
                    wrong.add(writer + ": " + lines.get(index) + " -> " + listing);
                if (!source(writer).contains(THE_DESCENDING_SORT))
                    wrong.add(writer + " heads a listing `" + WORST_FIRST + "` and sorts nothing that way");
            }
        }

        assertThat("no writer prints a `" + WORST_FIRST + "` header at all",
            WRITERS.stream().anyMatch(writer -> source(writer).contains(WORST_FIRST)), is(true));
        assertThat("console listings whose header promises the worst first over a list that is not "
            + "in that order", wrong, is(empty()));
    }

    @Test
    @DisplayName("the shared comparator really is ascending, so a failure sorts last")
    void theComparatorPutsTheWorstLast() {
        List<Double> deltas = new ArrayList<>(
            List.of(Double.POSITIVE_INFINITY, 2.0, 0.5));
        Comparator<Double> ranking = SweepReport.byDelta(Double::doubleValue);

        deltas.sort(ranking);

        assertThat("smallest first, with the in-memory failure marker last",
            deltas, contains(0.5, 2.0, Double.POSITIVE_INFINITY));
        assertThat("and a total comparison of the two numbers rather than a subtraction",
            ranking.compare(0.5, 2.0), equalTo(-1));
    }

    /**
     * Reads one writer's source.
     *
     * @param writer the file name
     * @return its text
     */
    private static String source(String writer) {
        return read(HOME.resolve(writer));
    }

    /**
     * Reads a tracked file's text, by path because none of these is on the classpath.
     *
     * @param file the repo-relative path
     * @return its text
     */
    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
