package lib.minecraft.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a renderer cannot draw what it was asked for from content that was read successfully.
 *
 * <p>Used for requests a renderer refuses and for a draw that reaches something it cannot complete.
 * Typical fire sites:
 * <ul>
 *   <li>An {@code options} record naming an entity or a style the context does not resolve, or a
 *       menu slot, scale or panel size the menu cannot hold.</li>
 *   <li>An entity pose or clip asking a mesh for what it cannot hold - a bone the mesh does not
 *       declare, or a scale no single bone factor carries.</li>
 *   <li>A draw with nothing to compose - an item with no elements and no {@code layer0}, an atlas
 *       that produced no tile, a timeline with no frame.</li>
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
