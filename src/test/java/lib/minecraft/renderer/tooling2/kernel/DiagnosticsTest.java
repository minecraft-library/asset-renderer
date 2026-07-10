package lib.minecraft.renderer.tooling2.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for the tooling2 {@link Diagnostics} scopes (SPINE 5.4): path shapes, subtree
 * aggregation, entry timestamps (doc-12 K4), and the Output modes. FQN deliberately shadows
 * the legacy {@code tooling/DiagnosticsTest} (doc 11 flag 4 - imports stay honest).
 */
@DisplayName("tooling2 Diagnostics scopes, subtree counts, output modes")
class DiagnosticsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("scope paths mirror the resolver tree; child scopes are memoized per tag")
    void scopePaths() {
        Diagnostics root = Diagnostics.root("entityModels", Diagnostics.Output.NONE, null);
        Diagnostics wolf = root.child("minecraft:wolf");
        wolf.child("overlays").warn("first");
        wolf.child("overlays").warn("second");
        assertEquals("entityModels/minecraft:wolf/overlays", wolf.child("overlays").entries().getFirst().path());
        assertEquals(2, wolf.child("overlays").entries().size(), "same tag reuses one scope");
    }

    @Test
    @DisplayName("counts + failed() aggregate over the subtree; siblings stay isolated")
    void subtreeAggregation() {
        Diagnostics root = Diagnostics.root("flow", Diagnostics.Output.NONE, null);
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
    @DisplayName("entries carry timestamps and record chronologically (doc-12 K4)")
    void timestamps() {
        Diagnostics root = Diagnostics.root("flow", Diagnostics.Output.NONE, null);
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
        Path log = tempDir.resolve("logs/flow-stamp.log");
        Diagnostics root = Diagnostics.root("flow", Diagnostics.Output.FILE, log);
        root.child("subject").error("kaput");
        assertThrows(ToolingException.class, () -> root.child("subject").flush(), "flush is root-only");
        root.flush();
        String written = Files.readString(log);
        assertTrue(written.contains("[ERROR] flow/subject - kaput"));
    }

    @Test
    @DisplayName("error(cause, ...) appends the cause; NONE mode still records")
    void causesAndNone() {
        Diagnostics root = Diagnostics.root("flow", Diagnostics.Output.NONE, null);
        root.error(new IllegalStateException("inner"), "outer %s", "context");
        assertEquals(1, root.count(Diagnostics.Severity.ERROR));
        assertTrue(root.entries().getFirst().message().contains("outer context"));
        assertTrue(root.entries().getFirst().message().contains("inner"));
        root.flush();                                      // NONE: no file target, no-op
    }

}
