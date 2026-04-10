package dev.sbs.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when the {@code pipeline/HttpFetcher} helper cannot complete a direct-URL download.
 * <p>
 * Wraps the underlying IO exception or reports a non-2xx status from the remote server.
 */
public final class HttpFetchException extends RendererException {

    public HttpFetchException(@NotNull Throwable cause) {
        super(cause);
    }

    public HttpFetchException(@NotNull String message) {
        super(message);
    }

    public HttpFetchException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    public HttpFetchException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    public HttpFetchException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
