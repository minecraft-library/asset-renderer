package lib.minecraft.refharness.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins where a key resolves: below the sweep's directory, one path segment a part, or nowhere.
 *
 * <p>A key built from a registry identifier is always one segment, so no boot reaches the refusal;
 * the keys that could are the ones a roster spells.
 */
@DisplayName("a key resolves below its sweep's directory")
class RefKeyTest {

    /** The sweep directory every case resolves against; nothing is written into it. */
    private static final Path ROOT = Path.of("references", "books");

    @Test
    @DisplayName("a named key, qualified, tokened and nested, resolves to the path it always did")
    void aKeyResolvesBelowTheSweepDirectory() {
        assertEquals(ROOT.resolve("one_line.png"), RefKey.named("one_line").resolve(ROOT));
        assertEquals(ROOT.resolve("plain").resolve("one_line_chrome~style=a__b.png"),
            RefKey.named("one_line").with("chrome").token("style", "a__b").in("plain").resolve(ROOT));
    }

    @Test
    @DisplayName("a key whose name or directory is not one path segment is refused")
    void aKeyThatIsNotOneSegmentIsRefused() {
        for (String name : List.of("", ".", "..", "a/b", "../a", "a\\b", "/a")) {
            assertThrows(IllegalArgumentException.class, () -> RefKey.named(name).resolve(ROOT), name);
            assertThrows(IllegalArgumentException.class, () -> RefKey.named("a").in(name).resolve(ROOT), name);
        }
    }
}
