package lib.minecraft.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Root exception type for the {@code asset-renderer} module.
 * <p>
 * Every module-specific exception extends this class so callers can catch a single type
 * when they do not care about the exact failure. Subclasses pass their constructor arguments
 * through and rely on this class to perform the {@code String.format(message, args)} expansion
 * and to reverse the {@code (message, cause)} argument order that {@link RuntimeException}
 * expects.
 */
public class RendererException extends RuntimeException {

    public RendererException(@NotNull Throwable cause) {
        super(cause);
    }

    public RendererException(@NotNull String message) {
        super(message);
    }

    public RendererException(@NotNull Throwable cause, @NotNull String message) {
        super(message, cause);
    }

    public RendererException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args));
    }

    public RendererException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args), cause);
    }

}
