package lib.minecraft.renderer.engine.kit;

import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGlyph;
import lib.minecraft.text.font.MinecraftGraphics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Static gradient-text rendering utilities - the compose-side realization of {@link GradientSpec}
 * (06 half 2). Splits into a pure color-evaluation core (unit-testable) and per-segment draw paths
 * driven by the {@link #PER_LETTER per-letter} vs per-pixel fidelity lever.
 * <p>
 * The color evaluator {@link #sample(GradientSpec, float)} maps a normalized position {@code t}
 * along the segment to an rgb; {@link #scrolledT} slides {@code t} by the scroll phase derived from
 * the absolute animation tick, and {@link #resolveShear} yields the band slant. Glyphs render as
 * the white-glyph-multiply identity: each glyph pixel's alpha mask is tinted by the sampled color
 * and {@code NORMAL}-blended, exactly as {@code MinecraftGraphics.blitGlyph} does for a solid tint -
 * so a degenerate single-color gradient matches the solid path pixel for pixel.
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
     * @param xMcPx the segment start cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param tick the absolute animation tick driving scroll phase
     * @return the advance width in mcPixels
     */
    public static int drawSegment(
        @NotNull MinecraftGraphics g,
        @NotNull ColorSegment segment,
        @NotNull GradientSpec spec,
        @NotNull String text,
        int xMcPx, int yMcPx,
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
     * @param xMcPx the segment start cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     * @param tick the absolute animation tick driving scroll phase
     * @param dropShadow whether to draw the offset shadow pass beneath the glyphs
     * @return the advance width in mcPixels
     */
    public static int drawSegment(
        @NotNull MinecraftGraphics g,
        @NotNull ColorSegment segment,
        @NotNull GradientSpec spec,
        @NotNull String text,
        int xMcPx, int yMcPx,
        long tick,
        boolean dropShadow
    ) {
        MinecraftFont font = MinecraftFont.Vanilla.of(segment.fontStyle());
        int segWidthOut = measureCodepoints(text, font);
        if (segWidthOut <= 0) return 0;

        if (spec.bandPx() == GradientSpec.PER_LETTER)
            drawPerLetter(g.target(), segment, spec, font, text, segWidthOut, xMcPx, yMcPx, tick, dropShadow);
        else
            drawPerPixel(g.target(), segment, spec, font, text, segWidthOut, xMcPx, yMcPx, tick, dropShadow);
        return segWidthOut / MinecraftFont.MC_PIXEL_SCALE;
    }

    /**
     * Sums the advance widths of {@code text} in output px walking CODEPOINTS (surrogate-safe),
     * matching the glyph the draw loop actually blits rather than
     * {@code MinecraftFontMetrics.stringWidth}'s UTF-16 code-unit walk.
     */
    static int measureCodepoints(@NotNull String text, @NotNull MinecraftFont font) {
        int width = 0;
        for (int cp : text.codePoints().toArray())
            width += font.glyph(cp).advanceWidth();
        return width;
    }

    // --- per-letter fidelity (bandPx == 0) ---

    /**
     * Per-letter path: each glyph samples ONE color at its advance-span center; two-phase (all
     * shadows, then all mains) so adjacent glyph bitmaps overlap as in the solid path.
     * Decorations follow the gradient, colored per glyph.
     */
    private static void drawPerLetter(
        @NotNull PixelBuffer target,
        @NotNull ColorSegment segment,
        @NotNull GradientSpec spec,
        @NotNull MinecraftFont font,
        @NotNull String text,
        int segWidthOut,
        int xMcPx, int yMcPx,
        long tick,
        boolean dropShadow
    ) {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int baseX = xMcPx * scale;
        int baseY = yMcPx * scale;
        int shadowOff = TextKit.SHADOW_OFFSET_MCPX * scale;
        int[] codepoints = text.codePoints().toArray();

        // Shadow pass (offset +1,+1 mcPx), darkened per-glyph color.
        if (dropShadow) {
            int cursor = 0;
            for (int cp : codepoints) {
                MinecraftGlyph glyph = font.glyph(cp);
                int rgb = shadowRgb(sample(spec, letterT(cursor, glyph.advanceWidth(), segWidthOut, spec, tick)));
                blitGlyphTinted(target, glyph, baseX + shadowOff + cursor, baseY + shadowOff, rgb);
                drawDecorations(target, segment, baseX + shadowOff + cursor, baseY + shadowOff, glyph.advanceWidth(), rgb);
                cursor += glyph.advanceWidth();
            }
        }

        // Main pass.
        int cursor = 0;
        for (int cp : codepoints) {
            MinecraftGlyph glyph = font.glyph(cp);
            int rgb = sample(spec, letterT(cursor, glyph.advanceWidth(), segWidthOut, spec, tick));
            blitGlyphTinted(target, glyph, baseX + cursor, baseY, rgb);
            drawDecorations(target, segment, baseX + cursor, baseY, glyph.advanceWidth(), rgb);
            cursor += glyph.advanceWidth();
        }
    }

    /**
     * The advance-center position for a glyph, scroll-adjusted: {@code t = (cursor + adv/2) /
     * segWidth}, then slid by the scroll phase.
     */
    private static float letterT(int cursorOut, int advanceOut, int segWidthOut, @NotNull GradientSpec spec, long tick) {
        float center = (cursorOut + advanceOut / 2f) / segWidthOut;
        return scrolledT(center, spec.scroll(), tick);
    }

    /**
     * Draws the strikethrough / underline bars for one glyph's advance span in {@code rgb}, so
     * decorations follow the gradient per letter.
     */
    private static void drawDecorations(@NotNull PixelBuffer target, @NotNull ColorSegment segment, int cursorX, int baselineY, int advanceOut, int rgb) {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int argb = 0xFF000000 | (rgb & 0xFFFFFF);
        if (segment.isStrikethrough())
            fillClamped(target, cursorX, baselineY + TextKit.STRIKETHROUGH_OFFSET_MCPX * scale, advanceOut, TextKit.STRIKETHROUGH_THICKNESS_MCPX * scale, argb);
        if (segment.isUnderlined())
            fillClamped(target, cursorX, baselineY + TextKit.UNDERLINE_OFFSET_MCPX * scale, advanceOut, TextKit.UNDERLINE_THICKNESS_MCPX * scale, argb);
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
     * bands sit exactly under main bands.
     */
    private static void drawPerPixel(
        @NotNull PixelBuffer target,
        @NotNull ColorSegment segment,
        @NotNull GradientSpec spec,
        @NotNull MinecraftFont font,
        @NotNull String text,
        int segWidthOut,
        int xMcPx, int yMcPx,
        long tick,
        boolean dropShadow
    ) {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int baseX = xMcPx * scale;
        int baselineY = yMcPx * scale;
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
     * {@code NORMAL}-blending onto {@code target}. The per-pixel counterpart of
     * {@link #blitGlyphTinted}.
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
    private static void fillDecorationsMasked(@NotNull PixelBuffer target, @NotNull ColorSegment segment, int cursorX, int baselineY, int advanceOut, @NotNull PixelColor color) {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        if (segment.isStrikethrough())
            fillMasked(target, cursorX, baselineY + TextKit.STRIKETHROUGH_OFFSET_MCPX * scale, advanceOut, TextKit.STRIKETHROUGH_THICKNESS_MCPX * scale, color);
        if (segment.isUnderlined())
            fillMasked(target, cursorX, baselineY + TextKit.UNDERLINE_OFFSET_MCPX * scale, advanceOut, TextKit.UNDERLINE_THICKNESS_MCPX * scale, color);
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

    // --- glyph blit (mirrors MinecraftGraphics.blitGlyph with an explicit per-glyph tint) ---

    /**
     * Blits a glyph's alpha mask tinted by {@code rgb}, {@code NORMAL}-blended onto {@code target} -
     * the same per-pixel pack + blend {@code MinecraftGraphics.blitGlyph} performs for a solid tint,
     * clamped to the target bounds. Assumes a zero graphics translate (tooltip / menu buffers are
     * drawn at their origin).
     */
    static void blitGlyphTinted(@NotNull PixelBuffer target, @NotNull MinecraftGlyph glyph, int cursorX, int cursorY, int rgb) {
        PixelBuffer bitmap = glyph.bitmap();
        int gx = cursorX + glyph.bearingX();
        int gy = cursorY + glyph.bearingY();
        int tw = target.width();
        int th = target.height();
        int tintR = ColorMath.red(rgb);
        int tintG = ColorMath.green(rgb);
        int tintB = ColorMath.blue(rgb);

        for (int by = 0; by < bitmap.height(); by++) {
            int py = gy + by;
            if (py < 0 || py >= th) continue;
            for (int bx = 0; bx < bitmap.width(); bx++) {
                int alpha = ColorMath.alpha(bitmap.getPixel(bx, by));
                if (alpha == 0) continue;
                int px = gx + bx;
                if (px < 0 || px >= tw) continue;
                int tinted = ColorMath.pack(alpha, tintR, tintG, tintB);
                target.setPixel(px, py, ColorMath.blend(tinted, target.getPixel(px, py), BlendMode.NORMAL));
            }
        }
    }

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
