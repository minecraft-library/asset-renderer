package lib.minecraft.renderer.parity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import lib.minecraft.renderer.support.BuildScripts;

/**
 * Relates the Java artifact roster to the store's own index.
 *
 * <p>The two are written independently on purpose. {@link ParityArtifacts} is the taxonomy
 * transcribed into Java; {@code index.json} is what the toolkit writes and rewrites on every
 * promotion. Neither is derived from the other, so an artifact appearing in one and not the other is
 * a decision somebody skipped rather than a typo that resolves itself - and that is the check whose
 * absence let a superseded manifest be cited as current for three phases.
 *
 * <p>It also asserts the partition is <b>exact</b>: an id in two maps would give one artifact two
 * homes, and an id in none would be an artifact nothing can find.
 *
 * <p>One artifact's <b>membership</b> is written twice for the same reason its registration is, and
 * is checked here on the same grounds. {@code manifest.visual} is defined as the union of a set of
 * {@code cache/visual} sub-directories, named once in the build file beside the task that writes each
 * one and again in the toolkit that walks them. The drift there is one-sided and therefore the
 * dangerous kind: the walk raises for a member directory that is absent, so removing a producer
 * fails loudly, while adding one to the build alone lands a render inside the artifact's declared
 * source and outside its manifest.
 *
 * <p>The membership and the promoted baseline are related here too, in the one direction a green
 * suite can hold: a baseline hashing a sub-tree the membership has dropped can never be reproduced,
 * where a membership naming a sub-tree the baseline predates is what a capture reports as added and
 * a promotion clears.
 *
 * <p>Every column the index carries is read back against what it describes: a stored row's digest
 * against the bytes of the file it names, a {@code sources} row's class against the source tree, an
 * {@code external} home against the same tree when it is spelled as a class - that column's grammar
 * is free text, and a directory or a prose home has nothing to resolve - and a {@code pointers}
 * row's citation against the document it points into. The store's own directory is walked beside
 * them, for the file no row names at all. The digest is written by every promotion and the homes and
 * citations by hand, and until these cases nothing read any of them back.
 */
@DisplayName("The parity index registers every artifact exactly once")
final class ParityIndexTest {

    /** The index's four maps, in the order the store writes them. */
    private static final Map<String, ParityArtifacts.Home> MAPS = Map.of(
        "artifacts", ParityArtifacts.Home.STORE,
        "pointers", ParityArtifacts.Home.POINTER,
        "external", ParityArtifacts.Home.EXTERNAL,
        "sources", ParityArtifacts.Home.SOURCE);

    /** Where a Java class named by an index row can live, in the order they are tried. */
    private static final List<Path> SOURCE_ROOTS =
        List.of(Path.of("src/test/java"), Path.of("src/main/java"), Path.of("src/jmh/java"));

    /**
     * A fully-qualified Java class name, which is what tells one {@code external} home from another.
     *
     * <p>That column's grammar is free text - a directory, a path with a placeholder in it, "commit
     * messages" - so only the entries shaped like a class are held to resolving as one. Lower-case
     * segments then an upper-case leaf, and every other shipped home carries a separator or a space
     * that puts it outside this.
     */
    private static final Pattern JAVA_FQN =
        Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+\\.[A-Z][A-Za-z0-9_]*");

    /**
     * The index-row column carrying how many runs a first promotion of that artifact performs.
     *
     * <p>The one route the roster's number takes into the toolkit. Python owns no floor table: the
     * promotion reads this column and writes it back, and the capture defaults its stamped run count
     * to it, so the value the operator is shown and the value a promotion refuses below are one
     * number in one place.
     */
    private static final String DETERMINISM_FLOOR = "determinism_floor";

    /** Where the toolkit derives the summary object a captured sweep carries beside its rows. */
    private static final Path SWEEP_MODULE = Path.of("scripts/parity/sweep.py");

    /** That derivation's returned mapping, which is every member a citation of it can name. */
    private static final Pattern DERIVES_THE_SUMMARY =
        Pattern.compile("(?s)def summary\\(.*?return \\{(.*?)}");

    /** One member of it, as the mapping spells its key. */
    private static final Pattern SUMMARY_MEMBER = Pattern.compile("\"(\\w+)\":");

    /** The part of a citation naming a member of that object. */
    private static final String SUMMARY_NODE = "/summary/";

    /** The placeholder standing for any stored file, which is a whole pointer's file half. */
    private static final String ANY_STORED_FILE = "<artifact>";

    /** The placeholder standing for one stored sweep's stem. */
    private static final String ANY_SWEEP_STEM = "<sweep>";

    /** The placeholder standing for any member of the node it indexes, which is a row index. */
    private static final String ANY_MEMBER = "<n>";

    /** Where a stored sweep lives, which is what {@link #ANY_SWEEP_STEM} ranges over. */
    private static final String SWEEP_DIRECTORY = "sweeps/";

    /** The skill's own body, which works a registration against a named row of a named artifact. */
    private static final Path PARITY_SKILL = Path.of(".claude/skills/parity-gate/SKILL.md");

    /** One such worked registration, capturing the artifact it names and the row key it uses. */
    private static final Pattern WORKED_REGISTRATION =
        Pattern.compile("-Partifact=(\\S+) -Pkey=(\\S+)");

    /**
     * The pointer-homed artifacts whose documented location holds nothing in any file it names.
     *
     * <p>Pinned rather than tolerated. A pointer-homed artifact is nothing but its citation, so one
     * that dereferences nowhere is a value the store advertises and cannot produce. Recording the
     * set exactly is what makes both directions fail: a pointer going dead is a new name here, and
     * repairing one is a name that has to leave. How the members divide between those kinds is
     * measured rather than written down, by {@link #citationShapes} - and that measurement is read
     * back too, because a derived description is only better than a typed one while its derivation
     * is checked.
     *
     * <p>The three that were waiting on a promotion have had one and are gone from here.
     * {@code report.buckets}, {@code report.sum} and {@code report.wall-time} each had a writer and
     * no promoted baseline carrying what it wrote - a captured sweep derives the {@code summary}
     * object the first two cite, and a capture step stamps the wall time the third reads - so each
     * left this set on the commit whose promotion first carried its value, which is what happened.
     *
     * <p>What remains cites a node of that same {@code summary} object <b>no writer derives</b>, so
     * no capture can fill one and no promotion will move them. Which member falls where is measured
     * against the writer's own derivation by {@link #citationShapes} and printed on every run rather
     * than described here, and that measurement is read back too, because a derived description is
     * only better than a typed one while its derivation is checked.
     */
    private static final Set<String> POINTERS_THAT_REACH_NOTHING = new TreeSet<>(List.of(
        "report.coverage-gaps", "report.worst-list"));

    /**
     * What writes each of the store's own two root files, which no capture step covers.
     *
     * <p>The build file's capture table is the second operand for every other stored row and holds
     * no row for either of these, so the two are typed here: a promotion stamps the index in the
     * same act as the baseline it writes, and nothing writes the hand-authored rule roster. The
     * values rather than an exemption, because exempting the pair is what left the one of them that
     * does name a task unread, beside a sentence saying no producer writes either.
     */
    private static final Map<String, List<String>> ROOT_FILE_PRODUCERS = Map.of(
        "report.oracle-index", List.of("parityPromote"),
        "roster.blindness-rules", List.of());

    @Test
    @DisplayName("every registered artifact appears in exactly one map, and in the one its home names")
    void everyArtifactIsRegisteredOnce() {
        JsonObject index = ParityStore.read("report.oracle-index");

        List<String> missing = new ArrayList<>();
        List<String> misplaced = new ArrayList<>();
        for (ParityArtifacts.Registration registration : ParityArtifacts.ALL) {
            List<String> found = MAPS.keySet().stream().sorted()
                .filter(map -> index.getAsJsonObject(map).has(registration.id()))
                .toList();
            if (found.isEmpty()) {
                missing.add(registration.id() + " (expected in '" + mapFor(registration.home()) + "')");
                continue;
            }
            if (found.size() > 1) {
                misplaced.add(registration.id() + " is in " + String.join(" and ", found));
                continue;
            }
            if (MAPS.get(found.getFirst()) != registration.home())
                misplaced.add(registration.id() + " is in '" + found.getFirst()
                    + "' but ParityArtifacts homes it at " + registration.home());
        }

        assertThat("artifacts registered in ParityArtifacts but absent from index.json - "
            + "coining one is an edit to both", missing, is(empty()));
        assertThat("artifacts whose index.json placement disagrees with ParityArtifacts",
            misplaced, is(empty()));
    }

    @Test
    @DisplayName("the index registers nothing ParityArtifacts does not")
    void theIndexRegistersNothingExtra() {
        JsonObject index = ParityStore.read("report.oracle-index");

        Set<String> registered = new TreeSet<>();
        for (String map : MAPS.keySet())
            registered.addAll(index.getAsJsonObject(map).keySet());

        Set<String> unknown = new TreeSet<>(registered);
        unknown.removeAll(ParityArtifacts.BY_ID.keySet());
        assertThat("index.json registers ids ParityArtifacts does not know; an artifact cannot enter "
            + "the store without being declared", unknown, is(empty()));

        assertThat("the index and the roster hold the same number of artifacts",
            registered.size(), equalTo(ParityArtifacts.ALL.size()));
    }

    @Test
    @DisplayName("every index row declares the determinism floor its registration declares")
    void everyRowsFloorIsTheRegisteredOne() {
        JsonObject artifacts = ParityStore.read("report.oracle-index").getAsJsonObject("artifacts");

        List<String> undeclared = new ArrayList<>();
        List<String> disagreeing = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : artifacts.entrySet()) {
            int registered = ParityArtifacts.of(entry.getKey()).determinismFloor();
            JsonObject row = entry.getValue().getAsJsonObject();
            if (!row.has(DETERMINISM_FLOOR)) {
                undeclared.add(entry.getKey());
                continue;
            }
            int declared = row.get(DETERMINISM_FLOOR).getAsInt();
            if (declared != registered)
                disagreeing.add(entry.getKey() + ": index " + declared + ", roster " + registered);
        }

        assertThat("stored artifacts, of which there are none - which would leave this check vacuous",
            artifacts.keySet(), is(not(empty())));
        assertThat("stored rows carrying no " + DETERMINISM_FLOOR + ". The promotion reads the floor "
            + "off the row it is replacing and writes it back, and the capture defaults its run "
            + "count to it, so a row without one refuses both - which is the loud half. The quiet "
            + "half is that this column is the only route the roster's number takes into the "
            + "toolkit", undeclared, is(empty()));
        assertThat("rows whose floor is not the one the roster registers. The number is published to "
            + "an operator out of the roster and enforced out of this column, and the two were two "
            + "hardcoded tables: nine artifacts published a floor of 1 while a promotion refused "
            + "them below 2, so the published number was unreachable and the refusal landed after "
            + "the capture that earned it", disagreeing, is(empty()));
    }

    @Test
    @DisplayName("every index row's digest still re-derives from the file it names")
    void everyRowsDigestReDerivesFromItsFile() {
        JsonObject artifacts = ParityStore.read("report.oracle-index").getAsJsonObject("artifacts");

        List<String> undeclared = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        List<String> drifted = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, JsonElement> entry : artifacts.entrySet()) {
            JsonObject row = entry.getValue().getAsJsonObject();
            if (!row.has("file")) continue;
            if (!row.has("sha256")) {
                // A promoted row always gets one. Without this arm, dropping the column is how a
                // file leaves the check rather than how it fails it.
                if (row.get("baselined").getAsBoolean()) undeclared.add(entry.getKey());
                continue;
            }
            Path file = ParityStore.PRODUCTION.resolve(row.get("file").getAsString());
            if (!Files.isRegularFile(file)) {
                absent.add(entry.getKey());
                continue;
            }
            checked++;
            if (!ParityJson.sha256Normalized(file).equals(row.get("sha256").getAsString()))
                drifted.add(entry.getKey());
        }

        assertThat("index rows carrying a digest, of which there are none - which would leave this "
            + "check vacuous", checked, is(greaterThan(0)));
        assertThat("baselined rows with a file and no digest column; the column is what a hand-edit "
            + "is detectable against, so a row without one is outside every check there is",
            undeclared, is(empty()));
        assertThat("rows naming a file the store does not hold", absent, is(empty()));
        assertThat("rows whose file no longer hashes to the digest recorded when it was promoted. "
            + "The promotion writes both together, so they can only part company by the file being "
            + "edited outside one - and an edited baseline is not a mover, it is a different "
            + "question that every later comparison answers as though it were one", drifted, is(empty()));
    }

    @Test
    @DisplayName("no file in the store is unreferenced by the index")
    void everyStoreFileIsNamedBySomeRow() throws IOException {
        JsonObject artifacts = ParityStore.read("report.oracle-index").getAsJsonObject("artifacts");

        Set<String> referenced = new TreeSet<>();
        for (JsonElement row : artifacts.asMap().values())
            if (row.getAsJsonObject().has("file"))
                referenced.add(row.getAsJsonObject().get("file").getAsString());

        List<String> orphans;
        try (Stream<Path> walk = Files.walk(ParityStore.PRODUCTION)) {
            orphans = walk.filter(Files::isRegularFile)
                .map(file -> ParityStore.PRODUCTION.relativize(file).toString().replace('\\', '/'))
                .filter(path -> path.endsWith(".json"))
                .filter(path -> !referenced.contains(path))
                .sorted()
                .toList();
        }

        assertThat("the index names no file at all, which would make every file below an orphan",
            referenced, is(not(empty())));
        assertThat("JSON files in the store that no index row names. The store is walked here "
            + "rather than derived from the id set, because the failure this catches is a file the "
            + "ids no longer reach - a rename promotes the new path and leaves the old one behind, "
            + "and every id-keyed check passes over it without ever opening the directory",
            orphans, is(empty()));
    }

    @Test
    @DisplayName("the roster and the build file name the same producers for every stored artifact")
    void theRosterAndTheBuildTableAgreeOnProducers() {
        Map<String, List<String>> build = buildFileArtifactRows();
        Map<String, List<String>> expected = new TreeMap<>(build);
        expected.putAll(ROOT_FILE_PRODUCERS);
        Map<String, List<String>> roster = ParityArtifacts.ALL.stream()
            .filter(registration -> registration.home() == ParityArtifacts.Home.STORE)
            .collect(Collectors.toMap(ParityArtifacts.Registration::id,
                ParityArtifacts.Registration::producers, (first, second) -> first, TreeMap::new));
        List<String> capturedRootFiles = ROOT_FILE_PRODUCERS.keySet().stream()
            .filter(build::containsKey)
            .sorted()
            .toList();

        assertThat("build.gradle.kts declares no artifact rows, which would leave this check vacuous",
            build.keySet(), is(not(empty())));
        assertThat("the edge a row's capture step takes on the producers below. The column these "
            + "rows fill is rendered into the skill under a sentence saying what a capture of the "
            + "row runs, and the two edges are not one thing: parityCapture depends on the "
            + "producers of every row it captures, and the step only orders itself after them. A "
            + "step that DEPENDED on its producers would widen a hand run of one of them into all "
            + "of them: a tooling flow is finalized by its row's step, so running one flow of an "
            + "eight-flow row would run the other seven", BuildScripts.all(),
            containsString("mustRunAfter(spec.producers)"));
        assertThat("root files the build file's capture table now holds a row for. The pair below is "
            + "merged over that table, so a row arriving for one would be silently overwritten by the "
            + "typed value and the comparison would stop reading the build file for it", capturedRootFiles,
            is(empty()));
        assertThat("what the Java roster says writes each stored artifact, against the rows the "
            + "build file runs. The roster's whole value is being an independently written second "
            + "opinion, and the producer column is rendered into the skill's artifact reference - "
            + "which is the column a reader with a zero-second budget is told to read. A row naming "
            + "one flow where the build runs eight sends that reader to a task that rewrites part of "
            + "the artifact and captures the rest stale, and the capture still hashes cleanly "
            + "because every declared member exists. The store's own two root files have no capture "
            + "step and no row in that table, so each is held to what writes it instead", roster,
            equalTo(expected));
    }

    @Test
    @DisplayName("every lines citation brackets the one site its anchor names")
    void everyLinesCitationBracketsItsRoster() {
        JsonObject sources = ParityStore.read("report.oracle-index").getAsJsonObject("sources");

        List<String> cited = new ArrayList<>();
        List<String> unpaired = new ArrayList<>();
        List<String> drifted = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : sources.entrySet()) {
            JsonObject row = entry.getValue().getAsJsonObject();
            List<String> ranges = citedRanges(row);
            List<String> anchors = citedAnchors(row);
            if (ranges.isEmpty() && anchors.isEmpty()) continue;
            if (ranges.size() != anchors.size()) {
                unpaired.add(entry.getKey() + " cites " + ranges.size() + " ranges against "
                    + anchors.size() + " anchors");
                continue;
            }
            List<String> lines = sourceLines(row.get("test_class").getAsString());
            for (int position = 0; position < ranges.size(); position++) {
                String anchor = anchors.get(position);
                cited.add(entry.getKey() + " " + ranges.get(position) + " -> `" + anchor + "`");
                citationFault(lines, ranges.get(position), anchor)
                    .ifPresent(fault -> drifted.add(cited.getLast() + ": " + fault));
            }
        }

        assertThat("no sources row carries a lines citation, so this case has no operand; it needs a "
            + "different shape rather than deleting", cited, is(not(empty())));
        assertThat("rows whose ranges and anchors do not pair off one for one. A range with no "
            + "anchor beside it is a number nothing can be checked against, which is the state every "
            + "one of these was in while three of the five pointed somewhere else", unpaired,
            is(empty()));
        assertThat("lines citations that do not bracket the site their anchor names. A range is a "
            + "reader's only pointer at the roster inside a file and nothing renders it, so it "
            + "drifts in silence: three of five opened on a closing brace, a javadoc terminator and "
            + "a blank line, and one of those cut two of seven armour subjects off the end of the "
            + "roster it claims to bracket. The anchor is what makes the range checkable - it has to "
            + "occur once in the file, the range has to open on the line carrying it, and the range "
            + "has to close what that line opens. Bracket balance alone is not that: every method "
            + "and every statement in the file balances, so a citation that slid onto a neighbouring "
            + "declaration would read as sound", drifted, is(empty()));
    }

    /**
     * Returns the line ranges one {@code sources} row cites, if any.
     *
     * @param row the index row
     * @return the ranges, each as {@code "N"} or {@code "N-M"}
     */
    private static List<String> citedRanges(JsonObject row) {
        if (!row.has("lines")) return List.of();
        return Stream.of(row.get("lines").getAsString().split(",")).map(String::trim).toList();
    }

    /**
     * Returns the anchors one {@code sources} row cites, in the order its ranges name them.
     *
     * @param row the index row
     * @return the anchors, each the text its range's first line carries
     */
    private static List<String> citedAnchors(JsonObject row) {
        if (!row.has("anchor")) return List.of();
        return row.getAsJsonArray("anchor").asList().stream().map(JsonElement::getAsString).toList();
    }

    /**
     * Returns what is wrong with one citation, or empty when it points where it says it does.
     *
     * <p>Three things have to hold and each is a way a shipped citation was already wrong. The
     * anchor occurs once in the file, so it names a site rather than a shape; the range opens on the
     * line carrying it, so it starts where the roster starts; and the range closes what that line
     * opened, so it ends where the roster ends.
     *
     * @param lines the source file's lines
     * @param range the citation, as {@code "N"} or {@code "N-M"}
     * @param anchor the text the range's first line has to carry
     * @return the fault, or empty
     */
    private static Optional<String> citationFault(List<String> lines, String range, String anchor) {
        List<Integer> sites = new ArrayList<>();
        for (int line = 1; line <= lines.size(); line++)
            if (lines.get(line - 1).contains(anchor)) sites.add(line);
        if (sites.size() != 1)
            return Optional.of("the anchor is on " + sites.size() + " lines, so it names no one site");

        int dash = range.indexOf('-');
        int from = Integer.parseInt(dash < 0 ? range : range.substring(0, dash));
        if (from < 1 || from > lines.size()) return Optional.of("the range opens outside the file");
        if (from != sites.getFirst()) {
            return Optional.of("line " + from + " is `" + lines.get(from - 1).trim()
                + "`, and the anchor is on line " + sites.getFirst());
        }
        if (!bracketsOneConstruct(lines, range))
            return Optional.of("the range does not close what line " + from + " opens");
        return Optional.empty();
    }

    /**
     * Returns whether a cited line range opens and closes exactly one bracketed construct.
     *
     * <p>A multi-line range has to open one on its first line, stay inside it through the middle, and
     * close it on its last; a one-line range has to be a non-blank line that opens and closes
     * whatever it opens. That is what the END of a citation of a declaration is, the anchor having
     * fixed where it starts.
     *
     * @param lines the source file's lines
     * @param range the citation, as {@code "N"} or {@code "N-M"}
     * @return whether the range brackets one construct
     */
    private static boolean bracketsOneConstruct(List<String> lines, String range) {
        int dash = range.indexOf('-');
        int from = Integer.parseInt(dash < 0 ? range : range.substring(0, dash));
        int to = dash < 0 ? from : Integer.parseInt(range.substring(dash + 1));
        if (from < 1 || to < from || to > lines.size()) return false;
        if (from == to) return !lines.get(from - 1).isBlank() && bracketDepth(lines.get(from - 1)) == 0;

        int depth = bracketDepth(lines.get(from - 1));
        if (depth <= 0) return false;
        for (int line = from + 1; line < to; line++) {
            depth += bracketDepth(lines.get(line - 1));
            if (depth <= 0) return false;
        }
        return depth + bracketDepth(lines.get(to - 1)) == 0;
    }

    /**
     * Returns how much deeper one line leaves the bracket nesting.
     *
     * @param line the source line
     * @return openers minus closers, over round, square and curly brackets alike
     */
    private static int bracketDepth(String line) {
        int depth = 0;
        for (char character : line.toCharArray()) {
            if (character == '(' || character == '[' || character == '{') depth++;
            if (character == ')' || character == ']' || character == '}') depth--;
        }
        return depth;
    }

    /**
     * Returns the lines of the source file a fully-qualified class name resolves to.
     *
     * @param className the fully-qualified name
     * @return its lines, in file order
     */
    private static List<String> sourceLines(String className) {
        Path file = SOURCE_ROOTS.stream()
            .map(root -> root.resolve(className.replace('.', '/') + ".java"))
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new AssertionError(className + " resolves under none of " + SOURCE_ROOTS));
        try {
            return Files.readAllLines(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Returns the build file's artifact table, each id against the producers its row runs.
     *
     * <p>Read as text because the table is Kotlin DSL, the same way this suite reads the two member
     * lists: a generated third copy would itself need a gate.
     *
     * @return the producers by artifact id
     */
    private static Map<String, List<String>> buildFileArtifactRows() {
        Map<String, List<String>> rows = new TreeMap<>();
        Matcher row = Pattern.compile("ParityArtifact\\(\"([^\"]+)\",\\s*listOf\\(([^)]*)\\)")
            .matcher(BuildScripts.all());
        while (row.find()) {
            rows.put(row.group(1), Pattern.compile("\"([^\"]+)\"").matcher(row.group(2))
                .results().map(hit -> hit.group(1)).toList());
        }
        return rows;
    }

    @Test
    @DisplayName("every source-homed artifact names a class that is on disk")
    void everySourceRowResolvesToAFile() {
        JsonObject sources = ParityStore.read("report.oracle-index").getAsJsonObject("sources");

        List<String> unresolved = sources.entrySet().stream()
            .filter(entry -> !resolvesAsJavaClass(entry.getValue().getAsJsonObject()
                .get("test_class").getAsString()))
            .map(entry -> entry.getKey() + " -> "
                + entry.getValue().getAsJsonObject().get("test_class").getAsString())
            .sorted()
            .toList();

        assertThat("the index homes nothing in Java source, which would leave this check vacuous",
            sources.keySet(), is(not(empty())));
        assertThat("source-homed artifacts whose class resolves under none of " + SOURCE_ROOTS
            + ". The column is where a reader is sent to find the value, and a moved or renamed "
            + "class sends them nowhere while every id-keyed check stays green", unresolved, is(empty()));
    }

    @Test
    @DisplayName("every external home shaped like a Java class resolves to one")
    void everyExternalClassHomeResolves() {
        JsonObject external = ParityStore.read("report.oracle-index").getAsJsonObject("external");

        List<String> homes = external.entrySet().stream()
            .map(entry -> entry.getValue().getAsJsonObject().get("home").getAsString())
            .filter(home -> JAVA_FQN.matcher(home).matches())
            .sorted()
            .toList();
        List<String> unresolved = homes.stream().filter(home -> !resolvesAsJavaClass(home)).toList();

        assertThat("no external home is shaped like a Java class, so this check has no operand; it "
            + "needs a different shape rather than deleting", homes, is(not(empty())));
        assertThat("external homes naming a Java class that is on no source path", unresolved, is(empty()));
    }

    @Test
    @DisplayName("every pointer's file half names files the index itself registers")
    void everyPointerNamesFilesTheStoreHolds() {
        JsonObject index = ParityStore.read("report.oracle-index");
        Set<String> stored = storedFiles(index.getAsJsonObject("artifacts"));

        List<String> reachNoFile = new ArrayList<>();
        List<String> expandToNothing = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry
            : index.getAsJsonObject("pointers").entrySet()) {
            String pointer = entry.getValue().getAsJsonObject().get("pointer").getAsString();
            List<String> files = expand(pointer.substring(0, pointer.indexOf('#')), stored);
            if (files.isEmpty()) expandToNothing.add(entry.getKey() + " -> " + pointer);
            files.stream().filter(file -> !stored.contains(file))
                .forEach(file -> reachNoFile.add(entry.getKey() + " -> " + file));
        }

        assertThat("the index registers no pointer, which would leave this check vacuous",
            index.getAsJsonObject("pointers").keySet(), is(not(empty())));
        assertThat("pointers whose placeholder expands over nothing at all. A template is only a "
            + "citation while some file answers it, and one that answers none reads identically to "
            + "one nobody has looked at", expandToNothing, is(empty()));
        assertThat("pointers naming a file the index registers no row for. The file half is half a "
            + "citation and it is the half a rename breaks: the row keeps pointing at the old path "
            + "while every id-keyed check passes, which is how a pointer column goes stale without "
            + "one of them noticing", reachNoFile, is(empty()));
    }

    @Test
    @DisplayName("the pointers that dereference are exactly the ones recorded as dereferencing")
    void theDereferencingPointersAreTheOnesRecordedAsSuch() {
        JsonObject index = ParityStore.read("report.oracle-index");
        Set<String> stored = storedFiles(index.getAsJsonObject("artifacts"));

        Set<String> reaches = new TreeSet<>();
        Set<String> reachesNothing = new TreeSet<>();
        for (Map.Entry<String, JsonElement> entry
            : index.getAsJsonObject("pointers").entrySet()) {
            String pointer = entry.getValue().getAsJsonObject().get("pointer").getAsString();
            int hash = pointer.indexOf('#');
            boolean found = expand(pointer.substring(0, hash), stored).stream()
                .anyMatch(file -> !dereference(readStoreFile(file), pointer.substring(hash + 1))
                    .isEmpty());
            (found ? reaches : reachesNothing).add(entry.getKey());
        }

        // Printed on a green run as well as a red one, because part of the recorded set is residue
        // rather than a state anybody chose, and a set pinned in a constant is read by whoever
        // opens this file rather than by whoever runs the suite. Which part is measured against the
        // writer's own derivation: a citation asking that object for a member it does not derive is
        // one no capture can ever fill, where the rest are waiting on a promotion.
        Set<String> derived = derivedSummaryMembers();
        List<String> unfillable = new ArrayList<>();
        for (String artifact : reachesNothing) {
            String pointer = index.getAsJsonObject("pointers").getAsJsonObject(artifact)
                .get("pointer").getAsString();
            int member = pointer.indexOf(SUMMARY_NODE);
            if (member >= 0 && !derived.contains(pointer.substring(member + SUMMARY_NODE.length())))
                unfillable.add(artifact);
        }
        System.out.printf("registered citations no writer can fill: %s - the captured sweep summary "
            + "derives %s, and a member outside that set is owed a writer before its registration "
            + "can resolve%n", unfillable, derived);

        assertThat("no pointer dereferences, so the walk below is broken rather than the column",
            reaches, is(not(empty())));
        assertThat("pointers whose documented location holds nothing in any file it expands over. "
            + "A pointer-homed artifact IS its citation, so one reaching nothing is a value the "
            + "store says it can produce and cannot. The recorded set is "
            + POINTERS_THAT_REACH_NOTHING.size() + ": "
            + citationShapes(index.getAsJsonObject("pointers"), POINTERS_THAT_REACH_NOTHING, stored)
            + ". It is pinned rather than allowed, so one joining them fails here and so does "
            + "repairing one of them", reachesNothing, equalTo(POINTERS_THAT_REACH_NOTHING));
    }

    @Test
    @DisplayName("how far a citation is described as resolving is how far the store answers it")
    void theDescribedDepthIsHowFarTheStoreAnswers() {
        JsonObject index = ParityStore.read("report.oracle-index");
        Set<String> stored = storedFiles(index.getAsJsonObject("artifacts"));
        Set<String> registered = new TreeSet<>(index.getAsJsonObject("pointers").keySet());

        Map<String, String> depth = new TreeMap<>();
        Set<String> describedWhole = new TreeSet<>();
        Set<String> answeredWhole = new TreeSet<>();
        for (Map.Entry<String, JsonElement> entry
            : index.getAsJsonObject("pointers").entrySet()) {
            String pointer = entry.getValue().getAsJsonObject().get("pointer").getAsString();
            String fragment = pointer.substring(pointer.indexOf('#') + 1);
            String resolves = resolvingPrefix(pointer, stored);
            depth.put(entry.getKey(), resolves.isEmpty() ? "(nothing)" : resolves);
            if (resolves.equals(fragment)) describedWhole.add(entry.getKey());
            if (expand(pointer.substring(0, pointer.indexOf('#')), stored).stream()
                .anyMatch(file -> !dereference(readStoreFile(file), fragment).isEmpty()))
                answeredWhole.add(entry.getKey());
        }

        assertThat("the store answers no citation's whole fragment, so there is no citation the walk "
            + "has to run to the end and the comparison below holds for a walk that never starts",
            answeredWhole, is(not(empty())));
        assertThat("the store answers every citation whole, so no citation has a stopping point "
            + "short of its own fragment and the comparison below holds for a walk that never stops",
            answeredWhole, is(not(equalTo(registered))));
        assertThat("the citations the depth walk describes as resolving whole, against the ones the "
            + "store actually answers whole. The walk extends the fragment one segment at a time and "
            + "stops at the first prefix no file the pointer names answers, so the prefix it "
            + "returns is the whole fragment exactly when some file answers all of it - and that "
            + "equivalence is the entire warrant for describing a dead citation by where it died. "
            + "Without the stop the walk returns every citation's own whole fragment, and the "
            + "sentence beside the recorded set then says a pointer dying on its first segment "
            + "resolves as far as a container no stored file carries: the same set, described "
            + "falsely. Measured depths: " + depth, describedWhole, equalTo(answeredWhole));
    }

    @Test
    @DisplayName("every registration the skill works through names a row the stored artifact holds")
    void theSkillsWorkedRegistrationsNameRowsThatExist() {
        String skill = read(PARITY_SKILL);

        List<String> unknown = new ArrayList<>();
        List<String> worked = new ArrayList<>();
        Matcher example = WORKED_REGISTRATION.matcher(skill);
        while (example.find()) {
            String artifact = example.group(1);
            String wanted = example.group(2);
            worked.add(artifact + " -> " + wanted);
            JsonObject stored = ParityStore.read(artifact);
            assertThat("the skill works a registration against " + artifact + ", which stores no "
                + "`rows` array to look a key up in; this check reads a row-keyed artifact and has "
                + "to grow a second shape rather than pass over one", stored.has("rows"), is(true));
            String column = stored.get("key").getAsString();
            if (stored.getAsJsonArray("rows").asList().stream().noneMatch(row ->
                wanted.equals(row.getAsJsonObject().get(column).getAsString()))) unknown.add(worked.getLast());
        }

        assertThat("the skill works no registration at all, so the documented flow shows no example "
            + "of the one verdict row that passes with movers in it", worked, is(not(empty())));
        assertThat("worked registrations naming a row key the artifact does not carry. A key "
            + "matching no row is written, counted in the `N mover(s) registered` line and never "
            + "consulted again - so an operator copying the documented line is told it worked and "
            + "still goes RED on the row they meant", unknown, is(empty()));
    }

    /**
     * Returns the members the toolkit derives into a captured sweep's summary object.
     *
     * @return the member names, sorted
     */
    private static Set<String> derivedSummaryMembers() {
        Matcher derives = DERIVES_THE_SUMMARY.matcher(read(SWEEP_MODULE));
        if (!derives.find())
            throw new AssertionError(SWEEP_MODULE + " derives no summary object to read members off");
        Set<String> members = new TreeSet<>();
        Matcher member = SUMMARY_MEMBER.matcher(derives.group(1));
        while (member.find()) members.add(member.group(1));
        return members;
    }

    /**
     * Describes a set of pointers by tallying how far into its own fragment each of them gets.
     *
     * <p>Both halves of every clause are measured, and that is the point of the method. A count
     * typed into a message is one nothing re-derives, and so is the wording beside it: a member
     * moving from one kind to another leaves a hand-written partition adding up while describing
     * the wrong split, which reads exactly like a correct one. Here the kinds are not a table to
     * mislabel - each is the deepest prefix of the citation that the store answers, taken off the
     * store, so a pointer whose node moves is described by where it now dies.
     *
     * <p>That depth is what the kinds in the set are: a citation resolving no segment names a
     * container no file carries, and one resolving all but its last segment names a container that
     * is there without the member it asks of it.
     *
     * @param pointers the index's pointers map
     * @param names the ids to describe
     * @param stored every store-relative file the index registers
     * @return one clause per depth present, each a count and where those citations stop
     */
    private static String citationShapes(JsonObject pointers, Set<String> names, Set<String> stored) {
        Map<String, Integer> tally = new TreeMap<>();
        for (String name : names)
            tally.merge(resolvingPrefix(pointers.getAsJsonObject(name).get("pointer").getAsString(),
                stored), 1, Integer::sum);
        return tally.entrySet().stream()
            .map(depth -> depth.getValue() + (depth.getKey().isEmpty()
                ? " resolving no segment of their fragment at all"
                : " resolving as far as `" + depth.getKey() + "` and no further"))
            .collect(Collectors.joining(", "));
    }

    /**
     * Returns the longest leading part of a pointer's fragment that some file it names answers.
     *
     * @param pointer the whole pointer, file half included
     * @param stored every store-relative file the index registers
     * @return that prefix, leading slash included, or the empty string when no segment resolves
     */
    private static String resolvingPrefix(String pointer, Set<String> stored) {
        int hash = pointer.indexOf('#');
        List<JsonElement> files =
            expand(pointer.substring(0, hash), stored).stream().map(ParityIndexTest::readStoreFile).toList();
        String resolves = "";
        StringBuilder prefix = new StringBuilder();
        for (String segment : pointer.substring(hash + 1).replaceFirst("^/", "").split("/")) {
            String deeper = prefix.append('/').append(segment).toString();
            if (files.stream().allMatch(file -> dereference(file, deeper).isEmpty())) break;
            resolves = deeper;
        }
        return resolves;
    }

    /**
     * Returns whether a fully-qualified class name has a source file under any source root.
     *
     * @param className the fully-qualified name
     * @return whether one of the roots holds it
     */
    private static boolean resolvesAsJavaClass(String className) {
        return SOURCE_ROOTS.stream()
            .anyMatch(root -> Files.isRegularFile(root.resolve(className.replace('.', '/') + ".java")));
    }

    /**
     * Returns every store-relative file the index's {@code artifacts} map names.
     *
     * @param artifacts the index's artifacts map
     * @return the registered paths
     */
    private static Set<String> storedFiles(JsonObject artifacts) {
        return artifacts.asMap().values().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(row -> row.has("file"))
            .map(row -> row.get("file").getAsString())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Expands a pointer's file half over the files it stands for.
     *
     * <p>Two templates are in use and each stands for a different set: {@code <sweep>} for the
     * stem of every stored sweep, and a whole file half of {@code <artifact>} for every stored file
     * there is. Expanded against the index's own rows rather than against the directory, so a
     * template that outlives the files it named fails as a citation rather than as a walk.
     *
     * @param filePart the pointer's text before the {@code #}
     * @param stored every store-relative file the index registers
     * @return the concrete paths, which is the one path itself when it carries no placeholder
     */
    private static List<String> expand(String filePart, Set<String> stored) {
        if (filePart.equals(ANY_STORED_FILE)) return List.copyOf(stored);
        if (filePart.contains(ANY_SWEEP_STEM))
            return stored.stream()
                .filter(file -> file.startsWith(SWEEP_DIRECTORY))
                .map(file -> filePart.replace(ANY_SWEEP_STEM,
                    file.substring(SWEEP_DIRECTORY.length(), file.lastIndexOf('.'))))
                .toList();
        return List.of(filePart);
    }

    /**
     * Resolves a JSON pointer, with {@code <n>} standing for any member of the node it indexes.
     *
     * <p>A list rather than one node, because {@code <n>} is a row index: a pointer naming a
     * column of every row reaches as many nodes as there are rows, and it dereferences while any
     * one of them carries it.
     *
     * @param from the document to walk
     * @param pointer the pointer, leading slash included
     * @return the nodes reached, empty when the pointer names nothing here
     */
    private static List<JsonElement> dereference(JsonElement from, String pointer) {
        List<JsonElement> at = new ArrayList<>(List.of(from));
        for (String segment : pointer.replaceFirst("^/", "").split("/")) {
            List<JsonElement> next = new ArrayList<>();
            for (JsonElement node : at) {
                if (ANY_MEMBER.equals(segment)) {
                    if (node.isJsonArray()) node.getAsJsonArray().forEach(next::add);
                    if (node.isJsonObject()) next.addAll(node.getAsJsonObject().asMap().values());
                } else if (node.isJsonObject() && node.getAsJsonObject().has(segment)) {
                    next.add(node.getAsJsonObject().get(segment));
                }
            }
            at = next;
            if (at.isEmpty()) return at;
        }
        return at;
    }

    /**
     * Reads one store file by its index-registered path.
     *
     * @param file the store-relative path
     * @return the parsed document
     */
    private static JsonElement readStoreFile(String file) {
        return JsonParser.parseString(
            ParityStore.readNormalized(ParityStore.PRODUCTION.resolve(file)));
    }

    @Test
    @DisplayName("the build file and the toolkit declare the same visual members")
    void theVisualMembershipIsWrittenOnceInTwoPlaces() {
        List<String> build = buildFileVisualMembers();
        List<String> toolkit = toolkitVisualMembers();

        assertThat("build.gradle.kts declares visualSweepProducers as an empty map, which would "
            + "leave this check vacuous", build, is(not(empty())));
        assertThat("manifest.py declares no members for manifest.visual, which would leave this "
            + "check vacuous", toolkit, is(not(empty())));
        assertThat("the cache/visual sub-trees the build file's producers write, against the ones "
            + "the toolkit walks when it builds manifest.visual. A directory only the build knows "
            + "about is a render inside the artifact's declared source that its manifest never "
            + "hashes, and nothing else in the tree relates the two lists", toolkit, equalTo(build));
    }

    @Test
    @DisplayName("the visual baseline names no sub-tree the membership no longer declares")
    void theVisualBaselineStaysInsideTheDeclaredMembership() {
        Set<String> declared = Set.copyOf(toolkitVisualMembers());
        Set<String> hashed = new TreeSet<>();
        for (JsonElement file : ParityStore.read("manifest.visual").getAsJsonArray("files"))
            hashed.add(file.getAsJsonObject().get("path").getAsString().split("/", 2)[0]);
        List<String> undeclared = hashed.stream().filter(name -> !declared.contains(name)).toList();
        List<String> unbaselined = declared.stream().filter(name -> !hashed.contains(name)).sorted().toList();
        // The other direction is not asserted, because it is what ADDING a member legitimately
        // creates: the baseline was promoted over the membership of its day, and until the next
        // promotion the new member's files are what the compare reports as `added`. Printed rather
        // than asserted, and printed with the act that clears it, so what the redefinition owes is a
        // command a reader can run rather than a state they have to infer.
        System.out.printf("declared visual members the baseline holds no row for: %s%s%n", unbaselined,
            unbaselined.isEmpty() ? "" : " - owed: " + Pins.rebaselineCommand("manifest.visual")
                + ". The capture depends on visualSweepSet; the commit ahead of it is what keeps the "
                + "baseline re-derivable, the compare between capture and promotion is what the "
                + "promotion applies, and -Preason has no default. Each is part of the command "
                + "rather than a step somebody is expected to know");

        assertThat("the visual baseline hashes no file, which would leave this check vacuous",
            hashed, is(not(empty())));
        assertThat("sub-trees the promoted baseline hashes that the membership no longer declares. "
            + "A renamed or retired producer leaves rows nothing can ever reproduce, and every "
            + "later compare reports them as dropped - which reads as a regression rather than as "
            + "the redefinition it is", undeclared, is(empty()));
    }

    /**
     * The {@code cache/visual} sub-trees {@code visualSweepProducers} maps its tasks onto.
     *
     * <p>Matched as whole {@code "task" to "directory"} pairs rather than as every literal in the
     * block, so the task half cannot be mistaken for a directory by a rule about how the two are
     * spelled.
     *
     * @return the directory names, sorted
     */
    private static List<String> buildFileVisualMembers() {
        String body = declarationBody(BuildScripts.all(),
            "val visualSweepProducers = mapOf(", "\n)");
        List<String> out = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"[^\"]+\"\\s+to\\s+\"([^\"]+)\"").matcher(body);
        while (matcher.find()) out.add(matcher.group(1));
        return out.stream().sorted().toList();
    }

    /**
     * The {@code cache/visual} sub-trees the toolkit walks for {@code manifest.visual}.
     *
     * @return the directory names, sorted
     */
    private static List<String> toolkitVisualMembers() {
        String body = declarationBody(read(Path.of("scripts/parity/manifest.py")),
            "\"manifest.visual\": (", ")");
        List<String> out = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(body);
        while (matcher.find()) out.add(matcher.group(1));
        return out.stream().sorted().toList();
    }

    /**
     * A declaration's body, from just after its opening to the first terminator.
     *
     * <p>Both operands are read as text because neither is a Java value: one is Kotlin DSL and the
     * other is Python, and the alternative is a third generated file that would itself need a gate.
     * The slice starts <b>after</b> the opening, so a marker that is itself quoted - a map key - does
     * not read as the first member of its own body.
     *
     * @param source the file's text
     * @param opening the declaration up to and including what opens its body
     * @param terminator what closes it
     * @return the body's text
     */
    private static String declarationBody(String source, String opening, String terminator) {
        int start = source.indexOf(opening);
        if (start < 0) throw new AssertionError("no declaration matching '" + opening + "'");
        int body = start + opening.length();
        int end = source.indexOf(terminator, body);
        if (end < 0) throw new AssertionError("'" + opening + "' is not closed by '" + terminator + "'");
        return source.substring(body, end);
    }

    /**
     * Reads a tracked file's text, by path because it is not on the classpath.
     *
     * @param file the repo-relative path
     * @return the file's text
     */
    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    @DisplayName("an empty store baselines nothing, and says so per artifact")
    void nothingIsBaselinedUntilItIsPromoted() {
        JsonObject artifacts = ParityStore.read("report.oracle-index").getAsJsonObject("artifacts");

        List<String> claimingBaselined = artifacts.entrySet().stream()
            .filter(entry -> entry.getValue().getAsJsonObject().get("baselined").getAsBoolean())
            .filter(entry -> !ParityStore.exists(entry.getKey()))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

        assertThat("artifacts the index calls baselined that have no file in the store; a comparison "
            + "against one of these would read as agreement", claimingBaselined, is(empty()));
    }

    @Test
    @DisplayName("an artifact with a file in the store is marked baselined")
    void aStoredValueIsAlwaysMarkedBaselined() {
        JsonObject artifacts = ParityStore.read("report.oracle-index").getAsJsonObject("artifacts");

        List<String> unclaimed = artifacts.entrySet().stream()
            .map(Map.Entry::getKey)
            .filter(id -> !ParityStore.isRootFile(id))
            .filter(ParityStore::exists)
            .filter(id -> !artifacts.getAsJsonObject(id).get("baselined").getAsBoolean())
            .sorted()
            .toList();

        assertThat("artifacts that have a file in the store but that the index does not call "
            + "baselined. This is the converse of the check above and it is the one that keeps a "
            + "self-captured pin honest: its reader asserts only when the index says there is a "
            + "value, so a row flipped back to false would silently turn its gate into a capture",
            unclaimed, is(empty()));
    }

    @Test
    @DisplayName("reading an artifact the store does not hold throws and names the command that captures it")
    void anAbsentArtifactThrowsRatherThanReadingEmpty() {
        // An artifact homed OUTSIDE the store that the naming rule nonetheless answers for. Those
        // are the permanently absent operands: `onlyStoreArtifactsGetAFile` above asserts none of
        // them ever gets a file, so this case cannot expire the way naming one unbaselined pin did -
        // that operand ran out the moment its phase promoted it.
        String absent = Stream.of(ParityArtifacts.Home.SOURCE, ParityArtifacts.Home.EXTERNAL,
                ParityArtifacts.Home.POINTER)
            .flatMap(home -> ParityArtifacts.withHome(home).stream())
            .filter(ParityIndexTest::isStorePath)
            .sorted()
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no registered artifact is homed outside the store while still resolving to a store "
                    + "path, so this case has no operand; it needs a different shape, not deleting"));

        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> Pins.crc32(absent, "any-key"));

        assertThat("an absent artifact must name itself", thrown.getMessage(), containsString(absent));
        assertThat("and must name the command that captures it",
            thrown.getMessage(), containsString("parityCapture -Partifacts=" + absent));
    }

    @Test
    @DisplayName("the store holds no file for an artifact whose home is elsewhere")
    void onlyStoreArtifactsGetAFile() {
        List<String> intruders = new ArrayList<>();
        for (ParityArtifacts.Home home : List.of(ParityArtifacts.Home.SOURCE,
            ParityArtifacts.Home.EXTERNAL, ParityArtifacts.Home.POINTER))
            for (String id : ParityArtifacts.withHome(home))
                if (isStorePath(id) && ParityStore.exists(id)) intruders.add(id + " (" + home + ")");

        assertThat("artifacts whose home is Java source, the working root, or another artifact's "
            + "file, that nonetheless have a file of their own in the store - which would be the "
            + "same value in two places", intruders, is(empty()));
    }

    /**
     * Returns whether an id maps to a store path at all.
     *
     * <p>{@link ParityStore#pathOf} is a pure naming rule over the id's kind prefix and knows nothing
     * about where an artifact's value lives, so a roster id has no path while {@code pin.armor-span}
     * has one it never uses.
     *
     * @param artifactId the artifact id
     * @return whether the naming rule answers for it
     */
    private static boolean isStorePath(String artifactId) {
        try {
            ParityStore.pathOf(artifactId);
            return true;
        } catch (ParityStoreException ex) {
            return false;
        }
    }

    /**
     * Returns the index map a home is registered in.
     *
     * @param home the home
     * @return the map's key
     */
    private static String mapFor(ParityArtifacts.Home home) {
        return MAPS.entrySet().stream()
            .filter(entry -> entry.getValue() == home)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow();
    }

}
