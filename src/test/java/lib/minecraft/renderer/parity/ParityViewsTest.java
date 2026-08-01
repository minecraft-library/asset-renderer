package lib.minecraft.renderer.parity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Proves every tracked markdown view is still what its JSON source renders to.
 *
 * <p>A generated file that is committed can drift from its source, and the mechanism that stops it
 * has to be a test rather than a rule. This one runs in the fast suite, needs no pipeline and no
 * network, and fails with the command that fixes it.
 *
 * <p>Passing {@code -Dasset.parity.regenerateViews=true} makes it <b>write</b> the view instead of
 * asserting against it, which is deliberately the same code path: a regeneration that went through
 * a second renderer could produce a file this test then rejects.
 */
@DisplayName("The tracked parity views regenerate from their JSON source")
final class ParityViewsTest {

    /** Set on the command line to rewrite the views rather than assert against them. */
    private static final boolean REGENERATE = Boolean.getBoolean("asset.parity.regenerateViews");

    @Test
    @DisplayName("every tracked markdown view regenerates byte-identically")
    void viewsAreRegenerable() {
        for (String view : ParityViews.TRACKED) {
            Path file = ParityStore.PRODUCTION.resolve(view);
            String rendered = ParityViews.render(view);

            if (REGENERATE || !Files.isRegularFile(file)) {
                write(file, rendered);
                continue;
            }

            assertThat(view + " is stale; regenerate with " + ParityViews.REGEN_COMMAND,
                ParityStore.readNormalized(file), equalTo(rendered));
        }
    }

    /**
     * Writes a view, LF and UTF-8, creating the store directory when absent.
     *
     * @param file the view file
     * @param text its rendered text
     */
    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
