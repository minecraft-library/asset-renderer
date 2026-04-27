package lib.minecraft.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a renderer cannot produce output from valid asset inputs due to an invalid configuration or a missing model element.
 */
public final class RenderException extends RendererException {

    public RenderException(@NotNull Throwable cause) {
        super(cause);
    }

    public RenderException(@NotNull String message) {
        super(message);
    }

    public RenderException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    public RenderException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    public RenderException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
