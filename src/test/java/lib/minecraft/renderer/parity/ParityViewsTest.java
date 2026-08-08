package lib.minecraft.renderer.parity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Proves every tracked markdown view is still what its JSON source renders to.
 *
 * <p>A generated file that is committed can drift from its source, and the mechanism that stops it
 * has to be a test rather than a rule. This one runs in the fast suite, needs no pipeline and no
 * network, and fails with the command that fixes it.
 *
 * <p>Passing {@code -Dasset.parity.regenerateViews=true} makes it <b>write</b> the view instead of
 * asserting against it, which is deliberately the same code path: a regeneration that went through
 * a second renderer could produce a file this test then rejects. That flag is the <b>only</b> arm
 * that writes: a view the store does not hold fails here, because re-creating one silently is how a
 * rename leaves the old path restored and the suite green.
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

            if (REGENERATE) {
                write(file, rendered);
                continue;
            }

            // Absence is a failure, not a bootstrap. Writing here as well made a tracked view lost
            // to a bad checkout or a rename silently reappear at the old path with the suite green,
            // so the run could report a STALE view and never a MISSING one - and the one arm anybody
            // documented is the flag above.
            assertThat("the store holds no " + view + "; it is a tracked file and this suite does "
                    + "not create one. Restore it, or regenerate with " + ParityViews.REGEN_COMMAND,
                Files.isRegularFile(file), is(true));
            assertThat(view + " is stale; regenerate with " + ParityViews.REGEN_COMMAND,
                ParityStore.readNormalized(file), equalTo(rendered));
        }
    }

    @Test
    @DisplayName("neither view suite re-creates a tracked file it was asked to assert against")
    void theOnlyArmThatWritesIsTheFlag() {
        for (String suite : List.of("ParityViewsTest", "ParityReferencesTest")) {
            String source = read(Path.of("src/test/java/lib/minecraft/renderer/parity", suite + ".java"));
            assertThat(suite + " guards its write arm on something other than the flag alone. What "
                    + "this replaced disjoined the flag with an absence test, so a tracked view lost "
                    + "to a bad checkout or left behind by a rename reappeared at the old path with "
                    + "the suite green - a STALE view reported and a MISSING one never. Read as the "
                    + "flag standing alone rather than as the absence of the other operand, because "
                    + "naming that operand here would put it in the source this reads",
                source, containsString("if (REGENERATE) {"));
        }
    }

    /**
     * Reads a source file's text, by path because a test source is not on the classpath.
     *
     * @param file the repo-relative path
     * @return its text
     */
    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
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
