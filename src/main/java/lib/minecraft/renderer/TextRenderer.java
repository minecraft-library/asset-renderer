package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.engine.compose.TooltipChrome;
import lib.minecraft.renderer.engine.compose.layer.ImageLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.kit.TextKit;
import lib.minecraft.renderer.option.TextOptions;
import lib.minecraft.renderer.option.slot.TextSlot;
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;

/**
 * Renders styled Minecraft text in one of two modes: item-style lore tooltips with a bordered
 * background, or plain chat text on a transparent canvas.
 * <p>
 * The LORE background and border are contributed by the {@linkplain TextOptions#getChrome() tooltip
 * chrome}: {@link TooltipChrome.Vanilla#PROCEDURAL} draws the legacy vanilla palette (background
 * {@code 0xF0100010}, gradient border {@code 0x505000FF} to {@code 0x5028007F}) with the
 * caller-configurable {@link TextOptions#getBackgroundAlpha()} / {@link TextOptions#getBorderAlpha()}
 * alphas; {@link TooltipChrome.Vanilla#SPRITE} nine-slices the pack's {@code tooltip/background} and
 * {@code tooltip/frame} sprites (resolved by the caller into {@link TextOptions#getChromeSprites()}).
 * The renderer owns only the glyph rows and the canvas sizing.
 * <p>
 * When any segment across any line is marked obfuscated, the renderer produces an animated
 * output of {@link TextOptions#getFrameCount()} frames, each rendering obfuscated spans with a
 * fresh {@link TextKit} obfuscation substitution.
 */
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
     * Default glyph colour for text segments that carry no explicit colour - vanilla lore grey.
     */
    private static final int DEFAULT_COLOR_ARGB = ChatColor.Legacy.GRAY.rgb();

    /** {@inheritDoc} */
    @Override
    public @NotNull ImageData render(@NotNull TextOptions options) {
        if (options.getLines().isEmpty())
            return Timeline.empty();

        boolean isLore = options.getStyle() == TextOptions.Style.LORE;
        boolean animated = hasObfuscation(options.getLines()) || hasAnimatedGradient(options.getLines());
        int padMcPx = isLore ? options.getChrome().paddingMcPx(options) : 0;
        int loreGapMcPx = isLore && options.getLines().size() > 1 ? LORE_GAP_MCPX : 0;
        int canvasWMcPx = measureWidthMcPixels(options) + padMcPx * 2;

        // Canvas height measures from the top padding to the last glyph's descender plus
        // bottom padding - NOT a full LINE_HEIGHT past the last baseline. Symmetric padding
        // matches in-game vanilla rendering.
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        int descentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getDescentMcPixels();
        int linesHeightMcPx = (options.getLines().size() - 1) * LINE_HEIGHT_MCPX + ascentMcPx + descentMcPx;
        int canvasHMcPx = linesHeightMcPx + padMcPx * 2 + loreGapMcPx;

        if (!animated)
            return Timeline.still(drawSingleFrame(options, canvasWMcPx, canvasHMcPx, 0L, 0L).getFirst());

        int ticksPerFrame = ticksPerFrame(options);
        int frameCount = animationFrameCount(options, ticksPerFrame);
        Timeline.TickLoop timeline = new Timeline.TickLoop(
            0, frameCount, ticksPerFrame, Timeline.delayForFps(options.getFramesPerSecond()));
        return timeline.wrap(f ->
            drawSingleFrame(options, canvasWMcPx, canvasHMcPx, f, timeline.tickAt(f)).getFirst());
    }

    /**
     * Draws one frame at the given mcPixel canvas dimensions. Composes an ordered
     * {@link LayerStack} of the tooltip background + gradient border (LORE style only) and the
     * glyph rows, then applies the caller's {@link TextOptions#getLayerDecorator() layer decorator}
     * before flattening onto a single buffer. The {@code frameSeed} drives the per-frame
     * obfuscation substitution so each animation frame shows a fresh scramble.
     *
     * @param options the text render options
     * @param canvasWMcPx the canvas width in mcPixels
     * @param canvasHMcPx the canvas height in mcPixels
     * @param frameSeed the obfuscation seed for this frame
     * @param tick the absolute animation tick driving gradient scroll phase
     * @return a single-element list holding the drawn frame buffer
     */
    private static @NotNull ConcurrentList<PixelBuffer> drawSingleFrame(
        @NotNull TextOptions options,
        int canvasWMcPx,
        int canvasHMcPx,
        long frameSeed,
        long tick
    ) {
        boolean isLore = options.getStyle() == TextOptions.Style.LORE;
        int padMcPx = isLore ? options.getChrome().paddingMcPx(options) : 0;

        int w = canvasWMcPx * MinecraftFont.MC_PIXEL_SCALE;
        int h = canvasHMcPx * MinecraftFont.MC_PIXEL_SCALE;
        PixelBuffer buffer = PixelBuffer.create(w, h);

        // Compose the frame as an ordered ImageLayer stack: the tooltip chrome contributes the
        // background + border (LORE only), then the renderer appends the glyph rows. Callers can splice
        // passes via TextOptions.layerDecorator. The obfuscation animation stays the renderer's per-frame
        // loop - the TEXT layer captures the seed.
        LayerStack<ImageLayer> stack = new LayerStack<>();
        if (isLore) {
            TooltipChrome.ChromeBox box = new TooltipChrome.ChromeBox(w, h, MinecraftFont.MC_PIXEL_SCALE);
            options.getChrome().contribute(stack, box, options.getChromeSprites(), options);
        }
        stack.append(TextSlot.TEXT, frame -> {
            MinecraftGraphics g = new MinecraftGraphics(frame);
            int baselineMcPx = padMcPx + MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
            for (int i = 0; i < options.getLines().size(); i++) {
                TextKit.drawLine(g, options.getLines().get(i), padMcPx, baselineMcPx, DEFAULT_COLOR_ARGB, frameSeed, tick);
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
            for (ColorSegment segment : line.getSegments())
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
            for (ColorSegment segment : line.getSegments())
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
            for (ColorSegment segment : line.getSegments()) {
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
     * Measures the widest line in mcPixels, clamped to a minimum of 16 mcPixels so short strings
     * still produce a non-degenerate canvas.
     */
    private static int measureWidthMcPixels(@NotNull TextOptions options) {
        int max = 0;
        for (LineSegment line : options.getLines())
            max = Math.max(max, TextKit.measureLineMcPixels(line));
        return Math.max(16, max);
    }

}
