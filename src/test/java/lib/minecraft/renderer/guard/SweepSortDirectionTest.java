package lib.minecraft.renderer.guard;

import lib.minecraft.renderer.store.ParityArtifacts;
import lib.minecraft.renderer.sweep.SweepReport;
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

/**
 * One sort direction across every sweep writer, asserted on their sources.
 *
 * <p>Nothing downstream can notice a table ranked the other way - the store re-keys every row by
 * subject and the fleet sum is order-independent - so what is at risk is a human reading the raw
 * table, where "worst first" and "best first" look the same.
 *
 * <p>Read off the sources rather than off a run, because a run of one of these boots the pipeline and
 * renders a corpus.
 *
 * <p>The same sources say which writers run at all: the writers named here are the ones the sweep
 * producers run, and the shared sweep JVM's table holds every writer the build hands it and no other.
 *
 * <p>And they say where a capture finds each sweep's table. The build's artifact row names the
 * directory the table is read from and the property whose presence suppresses the capture, and each
 * value is typed a second time - the directory in the writer that writes it, the property where the
 * producer reads it - so each pair is held equal here, and the directory to the layout the toolkit
 * attributes a table by.
 */
@DisplayName("The sweep writers rank their tables the same way")
final class SweepSortDirectionTest {

    /** Where the writers live. */
    private static final Path HOME = Path.of("src/visual/java/lib/minecraft/renderer/sweep");

    /**
     * The writers, named rather than globbed and held below to the writers the sweep producers run.
     *
     * <p>Naming them buys a reader the set at a glance; what makes the naming safe is that
     * relation, because a set typed once is one every case here goes on holding over after a member
     * is dropped from it.
     */
    private static final List<String> WRITERS = List.of(
        "EntityParitySweep.java", "EntityAnimationParitySweep.java",
        "BlockParitySweep.java", "ItemParitySweep.java",
        "PlayerParitySweep.java", "ArmorParitySweep.java", "GlintParitySweep.java",
        "MenuParitySweep.java", "TooltipParitySweep.java", "BookParitySweep.java");

    /** The id prefix of the artifacts whose producers are the writers, which is what selects them. */
    private static final String SWEEP_ID_PREFIX = "sweep.";

    /** What a writer's source file is called, given the class its producer runs. */
    private static final String JAVA = ".java";

    /** The source root the writers' classes are declared under. */
    private static final String VISUAL_SOURCES = "src/visual/java";

    /**
     * One sweep row of the build's artifact table, capturing its id, its producers, the directory its
     * table is read from and the properties that scope it.
     */
    private static final Pattern SWEEP_ROW = Pattern.compile("ParityArtifact\\(\"(sweep\\.[^\"]+)\",\\s*"
        + "listOf\\(([^)]*)\\),\\s*\"([^\"]+)\"(?:,\\s*listOf\\(([^)]*)\\))?");

    /** One quoted value of a list the build types inline. */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /** How the vanilla sweep JVM's table names the property a producer narrows by, by producer name. */
    private static final String SHARED_JVM_SCOPE = "\"%s\" to VanillaSweep\\(\"[^\"]+\"(?:,\\s*\"([^\"]+)\")?";

    /** How a registration reads a project property, capturing the property's name. */
    private static final Pattern READS_A_PROPERTY = Pattern.compile("project\\.findProperty\\(\"([^\"]+)\"\\)");

    /** How a writer declares the one directory it writes, capturing the directory. */
    private static final Pattern WRITES_UNDER = Pattern.compile("OUTPUT_DIR = Path\\.of\\(\"([^\"]+)\"\\)");

    /** Where every sweep writes its table, ahead of the sweep's name. */
    private static final String SWEEP_OUTPUT_ROOT = "cache/visual/";

    /** What follows the sweep's name in the directory it writes, which is what the toolkit attributes a table by. */
    private static final String SWEEP_OUTPUT_SUFFIX = "-parity-vanilla";

    /** How the vanilla sweep JVM's table hands a writer to the JVM, capturing the writer's simple name. */
    private static final Pattern HANDED_WRITER =
        Pattern.compile("to VanillaSweep\\(\"lib\\.minecraft\\.renderer\\.sweep\\.(\\w+)\"");

    /** How the shared JVM's own table holds a writer it runs, capturing the writer's simple name. */
    private static final Pattern HELD_WRITER =
        Pattern.compile("sweeps\\.put\\((\\w+)\\.class\\.getName\\(\\), \\1::run\\);");

    /** The shared sweep JVM, whose table is every writer it can run. */
    private static final Path BUNDLE = HOME.resolve("SweepBundle.java");

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
        // Distinct, because a writer is a FILE and two artifacts can share one: the walk sweep is the
        // animation sweep's driver at a gait, which is what keeps the pair one implementation rather
        // than two that could differ in how they measured rather than in what they measured. Naming
        // it twice would read the same source twice for every case below.
        List<String> run = resolved.writers().stream().distinct().toList();
        List<String> present = writerFilesInTheDirectory();

        assertThat("sweep producers whose writer the build file does not name",
            resolved.unreadable(), is(empty()));
        assertThat("the artifact roster registers no sweep at all", run, is(not(empty())));
        assertThat("the writers named here, against the writer each sweep producer actually runs",
            WRITERS.stream().sorted().toList(), equalTo(run));
        assertThat("and the directory holds no writer the producers do not run", present, equalTo(run));
    }

    @Test
    @DisplayName("the shared sweep JVM runs every writer the build hands it, and holds no other")
    void theSharedJvmRunsEveryWriterTheBuildHandsIt() {
        List<String> handed = HANDED_WRITER.matcher(BuildScripts.all()).results()
            .map(hit -> hit.group(1)).distinct().sorted().toList();
        List<String> held = HELD_WRITER.matcher(read(BUNDLE)).results()
            .map(hit -> hit.group(1)).sorted().toList();

        assertThat("the build hands the shared JVM no writer, which would leave the clause below holding "
            + "over nothing", handed, is(not(empty())));
        assertThat("the writers the build hands the shared JVM, against the ones its table holds. A writer the "
            + "table lacks refuses the whole plan, so every sweep of the invocation goes unreported, and one "
            + "the build never hands it is a row nothing reaches", held, equalTo(handed));
    }

    @Test
    @DisplayName("each sweep's capture row is scoped by the property its producer narrows by")
    void everySweepRowIsScopedByWhatItsProducerReads() {
        String build = BuildScripts.all();
        List<SweepRow> rows = sweepRows(build);
        List<String> unreadable = new ArrayList<>();
        List<String> parted = new ArrayList<>();
        for (SweepRow row : rows) {
            for (String producer : row.producers()) {
                Optional<List<String>> read = scopeReadBy(build, producer);
                if (read.isEmpty()) unreadable.add(producer);
                else if (!read.get().equals(row.scopedBy()))
                    parted.add(row.artifact() + " by " + row.scopedBy() + ", " + producer + " by " + read.get());
            }
        }

        assertThat("the sweep rows the build's artifact table holds, against the sweeps the roster registers",
            rows.stream().map(SweepRow::artifact).sorted().toList(), equalTo(registeredSweeps()));
        assertThat("no sweep row is scoped by any property, which would leave the clause below holding over "
            + "nothing", rows.stream().anyMatch(row -> !row.scopedBy().isEmpty()), is(true));
        assertThat("sweep producers neither the vanilla sweep JVM's table nor a JavaExec registration runs",
            unreadable, is(empty()));
        assertThat("sweep rows scoped by another property than the one their producer narrows by. The capture "
            + "reads only the row's, so a hand run narrowed by the producer's still captures its table as the "
            + "whole of it, and a run given the row's alone is suppressed with nothing narrowed", parted,
            is(empty()));
    }

    @Test
    @DisplayName("each sweep's capture row reads the directory its writer writes, in the layout the toolkit reads")
    void everySweepRowReadsTheDirectoryItsWriterWrites() {
        String build = BuildScripts.all();
        List<SweepRow> rows = sweepRows(build);
        List<String> offTheLayout = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<String> parted = new ArrayList<>();
        int declaring = 0;
        for (SweepRow row : rows) {
            String layout = SWEEP_OUTPUT_ROOT + row.artifact().substring(SWEEP_ID_PREFIX.length()) + SWEEP_OUTPUT_SUFFIX;
            if (!row.source().equals(layout)) offTheLayout.add(row.artifact() + " -> " + row.source());
            for (String producer : row.producers()) {
                Optional<Path> writer = BuildScripts.classRunBy(build, producer)
                    .map(name -> Path.of(VISUAL_SOURCES, name.replace('.', '/') + JAVA))
                    .filter(Files::isRegularFile);
                if (writer.isEmpty()) {
                    unreadable.add(producer);
                    continue;
                }
                Matcher declared = WRITES_UNDER.matcher(read(writer.get()));
                if (!declared.find()) continue;
                declaring++;
                String written = declared.group(1);
                if (!written.equals(row.source()))
                    parted.add(row.artifact() + " reads " + row.source() + ", its writer writes " + written);
            }
        }

        assertThat("the sweep rows the build's artifact table holds, against the sweeps the roster registers",
            rows.stream().map(SweepRow::artifact).sorted().toList(), equalTo(registeredSweeps()));
        assertThat("sweep producers whose writer the build does not name, or names outside " + VISUAL_SOURCES,
            unreadable, is(empty()));
        assertThat("no sweep writer declares the one directory it writes, which would leave the last clause "
            + "holding over nothing", declaring > 0, is(true));
        assertThat("sweep rows reading another directory than " + SWEEP_OUTPUT_ROOT + "<name>" + SWEEP_OUTPUT_SUFFIX
            + ", the directory name the toolkit attributes a table by, so a capture of one finds no table and "
            + "fails with MissingInput", offTheLayout, is(empty()));
        assertThat("sweep rows reading another directory than the one their writer writes, so a capture of one "
            + "finds no table and fails with MissingInput", parted, is(empty()));
    }

    /**
     * One sweep row of the build's artifact table.
     *
     * @param artifact the artifact id
     * @param producers the tasks that write the table
     * @param source the directory the capture reads the table from
     * @param scopedBy the project properties whose presence suppresses the capture
     */
    private record SweepRow(String artifact, List<String> producers, String source, List<String> scopedBy) {}

    /**
     * Reads every sweep row of the build's artifact table.
     *
     * @param build the build's text
     * @return the rows, in table order
     */
    private static List<SweepRow> sweepRows(String build) {
        return SWEEP_ROW.matcher(build).results()
            .map(row -> new SweepRow(row.group(1), quoted(row.group(2)), row.group(3),
                row.group(4) == null ? List.of() : quoted(row.group(4))))
            .toList();
    }

    /**
     * Reads the values of a list the build types inline.
     *
     * @param list the list's text between its parentheses
     * @return the values, in order
     */
    private static List<String> quoted(String list) {
        return QUOTED.matcher(list).results().map(hit -> hit.group(1)).toList();
    }

    /**
     * Returns every sweep the artifact roster registers.
     *
     * @return the ids, sorted
     */
    private static List<String> registeredSweeps() {
        return ParityArtifacts.ALL.stream().map(ParityArtifacts.Registration::id)
            .filter(id -> id.startsWith(SWEEP_ID_PREFIX)).sorted().toList();
    }

    /**
     * Reads the properties one registered producer narrows its sweep by.
     *
     * <p>A producer of the vanilla sweep JVM is narrowed by the property its row of that JVM's table
     * names. Any other reads its properties in its own registration.
     *
     * @param build the build's text
     * @param producer the task name
     * @return the property names, in the order the build reads them, or empty when the vanilla sweep JVM
     *     runs nothing for the producer and the build registers no {@code JavaExec} by that name
     */
    private static Optional<List<String>> scopeReadBy(String build, String producer) {
        Matcher shared = Pattern.compile(String.format(SHARED_JVM_SCOPE, Pattern.quote(producer))).matcher(build);
        if (shared.find()) return Optional.of(shared.group(1) == null ? List.of() : List.of(shared.group(1)));
        return BuildScripts.javaExecOf(build, producer).map(registration ->
            READS_A_PROPERTY.matcher(registration).results().map(hit -> hit.group(1)).distinct().toList());
    }

    /**
     * The writer file each sweep producer runs, beside the producers whose writer could not be read.
     *
     * @param writers the writer file names, sorted
     * @param unreadable the producers the build file neither registers a {@code JavaExec} naming a
     *     main class for nor runs in the vanilla sweep JVM
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
                Optional<String> className = BuildScripts.classRunBy(build, producer);
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
     * Returns every sweep writer's file name the directory holds.
     *
     * @return the file names, sorted
     */
    private static List<String> writerFilesInTheDirectory() {
        try (Stream<Path> files = Files.list(HOME)) {
            return files.map(file -> file.getFileName().toString())
                .filter(name -> name.endsWith("ParitySweep" + JAVA))
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
