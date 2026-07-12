package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.compose.FrameCompositor;
import lib.minecraft.renderer.engine.compose.TooltipChrome;
import lib.minecraft.renderer.engine.compose.layer.ImageLayer;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.kit.TextKit;
import lib.minecraft.renderer.option.TextOptions;
import lib.minecraft.renderer.option.slot.TextSlot;
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.ColorSegment;
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
            return FrameCompositor.wrapFrames(singleFrame(1, 1, ColorMath.TRANSPARENT), 0);

        boolean isLore = options.getStyle() == TextOptions.Style.LORE;
        boolean animated = hasObfuscation(options.getLines());
        int padMcPx = isLore ? options.getChrome().paddingMcPx(options) : 0;
        int loreGapMcPx = isLore && options.getLines().size() > 1 ? LORE_GAP_MCPX : 0;
        int canvasWMcPx = measureWidthMcPixels(options) + padMcPx * 2;

        // Canvas height measures from the top padding to the last glyph's descender plus
        // bottom padding - NOT a full LINE_HEIGHT past the last baseline. Symmetric padding
        // matches in-game vanilla rendering.
        int ascentMcPx = MinecraftFont.REGULAR.getFontMetrics().getAscentMcPixels();
        int descentMcPx = MinecraftFont.REGULAR.getFontMetrics().getDescentMcPixels();
        int linesHeightMcPx = (options.getLines().size() - 1) * LINE_HEIGHT_MCPX + ascentMcPx + descentMcPx;
        int canvasHMcPx = linesHeightMcPx + padMcPx * 2 + loreGapMcPx;

        if (!animated)
            return FrameCompositor.wrapFrames(drawSingleFrame(options, canvasWMcPx, canvasHMcPx, 0L), 0);

        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        for (int frameIndex = 0; frameIndex < options.getFrameCount(); frameIndex++)
            frames.addAll(drawSingleFrame(options, canvasWMcPx, canvasHMcPx, frameIndex));

        int delayMs = Math.max(1, Math.round(1000f / options.getFramesPerSecond()));
        return FrameCompositor.wrapFrames(frames, delayMs);
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
     * @return a single-element list holding the drawn frame buffer
     */
    private static @NotNull ConcurrentList<PixelBuffer> drawSingleFrame(
        @NotNull TextOptions options,
        int canvasWMcPx,
        int canvasHMcPx,
        long frameSeed
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
            int baselineMcPx = padMcPx + MinecraftFont.REGULAR.getFontMetrics().getAscentMcPixels();
            for (int i = 0; i < options.getLines().size(); i++) {
                TextKit.drawLine(g, options.getLines().get(i), padMcPx, baselineMcPx, DEFAULT_COLOR_ARGB, frameSeed);
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
     * Measures the widest line in mcPixels, clamped to a minimum of 16 mcPixels so short strings
     * still produce a non-degenerate canvas.
     */
    private static int measureWidthMcPixels(@NotNull TextOptions options) {
        int max = 0;
        for (LineSegment line : options.getLines())
            max = Math.max(max, TextKit.measureLineMcPixels(line));
        return Math.max(16, max);
    }

    /**
     * Builds a single-frame list holding one flat-filled buffer. Used for the empty-input
     * degenerate case (a 1x1 transparent frame).
     */
    private static @NotNull ConcurrentList<PixelBuffer> singleFrame(int w, int h, int fill) {
        PixelBuffer buffer = PixelBuffer.create(w, h);
        buffer.fill(fill);
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(buffer);
        return frames;
    }

}
