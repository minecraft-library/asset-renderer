package lib.minecraft.renderer.example;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when the atlas example cannot read back what it wrote, or a file it needs is absent or
 * undecodable.
 *
 * <p>Extends {@link RuntimeException} directly rather than the renderer hierarchy, for the reason
 * {@code AtlasRenderer}'s own failure type does: its skip-and-continue catches exist so one bad
 * model never aborts a batch, and a sidecar that does not round-trip has to abort the run that
 * wrote it.
 */
public class AtlasException extends RuntimeException {

    /**
     * Constructs a new {@code AtlasException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public AtlasException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code AtlasException} with a literal detail message.
     *
     * @param message the detail message
     */
    public AtlasException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code AtlasException} wrapping the given cause with a literal detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public AtlasException(@NotNull Throwable cause, @NotNull String message) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code AtlasException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public AtlasException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args));
    }

    /**
     * Constructs a new {@code AtlasException} wrapping the given cause with a printf-style format
     * and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public AtlasException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args), cause);
    }

}
