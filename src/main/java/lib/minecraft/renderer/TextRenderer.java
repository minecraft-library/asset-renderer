package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.AlphaMode;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.bake.gui.NineSliceKit;
import lib.minecraft.renderer.bake.gui.TextKit;
import lib.minecraft.renderer.bake.gui.TooltipChrome;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.slot.TextSlot;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.draw.SampledTexture;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.Optional;

/**
 * Renders styled Minecraft text in one of three styles: an item-style lore tooltip with a bordered
 * background, plain chat text on a transparent canvas, or one page of a written book on the book texture.
 * Its text is drawn in the colours its context's pack stack swaps them for
 * ({@link RendererContext#findTextPalette}), and every colour as given where the stack holds no palette or
 * the renderer holds no context. Every line is drawn in opaque white with the drop shadow asked for, as the
 * client draws a tooltip line, a chat line and a book's line, so a segment naming no colour draws white,
 * any shadow it derives a quarter as bright, one naming a colour draws that colour opaque, and one naming a
 * shadow colour draws that shadow in place of the derived one.
 * <p>
 * The LORE background and border are contributed by the {@link TooltipChrome} the request's
 * {@linkplain TextOptions#getChromeStyle() chrome style} selects: {@link ChromeStyle#PROCEDURAL} draws
 * vanilla's box from its colours (background {@code 0xF0100010}, gradient border {@code 0x505000FF} to
 * {@code 0x5028007F}), blended as the client blends its tooltip sprites, at the request's
 * {@link TextOptions#getBackgroundAlpha()} / {@link TextOptions#getBorderAlpha()} alphas;
 * {@link ChromeStyle#SPRITE} nine-slices the pack's {@code tooltip/background} and
 * {@code tooltip/frame} sprites, or the pair the request's
 * {@linkplain TextOptions#getTooltipStyle() tooltip style} names. The renderer resolves that pair once
 * per render through the context it was constructed with; a lore render naming the sprite chrome raises
 * a {@link RenderException} only when the renderer holds no context, so a renderer built without one
 * draws chat text and the procedural chrome, and no book. A request naming no chrome style is drawn in the
 * sprite chrome by a renderer holding a context and in the procedural chrome by one holding none. A sprite
 * of the pair no selected pack ships, or one that cannot be read, draws as the missing texture stretched
 * over the client's whole sprite rect, as the client draws its missing sprite, and is named in the
 * result as a stand-in.
 * <p>
 * A BOOK request draws one page on the game's book texture, {@code minecraft:gui/book}, read through the
 * renderer's context, a pack's file in place of vanilla's, and laid as the client's book screen lays it:
 * one quad over 192 Minecraft pixels a side reading the texture's top-left three quarters, whatever its
 * size. The texture is read through the sampler its sidecar's texture section selects, as the client's
 * book screen reads it, so a section that blurs smooths the page and widens its canvas by the half texel
 * the filter spills past every edge, and one that also clamps holds the quad's first texels to the
 * texture's own edge. The page is one text component, its section-sign codes still in its text. The
 * book's text style - black, with a shadow colour of no alpha - is merged under it
 * ({@link TextSegment#inherit}), it is wrapped at 114 Minecraft pixels as the client wraps a page
 * ({@link TextSegment#wrap}), and its first 14 lines are drawn 9 apart from the client's text origin,
 * the rest dropped. So the page's text is black where it names no colour and has no shadow where it
 * names no shadow colour, and text the wrap opens in no style at all, after a reset code a break
 * follows, draws white with a shadow, as the client draws it. The texture is read as the client's book
 * screen reads it, as a plain texture with its sidecar's texture section alone, so an animation section
 * there neither loses it nor animates it. A book texture no selected pack ships, one that does not
 * decode, or one whose sidecar does not parse or carries a texture section the client's codec refuses,
 * draws as the missing texture over the quad, as the client draws its missing texture, and is named in
 * the result as a stand-in. Only a context reads the texture, so a renderer holding none raises a
 * {@link RenderException} for a book.
 * <p>
 * For a tooltip the renderer owns only the glyph rows and the text block's measure, the box being its
 * chrome's; a book's texture quad and its lines the renderer lays itself. A tooltip's canvas is the box
 * its chrome lays out: the sprite chrome nine-slices both sprites over the rect the client hands them,
 * the text plus 12 Minecraft pixels a side, and the canvas ends at the first pixel they draw, never
 * cutting the text - vanilla's own box, the text plus 4 a side, a pack's wider box whole, or the whole
 * rect, the text plus 12 a side, where a sprite is the missing texture; the procedural chrome pads the
 * text by the request's {@linkplain TextOptions#getPadding() padding}, the client's 4 by default, and its
 * canvas ends at the first pixel it draws. Chat text's canvas ends at the first pixel its lines draw over
 * every frame, which {@link TextKit#inkBounds} answers before any frame is drawn, and a book's at the
 * first pixel its texture or its lines draw over every frame, rounded out to whole Minecraft pixels, which
 * {@link NineSliceKit#extent} and {@link TextKit#inkBounds} answer. A canvas holding no pixel answers the
 * empty 1x1 image: chat text drawing nothing, a tooltip box of no width, which lines that advance nothing
 * make where the code-drawn box pads them by 0, the request's alphas clearing every part of either box, or
 * a book whose texture is clear and whose page draws nothing.
 * <p>
 * When any segment across any line drawn is marked obfuscated, the renderer produces an animated
 * output of {@link TextOptions#getFrameCount()} frames, each rendering obfuscated spans with a
 * fresh {@link TextKit} obfuscation substitution - except a canvas holding no pixel, which is the
 * still, empty 1x1 image, obfuscated or not. A book's line past the fourteenth is not drawn and animates
 * nothing.
 * <p>
 * The sprite box, the code-drawn box, the book texture and the glyphs all blend onto a transparent
 * {@link PixelBuffer#canvas canvas}, so every image answers {@link AlphaMode#PREMULTIPLIED}:
 * {@link ImageData#toBufferedImage()} shares the canvas as a {@code TYPE_INT_ARGB_PRE} image, an encoder
 * writes each see-through pixel's straight colour, and a
 * {@linkplain TextOptions#getLayerDecorator() layer decorator} pass draws onto the canvas.
 */
@Parity(claim = "text-closure", mode = Mode.DEMOTE, subject = Subject.TEXT)
public final class TextRenderer implements Renderer<TextOptions> {

    /**
     * Distance between consecutive text baselines in mcPixels. Vanilla tooltip rendering
     * (every version from 1.8.9 through 26.1) advances {@code 10} mcPixels per line -
     * 8 glyph + 1 descender + 1 row of leading.
     */
    private static final int LINE_HEIGHT_MCPX = 10;

    /**
     * Inter-line gap between the title and body in lore tooltips, in mcPixels.
     */
    private static final int LORE_GAP_MCPX = 2;

    /**
     * The book texture's id, {@code minecraft:gui/book} - the plain texture the client's book screen blits
     * the page from, a pack's file in place of vanilla's.
     */
    private static final @NotNull ResourceId BOOK_TEXTURE_ID = ResourceId.parse("minecraft:gui/book");

    /**
     * The side of the square the book is drawn over, in mcPixels - the client's book blit is 192 a side.
     */
    private static final int BOOK_SIZE_MCPX = 192;

    /**
     * The side the client's book blit reads its texture as, in mcPixels - 256, so the 192 square shows the
     * texture's top-left three quarters whatever its size.
     */
    private static final int BOOK_SHEET_MCPX = 256;

    /**
     * How the book texture is laid over its square - one tile of the whole texture, {@link #BOOK_SHEET_MCPX}
     * a side, which the square cuts to its top-left corner: the client's one quad.
     */
    private static final @NotNull MCMeta.GuiScaling BOOK_SCALING = new MCMeta.GuiScaling.Tile(BOOK_SHEET_MCPX, BOOK_SHEET_MCPX);

    /**
     * The page text's left edge from the book's corner, in mcPixels.
     */
    private static final int BOOK_TEXT_LEFT_MCPX = 36;

    /**
     * The top of the page's first line from the book's corner, in mcPixels - the top of its glyph cell.
     */
    private static final int BOOK_TEXT_TOP_MCPX = 30;

    /**
     * The width a page is wrapped at, in mcPixels - the client's split width for a book page.
     */
    private static final int BOOK_TEXT_WIDTH_MCPX = 114;

    /**
     * The height of the page's text area, in mcPixels - the client draws as many lines as
     * {@link #BOOK_LINE_HEIGHT_MCPX} divides into it, fourteen.
     */
    private static final int BOOK_TEXT_HEIGHT_MCPX = 128;

    /**
     * Distance between consecutive page lines' baselines, in mcPixels - the client's font line height, with
     * no gap after any line.
     */
    private static final int BOOK_LINE_HEIGHT_MCPX = 9;

    /**
     * The page indicator's right edge from the book's corner, in mcPixels - the client right-aligns the
     * indicator to end here.
     */
    private static final int BOOK_PAGE_INDICATOR_RIGHT_MCPX = 148;

    /**
     * The top of the page indicator's glyph cell from the book's corner, in mcPixels.
     */
    private static final int BOOK_PAGE_INDICATOR_TOP_MCPX = 16;

    /**
     * The page indicator's text, the client's English {@code book.pageIndicator} - the page number, then the
     * page count, each written as {@link Integer#toString()} writes it, as the client writes a translation's
     * number.
     */
    private static final @NotNull String BOOK_PAGE_INDICATOR_FORMAT = "Page %1$s of %2$s";

    /**
     * The style a book page's text takes wherever it sets none of its own - black, and a shadow colour of no
     * alpha, which draws no shadow - as the client merges its page text style under every page.
     */
    private static final @NotNull TextSegment BOOK_TEXT_STYLE = TextSegment.builder()
        .color(ChatColor.of(0x000000))
        .shadowColor(ChatColor.builder().color(new Color(0, true)).build())
        .build();

    /**
     * The colour every line is drawn in - opaque white, as the client draws a tooltip line
     * ({@code ClientTextTooltip} passes {@code -1}), a chat line ({@code ARGB.white} at full opacity)
     * and a book's line (its text collector's {@code ARGB.white} at full opacity). A segment naming no colour
     * draws in it, and one naming a colour draws its red, green and blue in this colour's alpha.
     */
    private static final int DEFAULT_COLOR_ARGB = ColorMath.WHITE;

    /**
     * The context the book texture and a {@link ChromeStyle#SPRITE} render's sprite pair are read through,
     * whose presence makes the sprite chrome the one a request naming no chrome is drawn in - empty for a
     * renderer constructed without one, which draws such a request in the procedural chrome and draws no
     * book.
     */
    private final @NotNull Optional<RendererContext> context;

    /**
     * Constructs a new {@code TextRenderer} with no context, which draws chat text and the procedural
     * tooltip chrome, the chrome it draws a request naming none in, and raises a {@link RenderException}
     * for a book, whose page is the game's book texture.
     */
    public TextRenderer() {
        this.context = Optional.empty();
    }

    /**
     * Constructs a new {@code TextRenderer} bound to the given context, through whose pack stack the book
     * texture and a sprite chrome's pair are read - the sprite chrome being the one it draws a request
     * naming none in.
     *
     * @param context the render context resolving the book texture, the tooltip sprites and their sidecars
     */
    public TextRenderer(@NotNull RendererContext context) {
        this.context = Optional.of(context);
    }

    /**
     * Renders the text. A renderer holding a context draws on one built over a context recording every
     * stand-in it draws; one holding none draws on itself and answers no stand-in.
     *
     * @param options the text render options
     * @return the rendered text and every stand-in drawn in it
     * @throws RenderException if this renderer holds no context and the request is a book, or a tooltip
     * naming the sprite chrome
     */
    @Override
    public @NotNull RenderResult render(@NotNull TextOptions options) {
        return this.context
            .map(context -> context.record(recording -> new TextRenderer(recording).draw(options)))
            .orElseGet(() -> RenderResult.of(this.draw(options)));
    }

    /**
     * Draws the image {@link #render} answers, by the request's style: a tooltip or chat text from its lines,
     * or a book page on the book texture.
     *
     * @param options the text render options
     * @return the rendered text, empty where the canvas laid out for it holds no pixel or a tooltip or chat
     * request holds no line
     * @throws RenderException if this renderer holds no context and the request is a book, or a tooltip
     * naming the sprite chrome
     */
    @NotNull ImageData draw(@NotNull TextOptions options) {
        return switch (options.getStyle()) {
            case LORE -> this.drawLines(options, true);
            case CHAT -> this.drawLines(options, false);
            case BOOK -> this.drawPage(options);
        };
    }

    /**
     * Draws a tooltip or chat text from the request's lines - a tooltip in the chrome the request names or,
     * where it names none, this renderer's own, resolving a sprite chrome through this renderer's own context.
     *
     * @param options the text render options
     * @param lore whether the lines are a tooltip's, drawn in a chrome with a gap after the first line, rather
     * than chat text's, drawn on nothing
     * @return the rendered text, empty where the options hold no line or the canvas laid out for them holds
     * no pixel
     * @throws RenderException if this renderer holds no context and the lines are a tooltip's naming the
     * sprite chrome
     */
    private @NotNull ImageData drawLines(@NotNull TextOptions options, boolean lore) {
        if (options.getLines().isEmpty())
            return Timeline.empty();

        boolean animated = hasObfuscation(options.getLines()) || hasAnimatedGradient(options.getLines());
        int ticksPerFrame = ticksPerFrame(options);
        int frameCount = animated ? animationFrameCount(options.getLines(), options, ticksPerFrame) : 1;
        ChromeStyle chromeStyle = options.getChromeStyle()
            .orElse(this.context.isPresent() ? ChromeStyle.SPRITE : ChromeStyle.PROCEDURAL);
        TooltipChrome chrome = TooltipChrome.of(chromeStyle);
        Optional<TooltipChrome.ChromeSprites> sprites = lore && chromeStyle == ChromeStyle.SPRITE
            ? this.context.map(ctx -> TooltipChrome.ChromeSprites.resolve(ctx, options.getTooltipStyle()))
            : Optional.empty();
        int loreGapMcPx = lore && options.getLines().size() > 1 ? LORE_GAP_MCPX : 0;
        int textWMcPx = measureWidthMcPixels(options);

        // The text block runs from the first line's top to the last glyph's descender - NOT a full
        // LINE_HEIGHT past the last baseline.
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        int descentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getDescentMcPixels();
        int textHMcPx = (options.getLines().size() - 1) * LINE_HEIGHT_MCPX + ascentMcPx + descentMcPx + loreGapMcPx;
        TooltipChrome.ChromeBox box = lore
            ? chrome.box(textWMcPx, textHMcPx, sprites, options)
            : chatBox(options.getLines(), frameCount, textWMcPx, textHMcPx);
        if (box.width() <= 0 || box.height() <= 0)
            return Timeline.empty();

        TextPalette palette = this.context.map(context -> context.findTextPalette().orElse(TextPalette.VANILLA))
            .orElse(TextPalette.VANILLA);

        if (!animated)
            return Timeline.still(drawSingleFrame(options, lore, chrome, sprites, box, palette, 0L, 0L).getFirst());

        Timeline.TickLoop timeline = new Timeline.TickLoop(
            0, frameCount, ticksPerFrame, Timeline.delayForFps(options.getFramesPerSecond()));
        return timeline.wrap(f ->
            drawSingleFrame(options, lore, chrome, sprites, box, palette, f, timeline.tickAt(f)).getFirst());
    }

    /**
     * Draws a book page: the book texture read through this renderer's context, and the request's page on it,
     * wrapped and capped as the client's book screen wraps and caps a page - every frame on a canvas ending at
     * the first pixel the texture or the lines draw, rounded out to whole mcPixels. The texture and its
     * sampler, with any stand-in it is, and the palette are read once a render, and the page is wrapped
     * once, before the frame count, which reads its lines.
     *
     * @param options the text render options
     * @return the drawn page, empty where neither the texture nor the lines draw a pixel
     * @throws RenderException if this renderer holds no context
     */
    private @NotNull ImageData drawPage(@NotNull TextOptions options) {
        RendererContext context = this.context.orElseThrow(() -> new RenderException(
            "The BOOK text style draws its page from the texture '%s'; the text renderer needs a RendererContext to read it",
            BOOK_TEXTURE_ID));
        SampledTexture sheet = context.withMissingTexture().resolvePlainTexture(BOOK_TEXTURE_ID.id()).get();
        TextPalette palette = context.findTextPalette().orElse(TextPalette.VANILLA);
        ConcurrentList<LineSegment> lines = pageLines(options.getPage());
        boolean animated = hasObfuscation(lines) || hasAnimatedGradient(lines);
        int ticksPerFrame = ticksPerFrame(options);
        int frameCount = animated ? animationFrameCount(lines, options, ticksPerFrame) : 1;
        Optional<Rectangle> drawn = pageBounds(sheet, lines, frameCount);
        if (drawn.isEmpty())
            return Timeline.empty();

        Rectangle bounds = drawn.get();
        if (!animated)
            return Timeline.still(drawPageFrame(options, lines, sheet, bounds, palette, 0L, 0L));

        Timeline.TickLoop timeline = new Timeline.TickLoop(
            0, frameCount, ticksPerFrame, Timeline.delayForFps(options.getFramesPerSecond()));
        return timeline.wrap(f -> drawPageFrame(options, lines, sheet, bounds, palette, f, timeline.tickAt(f)));
    }

    /**
     * Answers the lines a book page draws, as the client's book screen splits it: the book's text style merged
     * under the page, the page wrapped at the page's width, and as many of its lines kept as the text area
     * holds, the rest dropped unmarked.
     *
     * @param page the page as one text component
     * @return the lines drawn, none for a page of no text
     */
    private static @NotNull ConcurrentList<LineSegment> pageLines(@NotNull TextSegment page) {
        return page.inherit(BOOK_TEXT_STYLE)
            .wrap(BOOK_TEXT_WIDTH_MCPX)
            .stream()
            .limit(BOOK_TEXT_HEIGHT_MCPX / BOOK_LINE_HEIGHT_MCPX)
            .collect(Concurrent.toList());
    }

    /**
     * Answers the bounds of every pixel a book page draws over every frame, before any is drawn, from the book
     * quad's corner in whole mcPixels: the texture's extent over the quad, which {@link NineSliceKit#extent}
     * answers, joined with the lines' ink where the page draws them ({@link #inkOf}), the floor of each minimum
     * and the ceiling of each maximum. They are rounded out because the quad is laid at a whole-mcPixel
     * corner, and a drawn pixel's edge can fall inside a Minecraft pixel.
     *
     * @param sheet the book texture and the sampler it is read through
     * @param lines the lines drawn
     * @param frameCount how many frames the render draws
     * @return the page's drawn bounds from the quad's corner in whole mcPixels, empty where it draws nothing
     */
    private static @NotNull Optional<Rectangle> pageBounds(
        @NotNull SampledTexture sheet,
        @NotNull ConcurrentList<LineSegment> lines,
        int frameCount
    ) {
        Optional<Rectangle2D> drawn = union(
            NineSliceKit.extent(sheet, BOOK_SCALING, BOOK_SIZE_MCPX, BOOK_SIZE_MCPX, 1f),
            inkOf(lines, BOOK_TEXT_LEFT_MCPX, BOOK_TEXT_TOP_MCPX, BOOK_LINE_HEIGHT_MCPX, frameCount));

        return drawn.map(bounds -> {
            int left = (int) Math.floor(bounds.getMinX());
            int top = (int) Math.floor(bounds.getMinY());
            int right = (int) Math.ceil(bounds.getMaxX());
            int bottom = (int) Math.ceil(bounds.getMaxY());
            return new Rectangle(left, top, right - left, bottom - top);
        });
    }

    /**
     * Draws one frame of a book page, in the client's order: the book texture as the client's one quad, at
     * the quad's corner on the canvas, then the page's lines from the client's text origin, each 9 below the
     * last, in the colour every line is drawn in with the shadow asked for - which a run's own colour and
     * shadow colour, the page's black and shadow colour of no alpha among them, override - then the caller's
     * {@link TextOptions#getLayerDecorator() layer decorator}. {@link TextKit} seeds each line's obfuscation
     * scramble from the {@code frameIndex} and the line's number.
     *
     * @param options the text render options
     * @param lines the lines drawn
     * @param sheet the book texture and the sampler it is read through
     * @param bounds the page's drawn bounds from the quad's corner in mcPixels, which the canvas spans
     * @param palette the colours the context's text shader draws in place of the lines' own
     * @param frameIndex the animation frame drawn, from which {@link TextKit} seeds each line's scramble
     * @param tick the absolute animation tick driving gradient scroll phase
     * @return the drawn frame
     */
    private static @NotNull PixelBuffer drawPageFrame(
        @NotNull TextOptions options,
        @NotNull ConcurrentList<LineSegment> lines,
        @NotNull SampledTexture sheet,
        @NotNull Rectangle bounds,
        @NotNull TextPalette palette,
        long frameIndex,
        long tick
    ) {
        int x0 = -bounds.x;
        int y0 = -bounds.y;
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        PixelBuffer buffer = PixelBuffer.canvas(bounds.width * MinecraftFont.MC_PIXEL_SCALE, bounds.height * MinecraftFont.MC_PIXEL_SCALE);

        LayerStack<ImageLayer> stack = new LayerStack<>();
        stack.append(TextSlot.BACKGROUND, frame ->
            NineSliceKit.draw(frame, sheet, BOOK_SCALING, x0, y0, BOOK_SIZE_MCPX, BOOK_SIZE_MCPX, 1f));
        stack.append(TextSlot.TEXT, frame -> {
            MinecraftGraphics g = new MinecraftGraphics(frame);
            // Unused on purpose while the book's page support is not decided - see drawPageIndicator.
            // drawPageIndicator(g, palette, x0, y0, 1, 1);
            for (int i = 0; i < lines.size(); i++) {
                TextKit.drawLine(g, lines.get(i), x0 + BOOK_TEXT_LEFT_MCPX,
                    y0 + BOOK_TEXT_TOP_MCPX + i * BOOK_LINE_HEIGHT_MCPX + ascentMcPx,
                    DEFAULT_COLOR_ARGB, palette, i, frameIndex, tick, true);
            }
        });

        Layers.foldInto(stack, options.getLayerDecorator(), buffer);
        return buffer;
    }

    /**
     * Draws the client's page indicator, {@code Page <page> of <pageCount>}, where the client's book screen
     * draws it above every page: right-aligned to end 148 Minecraft pixels right of the book quad's corner, the
     * top of its glyph cell 16 below it. It is the client's text in the page's text style - black, with a
     * shadow colour of no alpha - measured as the client's font measures it and drawn as a page's line is
     * drawn, so the text palette it is given recolours it as it recolours the page.
     * <p>
     * Unused on purpose. A book render draws one page and its page support is not decided, so the one call to
     * this method, first in the book's text layer, is a commented-out line. Its tests call it directly, which
     * holds it working while the call stays commented out. The page's canvas is measured from what the texture
     * and the lines draw, and from nothing this draws.
     *
     * @param g the graphics over the frame the page is drawn on, its origin the frame's corner
     * @param palette the colours a pack's text shader draws in place of the ones the indicator is given
     * @param x0 the book quad's left edge on the frame, in mcPixels
     * @param y0 the book quad's top edge on the frame, in mcPixels
     * @param page the page number shown, counted from one
     * @param pageCount the page count shown, raised to one where it is lower
     * @return the indicator's width in whole mcPixels, which it is right-aligned by
     */
    static int drawPageIndicator(
        @NotNull MinecraftGraphics g,
        @NotNull TextPalette palette,
        int x0, int y0,
        int page, int pageCount
    ) {
        TextSegment text = TextSegment.literal(String.format(BOOK_PAGE_INDICATOR_FORMAT, page, Math.max(pageCount, 1)))
            .inherit(BOOK_TEXT_STYLE);
        LineSegment line = LineSegment.builder().segments(Concurrent.newList(text)).build();
        int widthMcPx = TextKit.measureLineMcPixels(line);
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        TextKit.drawLine(g, line, x0 + BOOK_PAGE_INDICATOR_RIGHT_MCPX - widthMcPx,
            y0 + BOOK_PAGE_INDICATOR_TOP_MCPX + ascentMcPx, DEFAULT_COLOR_ARGB, palette, 0, 0L, 0L, true);
        return widthMcPx;
    }

    /**
     * Lays out a chat render's box: the bounds of every pixel its lines draw over every frame the render
     * draws ({@link #inkOf}), each line where the text block puts it, and the block's top-left at minus the
     * bounds' corner, so the canvas ends at the first pixel the text draws on every side and the text keeps
     * its place against its own ink. Lines drawing nothing answer a box of no size.
     *
     * @param lines the lines drawn
     * @param frameCount how many frames the render draws
     * @param textWMcPx the text block's width in mcPixels
     * @param textHMcPx the text block's height in mcPixels
     * @return the canvas the lines draw on, in mcPixels, and the text block's place on it
     */
    private static @NotNull TooltipChrome.ChromeBox chatBox(
        @NotNull ConcurrentList<LineSegment> lines,
        int frameCount,
        int textWMcPx, int textHMcPx
    ) {
        return inkOf(lines, 0, 0, LINE_HEIGHT_MCPX, frameCount)
            .map(ink -> new TooltipChrome.ChromeBox((float) ink.getWidth(), (float) ink.getHeight(),
                (float) -ink.getX(), (float) -ink.getY(), textWMcPx, textHMcPx))
            .orElseGet(() -> new TooltipChrome.ChromeBox(0, 0, 0, 0, textWMcPx, textHMcPx));
    }

    /**
     * Answers the bounds of every pixel a block of lines draws over every frame a render draws, before any
     * frame is drawn, as {@link TextKit#inkBounds} answers each line: line {@code i}'s baseline an ascent and
     * {@code i} pitches below the block's top, numbered as it is drawn, in the colour every line is drawn in
     * with the shadow asked for. Only an obfuscated run changes a line's pixels between frames, so a line
     * holding none is measured on the first frame alone.
     *
     * @param lines the lines drawn
     * @param xMcPx the block's left edge in mcPixels
     * @param topMcPx the block's top edge in mcPixels
     * @param pitchMcPx the distance between two lines' baselines in mcPixels
     * @param frameCount how many frames the render draws
     * @return the bounds of what the lines draw in mcPixels, empty where they draw nothing
     */
    private static @NotNull Optional<Rectangle2D> inkOf(
        @NotNull ConcurrentList<LineSegment> lines,
        float xMcPx, float topMcPx,
        int pitchMcPx,
        int frameCount
    ) {
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        Optional<Rectangle2D> drawn = Optional.empty();
        for (int i = 0; i < lines.size(); i++) {
            LineSegment line = lines.get(i);
            int frames = line.getSegments().stream().anyMatch(TextSegment::isObfuscated) ? frameCount : 1;
            for (int frame = 0; frame < frames; frame++)
                drawn = union(drawn, TextKit.inkBounds(line, xMcPx, topMcPx + ascentMcPx + i * pitchMcPx, DEFAULT_COLOR_ARGB, i, frame, true));
        }
        return drawn;
    }

    /**
     * Joins two rectangles either of which may be absent.
     *
     * @param first the first rectangle, empty where there is none
     * @param second the second rectangle, empty where there is none
     * @return the smallest rectangle holding both, empty where neither is present
     */
    private static @NotNull Optional<Rectangle2D> union(@NotNull Optional<Rectangle2D> first, @NotNull Optional<Rectangle2D> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        return Optional.of(first.get().createUnion(second.get()));
    }

    /**
     * Draws one frame of a tooltip or chat text on the given box. Composes an ordered {@link LayerStack} of
     * the tooltip background + gradient border (a tooltip's only) and the glyph rows, the first line at the
     * box's text origin, then applies the caller's {@link TextOptions#getLayerDecorator() layer decorator}
     * before flattening onto a single buffer. {@link TextKit} seeds each line's obfuscation scramble
     * from the {@code frameIndex} and the line's number, so each animation frame shows a fresh
     * scramble.
     *
     * @param options the text render options
     * @param lore whether the frame is a tooltip's, which draws its chrome under the lines and a gap after the
     * first
     * @param chrome the tooltip chrome the request's style selects
     * @param sprites the sprite pair resolved for this render, empty unless a sprite chrome resolved one
     * @param box the canvas and the text block's place on it, in mcPixels - the chrome's box for a
     * tooltip, the bounds of what the lines draw for chat
     * @param palette the colours the context's text shader draws in place of the lines' own
     * @param frameIndex the animation frame drawn, from which {@link TextKit} seeds each line's scramble
     * @param tick the absolute animation tick driving gradient scroll phase
     * @return a single-element list holding the drawn frame buffer
     */
    private static @NotNull ConcurrentList<PixelBuffer> drawSingleFrame(
        @NotNull TextOptions options,
        boolean lore,
        @NotNull TooltipChrome chrome,
        @NotNull Optional<TooltipChrome.ChromeSprites> sprites,
        @NotNull TooltipChrome.ChromeBox box,
        @NotNull TextPalette palette,
        long frameIndex,
        long tick
    ) {
        PixelBuffer buffer = PixelBuffer.canvas(
            Math.round(box.width() * MinecraftFont.MC_PIXEL_SCALE), Math.round(box.height() * MinecraftFont.MC_PIXEL_SCALE));

        // Compose the frame as an ordered ImageLayer stack: the tooltip chrome contributes the
        // background + border (a tooltip's only), then the renderer appends the glyph rows. Callers can
        // splice passes via TextOptions.layerDecorator. The obfuscation animation stays the renderer's
        // per-frame loop - the TEXT layer captures the frame index.
        LayerStack<ImageLayer> stack = new LayerStack<>();
        if (lore)
            chrome.contribute(stack, box, sprites, options);
        stack.append(TextSlot.TEXT, frame -> {
            MinecraftGraphics g = new MinecraftGraphics(frame);
            float baselineMcPx = box.textY() + MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
            for (int i = 0; i < options.getLines().size(); i++) {
                TextKit.drawLine(g, options.getLines().get(i), box.textX(), baselineMcPx, DEFAULT_COLOR_ARGB, palette, i, frameIndex, tick);
                baselineMcPx += LINE_HEIGHT_MCPX;
                if (lore && i == 0)
                    baselineMcPx += LORE_GAP_MCPX;
            }
        });

        Layers.foldInto(stack, options.getLayerDecorator(), buffer);

        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(buffer);
        return frames;
    }

    /**
     * Returns whether any segment across any line is obfuscated ({@code §k}), which promotes the
     * render to an animated multi-frame output.
     */
    private static boolean hasObfuscation(@NotNull ConcurrentList<LineSegment> lines) {
        for (LineSegment line : lines) {
            for (TextSegment segment : line.getSegments())
                if (segment.isObfuscated()) return true;
        }
        return false;
    }

    /**
     * Returns whether any segment carries a scrolling gradient, which - like obfuscation - promotes
     * the render to an animated multi-frame output.
     */
    private static boolean hasAnimatedGradient(@NotNull ConcurrentList<LineSegment> lines) {
        for (LineSegment line : lines) {
            for (TextSegment segment : line.getSegments())
                if (segment.getGradient().map(spec -> spec.scroll() != null).orElse(false)) return true;
        }
        return false;
    }

    /**
     * Game ticks per output frame at the render's frame rate ({@code VANILLA_TICK_FPS / fps}, min 1).
     * At the 20 fps default one output frame is one tick, so {@code tick(frame) == frame}.
     */
    private static int ticksPerFrame(@NotNull TextOptions options) {
        return Math.max(1, Math.round(TextOptions.VANILLA_TICK_FPS / (float) options.getFramesPerSecond()));
    }

    /**
     * Answers how many frames a render draws for a seamless animation loop over the lines it draws.
     * Obfuscation alone keeps the caller's {@link TextOptions#getFrameCount() frameCount}; a scrolling
     * gradient sizes the loop to a whole number of cycles - the LCM of every scroll's {@code cycleTicks}
     * (and the obfuscation loop, when combined), converted to frames and capped at
     * {@link Timeline#MAX_LOOP_MS}.
     *
     * @param lines the lines drawn
     * @param options the text render options, carrying the frame count and the frame rate
     * @param ticksPerFrame the game ticks one output frame spans
     * @return the frame count
     */
    private static int animationFrameCount(@NotNull ConcurrentList<LineSegment> lines, @NotNull TextOptions options, int ticksPerFrame) {
        if (!hasAnimatedGradient(lines))
            return options.getFrameCount();

        long loopTicks = 0;
        for (LineSegment line : lines) {
            for (TextSegment segment : line.getSegments()) {
                GradientSpec.Scroll scroll = segment.getGradient().map(GradientSpec::scroll).orElse(null);
                if (scroll != null)
                    loopTicks = loopTicks == 0 ? scroll.cycleTicks() : Timeline.lcm(loopTicks, scroll.cycleTicks());
            }
        }
        if (hasObfuscation(lines)) {
            long obfuscationLoopTicks = (long) options.getFrameCount() * ticksPerFrame;
            loopTicks = loopTicks == 0 ? obfuscationLoopTicks : Timeline.lcm(loopTicks, obfuscationLoopTicks);
        }

        // Size the loop to the smallest tick span that is BOTH a whole number of scroll cycles
        // (a multiple of loopTicks) AND an integer number of frames (a multiple of ticksPerFrame),
        // so the wrap frame lands exactly on phase 0: the least common multiple of loopTicks and
        // ticksPerFrame. At the 20 fps default (ticksPerFrame 1) this equals loopTicks, unchanged.
        // Without the ticksPerFrame factor a coarser frame cadence (fps < tick rate) would truncate
        // mid-cycle and seam.
        long spanTicks = Timeline.lcm(loopTicks, ticksPerFrame);
        int frameCount = Math.max(1, (int) (spanTicks / ticksPerFrame));
        int delayMs = Timeline.delayForFps(options.getFramesPerSecond());
        int maxFrames = Math.max(1, (int) (Timeline.MAX_LOOP_MS / delayMs));
        return Math.min(frameCount, maxFrames);
    }

    /**
     * Measures the text block's width: its widest line in whole mcPixels, as the client sizes a tooltip
     * from the widest of its lines' {@code Font#width}. Lines that advance nothing measure 0.
     *
     * @param options the text render options
     * @return the widest line's width in whole mcPixels
     */
    private static int measureWidthMcPixels(@NotNull TextOptions options) {
        int max = 0;
        for (LineSegment line : options.getLines())
            max = Math.max(max, TextKit.measureLineMcPixels(line));
        return max;
    }

}
