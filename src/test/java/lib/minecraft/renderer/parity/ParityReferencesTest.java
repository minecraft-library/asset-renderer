package lib.minecraft.renderer.parity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Proves the {@code parity-gate} skill's two generated reference files are still what their JSON
 * renders to.
 *
 * <p>A stale reference is worse than a missing one: it reads as current and tells a reader which
 * gates can see their change, so it is exactly the file that must not be allowed to drift. The
 * mechanism has to be a test rather than a rule, and it runs in the fast suite with no pipeline and
 * no network.
 *
 * <p>Third of three generated-file gates, each covering a different destination:
 * {@link ParityIndexTest} relates the Java roster to {@code index.json}, {@link ParityViewsTest}
 * gates the store's own markdown views, and this one gates the skill's reference files.
 *
 * <p>Passing {@code -Dasset.parity.regenerateViews=true} makes it <b>write</b> the references rather
 * than assert against them, through the same code path for {@code ParityViewsTest}'s reason: a
 * regeneration that went through a second renderer could produce a file this test then rejects.
 */
@DisplayName("The generated parity-gate references regenerate from their JSON source")
final class ParityReferencesTest {

    /** Set on the command line to rewrite the references rather than assert against them. */
    private static final boolean REGENERATE = Boolean.getBoolean("asset.parity.regenerateViews");

    @Test
    @DisplayName("every generated reference regenerates byte-identically")
    void referencesAreRegenerable() {
        for (Map.Entry<String, String> rendered : ParityReferences.renderAll().entrySet()) {
            Path file = ParityReferences.HOME.resolve(rendered.getKey());

            if (REGENERATE || !Files.isRegularFile(file)) {
                write(file, rendered.getValue());
                continue;
            }

            assertThat(rendered.getKey() + " is stale; regenerate with "
                    + ParityReferences.REGEN_COMMAND,
                ParityStore.readNormalized(file), equalTo(rendered.getValue()));
        }
    }

    @Test
    @DisplayName("the artifact reference names every registered artifact")
    void everyArtifactAppearsInTheReference() {
        // The population check the byte comparison above cannot make: two files can agree with each
        // other and both be missing a row. This is what makes coining a 60th artifact reach the
        // skill rather than only the store.
        String artifacts = ParityReferences.render("artifacts.md");
        for (ParityArtifacts.Registration registration : ParityArtifacts.ALL)
            assertThat("the skill's artifact reference omits a registered artifact",
                artifacts, containsString("`" + registration.id() + "`"));
    }

    @Test
    @DisplayName("the blindness reference names every rule and every no-reach glob")
    void everyRuleAppearsInTheReference() {
        String blindness = ParityReferences.render("blindness.md");
        var map = ParityStore.read("roster.blindness-rules");
        for (var rule : map.getAsJsonArray("rules"))
            assertThat("the skill's blindness reference omits a rule", blindness,
                containsString("## " + rule.getAsJsonObject().get("id").getAsString() + " -"));
        for (var glob : map.getAsJsonArray("no_reach"))
            assertThat("the skill's blindness reference omits a no_reach glob", blindness,
                containsString("`" + glob.getAsString() + "`"));
    }

    @Test
    @DisplayName("no generated reference prints an artifact count as a literal")
    void noReferenceRestatesTheCount() {
        // The count lives in index.json and nowhere else: a literal in shipped text went stale three
        // times over in the design documents before a line of code was written. This is that rule
        // made checkable on the two files most likely to break it.
        for (String rendered : ParityReferences.renderAll().values())
            for (String literal : new String[] {" 59 artifact", " 60 artifact", " 59 registered", " 60 registered"})
                assertThat("a generated reference restates the artifact count as a literal",
                    rendered.contains(literal), is(false));
    }

    /**
     * Writes a reference, LF and UTF-8, creating the directory when absent.
     *
     * @param file the reference file
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
