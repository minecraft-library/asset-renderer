package lib.minecraft.renderer.exception;

import api.simplified.mojang.exception.MojangApiException;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.parity.Parity;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when the vanilla client cannot be acquired from what the Mojang API served - the version is
 * absent from the Piston manifest, the jar cannot be written to the cache, or the cached jar cannot be
 * read or extracted.
 *
 * <p>A request the Mojang API itself fails is not wrapped here: it surfaces as that API's own
 * {@link MojangApiException}, which carries the HTTP status, headers and body this type cannot.
 *
 * <p>Extends {@link RuntimeException} rather than {@link RendererException}: a batch renderer's
 * skip-and-continue catches exist so one bad subject never aborts a run, where a client that failed
 * to acquire has to.
 *
 * @see ClientAcquisition
 */
@Parity(claim = "client-acquisition")
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
