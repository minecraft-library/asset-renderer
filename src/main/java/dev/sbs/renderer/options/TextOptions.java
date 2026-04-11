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
 * Configures a single {@code TextRenderer} invocation. Renders styled text in either the
 * Minecraft tooltip aesthetic ({@link Style#LORE}) or as plain transparent-background chat
 * text ({@link Style#CHAT}).
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class TextOptions {

    /** Rendering style - tooltip or plain chat text */
    @lombok.Builder.Default
    private final @NotNull Style style = Style.LORE;

    /** Styled text segments to render */
    @lombok.Builder.Default
    private final @NotNull ConcurrentList<LineSegment> lines = Concurrent.newList();

    /** Pixel padding around the text content */
    @lombok.Builder.Default
    private final int padding = 10;

    /** Alpha channel for the LORE background and border, in {@code [0, 255]}. */
    @lombok.Builder.Default
    private final int alpha = 245;

    /** Maximum characters per line before wrapping */
    @lombok.Builder.Default
    private final int wrapWidth = 38;

    /** Total number of frames produced when obfuscated text is present. */
    @lombok.Builder.Default
    private final int frameCount = 20;

    /** Target output frame rate; 20 fps matches vanilla's tick-synced obfuscation refresh. */
    @lombok.Builder.Default
    private final int framesPerSecond = 20;

    /** Output image format */
    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    public @NotNull TextOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull TextOptions defaults() {
        return builder().build();
    }

    /** Controls background rendering and line spacing behavior. */
    public enum Style {

        /** Plain text on a transparent background with uniform line spacing. */
        CHAT,

        /** Minecraft tooltip with purple border/background and wider gap after the first line. */
        LORE

    }

}
