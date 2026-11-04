package lib.minecraft.renderer.store;

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
 * developer - is thrown for real and read back whole against the bootstrap sequence.
 *
 * <p><b>And over the sources, because a roster reaches what somebody added to it.</b> Routing one
 * call site closed one call site: four others were reverted to the compare-less form with both
 * suites green. So the suite's own Java is read, and a prescription of a promotion that names no
 * compare - or a capture-only command dressed up as one - fails wherever it is written.
 */
@DisplayName("the prescribed re-baseline sequence is the one a promotion takes")
final class PinsTest {

    /** Any registered id would do; the spelling is a pure function of it. */
    private static final String ARTIFACT = "pin.block-crc";

    /**
     * The two source sets that produce every message on this list, and therefore where one can be
     * written - the suite, and the sweeps and drivers that name a pin.
     */
    private static final List<Path> SOURCES = List.of(
        Path.of("src", "test", "java", "lib", "minecraft", "renderer"),
        Path.of("src", "visual", "java", "lib", "minecraft", "renderer"));

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
    @DisplayName("re-baselining names the commit, the capture, the compare and the promotion")
    void theRebaselineSequenceNamesAllFour() {
        assertThat(Pins.rebaselineCommand(ARTIFACT), equalTo(
            "commit the change first - a capture from a dirty tree is refused at promotion - then: "
                + "./gradlew parityCapture -Partifacts=pin.block-crc"
                + ", ./gradlew parityCompare -Partifacts=pin.block-crc"
                + ", ./gradlew parityPromote -Partifacts=pin.block-crc -Preason=<why>"));
    }

    @Test
    @DisplayName("a first baseline is the same sequence under -Pbootstrap=true")
    void theBootstrapSequenceCarriesTheFlagOnBothHalves() {
        assertThat("the compare needs it too - a missing baseline is its refusal without it",
            Pins.bootstrapCommand(ARTIFACT), equalTo(
                "commit the change first - a capture from a dirty tree is refused at promotion - "
                    + "then: ./gradlew parityCapture -Partifacts=pin.block-crc"
                    + ", ./gradlew parityCompare -Partifacts=pin.block-crc -Pbootstrap=true"
                    + ", ./gradlew parityPromote -Partifacts=pin.block-crc"
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
                + "store at '" + empty.resolve("pins").resolve("block-crc.json")
                + "' - give it a first baseline: " + Pins.bootstrapCommand(ARTIFACT)));
    }

    @Test
    @DisplayName("a vector read at the wrong length is refused rather than truncated")
    void aVectorOfTheWrongLengthIsRefused() {
        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> Pins.floats("pin.vanilla-iso-pose", "pose", 15));

        assertThat("the caller states the length it requires, the file states its own, and the array "
                + "states a third - so any two disagreeing is a failure rather than a silent "
                + "narrowing. It is the guard an emptied golden array once walked straight past",
            thrown.getMessage(), containsString("holds 16 values and the caller requires 15"));
        assertThat("and it prescribes the regeneration, like every other refusal here",
            thrown.getMessage(), containsString(Pins.regenCommand("pin.vanilla-iso-pose")));
    }

    @Test
    @DisplayName("a key the pin does not declare is refused, and the message lists what it holds")
    void anAbsentKeyNamesWhatIsThere() {
        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> Pins.floats("pin.vanilla-iso-pose", "posz", 16));

        assertThat("a typo'd key would otherwise read as an absent pin, and the two are different "
                + "questions", thrown.getMessage(), containsString("has no key 'posz'"));
        assertThat("listing the real keys is what makes the message answer the typo",
            thrown.getMessage(), containsString("It holds: pose"));
    }

    @Test
    @DisplayName("a CRC entry read as a vector is refused rather than answering empty")
    void anEntryWithNoValuesArrayIsRefused() {
        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> Pins.floats("pin.block-crc", "piston_serial", 1));

        assertThat("a CRC entry carries a crc32 and no vector, so reading one as floats is a "
                + "caller error and not an empty answer",
            thrown.getMessage(), containsString("carries no 'values' array"));
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
     * Returns every Java source of the two source sets, so that a prescription is found wherever it is
     * written.
     *
     * @return the source files, in walk order
     * @throws IOException if a tree cannot be walked
     */
    private static List<Path> javaSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path root : SOURCES) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.getFileName().toString().endsWith(".java")).forEach(sources::add);
            }
        }
        return sources;
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

}
