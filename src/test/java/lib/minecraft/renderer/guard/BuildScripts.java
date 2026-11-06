package lib.minecraft.renderer.guard;

import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *
 * <p>Two answers more than one of those guards asks of the text are read here once: the class a
 * producer runs, and the registration that runs it.
 */
@UtilityClass
public final class BuildScripts {

    /** The root build script, applied by nothing and applying the rest. */
    public static final @NotNull Path ROOT = Path.of("build.gradle.kts");

    /** Where the applied scripts live, and a declared parity trigger root. */
    public static final @NotNull String SCRIPT_DIR = "gradle";

    /** How the vanilla sweep JVM's table names the class it runs for a producer, by producer name. */
    private static final @NotNull String SHARED_JVM_ROW = "\"%s\" to VanillaSweep\\(\"([^\"]+)\"";

    /** How a registration names the class it runs, which is where a producer's writer is read from. */
    private static final @NotNull Pattern RUNS_A_CLASS = Pattern.compile("mainClass\\.set\\(\"([^\"]+)\"\\)");

    /**
     * Reads the class one registered producer runs.
     *
     * <p>A producer of the vanilla sweep JVM runs the class its row of that JVM's table names. Any
     * other is read off its own {@linkplain #javaExecOf registration}, so a task registered without a
     * main class reads as one rather than borrowing the next registration's.
     *
     * @param build the build's text
     * @param producer the task name
     * @return the fully-qualified class name, or empty when the vanilla sweep JVM runs nothing for the
     *     producer and the build registers no {@code JavaExec} by that name, or its registration
     *     names no main class
     */
    public static @NotNull Optional<String> classRunBy(@NotNull String build, @NotNull String producer) {
        Matcher shared = Pattern.compile(String.format(SHARED_JVM_ROW, Pattern.quote(producer))).matcher(build);
        if (shared.find()) return Optional.of(shared.group(1));
        Optional<String> registration = javaExecOf(build, producer);
        if (registration.isEmpty()) return Optional.empty();
        Matcher runs = RUNS_A_CLASS.matcher(registration.get());
        return runs.find() ? Optional.of(runs.group(1)) : Optional.empty();
    }

    /**
     * Slices one {@code JavaExec} registration out of the build's text, from its opening to the brace
     * that closes it.
     *
     * <p>The closing brace is the first line after the opening that starts with the opening line's own
     * indent and a brace. The applied scripts register their tasks at different depths, so a slice to
     * the next registration of a fixed depth would run on through every registration of another and
     * answer with whatever the first of them declares.
     *
     * @param build the build's text
     * @param producer the task name
     * @return the registration's text, or empty when the build registers no {@code JavaExec} by that
     *     name or the registration never closes
     */
    public static @NotNull Optional<String> javaExecOf(@NotNull String build, @NotNull String producer) {
        String opening = "register<JavaExec>(\"" + producer + "\") {";
        int start = build.indexOf(opening);
        if (start < 0) return Optional.empty();
        int line = build.lastIndexOf('\n', start) + 1;
        int indented = line;
        while (indented < start && build.charAt(indented) == ' ') indented++;
        int end = build.indexOf("\n" + build.substring(line, indented) + "}", start);
        return end < 0 ? Optional.empty() : Optional.of(build.substring(start, end));
    }

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
