package lib.minecraft.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Root exception type for the {@code asset-renderer} module.
 *
 * <p>Every module-specific exception extends this class so callers can catch a single type
 * when they do not care about the exact failure. Subclasses pass their constructor arguments
 * through and rely on this class to:
 * <ul>
 *   <li>Perform the {@code String.format(message, args)} expansion exactly once at the root,
 *       so child constructors never re-format and {@code %} characters in already-expanded
 *       text never trip a second pass.</li>
 *   <li>Reverse the {@code (message, cause)} argument order that {@link RuntimeException}
 *       expects, so subclass call sites can match the project-wide
 *       {@code (cause, message, args...)} convention.</li>
 * </ul>
 *
 * <p><b>Subclasses.</b>
 * <ul>
 *   <li>{@link PipelineException} - asset extraction or parsing failures.</li>
 *   <li>{@link RenderException} - renderer output failures from valid inputs.</li>
 * </ul>
 *
 * <p>Build-time tooling failures are deliberately <b>not</b> in this hierarchy: the tooling kernel
 * raises its own {@code ToolingException} straight off {@link RuntimeException}, so a generator
 * failure is not something a renderer's skip-and-continue handler can swallow.</p>
 *
 * @see RuntimeException
 */
public class RendererException extends RuntimeException {

    /**
     * Constructs a new {@code RendererException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public RendererException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code RendererException} with a literal detail message.
     *
     * @param message the detail message
     */
    public RendererException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code RendererException} wrapping the given cause with a literal
     * detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public RendererException(@NotNull Throwable cause, @NotNull String message) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code RendererException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public RendererException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args));
    }

    /**
     * Constructs a new {@code RendererException} wrapping the given cause with a printf-style
     * format and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public RendererException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args), cause);
    }

}
