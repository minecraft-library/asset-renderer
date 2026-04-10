package dev.sbs.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when the asset extraction pipeline cannot parse, resolve, or persist client jar data.
 */
public final class AssetPipelineException extends RendererException {

    public AssetPipelineException(@NotNull Throwable cause) {
        super(cause);
    }

    public AssetPipelineException(@NotNull String message) {
        super(message);
    }

    public AssetPipelineException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    public AssetPipelineException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    public AssetPipelineException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
