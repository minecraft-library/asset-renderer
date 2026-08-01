package lib.minecraft.renderer.parity;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
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
    void anAbsentPinThrowsRatherThanReadingEmpty() {
        // A registered id with no file, whatever the store currently holds - so this stays the
        // empty-store case as artifacts are promoted one phase at a time, where naming a particular
        // pin made the test expire on the day that pin got a value.
        String absent = ParityArtifacts.withHome(ParityArtifacts.Home.STORE).stream()
            .filter(id -> !ParityStore.exists(id))
            .sorted()
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "every store artifact now has a file, so the empty-store case has no operand left; "
                    + "this test needs a different shape rather than deleting"));

        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> Pins.digest(absent, "any-key"));

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
