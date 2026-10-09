package lib.minecraft.renderer.exception;

import lib.minecraft.renderer.content.client.SkinFetch;
import lib.minecraft.renderer.content.read.BundledResource;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a read cannot be completed - a bundled table, a pack file or a fetched texture that is
 * absent where it is required, cannot be read, or does not bind to the shape it is read as.
 *
 * <p>Typical fire sites:
 * <ul>
 *   <li>A bundled table missing from the classpath, failing its envelope, or not binding to its
 *       DTO, through {@link BundledResource} and the table readers.</li>
 *   <li>A pack that cannot be opened or listed, or a pack file whose JSON, {@code .mcmeta} or
 *       properties shape cannot be parsed.</li>
 *   <li>A player skin, cape or elytra texture whose URL the Mojang API cannot serve, through
 *       {@link SkinFetch}.</li>
 * </ul>
 *
 * <p>Sealed, admitting a subtype only where a catch answers it differently from every other failed
 * read: {@link ColorMapException}, which a context load answers by loading the vanilla pack alone,
 * and {@link RuleRejection}, which the rule parser that raised it answers by dropping that one rule.
 *
 * <p>Acquiring the client jar is not a read in this sense and raises {@link ClientException}
 * instead, off this hierarchy.
 *
 * @see RendererException
 */
public sealed class ContentException extends RendererException permits ColorMapException, RuleRejection {

    /**
     * Constructs a new {@code ContentException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public ContentException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code ContentException} with a literal detail message.
     *
     * @param message the detail message
     */
    public ContentException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code ContentException} wrapping the given cause with a literal
     * detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public ContentException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    /**
     * Constructs a new {@code ContentException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public ContentException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    /**
     * Constructs a new {@code ContentException} wrapping the given cause with a printf-style
     * format and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public ContentException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
