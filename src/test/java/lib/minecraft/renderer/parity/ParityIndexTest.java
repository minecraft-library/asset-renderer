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
     * that dereferences nowhere is a value the store advertises and cannot produce - a {@code
     * summary} object no stored sweep has ever carried, row columns no producer writes, a
     * provenance key the registry declares and no record carries. Recording the set exactly is what
     * makes both directions fail: a pointer going dead is a new name here, and repairing one is a
     * name that has to leave. How the members divide between those kinds is measured rather than
     * written down, by {@link #citationShapes} - and that measurement is read back too, because a
     * derived description is only better than a typed one while its derivation is checked.
     */
    private static final Set<String> POINTERS_THAT_REACH_NOTHING = new TreeSet<>(List.of(
        "report.buckets", "report.canvas-mismatch", "report.coverage-gaps", "report.glint-frames",
        "report.panel-stats", "report.sum", "report.wall-time", "report.worst-list"));

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
        String body = declarationBody(read(Path.of("build.gradle.kts")),
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
