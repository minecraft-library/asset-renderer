package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.AlphaMode;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.bake.gui.TextKit;
import lib.minecraft.renderer.bake.gui.TooltipChrome;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.slot.TextSlot;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;

import java.awt.geom.Rectangle2D;
import java.util.Optional;

/**
 * Renders styled Minecraft text in one of two modes: item-style lore tooltips with a bordered
 * background, or plain chat text on a transparent canvas. Its text is drawn in the colours its
 * context's pack stack swaps them for ({@link RendererContext#findTextPalette}), and every colour as
 * given where the stack holds no palette or the renderer holds no context. Every line is drawn in
 * opaque white, the colour the client draws a tooltip line and a chat line in, so a segment naming no
 * colour draws white, any shadow it derives a quarter as bright, and one naming a colour draws that
 * colour opaque.
 * <p>
 * The LORE background and border are contributed by the {@link TooltipChrome} the request's
 * {@linkplain TextOptions#getChromeStyle() chrome style} selects: {@link ChromeStyle#PROCEDURAL} draws
 * the legacy vanilla palette (background {@code 0xF0100010}, gradient border {@code 0x505000FF} to
 * {@code 0x5028007F}) with the caller-configurable {@link TextOptions#getBackgroundAlpha()} /
 * {@link TextOptions#getBorderAlpha()} alphas; {@link ChromeStyle#SPRITE} nine-slices the pack's
 * {@code tooltip/background} and {@code tooltip/frame} sprites, or the pair the request's
 * {@linkplain TextOptions#getTooltipStyle() tooltip style} names. The renderer resolves that pair once
 * per render through the context it was constructed with; a lore render asking for the sprite chrome
 * raises a {@link RenderException} only when the renderer holds no context, so a renderer built without
 * one draws chat text and the procedural chrome. A sprite of the pair no selected pack ships, or one
 * that cannot be read, draws as the missing texture stretched over the client's whole sprite rect, as
 * the client draws its missing sprite, and is named in the result as a stand-in. The renderer owns
 * only the glyph rows and the text block's measure. A tooltip's canvas is the box its chrome lays out:
 * the sprite chrome nine-slices both sprites over the rect the client hands them, the text plus 12
 * Minecraft pixels a side, and the canvas ends at the first pixel they draw, never cutting the text -
 * vanilla's own box, the text plus 4 a side, a pack's wider box whole, or the whole rect, the text
 * plus 12 a side, where a sprite is the missing texture; the procedural chrome pads the text by the
 * request's {@linkplain TextOptions#getPadding() padding}. Chat text's canvas ends at the first pixel
 * its lines draw over every frame, which {@link TextKit#inkBounds} answers before any frame is drawn. A
 * canvas holding no pixel answers the empty 1x1 image: chat text drawing nothing, or a tooltip box of
 * no width, which lines that advance nothing make where the procedural chrome pads them by 0 or the
 * request's alphas clear both sprites.
 * <p>
 * When any segment across any line is marked obfuscated, the renderer produces an animated
 * output of {@link TextOptions#getFrameCount()} frames, each rendering obfuscated spans with a
 * fresh {@link TextKit} obfuscation substitution - except a canvas holding no pixel, which is the
 * still, empty 1x1 image, obfuscated or not.
 * <p>
 * The image is held in the convention its frames are drawn in. The sprite box and the glyphs blend
 * onto the transparent canvas, so a {@link ChromeStyle#SPRITE} tooltip, chat text and the empty image
 * answer {@link AlphaMode#PREMULTIPLIED}: {@link ImageData#toBufferedImage()} is then a straight
 * copy that edits made through it do not reach, and an encoder writes each see-through pixel's
 * straight colour. The {@link ChromeStyle#PROCEDURAL} box paints its colours as given, so a
 * procedural tooltip drawing a box answers {@link AlphaMode#STRAIGHT}. A
 * {@linkplain TextOptions#getLayerDecorator() layer decorator} pass writes its colour in the
 * convention the image answers.
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
     * The colour every line is drawn in - opaque white, as the client draws a tooltip line
     * ({@code ClientTextTooltip} passes {@code -1}) and a chat line ({@code ARGB.white} at full opacity).
     * A segment naming no colour draws in it, and one naming a colour draws its red, green and blue in
     * this colour's alpha.
     */
    private static final int DEFAULT_COLOR_ARGB = ColorMath.WHITE;

    /**
     * The context a {@link ChromeStyle#SPRITE} render resolves its sprite pair through, empty for a
     * renderer constructed without one.
     */
    private final @NotNull Optional<RendererContext> context;

    /**
     * Constructs a new {@code TextRenderer} with no context, which draws chat text and the procedural
     * tooltip chrome.
     */
    public TextRenderer() {
        this.context = Optional.empty();
    }

    /**
     * Constructs a new {@code TextRenderer} bound to the given context, through whose pack stack a
     * sprite chrome resolves its pair.
     *
     * @param context the render context resolving the tooltip sprites and their sidecars
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
     */
    @Override
    public @NotNull RenderResult render(@NotNull TextOptions options) {
        return this.context
            .map(context -> context.record(recording -> new TextRenderer(recording).draw(options)))
            .orElseGet(() -> RenderResult.of(this.draw(options)));
    }

    /**
     * Draws the image {@link #render} answers, resolving a sprite chrome through this renderer's own
     * context.
     *
     * @param options the text render options
     * @return the rendered text, empty where the options hold no line or the canvas laid out for them
     * holds no pixel
     */
    @NotNull ImageData draw(@NotNull TextOptions options) {
        if (options.getLines().isEmpty())
            return Timeline.empty();

        boolean isLore = options.getStyle() == TextOptions.Style.LORE;
        boolean animated = hasObfuscation(options.getLines()) || hasAnimatedGradient(options.getLines());
        int ticksPerFrame = ticksPerFrame(options);
        int frameCount = animated ? animationFrameCount(options, ticksPerFrame) : 1;
        TooltipChrome chrome = TooltipChrome.of(options.getChromeStyle());
        Optional<TooltipChrome.ChromeSprites> sprites = isLore && options.getChromeStyle() == ChromeStyle.SPRITE
            ? this.context.map(ctx -> TooltipChrome.ChromeSprites.resolve(ctx, options.getTooltipStyle()))
            : Optional.empty();
        int loreGapMcPx = isLore && options.getLines().size() > 1 ? LORE_GAP_MCPX : 0;
        int textWMcPx = measureWidthMcPixels(options);

        // The text block runs from the first line's top to the last glyph's descender - NOT a full
        // LINE_HEIGHT past the last baseline.
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        int descentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getDescentMcPixels();
        int textHMcPx = (options.getLines().size() - 1) * LINE_HEIGHT_MCPX + ascentMcPx + descentMcPx + loreGapMcPx;
        TooltipChrome.ChromeBox box = isLore
            ? chrome.box(textWMcPx, textHMcPx, sprites, options)
            : chatBox(options.getLines(), frameCount, textWMcPx, textHMcPx);
        if (box.width() <= 0 || box.height() <= 0)
            return Timeline.empty();

        // The code-drawn box paints its colours as given, so a frame holding it is straight; the sprite box
        // and the glyphs blend onto the transparent canvas, so every other frame is held premultiplied.
        AlphaMode alphaMode = isLore && options.getChromeStyle() == ChromeStyle.PROCEDURAL
            ? AlphaMode.STRAIGHT
            : AlphaMode.PREMULTIPLIED;
        TextPalette palette = this.context.map(context -> context.findTextPalette().orElse(TextPalette.VANILLA))
            .orElse(TextPalette.VANILLA);

        if (!animated)
            return Timeline.Static.ZERO.wrap(f -> drawSingleFrame(options, chrome, sprites, box, palette, 0L, 0L).getFirst(), alphaMode);

        Timeline.TickLoop timeline = new Timeline.TickLoop(
            0, frameCount, ticksPerFrame, Timeline.delayForFps(options.getFramesPerSecond()));
        return timeline.wrap(f ->
            drawSingleFrame(options, chrome, sprites, box, palette, f, timeline.tickAt(f)).getFirst(), alphaMode);
    }

    /**
     * Lays out a chat render's box: the bounds of every pixel its lines draw over every frame the
     * render draws, which {@link TextKit#inkBounds} answers before any frame is drawn. Each line is
     * measured where the text block puts it - its baseline an ascent and its line pitch down from the
     * block's top, numbered as it is drawn - and the block's top-left lands at minus the bounds'
     * corner, so the canvas ends at the first pixel the text draws on every side and the text keeps
     * its place against its own ink. Only an obfuscated run changes a line's pixels between frames, so
     * a line holding none is measured on the first frame alone. Lines drawing nothing answer a box of
     * no size.
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
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        Optional<Rectangle2D> drawn = Optional.empty();
        for (int i = 0; i < lines.size(); i++) {
            LineSegment line = lines.get(i);
            int frames = line.getSegments().stream().anyMatch(TextSegment::isObfuscated) ? frameCount : 1;
            for (int frame = 0; frame < frames; frame++)
                drawn = union(drawn, TextKit.inkBounds(line, 0, ascentMcPx + i * LINE_HEIGHT_MCPX, DEFAULT_COLOR_ARGB, i, frame, true));
        }

        return drawn
            .map(ink -> new TooltipChrome.ChromeBox((float) ink.getWidth(), (float) ink.getHeight(),
                (float) -ink.getX(), (float) -ink.getY(), textWMcPx, textHMcPx))
            .orElseGet(() -> new TooltipChrome.ChromeBox(0, 0, 0, 0, textWMcPx, textHMcPx));
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
     * Draws one frame on the given box. Composes an ordered {@link LayerStack} of the tooltip
     * background + gradient border (LORE style only) and the glyph rows, the first line at the box's
     * text origin, then applies the caller's {@link TextOptions#getLayerDecorator() layer decorator}
     * before flattening onto a single buffer. {@link TextKit} seeds each line's obfuscation scramble
     * from the {@code frameIndex} and the line's number, so each animation frame shows a fresh
     * scramble.
     *
     * @param options the text render options
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
        @NotNull TooltipChrome chrome,
        @NotNull Optional<TooltipChrome.ChromeSprites> sprites,
        @NotNull TooltipChrome.ChromeBox box,
        @NotNull TextPalette palette,
        long frameIndex,
        long tick
    ) {
        boolean isLore = options.getStyle() == TextOptions.Style.LORE;
        PixelBuffer buffer = PixelBuffer.create(
            Math.round(box.width() * MinecraftFont.MC_PIXEL_SCALE), Math.round(box.height() * MinecraftFont.MC_PIXEL_SCALE));

        // Compose the frame as an ordered ImageLayer stack: the tooltip chrome contributes the
        // background + border (LORE only), then the renderer appends the glyph rows. Callers can splice
        // passes via TextOptions.layerDecorator. The obfuscation animation stays the renderer's per-frame
        // loop - the TEXT layer captures the frame index.
        LayerStack<ImageLayer> stack = new LayerStack<>();
        if (isLore)
            chrome.contribute(stack, box, sprites, options);
        stack.append(TextSlot.TEXT, frame -> {
            MinecraftGraphics g = new MinecraftGraphics(frame);
            float baselineMcPx = box.textY() + MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
            for (int i = 0; i < options.getLines().size(); i++) {
                TextKit.drawLine(g, options.getLines().get(i), box.textX(), baselineMcPx, DEFAULT_COLOR_ARGB, palette, i, frameIndex, tick);
                baselineMcPx += LINE_HEIGHT_MCPX;
                if (isLore && i == 0)
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
     * The number of frames to render for a seamless animation loop. Obfuscation alone keeps the
     * caller's {@link TextOptions#getFrameCount() frameCount} (byte-identical to the pre-gradient
     * behaviour); a scrolling gradient sizes the loop to a whole number of cycles - the LCM of every
     * scroll's {@code cycleTicks} (and the obfuscation loop, when combined), converted to frames and
     * capped at {@link Timeline#MAX_LOOP_MS}.
     */
    private static int animationFrameCount(@NotNull TextOptions options, int ticksPerFrame) {
        if (!hasAnimatedGradient(options.getLines()))
            return options.getFrameCount();

        long loopTicks = 0;
        for (LineSegment line : options.getLines()) {
            for (TextSegment segment : line.getSegments()) {
                GradientSpec.Scroll scroll = segment.getGradient().map(GradientSpec::scroll).orElse(null);
                if (scroll != null)
                    loopTicks = loopTicks == 0 ? scroll.cycleTicks() : Timeline.lcm(loopTicks, scroll.cycleTicks());
            }
        }
        if (hasObfuscation(options.getLines())) {
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
