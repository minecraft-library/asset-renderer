package lib.minecraft.renderer.call.request;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.call.slot.TextSlot;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.LineSegment;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@code TextRenderer} invocation. Renders styled text in either the
 * Minecraft tooltip aesthetic ({@link Style#LORE}) or as plain transparent-background chat
 * text ({@link Style#CHAT}).
 *
 * <p>Padding, background, and border only apply to {@link Style#LORE}; {@link Style#CHAT} draws the
 * glyph rows alone. When any {@link #getLines() line} carries obfuscated text the renderer emits an
 * animated image of {@link #getFrameCount() frameCount} frames at {@link #getFramesPerSecond() fps}.
 *
 * @see lib.minecraft.renderer.TextRenderer
 */
@Getter
@ClassBuilder
public class TextOptions implements RenderOptions {

    /**
     * Default per-side inner padding between the tooltip border and the first glyph (mcPixels).
     */
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

    /**
     * Default wrap width; matches vanilla tooltip line break behaviour (~38 chars).
     */
    public static final int VANILLA_WRAP_WIDTH_CHARS = 38;

    /**
     * Obfuscation animation frame rate; matches vanilla's 20 ticks-per-second refresh.
     */
    public static final int VANILLA_TICK_FPS = Timeline.TICKS_PER_SECOND;

    /**
     * Rendering style - {@link Style#LORE} tooltip chrome or plain {@link Style#CHAT} text.
     */
    private final @NotNull Style style = Style.LORE;

    /**
     * Styled text segments to render; each {@link LineSegment} is drawn as its own line (the
     * renderer does not itself re-wrap segments).
     */
    private final @NotNull ConcurrentList<LineSegment> lines = Concurrent.newList();

    /**
     * Padding in mcPixels between the tooltip border and the text content (1 mcPixel = 2 output pixels at native sampling).
     */
    private final int padding = TOOLTIP_PADDING_MCPX;

    /**
     * The chrome the LORE background and border are drawn in. Defaults to
     * {@link ChromeStyle#PROCEDURAL}, which needs no context; {@link ChromeStyle#SPRITE} asks the
     * renderer to resolve the pack's sprite pair through the context it was constructed with.
     */
    private final @NotNull ChromeStyle chromeStyle = ChromeStyle.PROCEDURAL;

    /**
     * The {@code minecraft:tooltip_style} key a {@link ChromeStyle#SPRITE} render resolves its sprite
     * pair by - {@code ns:path} selects {@code ns:tooltip/<path>_background} and {@code _frame}. Empty
     * for the default {@code minecraft:tooltip/background} and {@code tooltip/frame} pair, and read by
     * no other chrome.
     */
    private final @NotNull Optional<ResourceId> tooltipStyle = Optional.empty();

    /**
     * Alpha channel for the LORE background fill, in {@code [0, 255]}. Defaults to
     * {@link #VANILLA_TOOLTIP_BG_ALPHA}, matching the vanilla tooltip background
     * {@code 0xF0100010} constant used in every version from 1.8.9 through 26.1.
     */
    private final int backgroundAlpha = VANILLA_TOOLTIP_BG_ALPHA;

    /**
     * Alpha channel for the LORE border gradient, in {@code [0, 255]}. Defaults to
     * {@link #VANILLA_TOOLTIP_BORDER_ALPHA}, matching the vanilla tooltip border endpoints
     * {@code 0x505000FF} and {@code 0x5028007F}.
     */
    private final int borderAlpha = VANILLA_TOOLTIP_BORDER_ALPHA;

    /**
     * Advisory maximum characters per line, defaulting to {@link #VANILLA_WRAP_WIDTH_CHARS}. Exposed
     * for callers that pre-wrap their {@link #lines} to vanilla tooltip width; the renderer does not
     * wrap on its own.
     */
    private final int wrapWidth = VANILLA_WRAP_WIDTH_CHARS;

    /**
     * Total number of frames produced when obfuscated text is present.
     */
    private final int frameCount = 20;

    /**
     * Target output frame rate; matches vanilla's tick-synced obfuscation refresh.
     */
    private final int framesPerSecond = VANILLA_TICK_FPS;

    /**
     * Transform applied to the default {@link ImageLayer} stack (background, border, text) before it
     * runs, letting callers splice custom passes relative to the {@link TextSlot} slots. Defaults to
     * {@linkplain UnaryOperator#identity() identity}.
     */
    private final @NotNull UnaryOperator<LayerStack<ImageLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * The default text options - an empty {@linkplain Style#LORE lore} tooltip with vanilla-matched
     * padding, background/border alphas, and wrap width, ready to have {@linkplain #getLines() lines}
     * appended.
     *
     * @return the default options
     */
    public static @NotNull TextOptions defaults() {
        return builder().build();
    }

    /**
     * Controls background rendering and line spacing behavior.
     */
    public enum Style {

        /**
         * Plain text on a transparent background with uniform line spacing.
         */
        CHAT,

        /**
         * Minecraft tooltip with purple border/background and wider gap after the first line.
         */
        LORE

    }

}
