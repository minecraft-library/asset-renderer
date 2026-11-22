package lib.minecraft.renderer.driver;

import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Names what a driver or a sweep writes directly inside its own output directory.
 *
 * <p>An output name is built from a value the program was handed - a subject id from the command
 * line, a label, a roster key - and a name carrying a separator or a parent reference stops being a
 * name and becomes a path to somewhere else. Every such name is resolved through {@link #inside}, so
 * what a program writes stays in the directory it says it writes to.
 */
@UtilityClass
public final class OutputFiles {

    /**
     * Resolves a single file or directory name inside a directory.
     *
     * <p>The answer is {@code directory.resolve(name)}, unchanged for every name that is one path
     * segment, so no output moves.
     *
     * @param directory the directory the name must sit directly inside
     * @param name the file or directory name
     * @return the name resolved against the directory
     * @throws IllegalArgumentException if the name is empty, carries a separator or a NUL, is a
     *     parent or self reference, or resolves anywhere but directly inside the directory
     */
    public static @NotNull Path inside(@NotNull Path directory, @NotNull String name) {
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw refused(directory, name);
        Path base = directory.toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = base.resolve(name).normalize();
        } catch (InvalidPathException ex) {
            throw refused(directory, name);
        }
        if (!base.equals(resolved.getParent()))
            throw refused(directory, name);
        return directory.resolve(name);
    }

    /**
     * Builds the refusal for a name that is not one path segment.
     *
     * @param directory the directory the name was meant for
     * @param name the refused name
     * @return the exception to throw
     */
    private static @NotNull IllegalArgumentException refused(@NotNull Path directory, @NotNull String name) {
        return new IllegalArgumentException(String.format(
            "'%s' is not a single file name inside '%s' - no separators, parent references or absolute paths", name, directory));
    }

}
