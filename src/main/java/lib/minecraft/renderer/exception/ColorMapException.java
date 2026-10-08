package lib.minecraft.renderer.exception;

import lib.minecraft.renderer.content.pack.ColorMapLoader;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a biome colormap a tint target names cannot be loaded from a pack stack - no pack ships
 * it, or the copy the winning pack ships cannot be read or decoded.
 *
 * <p>A context load answers it by loading the vanilla pack alone, as the client's resource reload
 * answers the same failure by dropping every selected pack, and raises it only where the vanilla pack
 * cannot supply the colormap either. Every other {@link ContentException} a context load meets
 * propagates as it is.
 *
 * @see ContentException
 * @see ColorMapLoader
 */
public final class ColorMapException extends ContentException {

    /**
     * Constructs a new {@code ColorMapException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public ColorMapException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code ColorMapException} with a literal detail message.
     *
     * @param message the detail message
     */
    public ColorMapException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code ColorMapException} wrapping the given cause with a literal
     * detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public ColorMapException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    /**
     * Constructs a new {@code ColorMapException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public ColorMapException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    /**
     * Constructs a new {@code ColorMapException} wrapping the given cause with a printf-style
     * format and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public ColorMapException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
