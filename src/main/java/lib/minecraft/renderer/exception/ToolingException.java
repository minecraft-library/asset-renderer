package lib.minecraft.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a build-time tooling task cannot extract, parse, or validate vanilla client assets.
 */
public final class ToolingException extends RendererException {

    public ToolingException(@NotNull Throwable cause) {
        super(cause);
    }

    public ToolingException(@NotNull String message) {
        super(message);
    }

    public ToolingException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    public ToolingException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    public ToolingException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
