package lib.minecraft.renderer.exception;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when an authored pose style is refused - a lowering rule of the style compiler, a guard of the
 * form walk or an install guard of the style registrar rejects what the author wrote, or the style
 * claims an id the universal rows answer.
 *
 * <p>An authoring error rather than a failed read or a failed draw: a style is refused where it is
 * built and installed, before anything renders. A refusal raised by the compiler, the form walk or the
 * registrar records its context as an {@code ERROR} entry in the diagnostics tree immediately before
 * it is thrown.
 *
 * @see RendererException
 */
@Parity(subject = Subject.ENTITY)
public final class StyleException extends RendererException {

    /**
     * Constructs a new {@code StyleException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public StyleException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code StyleException} with a literal detail message.
     *
     * @param message the detail message
     */
    public StyleException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code StyleException} wrapping the given cause with a literal
     * detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public StyleException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    /**
     * Constructs a new {@code StyleException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public StyleException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    /**
     * Constructs a new {@code StyleException} wrapping the given cause with a printf-style
     * format and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public StyleException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
