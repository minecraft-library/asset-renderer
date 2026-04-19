package dev.sbs.renderer.options;

import lib.minecraft.text.LineSegment;
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

    /** Default per-side inner padding between the tooltip border and the first glyph (mcPixels). */
    public static final int TOOLTIP_PADDING_MCPX = 5;

    /**
     * Alpha component matching the vanilla 0xF0100010 tooltip background. {@code 0xF0 = 240}
     * is used in every client version from 1.8.9 through 26.1.
     */
    public static final int VANILLA_TOOLTIP_BG_ALPHA = 240;

    /**
     * Alpha component matching the vanilla 0x50 border gradient endpoints
     * (0x505000FF top, 0x5028007F bottom).
     */
    public static final int VANILLA_TOOLTIP_BORDER_ALPHA = 80;

    /** Default wrap width; matches vanilla tooltip line break behaviour (~38 chars). */
    public static final int VANILLA_WRAP_WIDTH_CHARS = 38;

    /** Obfuscation animation frame rate; matches vanilla's 20 ticks-per-second refresh. */
    public static final int VANILLA_TICK_FPS = 20;

    /** Rendering style - tooltip or plain chat text */
    @lombok.Builder.Default
    private final @NotNull Style style = Style.LORE;

    /** Styled text segments to render */
    @lombok.Builder.Default
    private final @NotNull ConcurrentList<LineSegment> lines = Concurrent.newList();

    /** Padding in mcPixels between the tooltip border and the text content (1 mcPixel = 2 output pixels at native sampling). */
    @lombok.Builder.Default
    private final int padding = TOOLTIP_PADDING_MCPX;

    /**
     * Alpha channel for the LORE background fill, in {@code [0, 255]}. Defaults to
     * {@link #VANILLA_TOOLTIP_BG_ALPHA}, matching the vanilla tooltip background
     * {@code 0xF0100010} constant used in every version from 1.8.9 through 26.1.
     */
    @lombok.Builder.Default
    private final int backgroundAlpha = VANILLA_TOOLTIP_BG_ALPHA;

    /**
     * Alpha channel for the LORE border gradient, in {@code [0, 255]}. Defaults to
     * {@link #VANILLA_TOOLTIP_BORDER_ALPHA}, matching the vanilla tooltip border endpoints
     * {@code 0x505000FF} and {@code 0x5028007F}.
     */
    @lombok.Builder.Default
    private final int borderAlpha = VANILLA_TOOLTIP_BORDER_ALPHA;

    /** Maximum characters per line before wrapping */
    @lombok.Builder.Default
    private final int wrapWidth = VANILLA_WRAP_WIDTH_CHARS;

    /** Total number of frames produced when obfuscated text is present. */
    @lombok.Builder.Default
    private final int frameCount = 20;

    /** Target output frame rate; matches vanilla's tick-synced obfuscation refresh. */
    @lombok.Builder.Default
    private final int framesPerSecond = VANILLA_TICK_FPS;

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
