package lib.minecraft.renderer.client.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when the vanilla client cannot be acquired - the version manifest or its metadata is
 * unreachable or malformed, the jar download fails, or the extracted tree cannot be written.
 *
 * <p>Extends {@link RuntimeException} directly. This module is a leaf that both the renderer and the
 * generators depend on, so it cannot reach the renderer's own hierarchy - and should not: a batch
 * renderer's skip-and-continue catches exist so one bad model never aborts a run, where a client that
 * failed to acquire has to.
 */
public class ClientException extends RuntimeException {

    /**
     * Constructs a new {@code ClientException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public ClientException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code ClientException} with a literal detail message.
     *
     * @param message the detail message
     */
    public ClientException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code ClientException} wrapping the given cause with a literal detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public ClientException(@NotNull Throwable cause, @NotNull String message) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code ClientException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public ClientException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args));
    }

    /**
     * Constructs a new {@code ClientException} wrapping the given cause with a printf-style format
     * and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public ClientException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args), cause);
    }

}
