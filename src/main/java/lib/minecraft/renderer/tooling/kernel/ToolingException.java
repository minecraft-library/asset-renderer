package lib.minecraft.renderer.tooling.kernel;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a tooling flow cannot read its inputs, a required class or member is missing
 * from the client jar, or a fatal derivation fails.
 *
 * <p>The single failure type of the tooling kernel: jar IO, failed {@code require*} lookups,
 * and fatal derivation errors all surface as this type. Recoverable degradations go through
 * {@code Diagnostics} instead - a failed derivation that can continue records an ERROR entry
 * rather than throwing.
 */
public class ToolingException extends RuntimeException {

    /**
     * Constructs a new {@code ToolingException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public ToolingException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code ToolingException} with a literal detail message.
     *
     * @param message the detail message
     */
    public ToolingException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code ToolingException} wrapping the given cause with a literal
     * detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public ToolingException(@NotNull Throwable cause, @NotNull String message) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code ToolingException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public ToolingException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args));
    }

    /**
     * Constructs a new {@code ToolingException} wrapping the given cause with a printf-style
     * format and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public ToolingException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args), cause);
    }

}
