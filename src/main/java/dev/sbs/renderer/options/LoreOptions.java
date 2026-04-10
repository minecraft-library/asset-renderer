package dev.sbs.renderer.options;

import dev.sbs.renderer.text.segment.LineSegment;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageFormat;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Configures a single {@code LoreRenderer} invocation. Renders styled text in the Minecraft
 * tooltip style with optional obfuscation animation.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class LoreOptions {

    @lombok.Builder.Default
    private final @NotNull ConcurrentList<LineSegment> lines = Concurrent.newList();

    @lombok.Builder.Default
    private final int padding = 10;

    @lombok.Builder.Default
    private final int wrapWidth = 38;

    /** Total number of frames produced when obfuscated text is present. */
    @lombok.Builder.Default
    private final int frameCount = 20;

    /** Target output frame rate; 20 fps matches vanilla's tick-synced obfuscation refresh. */
    @lombok.Builder.Default
    private final int framesPerSecond = 20;

    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    public @NotNull LoreOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull LoreOptions defaults() {
        return builder().build();
    }

}
