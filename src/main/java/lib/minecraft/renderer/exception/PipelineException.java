package lib.minecraft.renderer.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when the asset extraction pipeline cannot parse, resolve, or persist client jar data.
 *
 * <p>Typical fire sites:
 * <ul>
 *   <li>Network failures while downloading the client jar through
 *       {@code api.simplified.mojang.MojangContract}.</li>
 *   <li>Corrupted or unsupported JSON / NBT shapes in {@code assets/minecraft/**}.</li>
 *   <li>Filesystem errors writing the extracted pack to the cache root.</li>
 * </ul>
 *
 * @see RendererException
 * @see lib.minecraft.renderer.pipeline.ClientAcquisition
 */
public final class PipelineException extends RendererException {

    /**
     * Constructs a new {@code PipelineException} wrapping the given underlying cause.
     *
     * @param cause the underlying throwable
     */
    public PipelineException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code PipelineException} with a literal detail message.
     *
     * @param message the detail message
     */
    public PipelineException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code PipelineException} wrapping the given cause with a literal
     * detail message.
     *
     * @param cause the underlying throwable
     * @param message the detail message
     */
    public PipelineException(@NotNull Throwable cause, @NotNull String message) {
        super(cause, message);
    }

    /**
     * Constructs a new {@code PipelineException} with a printf-style format and arguments.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public PipelineException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(message, args);
    }

    /**
     * Constructs a new {@code PipelineException} wrapping the given cause with a printf-style
     * format and arguments.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public PipelineException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(cause, message, args);
    }

}
