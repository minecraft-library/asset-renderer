package lib.minecraft.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when the asset extraction pipeline cannot parse, resolve, or persist client jar data.
 */
public final class PipelineException extends RendererException {

    public PipelineException(@NotNull Throwable cause) {
        super(cause);
    }

    public PipelineException(@NotNull String message) {
        super(message);
    }

    public PipelineException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    public PipelineException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    public PipelineException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
