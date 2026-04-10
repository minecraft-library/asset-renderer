package dev.sbs.renderer.options;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageFormat;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Configures a single {@code AtlasRenderer} invocation. Selects which model sources to render,
 * what tile size to output, and how the renderer should report progress.
 * <p>
 * Output paths are deliberately not part of these options - the renderer produces an
 * {@code AtlasResult} carrying the composed image data and a sidecar JSON string, and the
 * caller (typically the {@code generateAtlas} Gradle task) decides where to write them.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class AtlasOptions {

    /** Which model kind(s) the atlas includes. */
    public enum Source {
        BLOCK,
        ITEM,
        BOTH
    }

    @lombok.Builder.Default
    private final @NotNull Source source = Source.BOTH;

    /** An optional predicate filter evaluated per model id. */
    @lombok.Builder.Default
    private final @NotNull Optional<java.util.function.Predicate<String>> filter = Optional.empty();

    @lombok.Builder.Default
    private final int tileSize = 64;

    @lombok.Builder.Default
    private final int columns = 16;

    @lombok.Builder.Default
    private final int backgroundArgb = 0x00000000;

    @lombok.Builder.Default
    private final @NotNull ConcurrentList<String> texturePackIds = Concurrent.newList();

    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    /**
     * When {@code true}, the renderer prints per-100-tile progress lines and per-failure
     * warnings to stdout / stderr. CLI consumers (e.g. the {@code generateAtlas} Gradle task)
     * leave this enabled; programmatic consumers that don't want their logs cluttered can flip
     * it off via the builder.
     */
    @lombok.Builder.Default
    private final boolean progressLogging = true;

    public @NotNull AtlasOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull AtlasOptions defaults() {
        return builder().build();
    }

}
