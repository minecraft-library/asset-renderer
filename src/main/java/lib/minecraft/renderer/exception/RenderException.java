package lib.minecraft.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a renderer cannot produce output from otherwise valid asset inputs.
 *
 * <p>Used for caller-facing configuration errors and missing-model-element conditions that
 * are not the pipeline's fault. Typical fire sites:
 * <ul>
 *   <li>An {@code options} record that references an unknown block / item / entity id.</li>
 *   <li>A texture id whose resolution falls through every active pack.</li>
 *   <li>A render path that received a model with an unsupported element shape (e.g. no
 *       fluid texture id when {@code FluidRenderer} needs one).</li>
 * </ul>
 *
 * @see RendererException
 * @see lib.minecraft.renderer.Renderer
 */
public final class RenderException extends RendererException {

    /**
     * Constructs a new {@code RenderException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public RenderException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code RenderException} with a literal detail message.
     *
     * @param message the detail message
     */
    public RenderException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code RenderException} wrapping the given cause with a literal
     * detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public RenderException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    /**
     * Constructs a new {@code RenderException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public RenderException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    /**
     * Constructs a new {@code RenderException} wrapping the given cause with a printf-style
     * format and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public RenderException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
