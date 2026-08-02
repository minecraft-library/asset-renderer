package lib.minecraft.renderer.parity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
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
 */
@DisplayName("The parity index registers every artifact exactly once")
final class ParityIndexTest {

    /** The index's four maps, in the order the store writes them. */
    private static final Map<String, ParityArtifacts.Home> MAPS = Map.of(
        "artifacts", ParityArtifacts.Home.STORE,
        "pointers", ParityArtifacts.Home.POINTER,
        "external", ParityArtifacts.Home.EXTERNAL,
        "sources", ParityArtifacts.Home.SOURCE);

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
            unbaselined.isEmpty() ? "" : " - owed: ./gradlew parityCapture -Partifacts=manifest.visual"
                + ", then ./gradlew parityPromote -Partifacts=manifest.visual -Preason=<why>. The "
                + "capture depends on visualSweepSet, and -Preason has no default: promote refuses at "
                + "configuration time without one, so it is part of the command rather than a step "
                + "somebody is expected to know");

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
