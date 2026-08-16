package lib.minecraft.renderer.support;

import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The build's Kotlin scripts as one text, for the guards that read a wiring decision back out of
 * where it was typed.
 *
 * <p>Those guards ask what the build declares, not which file declares it - a task registration, an
 * artifact row, a declared input. The build is spread over the root script and the ones it applies
 * from {@value #SCRIPT_DIR}, so reading the root alone would answer for a fraction of it, and a
 * guard whose subject moved into an applied script would pass over nothing rather than fail. Joining
 * them keeps the question the same wherever the answer happens to live.
 *
 * <p>The join is a blank line, which is a hard boundary for the slicing a couple of those guards do:
 * a declaration read to the next blank line cannot run off the end of one script and into the next.
 * Order is the root first and the applied scripts sorted, so two runs read identical bytes.
 */
@UtilityClass
public final class BuildScripts {

    /** The root build script, applied by nothing and applying the rest. */
    public static final @NotNull Path ROOT = Path.of("build.gradle.kts");

    /** Where the applied scripts live, and a declared parity trigger root. */
    public static final @NotNull String SCRIPT_DIR = "gradle";

    /**
     * Every build script as one text, the root first.
     *
     * @return the joined scripts
     * @throws UncheckedIOException if a script cannot be read
     */
    public static @NotNull String all() {
        List<String> parts = new ArrayList<>();
        parts.add(read(ROOT));
        for (Path script : applied()) parts.add(read(script));
        return String.join("\n\n", parts);
    }

    /**
     * The scripts the root applies, sorted by file name.
     *
     * @return the applied script paths, empty when none are present
     * @throws UncheckedIOException if the script directory cannot be walked
     */
    public static @NotNull List<Path> applied() {
        Path dir = Path.of(SCRIPT_DIR);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".gradle.kts"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to list " + dir.toAbsolutePath(), failure);
        }
    }

    /**
     * Reads one script.
     *
     * @param script the script path
     * @return its text
     * @throws UncheckedIOException if it cannot be read
     */
    public static @NotNull String read(@NotNull Path script) {
        try {
            return Files.readString(script);
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to read " + script.toAbsolutePath(), failure);
        }
    }

}
