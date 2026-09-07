package lib.minecraft.renderer.pose.compile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for the {@link StyleDiagnostics} scopes: path shapes, subtree aggregation, entry
 * timestamps, the {@link StyleDiagnostics.Output} modes, and the sink's two exceptions.
 */
@DisplayName("style diagnostics scopes, subtree counts, output modes")
class StyleDiagnosticsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("scope paths mirror the install tree; child scopes are memoized per tag")
    void scopePaths() {
        StyleDiagnostics root = StyleDiagnostics.root("styles", StyleDiagnostics.Output.NONE, null);
        StyleDiagnostics stand = root.child("minecraft:armor_stand");
        stand.child("sit").warn("first");
        stand.child("sit").warn("second");
        assertEquals("styles/minecraft:armor_stand/sit", stand.child("sit").entries().getFirst().path());
        assertEquals(2, stand.child("sit").entries().size(), "same tag reuses one scope");
    }

    @Test
    @DisplayName("counts + failed() aggregate over the subtree; siblings stay isolated")
    void subtreeAggregation() {
        StyleDiagnostics root = StyleDiagnostics.root("styles", StyleDiagnostics.Output.NONE, null);
        StyleDiagnostics a = root.child("a");
        StyleDiagnostics b = root.child("b");
        a.child("deep").error("boom");
        a.info("note");
        b.warn("only warn");
        assertEquals(1, root.count(StyleDiagnostics.Severity.ERROR));
        assertEquals(1, root.count(StyleDiagnostics.Severity.WARN));
        assertEquals(1, root.count(StyleDiagnostics.Severity.INFO));
        assertTrue(root.failed());
        assertTrue(a.failed());
        assertFalse(b.failed(), "sibling subtree unaffected");
        assertEquals(0, b.count(StyleDiagnostics.Severity.ERROR));
        // prefix isolation: "a" must not swallow a hypothetical sibling "ab"
        StyleDiagnostics ab = root.child("ab");
        ab.error("other");
        assertEquals(1, a.count(StyleDiagnostics.Severity.ERROR), "'a' does not match 'ab' entries");
    }

    @Test
    @DisplayName("entries carry timestamps and record chronologically")
    void timestamps() {
        StyleDiagnostics root = StyleDiagnostics.root("styles", StyleDiagnostics.Output.NONE, null);
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
        StyleDiagnostics root = StyleDiagnostics.root("styles", StyleDiagnostics.Output.FILE, log);
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
        StyleDiagnostics root = StyleDiagnostics.root("styles", StyleDiagnostics.Output.FILE, bare);
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
        StyleDiagnostics root = StyleDiagnostics.root("styles", StyleDiagnostics.Output.NONE, null);
        root.error(new IllegalStateException("inner"), "outer %s", "context");
        assertEquals(1, root.count(StyleDiagnostics.Severity.ERROR));
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
            StyleDiagnostics root = StyleDiagnostics.root("styles", StyleDiagnostics.Output.NONE, null);
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
        StyleDiagnostics root = StyleDiagnostics.root("styles", StyleDiagnostics.Output.FILE, blocker.resolve("log.log"));
        root.error("kaput");
        UncheckedIOException refused = assertThrows(UncheckedIOException.class, root::flush);
        assertTrue(refused.getMessage().contains("blocker"), "the refusal names the target: " + refused.getMessage());
    }

}
