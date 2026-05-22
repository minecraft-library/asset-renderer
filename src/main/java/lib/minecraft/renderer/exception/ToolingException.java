package lib.minecraft.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a build-time tooling task cannot extract, parse, or validate vanilla client
 * assets.
 *
 * <p>Used by every entry point under {@link lib.minecraft.renderer.tooling}. Typical fire
 * sites:
 * <ul>
 *   <li>An ASM scan whose expected bytecode pattern is missing (most often when a Minecraft
 *       version bump renames or refactors a vanilla source - the tooling needs an update).</li>
 *   <li>JSON / NBT writes failing because the output path is unwritable.</li>
 *   <li>Atlas diagnostic checks failing post-render (missing tiles, empty bounding boxes,
 *       broken sidecar shape).</li>
 * </ul>
 *
 * @see RendererException
 * @see lib.minecraft.renderer.tooling
 */
public final class ToolingException extends RendererException {

    /**
     * Constructs a new {@code ToolingException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public ToolingException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code ToolingException} with a literal detail message.
     *
     * @param message the detail message
     */
    public ToolingException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code ToolingException} wrapping the given cause with a literal
     * detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public ToolingException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    /**
     * Constructs a new {@code ToolingException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public ToolingException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    /**
     * Constructs a new {@code ToolingException} wrapping the given cause with a printf-style
     * format and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public ToolingException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
