package lib.minecraft.renderer.json;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when JSON bytes cannot be parsed or a JSON document cannot be written.
 *
 * <p>The single failure type of the neutral {@link JsonNode} surface, shared by the tooling flows
 * and the pipeline loaders. Callers that need a domain-specific failure wrap it into their own
 * exception at the boundary.
 */
public class JsonException extends RuntimeException {

    /**
     * Constructs a new {@code JsonException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public JsonException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code JsonException} with a literal detail message.
     *
     * @param message the detail message
     */
    public JsonException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code JsonException} wrapping the given cause with a literal detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public JsonException(@NotNull Throwable cause, @NotNull String message) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code JsonException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public JsonException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args));
    }

    /**
     * Constructs a new {@code JsonException} wrapping the given cause with a printf-style format and
     * arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public JsonException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args), cause);
    }

}
