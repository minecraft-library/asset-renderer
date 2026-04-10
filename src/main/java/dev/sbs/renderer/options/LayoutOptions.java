package dev.sbs.renderer.options;

import dev.sbs.renderer.Renderer;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFormat;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Configures a single {@code LayoutRenderer} invocation. Uses a custom (non-Lombok) builder so
 * the {@code child(Renderer, Options)} method can erase the options type parameter cleanly.
 */
@Getter
public class LayoutOptions {

    private final @NotNull Layout layout;
    private final @NotNull ConcurrentList<Supplier<ImageData>> children;
    private final int framesPerSecond;
    private final int backgroundArgb;
    private final @NotNull ImageFormat outputFormat;

    private LayoutOptions(
        @NotNull Layout layout,
        @NotNull ConcurrentList<Supplier<ImageData>> children,
        int framesPerSecond,
        int backgroundArgb,
        @NotNull ImageFormat outputFormat
    ) {
        this.layout = layout;
        this.children = children;
        this.framesPerSecond = framesPerSecond;
        this.backgroundArgb = backgroundArgb;
        this.outputFormat = outputFormat;
    }

    /**
     * Returns a new builder.
     *
     * @return the builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Returns the default layout options (a row of nothing at 30 fps).
     *
     * @return the default options
     */
    public static @NotNull LayoutOptions defaults() {
        return builder().build();
    }

    /**
     * Mutable builder. Supports both pre-rendered children and deferred renderer+options pairs
     * whose render is postponed until the containing renderer walks the layout.
     */
    public static class Builder {

        private @NotNull Layout layout = Layout.row();
        private final @NotNull ConcurrentList<Supplier<ImageData>> children = Concurrent.newList();
        private int framesPerSecond = 30;
        private int backgroundArgb = 0x00000000;
        private @NotNull ImageFormat outputFormat = ImageFormat.PNG;

        /**
         * Sets the layout strategy (row, column, grid, stack, custom).
         *
         * @param layout the layout strategy
         * @return this builder
         */
        public @NotNull Builder layout(@NotNull Layout layout) {
            this.layout = layout;
            return this;
        }

        /**
         * Appends a child described by a renderer and its options. The render call is deferred
         * until the parent renderer walks the layout, so a caller can build multiple variants of
         * the same layout cheaply.
         *
         * @param renderer the child renderer
         * @param options the child options
         * @param <O> the options type
         * @return this builder
         */
        public <O> @NotNull Builder child(@NotNull Renderer<O> renderer, @NotNull O options) {
            this.children.add(() -> renderer.render(options));
            return this;
        }

        /**
         * Appends a child from a pre-rendered image. Useful when a caller wants to render once
         * and place the result into multiple layouts without repeating the work.
         *
         * @param preRendered the pre-rendered image data
         * @return this builder
         */
        public @NotNull Builder child(@NotNull ImageData preRendered) {
            this.children.add(() -> preRendered);
            return this;
        }

        /**
         * Sets the target output frame rate used when any child is animated.
         *
         * @param fps the target frame rate
         * @return this builder
         */
        public @NotNull Builder framesPerSecond(int fps) {
            this.framesPerSecond = fps;
            return this;
        }

        /**
         * Sets the canvas background colour applied before blitting any child.
         *
         * @param argb the ARGB background colour
         * @return this builder
         */
        public @NotNull Builder backgroundArgb(int argb) {
            this.backgroundArgb = argb;
            return this;
        }

        /**
         * Sets the preferred output image format for the caller.
         *
         * @param format the output format
         * @return this builder
         */
        public @NotNull Builder outputFormat(@NotNull ImageFormat format) {
            this.outputFormat = format;
            return this;
        }

        /**
         * Builds the immutable options instance.
         *
         * @return the options
         */
        public @NotNull LayoutOptions build() {
            return new LayoutOptions(this.layout, this.children, this.framesPerSecond, this.backgroundArgb, this.outputFormat);
        }

    }

}
