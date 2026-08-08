package lib.minecraft.renderer.parity;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves a self-captured row is written into an open capture and never into a finished one.
 *
 * <p>The root is a parameter here rather than {@link ParityStore#WORKING}, because the state under
 * test is a marker file in it and the production root is resolved once from a system property. The
 * two writers share one body, so what is asserted through the parameter is what the suite does.
 *
 * <p>The case that made this necessary is an ordinary {@code ./gradlew test} run after a capture:
 * it rewrote four artifacts inside the finished root, stripping the provenance a capture step had
 * stamped, while the completion marker still described the earlier run. Nothing downstream could
 * see it, because provenance is outside the payload a compare joins.
 *
 * <p>The line the decline prints is under test as much as the decline is. A write that does not
 * happen and says nothing is indistinguishable from one that happened, so the message is asserted
 * whole, silence is asserted on the path that writes, and the prefix the build forwards to the
 * console is read out of the build file rather than restated here.
 *
 * <p>So is the forwarding itself. A printed line whose only channel to a console has been deleted
 * is the same silence one level out, and nothing else in the suite would notice: the two cases
 * that read the build file are what make the console side of this a guard rather than a habit.
 *
 * <p>And nothing here prints for real. Every case runs with {@code System.out} pointed at a buffer,
 * because a decline this class provokes in a scratch directory would otherwise be forwarded to the
 * console beside the ones an operator can act on.
 */
@DisplayName("A self-captured row writes into an open capture and never into a finished one")
final class SelfCaptureTest {

    /** A one-entry pin payload, which is the smallest thing the writer accepts. */
    private static final Map<String, JsonObject> ENTRIES = Map.of("k", entry());

    /** The build-side declaration of the prefix whose lines reach the console. */
    private static final Pattern FORWARDED_PREFIX =
        Pattern.compile("val parityOutputPrefix: String = \"([^\"]*)\"");

    /**
     * The listener that carries a line from a test JVM to the console, as the build file spells it.
     *
     * <p>Its body holds no brace of its own, so up to the first {@code }} is the whole statement.
     */
    private static final Pattern FORWARDING_LISTENER =
        Pattern.compile("addTestOutputListener \\{[^}]*}");

    /** What that statement has to be, whitespace collapsed. */
    private static final String FORWARDS_THE_PREFIX =
        "addTestOutputListener { _, event -> "
            + "if (event.message.startsWith(parityOutputPrefix)) "
            + "logger.lifecycle(event.message.trimEnd()) }";

    /** What the class under test printed, and the reason none of it reaches a console. */
    private ByteArrayOutputStream printed;

    private PrintStream console;

    /**
     * Points {@code System.out} at a buffer for every case, not only the ones that read it.
     *
     * <p>Redirection rather than a stream parameter, because the shipped call site prints to
     * {@code System.out} and a seam taking one would be a second code path asserted in place of it.
     * Whole-class rather than per call, because the build forwards this prefix to the console: a
     * case that declines a write in a scratch directory and lets the line through puts an
     * instruction naming a deleted temp path in front of every ordinary run, on the one channel
     * narrowed so that each line on it could be trusted. Wired here, that is unrepresentable rather
     * than remembered.
     *
     * <p>The suite runs a class at a time in one JVM, so the swap is not racing another test.
     */
    @BeforeEach
    void redirectStdout() {
        console = System.out;
        printed = new ByteArrayOutputStream();
        System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(console);
    }

    @Test
    @DisplayName("a root with no capture at all is written")
    void anEmptyRootIsWritten(@TempDir Path root) {
        Optional<Path> written = SelfCapture.writeInto(root, "pin.kit-corners", ENTRIES, List.of());
        assertThat("an ordinary run before any capture writes its value as it always has",
            written, equalTo(Optional.of(root.resolve("pins/kit-corners.json"))));
        assertThat(Files.isRegularFile(root.resolve("pins/kit-corners.json")), is(true));
    }

    @Test
    @DisplayName("a root with an open capture is written")
    void anOpenCaptureIsWritten(@TempDir Path root) {
        mark(root, "OPEN");
        assertThat("capture-begin opens the capture before every producer, so this is the state a "
                + "capture step's own suite run is in; declining here would fail the capture on an "
                + "absent file",
            SelfCapture.writeInto(root, "pin.kit-corners", ENTRIES, List.of()).isPresent(), is(true));
    }

    @Test
    @DisplayName("a root carrying the completion marker is declined and left alone")
    void aFinishedCaptureIsDeclined(@TempDir Path root) {
        Path target = root.resolve("pins/kit-corners.json");
        SelfCapture.writeInto(root, "pin.kit-corners", ENTRIES, List.of());
        String before = read(target);
        mark(root, "COMPLETE");

        Optional<Path> written = SelfCapture.writeInto(root, "pin.kit-corners", Map.of("k", other()),
            List.of());

        assertThat("a declined write says so in its answer; a path back would read as written",
            written, equalTo(Optional.empty()));
        assertThat("the finished capture's own bytes must be exactly what they were - a rewrite is "
                + "invisible to the compare, which never joins the provenance a capture stamped",
            read(target), equalTo(before));
    }

    @Test
    @DisplayName("a declined write reports itself, naming the artifact, the root and the way out")
    void aDeclinedWriteIsReported(@TempDir Path root) {
        mark(root, "COMPLETE");

        SelfCapture.writeInto(root, "pin.kit-corners", ENTRIES, List.of());

        assertThat("a skip nothing says out loud is the failure this store is built against, and "
                + "the empty answer reaches only a caller that reads it. Asserted whole rather than "
                + "by prefix: a line that still starts this way and then says something else is "
                + "exactly what a half-edited message looks like",
            printed(), equalTo("parity: not capturing pin.kit-corners into " + root
                + " - it holds a finished capture. Run ./gradlew parityCapture to take a new one."
                + System.lineSeparator()));
    }

    @Test
    @DisplayName("the build installs the listener that carries a decline to the console")
    void theBuildForwardsTheDeclineToTheConsole() {
        Matcher listener = FORWARDING_LISTENER.matcher(read(Path.of("build.gradle.kts")));
        assertThat("Gradle shows a test's own output to nobody, so with no listener on the test "
                + "task the decline is written to a stream nothing reads - which is the silence "
                + "every case in this class exists to rule out, restored by deleting four lines. "
                + "The prefix case below reads a declaration that nothing would then consume",
            listener.find(), is(true));
        assertThat("asserted whole rather than by presence: a listener forwarding another prefix, "
                + "or forwarding at a log level the console does not show, is installed and silent",
            listener.group().replaceAll("\\s+", " "), equalTo(FORWARDS_THE_PREFIX));
    }

    @Test
    @DisplayName("the prefix the build forwards to the console is the one a decline prints")
    void theForwardedPrefixIsTheOneADeclinePrints(@TempDir Path root) {
        Matcher declared = FORWARDED_PREFIX.matcher(read(Path.of("build.gradle.kts")));
        assertThat("the build must declare the prefix its test-output listener forwards; a listener "
                + "filtering on a literal nothing relates to this class forwards nothing and still "
                + "reads as wired",
            declared.find(), is(true));

        mark(root, "COMPLETE");
        SelfCapture.writeInto(root, "pin.kit-corners", ENTRIES, List.of());

        String message = printed();
        int afterLeader = message.indexOf(' ') + 1;
        assertThat("the leader is the message up to and including its first space, so a line with "
                + "no space in it has nothing to compare a declaration against",
            afterLeader, is(greaterThan(0)));
        assertThat("equality, because the relation the listener applies holds in one direction "
                + "only: `parity` leads this line exactly as much as `parity: ` does, so a "
                + "declaration truncated to any prefix of the leader reads as wired while widening "
                + "the channel to every line beginning that way - the one channel narrowed so that "
                + "each line on it could be trusted",
            declared.group(1), equalTo(message.substring(0, afterLeader)));
    }

    @Test
    @DisplayName("a write that happens says nothing")
    void anAcceptedWriteIsQuiet(@TempDir Path root) {
        SelfCapture.writeInto(root, "pin.kit-corners", ENTRIES, List.of());
        assertThat("the forwarded line means one thing, so a run that captured normally must not "
                + "emit one",
            printed(), equalTo(""));
    }

    @Test
    @DisplayName("the marker is what decides it, at the run directory the toolkit writes")
    void theMarkerIsTheWholePredicate(@TempDir Path root) {
        assertThat(SelfCapture.holdsFinishedCapture(root), is(false));
        mark(root, "OPEN");
        assertThat("an open capture is not a finished one", SelfCapture.holdsFinishedCapture(root),
            is(false));
        mark(root, "COMPLETE");
        assertThat(SelfCapture.holdsFinishedCapture(root), is(true));
    }

    @Test
    @DisplayName("a wrong id and an empty payload stay errors whatever the root holds")
    void thePayloadRefusalsAreUnconditional(@TempDir Path root) {
        mark(root, "COMPLETE");
        assertThrows(ParityStoreException.class,
            () -> SelfCapture.writeInto(root, "sweep.entity", ENTRIES, List.of()),
            "a sweep does not self-capture, and a closed root does not make that acceptable");
        assertThrows(ParityStoreException.class,
            () -> SelfCapture.writeInto(root, "pin.kit-corners", Map.of(), List.of()),
            "an empty artifact compares clean against anything, closed root or not");
    }

    /** A distinguishable second value, so a rewrite of the first is visible as a byte difference. */
    private static JsonObject other() {
        JsonObject value = new JsonObject();
        value.addProperty("subject", "a probe");
        value.addProperty("type", "int");
        value.addProperty("count", 2);
        return value;
    }

    private static JsonObject entry() {
        JsonObject value = new JsonObject();
        value.addProperty("subject", "a probe");
        value.addProperty("type", "int");
        value.addProperty("count", 1);
        return value;
    }

    /** Everything the class under test printed during the case now running. */
    private String printed() {
        return printed.toString(StandardCharsets.UTF_8);
    }

    /** Writes one of the toolkit's two run markers into a root. */
    private static void mark(Path root, String marker) {
        try {
            Path run = root.resolve(ParityStore.RUN_DIR);
            Files.createDirectories(run);
            Files.writeString(run.resolve(marker), "");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
