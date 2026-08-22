package lib.minecraft.renderer.asset.pose;

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
 * The operator roster held against the generator's own copy of it.
 *
 * <p>The two builds share no classpath, so the vocabulary travels between them as tokens in a table
 * and the two enums are maintained as copies. Most of what that risks fails loudly: a token only one
 * side declares arrives as a table this renderer cannot read, and the load throws naming it.
 *
 * <p><b>The arithmetic is the half that would not.</b> The generator folds every sub-expression
 * whose operands it already knows, so a shipped table is part folded value and part expression to
 * evaluate, and the two are indistinguishable once written. An {@code apply} arm that disagreed
 * across the divide would leave one expression carrying both readings of the same operation - no
 * token missing, nothing to throw on, and a limb wrong by less than an ULP on every frame it draws.
 *
 * <p>So this compares the SOURCE, stripped of the prose the two sides word differently. Every
 * constant, every token, every arity, every width and every arm of the switch has to match
 * character for character; only comments, javadoc, the package and the imports may differ.
 */
@DisplayName("the pose operator roster")
class PoseOperatorMirrorTest {

    /** This build's copy, and the one it is a reader for. */
    private static final @NotNull Path RENDERER =
        Path.of("src/main/java/lib/minecraft/renderer/asset/pose/PoseOperator.java");

    /** The generator's copy, which is where a folded value came from. */
    private static final @NotNull Path TOOLING = Path.of(
        "tooling/src/main/java/lib/minecraft/renderer/tooling/animation/PoseOperator.java");

    @Test
    @DisplayName("is the generator's own, arm for arm, so a folded value and an evaluated one agree")
    void theTwoCopiesCarryTheSameCode() {
        List<String> renderer = code(RENDERER);
        List<String> tooling = code(TOOLING);

        // Guard the stripper before trusting what it produced: a bug that ate everything would make
        // two empty lists compare equal and report the strongest possible agreement.
        assertTrue(renderer.size() > 60, "the renderer's copy is expected to survive stripping");
        assertTrue(renderer.contains("public enum PoseOperator {"), "and to still be the enum");
        assertTrue(renderer.stream().anyMatch(line -> line.contains("case LIBM_MAX ->")),
            "and to still carry its arithmetic");

        assertEquals(tooling, renderer,
            "the generator's operator roster and this one differ; a folded value and an evaluated "
                + "one would not answer the same bits");
    }

    // ------------------------------------------------------------------------------------

    /**
     * One file's code, with everything the two sides are free to word differently removed.
     *
     * <p>The package and the imports go because they name where each copy lives, and the comments
     * and javadoc go because they address different readers - one explains what to record, the other
     * what a shipped table can say. What is left is the declaration and the arithmetic.
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
            if (line.startsWith("package ") || line.startsWith("import ")) continue;
            out.add(line);
        }
        return out;
    }

    private static @NotNull List<String> read(@NotNull Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read " + source.toAbsolutePath(), error);
        }
    }

}
