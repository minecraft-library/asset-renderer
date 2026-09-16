package lib.minecraft.renderer.pose.compile;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diagnostics sink held against the generator's own copy of it.
 *
 * <p>The two builds share no classpath, so a sink either side wants is a sink it declares, and the
 * two are maintained as copies. Nothing joins them: neither javadoc names the other, no table
 * carries a token between them, and unlike a vocabulary there is no load that would throw on a
 * disagreement - a scope tree, a severity ladder and a flush are behaviour rather than data, so a
 * copy that drifted would simply record differently and say nothing about it.
 *
 * <p><b>What drift costs is a reading of the same log.</b> Both sides gate on counts a caller reads
 * off the root, and both promise that recording is unconditional where emission is not. A copy that
 * changed which severity a case takes, or stopped rolling a child's counts into its parent, would
 * leave two builds answering one question two ways under one vocabulary - and the half a reader
 * checked would be the half that was right.
 *
 * <p>So this compares the SOURCE, with each side's own name for the type folded together and the
 * prose stripped. The exception policy is the one thing they may differ on, and it is asserted
 * rather than merely skipped: neither build can name the other's throwable, so those lines are
 * held to what each side is expected to raise instead of being left out of the reading.
 */
@DisplayName("the pose diagnostics sink")
class DiagnosticsMirrorTest {

    /** This build's copy. */
    private static final @NotNull Path RENDERER =
        Path.of("src/main/java/lib/minecraft/renderer/pose/compile/Diagnostics.java");

    /** The generator's copy, which records the same shape for a different reader. */
    private static final @NotNull Path TOOLING =
        Path.of("tooling/src/main/java/lib/minecraft/renderer/tooling/kernel/Diagnostics.java");

    @Test
    @DisplayName("is the generator's own, scope for scope, so one log reads the same way from either build")
    void theTwoCopiesCarryTheSameCode() {
        List<String> renderer = code(RENDERER, "Diagnostics");
        List<String> tooling = code(TOOLING, "Diagnostics");

        // Guard the stripper before trusting what it produced: a bug that ate everything would make
        // two empty lists compare equal and report the strongest possible agreement.
        assertTrue(renderer.size() > 60, "the renderer's copy is expected to survive stripping");
        assertTrue(renderer.contains("public final class Diagnostics {"),
            "and to still be the class, under the folded name");
        assertTrue(renderer.contains("public enum Severity { INFO, WARN, ERROR }"),
            "and to still carry the severity ladder");
        assertTrue(renderer.stream().anyMatch(line -> line.startsWith("public void flush()")),
            "and to still carry the flush the counts are read before");

        assertEquals(tooling, renderer,
            "the generator's diagnostics sink and this one differ; two builds would record the "
                + "same run two ways, with nothing at load or at compile to say which had drifted");
    }

    @Test
    @DisplayName("differs from it only in what it raises, which neither build can name for the other")
    void theOneDivergenceIsTheExceptionPolicy() {
        assertEquals(
            List.of(
                "throw new IllegalStateException(String.format(\"Flush is root-only (called on scope '%s')\", this.path));",
                "throw new UncheckedIOException(String.format(\"Failed to write diagnostics log '%s'\", this.fileTarget), ex);"),
            raises(RENDERER),
            "this build raises its own, a renderer having no tooling exception to reach for");
        assertEquals(
            List.of(
                "throw new ToolingException(\"Flush is root-only (called on scope '%s')\", this.path);",
                "throw new ToolingException(ex, \"Failed to write diagnostics log '%s'\", this.fileTarget);"),
            raises(TOOLING),
            "and the generator raises its own, which is why the two lines are folded out above "
                + "rather than being a drift the comparison should report");
    }

    // ------------------------------------------------------------------------------------

    /**
     * One file's code, with everything the two sides are free to word differently removed and each
     * side's own name for the type folded to one.
     *
     * <p>The package and the imports go because they name where each copy lives, the comments and
     * javadoc go because they address different readers, and the parity declaration goes because
     * only one of the two builds writes one at all. The throws go because neither build can name
     * the other's throwable; the test above is what holds them.
     *
     * <p>Two identifiers fold rather than going, because each side spells one thing in its own
     * vocabulary: the type, and the root factory's parameter, which is the flow a generator roots a
     * tree per and a caller-chosen name here. A third spelling of either reads as a difference,
     * which is the right answer - a rename nobody declared is a drift somebody should look at.
     */
    private static @NotNull List<String> code(@NotNull Path source, @NotNull String typeName) {
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
            if (line.startsWith("package ") || line.startsWith("import ")) continue;
            if (line.startsWith("@Parity(")) continue;
            if (line.startsWith("throw new ")) continue;
            out.add(line.replace(typeName, "Diagnostics")
                .replace("@NotNull String flow", "@NotNull String name")
                .replace("(null, flow,", "(null, name,"));
        }
        return out;
    }

    /**
     * The throws one copy raises, in source order, stripped of their indentation.
     */
    private static @NotNull List<String> raises(@NotNull Path source) {
        return read(source).stream()
            .map(String::strip)
            .filter(line -> line.startsWith("throw new "))
            .toList();
    }

    private static @NotNull List<String> read(@NotNull Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read " + source.toAbsolutePath(), error);
        }
    }

}
