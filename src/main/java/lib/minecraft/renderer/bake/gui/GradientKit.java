package lib.minecraft.renderer.bake.gui;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGlyph;
import lib.minecraft.text.font.MinecraftGlyphVector;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Static gradient-text rendering utilities - the compose-side realization of {@link GradientSpec}.
 * Splits into a pure color-evaluation core (unit-testable) and per-segment draw paths driven by the
 * {@link GradientSpec#PER_LETTER per-letter} vs per-pixel fidelity lever.
 * <p>
 * The color evaluator {@link #sample(GradientSpec, float)} maps a normalized position {@code t}
 * along the segment to an rgb; {@link #scrolledT} slides {@code t} by the scroll phase derived from
 * the absolute animation tick, and {@link #resolveShear} yields the band slant. Glyphs render as
 * the white-glyph-multiply identity: each glyph pixel's alpha mask is tinted by the sampled color
 * and {@code NORMAL}-blended, exactly as {@code MinecraftGraphics.blitGlyph} does for a solid tint -
 * so a degenerate single-color gradient matches the solid path pixel for pixel.
 * <p>
 * Positions are in mcPixels, a segment's origin fractional where it follows another. The
 * per-letter path draws each letter through the graphics in its own color, so the graphics
 * converts to output pixels where it blits. The per-pixel path tints each glyph pixel itself,
 * converting its mcPixel origin once where it writes, since its bands are output pixels. The
 * decoration bars and the per-pixel path write the target directly, assuming a zero graphics
 * translate (tooltip and menu buffers are drawn at their origin).
 *
 * @see GradientSpec
 * @see TextKit
 */
@UtilityClass
public class GradientKit {

    /**
     * Per-channel low-bit-clear + right-shift-2 vanilla shadow formula {@code (rgb & 0xFCFCFC) >> 2}.
     */
    private static final int SHADOW_RGB_MASK = 0xFCFCFC;

    // --- entry ---

    /**
     * Draws a gradient segment's (already obfuscation-substituted) {@code text} at the given mcPixel
     * origin and returns its advance in mcPixels. Routes to the per-letter or per-pixel path by
     * {@link GradientSpec#bandPx()}.
     *
     * @param g the graphics whose target buffer receives the glyphs
     * @param segment the styled segment (supplies font style + decorations + italic)
     * @param spec the gradient spec
     * @param text the segment text after obfuscation substitution
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param tick the absolute animation tick driving scroll phase
     * @return the advance width in mcPixels, its fraction kept
     */
    static float drawSegment(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull GradientSpec spec,
        @NotNull String text,
        float xMcPx, float yMcPx,
        long tick
    ) {
        return drawSegment(g, segment, spec, text, xMcPx, yMcPx, tick, true);
    }

    /**
     * Draws a gradient segment with the drop shadow declinable, so a gradient title declines one
     * where a plain title does rather than keeping a shadow the caller asked to be rid of.
     *
     * @param g the graphics whose target buffer receives the glyphs
     * @param segment the styled segment (supplies font style + decorations + italic)
     * @param spec the gradient spec
     * @param text the segment text after obfuscation substitution
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param tick the absolute animation tick driving scroll phase
     * @param dropShadow whether to draw the offset shadow pass beneath the glyphs
     * @return the advance width in mcPixels, its fraction kept
     */
    static float drawSegment(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull GradientSpec spec,
        @NotNull String text,
        float xMcPx, float yMcPx,
        long tick,
        boolean dropShadow
    ) {
        MinecraftFont font = MinecraftFont.Vanilla.of(segment.fontStyle());
        float widthMcPx = font.metrics().stringAdvanceMcPixels(text);
        if (widthMcPx <= 0) return 0;

        if (spec.bandPx() == GradientSpec.PER_LETTER)
            drawPerLetter(g, segment, spec, font, text, widthMcPx, xMcPx, yMcPx, tick, dropShadow);
        else
            drawPerPixel(g.target(), segment, spec, font, text, widthMcPx, xMcPx, yMcPx, tick, dropShadow);
        return widthMcPx;
    }

    /**
     * Answers what {@link #drawSegment} lays down for a gradient segment's (already
     * obfuscation-substituted) {@code text} at the given mcPixel origin, before it is drawn - the
     * bounds of every pixel it draws, in mcPixels. Empty where the text's width is not positive, which
     * the draw skips; otherwise the pixels the text library answers the run's glyphs draw
     * ({@link MinecraftGlyphVector#getVisualBounds()}), since both paths place a glyph where
     * {@link MinecraftGraphics#drawString(String, float, float)} does, with the bars over the run's
     * width, each again at the shadow's offset when the shadow is drawn. Neither the colour nor the
     * scroll moves a pixel's place, so the tick does not enter.
     *
     * @param segment the styled segment (supplies font style + decorations)
     * @param text the segment text after obfuscation substitution
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param dropShadow whether the offset shadow pass is drawn
     * @return the bounds of what the segment draws in mcPixels, empty where it draws nothing
     */
    static @NotNull Optional<Rectangle2D> inkBounds(
        @NotNull TextSegment segment,
        @NotNull String text,
        float xMcPx, float yMcPx,
        boolean dropShadow
    ) {
        MinecraftFont font = MinecraftFont.Vanilla.of(segment.fontStyle());
        float widthMcPx = font.metrics().stringAdvanceMcPixels(text);
        if (widthMcPx <= 0) return Optional.empty();

        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int xOut = Math.round(xMcPx * scale);
        int baselineOut = Math.round(yMcPx * scale);
        List<Rectangle2D> drawn = new ArrayList<>();
        Rectangle glyphs = font.layout(text).getVisualBounds().getBounds();
        glyphs.translate(xOut, baselineOut);
        TextKit.addInk(drawn, glyphs, dropShadow);
        for (Rectangle bar : bars(segment, xOut, baselineOut, Math.round(widthMcPx * scale)))
            TextKit.addInk(drawn, bar, dropShadow);
        return drawn.stream().reduce(Rectangle2D::createUnion);
    }

    // --- per-letter fidelity (bandPx == 0) ---

    /**
     * Per-letter path: each glyph samples ONE color at its advance-span center; two-phase (all
     * shadows, then all mains) so adjacent glyph bitmaps overlap as in the solid path.
     * Decorations follow the gradient, colored per glyph.
     */
    private static void drawPerLetter(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull GradientSpec spec,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float widthMcPx,
        float xMcPx, float yMcPx,
        long tick,
        boolean dropShadow
    ) {
        int[] codepoints = text.codePoints().toArray();
        g.setFont(font);

        // Shadow pass (offset +1,+1 mcPx), darkened per-glyph color.
        if (dropShadow)
            drawLetterPass(g, segment, spec, font, codepoints, widthMcPx, xMcPx + TextKit.SHADOW_OFFSET_MCPX, yMcPx + TextKit.SHADOW_OFFSET_MCPX, tick, true);

        // Main pass.
        drawLetterPass(g, segment, spec, font, codepoints, widthMcPx, xMcPx, yMcPx, tick, false);
    }

    /**
     * Draws one pass of the per-letter path: each letter through the graphics in the color its
     * advance-span center samples - darkened on the shadow pass - with its decoration bars, the pen
     * keeping each letter's fraction.
     */
    private static void drawLetterPass(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull GradientSpec spec,
        @NotNull MinecraftFont font,
        int @NotNull [] codepoints,
        float widthMcPx,
        float xMcPx, float yMcPx,
        long tick,
        boolean shadow
    ) {
        float penMcPx = 0;
        for (int cp : codepoints) {
            String letter = Character.toString(cp);
            float advanceMcPx = font.metrics().stringAdvanceMcPixels(letter);
            int sampled = sample(spec, letterT(penMcPx, advanceMcPx, widthMcPx, spec, tick));
            int rgb = shadow ? shadowRgb(sampled) : sampled;
            g.setColor(new java.awt.Color(rgb));
            g.drawString(letter, xMcPx + penMcPx, yMcPx);
            drawDecorations(g.target(), segment, xMcPx + penMcPx, yMcPx, advanceMcPx, rgb);
            penMcPx += advanceMcPx;
        }
    }

    /**
     * The advance-center position for a glyph, scroll-adjusted: {@code t = (pen + adv/2) /
     * width}, then slid by the scroll phase.
     */
    private static float letterT(float penMcPx, float advanceMcPx, float widthMcPx, @NotNull GradientSpec spec, long tick) {
        float center = (penMcPx + advanceMcPx / 2f) / widthMcPx;
        return scrolledT(center, spec.scroll(), tick);
    }

    /**
     * Draws the strikethrough / underline bars for one glyph's advance span in {@code rgb}, so
     * decorations follow the gradient per letter. The mcPixel span converts to output pixels here,
     * where the bars are written.
     */
    private static void drawDecorations(@NotNull PixelBuffer target, @NotNull TextSegment segment, float xMcPx, float baselineMcPx, float advanceMcPx, int rgb) {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int cursorX = Math.round(xMcPx * scale);
        int baselineY = Math.round(baselineMcPx * scale);
        int advanceOut = Math.round(advanceMcPx * scale);
        int argb = 0xFF000000 | (rgb & 0xFFFFFF);
        for (Rectangle bar : bars(segment, cursorX, baselineY, advanceOut))
            fillClamped(target, bar.x, bar.y, bar.width, bar.height, argb);
    }

    /**
     * Answers the strikethrough and underline bars over a span of a gradient run, in output pixels and
     * in the order they are drawn - each from the span's start and as wide as it, with no pad. Both
     * draw paths fill them glyph by glyph and {@link #inkBounds} reads them over the whole run.
     *
     * @param segment the segment, supplying which bars it carries
     * @param xOut the span's start X in output pixels
     * @param baselineOut the baseline Y in output pixels
     * @param widthOut the span's width in output pixels
     * @return the bars the span carries, in draw order
     */
    private static @NotNull List<Rectangle> bars(@NotNull TextSegment segment, int xOut, int baselineOut, int widthOut) {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        List<Rectangle> bars = new ArrayList<>(2);
        if (segment.isStrikethrough())
            bars.add(new Rectangle(xOut, baselineOut + TextKit.STRIKETHROUGH_OFFSET_MCPX * scale, widthOut, TextKit.STRIKETHROUGH_THICKNESS_MCPX * scale));
        if (segment.isUnderlined())
            bars.add(new Rectangle(xOut, baselineOut + TextKit.UNDERLINE_OFFSET_MCPX * scale, widthOut, TextKit.UNDERLINE_THICKNESS_MCPX * scale));
        return bars;
    }

    // --- per-pixel fidelity (bandPx >= 1) ---

    /**
     * Per-pixel path: uniform bands of {@link GradientSpec#bandPx()} output px over the segment,
     * each glyph pixel tinted by {@link #sample(GradientSpec, float)} at its band center.
     * This is the white-glyph-multiply identity realized directly - each glyph pixel's alpha mask is
     * tinted by its band color and {@code NORMAL}-blended onto the frame, confining the multiply to
     * the glyph mask so neighbours, descenders' background, and chrome are never touched (the same
     * guarantee a per-segment scratch gives, without a transparent intermediate that
     * {@code BlendMode.NORMAL} would premultiply). Bands slant by the shear; the shadow
     * pass is split out and samples the MAIN letterform position (offset by {@code +delta}) so shadow
     * bands sit exactly under main bands. The mcPixel origin and width convert to output pixels once,
     * here, since the bands are output pixels.
     */
    private static void drawPerPixel(
        @NotNull PixelBuffer target,
        @NotNull TextSegment segment,
        @NotNull GradientSpec spec,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float widthMcPx,
        float xMcPx, float yMcPx,
        long tick,
        boolean dropShadow
    ) {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int baseX = Math.round(xMcPx * scale);
        int baselineY = Math.round(yMcPx * scale);
        int segWidthOut = Math.round(widthMcPx * scale);
        int delta = TextKit.SHADOW_OFFSET_MCPX * scale;
        int bandPx = spec.bandPx();
        float shear = resolveShear(spec, segment.isItalic());
        int[] codepoints = text.codePoints().toArray();

        // Band rgb at an absolute frame pixel, relative to the MAIN letterform.
        PixelColor main = (px, py) -> bandColor(spec, px - baseX, baselineY - py, shear, bandPx, segWidthOut, tick);
        // Shadow samples the main position (offset back by -delta), then darkens.
        PixelColor shadow = (px, py) -> shadowRgb(main.at(px - delta, py - delta));

        // Shadow pass (offset +delta), then main pass.
        if (dropShadow) {
            int cursor = 0;
            for (int cp : codepoints) {
                MinecraftGlyph glyph = font.glyph(cp);
                blitGlyphMasked(target, glyph, baseX + delta + cursor, baselineY + delta, shadow);
                fillDecorationsMasked(target, segment, baseX + delta + cursor, baselineY + delta, glyph.advanceWidth(), shadow);
                cursor += glyph.advanceWidth();
            }
        }

        int cursor = 0;
        for (int cp : codepoints) {
            MinecraftGlyph glyph = font.glyph(cp);
            blitGlyphMasked(target, glyph, baseX + cursor, baselineY, main);
            fillDecorationsMasked(target, segment, baseX + cursor, baselineY, glyph.advanceWidth(), main);
            cursor += glyph.advanceWidth();
        }
    }

    /**
     * The banded, sheared, scroll-adjusted rgb for a pixel at column {@code xRel} output px from the
     * segment start and {@code heightAboveBaseline} output px above the baseline.
     */
    private static int bandColor(@NotNull GradientSpec spec, int xRel, int heightAboveBaseline, float shear, int bandPx, int segWidthOut, long tick) {
        float center = bandCenterT(xRel, heightAboveBaseline, shear, bandPx, segWidthOut);
        return sample(spec, scrolledT(center, spec.scroll(), tick));
    }

    /**
     * The band-center position {@code t} for a pixel: shears the column
     * ({@code x' = xRel - shear*(yBase - y)}), takes the band {@code floor(x'/bandPx)}, and returns
     * its center {@code ((band + 0.5)*bandPx)/segWidth}. Not yet scroll-adjusted or
     * clamped - {@link #scrolledT} and {@link #sample} finish the mapping.
     *
     * @param xRel the pixel column in output px from the segment start
     * @param heightAboveBaseline the pixel's output px above the baseline ({@code yBase - y})
     * @param shear the band slant
     * @param bandPx the band width in output px (>= 1)
     * @param segWidthOut the segment width in output px
     * @return the band-center position
     */
    static float bandCenterT(int xRel, int heightAboveBaseline, float shear, int bandPx, int segWidthOut) {
        float xPrime = xRel - shear * heightAboveBaseline;
        int band = (int) Math.floor(xPrime / bandPx);
        return ((band + 0.5f) * bandPx) / segWidthOut;
    }

    /**
     * Blits a glyph's alpha mask, tinting each pixel by {@code color} at its frame position and
     * {@code NORMAL}-blending onto {@code target}.
     */
    private static void blitGlyphMasked(@NotNull PixelBuffer target, @NotNull MinecraftGlyph glyph, int cursorX, int cursorY, @NotNull PixelColor color) {
        PixelBuffer bitmap = glyph.bitmap();
        int gx = cursorX + glyph.bearingX();
        int gy = cursorY + glyph.bearingY();
        int tw = target.width();
        int th = target.height();

        for (int by = 0; by < bitmap.height(); by++) {
            int py = gy + by;
            if (py < 0 || py >= th) continue;
            for (int bx = 0; bx < bitmap.width(); bx++) {
                int alpha = ColorMath.alpha(bitmap.getPixel(bx, by));
                if (alpha == 0) continue;
                int px = gx + bx;
                if (px < 0 || px >= tw) continue;
                int rgb = color.at(px, py);
                int tinted = ColorMath.pack(alpha, ColorMath.red(rgb), ColorMath.green(rgb), ColorMath.blue(rgb));
                target.setPixel(px, py, ColorMath.blend(tinted, target.getPixel(px, py), BlendMode.NORMAL));
            }
        }
    }

    /**
     * Draws a glyph's strikethrough / underline bars, each pixel tinted by {@code color} so
     * decorations follow the per-pixel gradient.
     */
    private static void fillDecorationsMasked(@NotNull PixelBuffer target, @NotNull TextSegment segment, int cursorX, int baselineY, int advanceOut, @NotNull PixelColor color) {
        for (Rectangle bar : bars(segment, cursorX, baselineY, advanceOut))
            fillMasked(target, bar.x, bar.y, bar.width, bar.height, color);
    }

    private static void fillMasked(@NotNull PixelBuffer target, int x, int y, int w, int h, @NotNull PixelColor color) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(target.width(), x + w);
        int y1 = Math.min(target.height(), y + h);
        for (int py = y0; py < y1; py++)
            for (int px = x0; px < x1; px++) {
                int argb = 0xFF000000 | (color.at(px, py) & 0xFFFFFF);
                target.setPixel(px, py, ColorMath.blend(argb, target.getPixel(px, py), BlendMode.NORMAL));
            }
    }

    /**
     * A per-pixel color source: the 24-bit rgb at an absolute frame pixel.
     */
    @FunctionalInterface
    private interface PixelColor {
        int at(int px, int py);
    }

    // --- pure color evaluation (unit-tested) ---

    /**
     * Evaluates the gradient at normalized position {@code t}. Straight sRGB channel interpolation
     * for {@link GradientSpec.Mode#START_END START_END} / {@link GradientSpec.Mode#RANGE RANGE} /
     * {@link GradientSpec.Mode#SPECIFIC SPECIFIC} (flat-clamped outside the pinned span), HSV hue
     * sweep for {@link GradientSpec.Mode#RAINBOW RAINBOW}. Returns a 24-bit rgb.
     *
     * @param spec the gradient spec
     * @param t the normalized position
     * @return the sampled 24-bit rgb
     */
    public static int sample(@NotNull GradientSpec spec, float t) {
        return switch (spec.mode()) {
            case START_END, RANGE, SPECIFIC -> piecewise(spec.stops(), t);
            case RAINBOW -> ColorMath.hsvToArgb(frac(t * spec.hueCycles()) * 360f, 1f, 1f) & 0xFFFFFF;
        };
    }

    /**
     * Piecewise-linear sRGB interpolation across sorted stops, flat-clamped outside the span (a
     * {@code t} before the first stop returns the first color, after the last returns the last).
     */
    private static int piecewise(@NotNull List<GradientSpec.Stop> stops, float t) {
        GradientSpec.Stop first = stops.getFirst();
        if (t <= first.position()) return first.rgb() & 0xFFFFFF;
        GradientSpec.Stop last = stops.getLast();
        if (t >= last.position()) return last.rgb() & 0xFFFFFF;

        for (int i = 1; i < stops.size(); i++) {
            GradientSpec.Stop upper = stops.get(i);
            if (t <= upper.position()) {
                GradientSpec.Stop lower = stops.get(i - 1);
                float span = upper.position() - lower.position();
                float local = span <= 0f ? 0f : (t - lower.position()) / span;
                return lerpRgb(lower.rgb(), upper.rgb(), local);
            }
        }
        return last.rgb() & 0xFFFFFF;
    }

    /**
     * Straight (non-premultiplied) sRGB channel interpolation between two 24-bit colors, rounding
     * each channel like the project's other lerps ({@code TextRenderer.lerpArgb},
     * {@code PixelBuffer.lerp}).
     *
     * @param from the color at {@code t == 0}
     * @param to the color at {@code t == 1}
     * @param t the interpolation fraction
     * @return the interpolated 24-bit rgb
     */
    public static int lerpRgb(int from, int to, float t) {
        int r = Math.round(ColorMath.red(from) + (ColorMath.red(to) - ColorMath.red(from)) * t);
        int g = Math.round(ColorMath.green(from) + (ColorMath.green(to) - ColorMath.green(from)) * t);
        int b = Math.round(ColorMath.blue(from) + (ColorMath.blue(to) - ColorMath.blue(from)) * t);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * The scroll phase in {@code [0,1)} for the absolute {@code tick}: {@code floorMod(tick,
     * cycleTicks) / cycleTicks}. Phase derives from the absolute tick (glint precedent),
     * not the frame index, so gradients baked with the same start tick animate in phase.
     *
     * @param tick the absolute animation tick
     * @param cycleTicks ticks per full slide
     * @return the phase in {@code [0,1)}
     */
    public static float phase(long tick, int cycleTicks) {
        return Math.floorMod(tick, cycleTicks) / (float) cycleTicks;
    }

    /**
     * Slides {@code t} by the scroll phase: {@code wrap(t - dir * phase)} with {@code LEFT = +1},
     * {@code RIGHT = -1}. A {@code null} scroll returns {@code t} unchanged (the sampler
     * flat-clamps it).
     *
     * @param t the base position
     * @param scroll the scroll animation, or {@code null} for static
     * @param tick the absolute animation tick
     * @return the scrolled position
     */
    public static float scrolledT(float t, @Nullable GradientSpec.Scroll scroll, long tick) {
        if (scroll == null) return t;
        float direction = scroll.direction() == GradientSpec.Scroll.Direction.LEFT ? 1f : -1f;
        return frac(t - direction * phase(tick, scroll.cycleTicks()));
    }

    /**
     * The band slant for a spec: the spec's explicit {@link GradientSpec#shear()}, or - when it is
     * the {@link GradientSpec#AUTO_SHEAR auto sentinel} - {@link MinecraftFont#ITALIC_SHEAR} for an
     * italic segment, else {@code 0}.
     *
     * @param spec the gradient spec
     * @param italic whether the owning segment is italic
     * @return the shear as dx per +1 py above baseline
     */
    public static float resolveShear(@NotNull GradientSpec spec, boolean italic) {
        if (!Float.isNaN(spec.shear())) return spec.shear();
        return italic ? MinecraftFont.ITALIC_SHEAR : 0f;
    }

    /**
     * The cyclic fractional part of {@code x} in {@code [0,1)}.
     */
    static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    // --- bar fill and shadow color ---

    /**
     * Fills a rect clamped to the target bounds (decoration bars are already in output px).
     */
    private static void fillClamped(@NotNull PixelBuffer target, int x, int y, int w, int h, int argb) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(target.width(), x + w);
        int y1 = Math.min(target.height(), y + h);
        for (int py = y0; py < y1; py++)
            for (int px = x0; px < x1; px++)
                target.setPixel(px, py, ColorMath.blend(argb, target.getPixel(px, py), BlendMode.NORMAL));
    }

    private static int shadowRgb(int rgb) {
        return (rgb & SHADOW_RGB_MASK) >> 2;
    }

}
