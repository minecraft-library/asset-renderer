package lib.minecraft.renderer.pose.compile;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for the {@link Diagnostics} scopes: path shapes, subtree aggregation, entry
 * timestamps, the {@link Diagnostics.Output} modes, and the sink's two exceptions.
 */
@DisplayName("diagnostics scopes, subtree counts, output modes")
class DiagnosticsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("scope paths mirror the install tree; child scopes are memoized per tag")
    void scopePaths() {
        Diagnostics root = Diagnostics.root("styles", Diagnostics.Output.NONE, null);
        Diagnostics stand = root.child("minecraft:armor_stand");
        stand.child("sit").warn("first");
        stand.child("sit").warn("second");
        assertEquals("styles/minecraft:armor_stand/sit", stand.child("sit").entries().getFirst().path());
        assertEquals(2, stand.child("sit").entries().size(), "same tag reuses one scope");
    }

    @Test
    @DisplayName("counts + failed() aggregate over the subtree; siblings stay isolated")
    void subtreeAggregation() {
        Diagnostics root = Diagnostics.root("styles", Diagnostics.Output.NONE, null);
        Diagnostics a = root.child("a");
        Diagnostics b = root.child("b");
        a.child("deep").error("boom");
        a.info("note");
        b.warn("only warn");
        assertEquals(1, root.count(Diagnostics.Severity.ERROR));
        assertEquals(1, root.count(Diagnostics.Severity.WARN));
        assertEquals(1, root.count(Diagnostics.Severity.INFO));
        assertTrue(root.failed());
        assertTrue(a.failed());
        assertFalse(b.failed(), "sibling subtree unaffected");
        assertEquals(0, b.count(Diagnostics.Severity.ERROR));
        // prefix isolation: "a" must not swallow a hypothetical sibling "ab"
        Diagnostics ab = root.child("ab");
        ab.error("other");
        assertEquals(1, a.count(Diagnostics.Severity.ERROR), "'a' does not match 'ab' entries");
    }

    @Test
    @DisplayName("entries carry timestamps and record chronologically")
    void timestamps() {
        Diagnostics root = Diagnostics.root("styles", Diagnostics.Output.NONE, null);
        root.info("one");
        root.child("x").warn("two");
        var entries = root.entries();
        assertEquals(2, entries.size());
        assertNotNull(entries.getFirst().timestamp());
        assertEquals("one", entries.getFirst().message());
        assertEquals("two", entries.getLast().message());
        assertFalse(entries.getFirst().timestamp().isAfter(entries.getLast().timestamp()), "chronological");
    }

    @Test
    @DisplayName("FILE mode writes the run log at flush; flush is root-only")
    void fileMode() throws IOException {
        Path log = tempDir.resolve("logs/styles-stamp.log");
        Diagnostics root = Diagnostics.root("styles", Diagnostics.Output.FILE, log);
        root.child("subject").error("kaput");
        assertThrows(IllegalStateException.class, () -> root.child("subject").flush(), "flush is root-only");
        root.flush();
        String written = Files.readString(log);
        assertTrue(written.contains("[ERROR] styles/subject - kaput"));
        assertTrue(written.endsWith("\n"), "terminated with a literal LF");
        assertFalse(written.contains("\r"), "joined with literal LFs, never the host newline");
    }

    @Test
    @DisplayName("a bare filename target flushes into the working directory")
    void bareFilenameTarget() throws IOException {
        Path bare = Path.of("styles-diagnostics-bare-target.log");
        Diagnostics root = Diagnostics.root("styles", Diagnostics.Output.FILE, bare);
        root.info("landed");
        try {
            root.flush();
            assertTrue(Files.readString(bare).contains("[INFO] styles - landed"),
                "a target with no parent still writes - there is no directory to create");
        } finally {
            Files.deleteIfExists(bare);
        }
    }

    @Test
    @DisplayName("error(cause, ...) appends the cause; NONE mode still records")
    void causesAndNone() {
        Diagnostics root = Diagnostics.root("styles", Diagnostics.Output.NONE, null);
        root.error(new IllegalStateException("inner"), "outer %s", "context");
        assertEquals(1, root.count(Diagnostics.Severity.ERROR));
        assertTrue(root.entries().getFirst().message().contains("outer context"));
        assertTrue(root.entries().getFirst().message().contains("inner"));
        root.flush();                                      // NONE: no file target, no-op
    }

    @Test
    @DisplayName("NONE records with zero console output")
    void noneIsSilent() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outCaptured = new ByteArrayOutputStream();
        ByteArrayOutputStream errCaptured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(outCaptured, true));
            System.setErr(new PrintStream(errCaptured, true));
            Diagnostics root = Diagnostics.root("styles", Diagnostics.Output.NONE, null);
            root.info("quiet");
            root.child("subject").error("still quiet");
            assertEquals(2, root.entries().size(), "recording is unconditional");
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertEquals(0, outCaptured.size(), "nothing lands on stdout");
        assertEquals(0, errCaptured.size(), "nothing lands on stderr");
    }

    @Test
    @DisplayName("an unwritable FILE target refuses at flush")
    void unwritableFileTarget() throws IOException {
        Path blocker = Files.createFile(tempDir.resolve("blocker"));
        Diagnostics root = Diagnostics.root("styles", Diagnostics.Output.FILE, blocker.resolve("log.log"));
        root.error("kaput");
        UncheckedIOException refused = assertThrows(UncheckedIOException.class, root::flush);
        assertTrue(refused.getMessage().contains("blocker"), "the refusal names the target: " + refused.getMessage());
    }

    /**
     * The error severity held to the refusal it is recorded beside.
     *
     * <p>An error entry is a refusal's post-mortem and never a second refusal channel. Both halves
     * of that are facts about where a call sits rather than about any signature, so neither is
     * anything a parameter list or a modifier can carry and both are read off the source instead:
     * an error is recorded only inside a refusal builder, and every call to one ends the branch it
     * is written in.
     *
     * <p>A builder records the refusal and hands it back for the call site to throw, so a call
     * that drops the keyword compiles clean and records an error that refuses nobody. That is the
     * half no behavioural test reaches, because the style it would refuse installs instead.
     */
    @Nested
    @DisplayName("the error severity, held to the refusal beside it")
    class ErrorPlacement {

        /** The two files that build a refusal, and the only two that record an error. */
        private static final @NotNull List<Path> REFUSING = List.of(
            Path.of("src/main/java/lib/minecraft/renderer/pose/compile/PoseCompiler.java"),
            Path.of("src/main/java/lib/minecraft/renderer/pose/install/StyleRegistrar.java"));

        /** What a refusal builder's own signature reads, in both files. */
        private static final @NotNull String BUILDER = "IllegalArgumentException refuse(";

        @Test
        @DisplayName("records an error only inside a refusal builder, where a throw follows it")
        void everyErrorStandsBesideARefusal() {
            for (Path source : REFUSING) {
                List<String> code = code(source);
                Set<Integer> builders = builderBodies(code);
                assertFalse(builders.isEmpty(), source + " declares a refusal builder");

                for (int at = 0; at < code.size(); at++)
                    if (code.get(at).contains(".error("))
                        assertTrue(builders.contains(at), source + " records an error outside a "
                            + "refusal builder, where no throw follows it: " + code.get(at));
            }
        }

        @Test
        @DisplayName("ends every branch that builds a refusal")
        void everyRefusalEndsItsBranch() {
            for (Path source : REFUSING) {
                int calls = 0;
                for (String line : code(source)) {
                    if (!line.contains("refuse(") || line.contains(BUILDER)) continue;
                    calls++;
                    assertTrue(line.startsWith("throw ") || line.startsWith("return "), source
                        + " builds a refusal it discards, so the guard no longer guards: " + line);
                }
                assertTrue(calls > 5, source + " is expected to call its refusal builder");
            }
        }

        // ------------------------------------------------------------------------------------

        /**
         * One file's code, with the comments and the javadoc stripped.
         *
         * <p>Stripping is what keeps the scan off its own documentation: both files quote the
         * idiom {@code throw this.refuse(...)} in the builder's own javadoc, so a raw match reads
         * two recitals as call sites and counts forty-four where there are forty-two.
         */
        private static @NotNull List<String> code(@NotNull Path source) {
            List<String> out = new ArrayList<>();
            boolean inBlockComment = false;
            for (String raw : read(source)) {
                String line = raw.strip();
                if (inBlockComment) {
                    inBlockComment = !line.contains("*/");
                    continue;
                }
                if (line.startsWith("/*")) {
                    inBlockComment = !line.contains("*/");
                    continue;
                }
                if (line.isEmpty() || line.startsWith("//")) continue;
                out.add(line);
            }
            // Guard the stripper before trusting what it produced: a bug that ate the file would
            // leave nothing to match and report the strongest possible agreement.
            assertTrue(out.size() > 150, source + " is expected to survive stripping");
            assertTrue(out.stream().anyMatch(line -> line.contains(BUILDER)),
                source + " is expected to still declare its refusal builder");
            return out;
        }

        /**
         * The line indices falling inside a refusal builder's body.
         *
         * <p>A signature wraps over three lines in both files, so the brace that opens the body
         * arrives well after the line the builder is recognised by. The depth it opened at is what
         * closes it, and the body is read as entered only once the depth has passed that - which
         * is what keeps the declaration's own line from reading as the close.
         */
        private static @NotNull Set<Integer> builderBodies(@NotNull List<String> code) {
            Set<Integer> inside = new HashSet<>();
            int depth = 0;
            int builder = -1;
            boolean entered = false;
            for (int at = 0; at < code.size(); at++) {
                String line = code.get(at);
                if (builder < 0 && line.contains(BUILDER)) {
                    builder = depth;
                    entered = false;
                }
                depth += count(line, '{') - count(line, '}');
                if (builder < 0) continue;
                if (depth > builder) {
                    entered = true;
                    inside.add(at);
                } else if (entered) builder = -1;
            }
            return inside;
        }

        /**
         * How many times one character stands in a line.
         */
        private static int count(@NotNull String line, char of) {
            int seen = 0;
            for (int at = 0; at < line.length(); at++)
                if (line.charAt(at) == of) seen++;
            return seen;
        }

        private static @NotNull List<String> read(@NotNull Path source) {
            try {
                return Files.readAllLines(source, StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new UncheckedIOException("cannot read " + source.toAbsolutePath(), error);
            }
        }

    }

}
