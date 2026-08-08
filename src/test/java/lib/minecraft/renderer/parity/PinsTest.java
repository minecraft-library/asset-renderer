package lib.minecraft.renderer.parity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the sequence a shipped instruction prescribes is the sequence a promotion actually takes.
 *
 * <p>Every message that tells an operator to re-baseline a pin builds its command list here, and
 * the list names the commit, the capture, the compare and the promotion in that order. A capture
 * erases the working root's run directory before any producer writes into it, so the comparison a
 * promotion applies can only be one taken between the two, and the two-command form these messages
 * used to print is refused at its last step: by the compare requirement wherever a baseline already
 * exists, and by the dirty-tree refusal either way.
 *
 * <p>The commit at the front is that second obligation. These messages print because an uncommitted
 * edit moved a value, so the capture they ask for records a dirty tree and the promotion refuses it
 * - a prescription naming neither step is one that fails at its end whichever way it is read.
 *
 * <p>Asserted whole rather than by substring: a prescription that still starts with the right
 * command and then says something else is exactly the failure that shipped.
 *
 * <p><b>And asserted where a message is produced, not only where one is built.</b> The invariant
 * over the two builders says nothing about a caller that spells its own sequence beside them, which
 * is what every one of these messages did before it was routed here. So the store's own
 * absent-artifact message - the one message on this list that a reader meets rather than a
 * developer - is thrown for real and read back, and it goes through the loop with the rest.
 *
 * <p><b>And over the sources, because a roster reaches what somebody added to it.</b> Routing one
 * call site closed one call site: four others were reverted to the compare-less form with both
 * suites green. So the suite's own Java is read, and a prescription of a promotion that names no
 * compare - or a capture-only command dressed up as one - fails wherever it is written.
 */
@DisplayName("the prescribed re-baseline sequence is the one a promotion takes")
final class PinsTest {

    /** Any registered id would do; the spelling is a pure function of it. */
    private static final String ARTIFACT = "digest.shipped-tables";

    /** What every prescription opens its command list with, and the first act of the sequence. */
    private static final String COMMIT_FIRST = "commit the change first";

    /** The suite that produces every message on this list, and therefore where one can be written. */
    private static final Path SOURCES = Path.of("src", "test", "java", "lib", "minecraft", "renderer");

    /** The file the sequence is built in, and the one place a promotion is named beside a capture. */
    private static final String BUILDER = "Pins.java";

    /**
     * How a prescription spells a task, which the two tokens below complete.
     *
     * <p>Assembled rather than written out because the scan reads this file too: either token spelt
     * whole here is a prescription of that task sitting alone in its own declaration, and the check
     * would report itself.
     */
    private static final String INVOKES = "gradlew parity";

    /** The act a hand-written sequence has to carry to be one. */
    private static final String PRESCRIBES_A_PROMOTION = INVOKES + "Promote";

    /** And the step it is refused without, so a prescription naming the one has to name the other. */
    private static final String PRESCRIBES_A_COMPARE = INVOKES + "Compare";

    /** The capture-only builder, which names no promotion and must not be dressed up as one. */
    private static final String BUILDS_THE_CAPTURE_ALONE = "regenCommand(";

    /** Telling a reader to promote without spelling the task, which is what the fifth caller did. */
    private static final Pattern SAYS_PROMOTE =
        Pattern.compile("\\bpromot\\w*", Pattern.CASE_INSENSITIVE);

    /**
     * Where one prescription ends and the next begins.
     *
     * <p>A statement or a comment, because a message is concatenated across lines and a runbook in
     * javadoc carries no statement at all. Reading a line at a time would fail the builders, whose
     * three commands are three lines of one expression.
     */
    private static final Pattern REGIONS = Pattern.compile(";|\\*/");

    @Test
    @DisplayName("a capture alone is what regenerates the value")
    void theCaptureCommandStandsAlone() {
        assertThat("the file's own regen line is built from this, and it names the act that writes "
                + "the working root rather than the act that replaces a baseline",
            Pins.regenCommand(ARTIFACT),
            equalTo("./gradlew parityCapture -Partifacts=digest.shipped-tables"));
    }

    @Test
    @DisplayName("re-baselining names the commit, the capture, the compare and the promotion")
    void theRebaselineSequenceNamesAllFour() {
        assertThat(Pins.rebaselineCommand(ARTIFACT), equalTo(
            "commit the change first - a capture from a dirty tree is refused at promotion - then: "
                + "./gradlew parityCapture -Partifacts=digest.shipped-tables"
                + ", ./gradlew parityCompare -Partifacts=digest.shipped-tables"
                + ", ./gradlew parityPromote -Partifacts=digest.shipped-tables -Preason=<why>"));
    }

    @Test
    @DisplayName("a first baseline is the same sequence under -Pbootstrap=true")
    void theBootstrapSequenceCarriesTheFlagOnBothHalves() {
        assertThat("the compare needs it too - a missing baseline is its refusal without it",
            Pins.bootstrapCommand(ARTIFACT), equalTo(
                "commit the change first - a capture from a dirty tree is refused at promotion - "
                    + "then: ./gradlew parityCapture -Partifacts=digest.shipped-tables"
                    + ", ./gradlew parityCompare -Partifacts=digest.shipped-tables -Pbootstrap=true"
                    + ", ./gradlew parityPromote -Partifacts=digest.shipped-tables"
                    + " -Pbootstrap=true -Preason=<why>"));
    }

    @Test
    @DisplayName("the store's absent-artifact message is that sequence rather than its own")
    void theAbsentArtifactMessageIsBuiltFromTheBootstrapSequence(@TempDir Path empty) {
        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> ParityStore.readFrom(empty, ARTIFACT));

        assertThat("the message a reader meets when a baseline is missing hand-spelled a capture "
                + "and the words 'and promote it', which is a promotion prescribed with no compare "
                + "and no commit - refused at its last step both ways. Asserted against the builder "
                + "rather than against the sentence: what is under test is that it is ROUTED, and "
                + "the sentence itself is asserted whole by the case above",
            thrown.getMessage(), equalTo("Parity artifact '" + ARTIFACT + "' is absent from the "
                + "store at '" + empty.resolve("digests").resolve("shipped-tables.json")
                + "' - give it a first baseline: " + Pins.bootstrapCommand(ARTIFACT)));
    }

    @Test
    @DisplayName("a vector read at the wrong length is refused rather than truncated")
    void aVectorOfTheWrongLengthIsRefused() {
        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> Pins.floats("pin.kit-corners", "corners", 23));

        assertThat("the caller states the length it requires, the file states its own, and the array "
                + "states a third - so any two disagreeing is a failure rather than a silent "
                + "narrowing. It is the guard an emptied golden array once walked straight past",
            thrown.getMessage(), containsString("holds 24 values and the caller requires 23"));
        assertThat("and it prescribes the regeneration, like every other refusal here",
            thrown.getMessage(), containsString(Pins.regenCommand("pin.kit-corners")));
    }

    @Test
    @DisplayName("a key the pin does not declare is refused, and the message lists what it holds")
    void anAbsentKeyNamesWhatIsThere() {
        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> Pins.floats("pin.kit-corners", "cornerz", 24));

        assertThat("a typo'd key would otherwise read as an absent pin, and the two are different "
                + "questions", thrown.getMessage(), containsString("has no key 'cornerz'"));
        assertThat("listing the real keys is what makes the message answer the typo",
            thrown.getMessage(), containsString("It holds: corners"));
    }

    @Test
    @DisplayName("a digest-set entry read as a vector is refused rather than answering empty")
    void anEntryWithNoValuesArrayIsRefused() {
        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> Pins.floats("digest.shipped-tables", "block_models", 1));

        assertThat("a digest entry carries a sha256 and no vector, so reading one as floats is a "
                + "caller error and not an empty answer",
            thrown.getMessage(), containsString("carries no 'values' array"));
    }

    @Test
    @DisplayName("no prescription names the promotion without naming the compare and the commit")
    void aPromotionIsNeverPrescribedWithoutItsPreconditions(@TempDir Path empty) {
        List<String> prescriptions = prescriptions(empty);

        assertThat("an empty list passes every case below with nothing read",
            prescriptions, is(not(empty())));
        for (String prescribed : prescriptions) {
            assertThat(prescribed, containsString("parityPromote"));
            assertThat("a promotion of a capture no compare of it covered is refused wherever a "
                    + "baseline already exists, and a first baseline is worth being looked at "
                    + "before it becomes what everything after it is measured against",
                prescribed, containsString("parityCompare"));
            // The same shape one refusal along, and the one that made every message here print in
            // the first place: these fire on an UNCOMMITTED edit that moved a value, so the capture
            // they ask for records asset_dirty and the promotion at the end of them refuses it.
            assertThat("a promotion of a capture taken from a dirty tree is refused, so the commit "
                    + "is a step of the sequence rather than a convention around it",
                prescribed, containsString(COMMIT_FIRST));
            assertThat("and it has to come before the capture, which is the thing that must run on "
                    + "the committed tree",
                prescribed.indexOf(COMMIT_FIRST),
                is(lessThan(prescribed.indexOf("parityCapture"))));
        }
    }

    @Test
    @DisplayName("a builder opens with the commit, so a message embedding one cannot bury it")
    void eachBuilderOpensWithTheCommit() {
        for (String built : new String[] {Pins.rebaselineCommand(ARTIFACT),
            Pins.bootstrapCommand(ARTIFACT)}) {
            assertThat("callers concatenate a lead-in in front of this, so the commit being first "
                    + "in what the builder returns is what keeps it first in what they print",
                built.indexOf(COMMIT_FIRST), is(0));
        }
    }

    @Test
    @DisplayName("no source spells a sequence the promotion at the end of it would refuse")
    void noSourceSpellsASequenceThatCannotBeFollowed() throws IOException {
        Set<String> read = new TreeSet<>();
        List<String> uncompared = new ArrayList<>();
        List<String> dressedUp = new ArrayList<>();
        int found = 0;

        for (Path source : javaSources()) {
            read.add(source.getFileName().toString());
            // The builder file is where a promotion is named beside a capture on purpose, so it is
            // held to the first rule and exempt from the second: the sequence has to be spelled
            // somewhere, and the whole point of the second rule is that it is spelled only there.
            boolean builder = BUILDER.equals(source.getFileName().toString());
            for (String region : REGIONS.split(Files.readString(source))) {
                if (region.contains(PRESCRIBES_A_PROMOTION)) {
                    found++;
                    if (!region.contains(PRESCRIBES_A_COMPARE))
                        uncompared.add(excerpt(source, region, PRESCRIBES_A_PROMOTION));
                }
                if (!builder && region.contains(BUILDS_THE_CAPTURE_ALONE)
                    && SAYS_PROMOTE.matcher(region).find())
                    dressedUp.add(excerpt(source, region, BUILDS_THE_CAPTURE_ALONE));
            }
        }

        assertThat("the scan read no source at all", read, is(not(empty())));
        assertThat("and it has to reach the file the sequence is built in, or what it is scanning "
            + "for is outside what it read", read, hasItem(BUILDER));
        assertThat("no source prescribes a promotion at all, which is the same vacuity one step "
            + "along - the builders alone spell two", found, is(greaterThan(0)));
        assertThat("a promotion of a capture no compare of it covered is refused, so a sequence "
                + "spelling one without a compare is an instruction that fails at its last step. "
                + "Build it from Pins.rebaselineCommand or Pins.bootstrapCommand", uncompared,
            is(empty()));
        assertThat("Pins.regenCommand names the capture and nothing else, so a message that calls "
                + "it and then tells a reader to promote prescribes the two-command form every one "
                + "of these messages used to print - no compare in it, and no commit", dressedUp,
            is(empty()));
    }

    /**
     * Returns every Java source of the suite, so that a prescription is found wherever it is written.
     *
     * @return the source files, in walk order
     * @throws IOException if the tree cannot be walked
     */
    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".java")).toList();
        }
    }

    /**
     * Returns the line of a region a token sits on, named by its file.
     *
     * @param source the file the region came from
     * @param region the region
     * @param token the token that matched
     * @return the file and the offending line
     */
    private static String excerpt(Path source, String region, String token) {
        for (String line : region.split("\n"))
            if (line.contains(token)) return source + ": " + line.strip();
        return source + ": " + token;
    }

    /**
     * Every prescription in the store, built ones and produced ones alike.
     *
     * @param empty a store root holding nothing, so the read below fails the way a first
     *     baseline's absence fails
     * @return the two builders' answers and the message the store throws for an absent artifact
     */
    private static List<String> prescriptions(Path empty) {
        return List.of(Pins.rebaselineCommand(ARTIFACT), Pins.bootstrapCommand(ARTIFACT),
            assertThrows(ParityStoreException.class,
                () -> ParityStore.readFrom(empty, ARTIFACT)).getMessage());
    }

}
