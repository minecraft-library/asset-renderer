package lib.minecraft.renderer.call.request;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.call.slot.TextSlot;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@link TextRenderer} invocation. Renders styled text in the Minecraft tooltip
 * aesthetic ({@link Style#LORE}), as plain transparent-background chat text ({@link Style#CHAT}), or as one
 * page of a written book on the book texture ({@link Style#BOOK}).
 * <p>
 * A tooltip and chat text draw their {@link #getLines() lines}; a book draws its {@link #getPage() page},
 * which the renderer wraps itself. Padding and the tooltip's background and border apply only to
 * {@link Style#LORE}; {@link Style#CHAT} draws the glyph rows alone, on a canvas ending at the first pixel
 * they draw, and {@link Style#BOOK} the book texture and its page, on a canvas ending at the first pixel
 * either draws, rounded out to whole Minecraft pixels. When any line drawn carries obfuscated text the
 * renderer emits an animated image of {@link #getFrameCount() frameCount} frames at
 * {@link #getFramesPerSecond() fps}, except that a render whose canvas holds no pixel - chat text drawing
 * nothing, a tooltip box of no width round lines that advance nothing, or a book whose texture and page
 * draw nothing - is the still, empty 1x1 image, obfuscated or not.
 *
 * @see TextRenderer
 */
@Getter
@ClassBuilder
public class TextOptions implements RenderOptions {

    /**
     * The distance in mcPixels the client's tooltip box reaches past its text on every side - both
     * tooltip sprites are drawn over the text plus 12 a side, and the background's first 8 texels are
     * clear.
     */
    public static final int TOOLTIP_PADDING_MCPX = 4;

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
     * Rendering style - {@link Style#LORE} tooltip chrome, plain {@link Style#CHAT} text, or a
     * {@link Style#BOOK} page.
     */
    private final @NotNull Style style = Style.LORE;

    /**
     * The lines a {@link Style#LORE} or {@link Style#CHAT} request draws, each {@link LineSegment} as its own
     * line - the renderer never re-wraps them. A {@link Style#BOOK} request draws its {@link #getPage() page}
     * and does not read them.
     */
    private final @NotNull ConcurrentList<LineSegment> lines = Concurrent.newList();

    /**
     * The page a {@link Style#BOOK} request draws, as one text component with its section-sign ({@code §})
     * codes still in its text - the renderer wraps it to the page as the client wraps a book page, reading
     * those codes as it wraps and drawing any other character, {@code &} among them, as written - and the
     * empty component, a page of no text, by default. Read by no other style.
     */
    private final @NotNull TextSegment page = TextSegment.literal("");

    /**
     * The distance in mcPixels the code-drawn box reaches past the text on every side, its ring 1 inside
     * the box's edge; the sprite box ends where its sprites draw and reads no padding.
     */
    private final int padding = TOOLTIP_PADDING_MCPX;

    /**
     * The chrome the LORE background and border are drawn in, empty for the renderer's own -
     * {@link ChromeStyle#SPRITE} for a renderer holding a context, {@link ChromeStyle#PROCEDURAL} for one
     * holding none. {@link ChromeStyle#SPRITE} resolves the pack's sprite pair through the context the
     * renderer was constructed with, so a renderer holding none refuses it;
     * {@link ChromeStyle#PROCEDURAL} needs no context.
     */
    private final @NotNull Optional<ChromeStyle> chromeStyle = Optional.empty();

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
     * {@code 0xF0100010} constant used in every version from 1.8.9 through 26.1. The code-drawn fill is
     * drawn at it, and the sprite box scales each background texel's alpha by it over 240 - one meaning,
     * since vanilla's fill carries that alpha. A fill drawn at 0 draws nothing, so the canvas ends at the
     * next part drawn.
     */
    private final int backgroundAlpha = VANILLA_TOOLTIP_BG_ALPHA;

    /**
     * Alpha channel for the LORE border gradient, in {@code [0, 255]}. Defaults to
     * {@link #VANILLA_TOOLTIP_BORDER_ALPHA}, matching the vanilla tooltip border endpoints
     * {@code 0x505000FF} and {@code 0x5028007F}. The code-drawn ring is drawn at it, and the sprite box
     * scales each frame texel's alpha by it over 80 - one meaning, since vanilla's ring carries that
     * alpha. A ring drawn at 0 draws nothing, so the canvas ends at the next part drawn.
     */
    private final int borderAlpha = VANILLA_TOOLTIP_BORDER_ALPHA;

    /**
     * Advisory maximum characters per line, defaulting to {@link #VANILLA_WRAP_WIDTH_CHARS} - a character
     * count no style reads. The renderer never wraps the {@link #lines} by it, and a {@link Style#BOOK} page is
     * wrapped at the client's own page width of 114 Minecraft pixels.
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
     * The default text options - an empty {@linkplain Style#LORE lore} tooltip naming no chrome, so drawn
     * in the renderer's own, with vanilla-matched padding, background/border alphas, and wrap width, ready
     * to have {@linkplain #getLines() lines} appended.
     *
     * @return the default options
     */
    public static @NotNull TextOptions defaults() {
        return builder().build();
    }

    /**
     * What a request draws and on what - the input it reads, the background drawn under it and the spacing
     * between its lines.
     */
    public enum Style {

        /**
         * Plain text on a transparent canvas that ends at the first pixel it draws, with uniform line
         * spacing. It draws the {@linkplain TextOptions#getLines() lines} and reads neither the
         * {@linkplain TextOptions#getPage() page}, nor the {@link TextOptions#getWrapWidth() wrapWidth}, nor any
         * of the tooltip's fields - {@link TextOptions#getChromeStyle() chromeStyle},
         * {@link TextOptions#getTooltipStyle() tooltipStyle}, {@link TextOptions#getPadding() padding},
         * {@link TextOptions#getBackgroundAlpha() backgroundAlpha} and
         * {@link TextOptions#getBorderAlpha() borderAlpha}.
         */
        CHAT,

        /**
         * Minecraft tooltip with purple border/background and wider gap after the first line. It draws the
         * {@linkplain TextOptions#getLines() lines} in the chrome {@link TextOptions#getChromeStyle() chromeStyle}
         * selects, and reads no {@linkplain TextOptions#getPage() page} and no
         * {@link TextOptions#getWrapWidth() wrapWidth}.
         */
        LORE,

        /**
         * One page of a written book as the client's book screen draws it: the game's book texture,
         * {@code minecraft:gui/book}, a pack's in place of vanilla's, laid as the client lays it - one quad over
         * 192 Minecraft pixels a side reading the texture's top-left three quarters, whatever its size - and
         * the {@linkplain TextOptions#getPage() page} on it. The page is wrapped at 114 Minecraft pixels as the
         * client wraps a page, reading its legacy codes as it wraps, and its first 14 lines are drawn 9 apart
         * from the client's text origin; the rest are dropped unmarked. Its text is black where it names no
         * colour and has no shadow where it names no shadow colour, and text the wrap opens in no style at all,
         * after a reset code a break follows, draws white with a drop shadow, as the client draws it. The canvas
         * ends at the first pixel the texture or the text draws, rounded out to whole Minecraft pixels.
         * <p>
         * A page's codes are section-sign ({@code §}) codes, which the wrap reads; any other character,
         * {@code &} among them, draws as written. A page of plain or section-sign-coded text is
         * {@link TextSegment#literal(String)} of it, its codes left in its text for the wrap - never
         * {@link TextSegment#fromLegacy(String, char)} or {@link LineSegment#fromLegacy(String, char)}, which
         * read the codes before any wrap and answer lines. A book needs a renderer built with a
         * {@link RendererContext}, which reads the texture; one built without raises a {@link RenderException}.
         * {@link TextOptions#getLines() lines},
         * {@link TextOptions#getChromeStyle() chromeStyle}, {@link TextOptions#getTooltipStyle() tooltipStyle},
         * {@link TextOptions#getPadding() padding}, {@link TextOptions#getBackgroundAlpha() backgroundAlpha},
         * {@link TextOptions#getBorderAlpha() borderAlpha} and {@link TextOptions#getWrapWidth() wrapWidth}
         * are not read.
         */
        BOOK

    }

}
