package dev.sbs.renderer.exception;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a font cannot be loaded from a specified resource path or has encountered IO issues.
 */
public final class FontException extends RendererException {

    public FontException(@NotNull Throwable cause, @NotNull String resourcePath) {
        super(cause, "Unable to load font from file '%s'", resourcePath);
    }

}
