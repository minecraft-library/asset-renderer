package lib.minecraft.renderer.parity;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when the parity store cannot answer a read.
 *
 * <p>Every failure mode of a pinned value arrives here rather than as a silent default: an absent
 * artifact file, an absent key inside one, a stored schema newer than the reader, an array whose
 * length disagrees with what the caller declared. That is the structural half of the migration - a
 * pin read is a method call that can throw, where a Java literal was a constant that could be
 * emptied and still report green.
 */
public class ParityStoreException extends RuntimeException {

    /**
     * Constructs a new {@code ParityStoreException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public ParityStoreException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code ParityStoreException} with a literal detail message.
     *
     * @param message the detail message
     */
    public ParityStoreException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code ParityStoreException} wrapping the given cause with a literal detail
     * message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public ParityStoreException(@NotNull Throwable cause, @NotNull String message) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code ParityStoreException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public ParityStoreException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args));
    }

    /**
     * Constructs a new {@code ParityStoreException} wrapping the given cause with a printf-style
     * format and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public ParityStoreException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args), cause);
    }

}
