package lib.minecraft.renderer.bake.gui;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.pack.TextPalette;
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
import java.util.function.IntUnaryOperator;

/**
 * Static gradient-text rendering utilities - the compose-side realization of {@link GradientSpec}.
 * Splits into a pure color-evaluation core (unit-testable) and per-segment draw paths driven by the
 * {@link GradientSpec#PER_LETTER per-letter} vs per-pixel fidelity lever.
 * <p>
 * The color evaluator {@link #sample(GradientSpec, float)} maps a normalized position {@code t}
 * along the segment to an rgb; {@link #scrolledT} slides {@code t} by the scroll phase derived from
 * the absolute animation tick, and {@link #resolveShear} yields the band slant. Glyphs render in
 * the font {@link TextKit} resolved for the segment, as the white-glyph-multiply identity: each
 * glyph pixel's alpha mask is tinted by the sampled color and {@code NORMAL}-blended, exactly as
 * {@code MinecraftGraphics.blitGlyph} does for a solid tint - so a degenerate single-color gradient
 * matches the solid path pixel for pixel.
 * <p>
 * Positions and the strikethrough and underline bars are in mcPixels, a segment's origin fractional
 * where it follows another. The per-letter path draws each letter and its bars through the graphics
 * in the letter's own colour ({@link MinecraftGraphics#drawString(String, float, float)},
 * {@link MinecraftGraphics#fillEffect(float, float, float, float)}), so the graphics converts to
 * output pixels where it lays them. The per-pixel path changes colour inside a letter, which the text
 * library has no draw for, so it tints each glyph and bar pixel itself, converting where it writes as
 * the graphics converts - each glyph at its origin plus its pen rounded once, each bar edge rounded
 * once - its bands being output pixels as {@link GradientSpec#bandPx()} defines them. Those writes go
 * to the target at a zero graphics translate (tooltip and menu buffers are drawn at their origin).
 * <p>
 * A run's shadow is derived as the client's font derives a glyph's, from the colour the letter is
 * drawn in ({@link TextKit#shadowColor(TextSegment, int, boolean)}). Where the segment names its own
 * shadow colour, that one colour is drawn over the run, laid through the graphics on either path so
 * its alpha and the text shader's cut hold, drawn whether or not the caller asks for a shadow and not
 * drawn for a shadow colour of {@code 0}; otherwise the shadow is each letter's or band's colour at a
 * quarter, drawn where the caller asks for one. Every letter and band is drawn opaque: a gradient
 * names red, green and blue alone, and the colour a line is drawn in reaches no gradient pixel.
 * <p>
 * Each colour a segment lays down - a letter's or a band's, its shadow's and its bars' - is swapped
 * through the caller's {@link TextPalette} after the shadow is derived from the unswapped colour, as
 * {@link TextKit} does for a solid run.
 *
 * @see GradientSpec
 * @see TextKit
 */
@UtilityClass
public class GradientKit {

    // --- entry ---

    /**
     * Draws a gradient segment's (already obfuscation-substituted) {@code text} at the given mcPixel
     * origin and returns its advance in mcPixels. Routes to the per-letter or per-pixel path by
     * {@link GradientSpec#bandPx()}.
     *
     * @param g the graphics whose target buffer receives the glyphs
     * @param segment the styled segment (supplies decorations and italic)
     * @param spec the gradient spec
     * @param font the font the text was substituted through, which draws and measures it
     * @param text the segment text after obfuscation substitution
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param palette the colours a pack's text shader draws in place of the ones the segment lays down
     * @param tick the absolute animation tick driving scroll phase
     * @return the advance width in mcPixels, its fraction kept
     */
    static float drawSegment(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull GradientSpec spec,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float xMcPx, float yMcPx,
        @NotNull TextPalette palette,
        long tick
    ) {
        return drawSegment(g, segment, spec, font, text, xMcPx, yMcPx, palette, tick, true);
    }

    /**
     * Draws a gradient segment with the shadow a run derives from its colour declinable, so a gradient
     * title declines one where a plain title does; a segment naming its own shadow colour draws it
     * regardless, as the client's font does.
     *
     * @param g the graphics whose target buffer receives the glyphs
     * @param segment the styled segment (supplies decorations and italic)
     * @param spec the gradient spec
     * @param font the font the text was substituted through, which draws and measures it
     * @param text the segment text after obfuscation substitution
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param palette the colours a pack's text shader draws in place of the ones the segment lays down
     * @param tick the absolute animation tick driving scroll phase
     * @param dropShadow whether the shadow a run derives from its colour is drawn - a segment naming a
     * shadow colour draws it either way
     * @return the advance width in mcPixels, its fraction kept
     */
    static float drawSegment(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull GradientSpec spec,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float xMcPx, float yMcPx,
        @NotNull TextPalette palette,
        long tick,
        boolean dropShadow
    ) {
        float widthMcPx = font.metrics().stringAdvanceMcPixels(text);
        if (widthMcPx <= 0) return 0;

        if (spec.bandPx() == GradientSpec.PER_LETTER)
            drawPerLetter(g, segment, spec, font, text, widthMcPx, xMcPx, yMcPx, palette, tick, dropShadow);
        else
            drawPerPixel(g, segment, spec, font, text, widthMcPx, xMcPx, yMcPx, palette, tick, dropShadow);
        return widthMcPx;
    }

    /**
     * Answers what {@link #drawSegment} lays down for a gradient segment's (already
     * obfuscation-substituted) {@code text} at the given mcPixel origin, before it is drawn - the
     * bounds of every pixel it draws, in mcPixels. Empty where the text's width is not positive, which
     * the draw skips; otherwise the pixels the text library answers the run's glyphs draw
     * ({@link MinecraftGlyphVector#getVisualBounds()}), since both paths place a glyph where
     * {@link MinecraftGraphics#drawString(String, float, float)} does, with the bars over the run's
     * width, each again at the shadow's offset where the shadow pass lays - a shadow colour the
     * segment names, above the text shader's cut, or the run's derived shadow where the caller asks for
     * one. The run is read from the output pixel its origin rounds to, as {@link TextKit} reads a solid
     * run. Neither the colour nor the scroll moves a pixel's place, so the tick does not enter.
     *
     * @param segment the styled segment (supplies decorations)
     * @param font the font the text was substituted through, which draws and measures it
     * @param text the segment text after obfuscation substitution
     * @param xMcPx the segment start X in mcPixels, fractional where the segment follows another
     * @param yMcPx the baseline Y in mcPixels
     * @param dropShadow whether the shadow a run derives from its colour is drawn - a segment naming a
     * shadow colour draws it either way
     * @return the bounds of what the segment draws in mcPixels, empty where it draws nothing
     */
    static @NotNull Optional<Rectangle2D> inkBounds(
        @NotNull TextSegment segment,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float xMcPx, float yMcPx,
        boolean dropShadow
    ) {
        float widthMcPx = font.metrics().stringAdvanceMcPixels(text);
        if (widthMcPx <= 0) return Optional.empty();

        // Every letter and band is laid opaque, so the derivation answers one alpha for the whole run.
        boolean shadow = ColorMath.alpha(TextKit.shadowColor(segment, ColorMath.WHITE, dropShadow)) > MinecraftGraphics.DISCARD_ALPHA;
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int xOut = Math.round(xMcPx * scale);
        int baselineOut = Math.round(yMcPx * scale);
        List<Rectangle2D> drawn = new ArrayList<>();
        Rectangle glyphs = font.layout(text).getVisualBounds().getBounds();
        glyphs.translate(xOut, baselineOut);
        TextKit.addInk(drawn, TextKit.mcPixels(glyphs), true, shadow);
        for (Rectangle2D.Float bar : bars(segment, xOut / (float) scale, baselineOut / (float) scale, widthMcPx))
            TextKit.addInk(drawn, bar, true, shadow);
        return drawn.stream().reduce(Rectangle2D::createUnion);
    }

    // --- per-letter fidelity (bandPx == 0) ---

    /**
     * Per-letter path: each glyph samples ONE color at its advance-span center; two-phase (all
     * shadows, then all mains) so adjacent glyph bitmaps overlap as in the solid path.
     * Decorations follow the gradient, colored per glyph. Each letter's shadow is derived from its
     * colour as {@link TextKit#shadowColor(TextSegment, int, boolean)} derives a glyph's - the
     * segment's own shadow colour where it names one.
     *
     * @param g the graphics the letters and their bars are laid through
     * @param segment the styled segment, supplying which bars each letter carries and any shadow colour
     * it names
     * @param spec the gradient spec
     * @param font the segment's font
     * @param text the segment text after obfuscation substitution
     * @param widthMcPx the run's width in mcPixels
     * @param xMcPx the run's origin X in mcPixels
     * @param yMcPx the run's baseline Y in mcPixels
     * @param palette the colours a pack's text shader draws in place of the ones the run lays down
     * @param tick the absolute animation tick driving scroll phase
     * @param dropShadow whether the shadow a run derives from its colour is drawn - a segment naming a
     * shadow colour draws it either way
     */
    private static void drawPerLetter(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull GradientSpec spec,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float widthMcPx,
        float xMcPx, float yMcPx,
        @NotNull TextPalette palette,
        long tick,
        boolean dropShadow
    ) {
        int[] codepoints = text.codePoints().toArray();
        g.setFont(font);

        // Shadow pass (offset +1,+1 mcPx), each letter's shadow derived from its colour.
        drawLetterPass(g, segment, spec, font, codepoints, widthMcPx, xMcPx + TextKit.SHADOW_OFFSET_MCPX, yMcPx + TextKit.SHADOW_OFFSET_MCPX, palette, tick,
            argb -> TextKit.shadowColor(segment, argb, dropShadow));

        // Main pass.
        drawLetterPass(g, segment, spec, font, codepoints, widthMcPx, xMcPx, yMcPx, palette, tick, IntUnaryOperator.identity());
    }

    /**
     * Draws one pass of the per-letter path: each letter through the graphics, with its decoration
     * bars, in the colour {@code pass} answers for the colour its advance-span center samples, the pen
     * keeping each letter's fraction. A letter whose colour is {@code 0} is not drawn, as the client
     * emits no quad for it. The palette swaps each letter's colour after the pass derives it.
     *
     * @param g the graphics the letters and their bars are laid through
     * @param segment the styled segment, supplying which bars each letter carries
     * @param spec the gradient spec
     * @param font the segment's font
     * @param codepoints the segment text's codepoints after obfuscation substitution
     * @param widthMcPx the run's width in mcPixels
     * @param xMcPx the pass's origin X in mcPixels
     * @param yMcPx the pass's baseline Y in mcPixels
     * @param palette the colours a pack's text shader draws in place of the ones the pass lays down
     * @param tick the absolute animation tick driving scroll phase
     * @param pass answers the colour this pass draws a letter in from the opaque colour the letter is
     * drawn in, {@code 0} where it draws none
     */
    private static void drawLetterPass(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull GradientSpec spec,
        @NotNull MinecraftFont font,
        int @NotNull [] codepoints,
        float widthMcPx,
        float xMcPx, float yMcPx,
        @NotNull TextPalette palette,
        long tick,
        @NotNull IntUnaryOperator pass
    ) {
        float penMcPx = 0;
        for (int cp : codepoints) {
            String letter = Character.toString(cp);
            float advanceMcPx = font.metrics().stringAdvanceMcPixels(letter);
            int argb = pass.applyAsInt(ColorMath.withAlpha(sample(spec, letterT(penMcPx, advanceMcPx, widthMcPx, spec, tick)), 0xFF));
            if (argb != 0) {
                g.setColor(new java.awt.Color(palette.swap(argb), true));
                g.drawString(letter, xMcPx + penMcPx, yMcPx);
                for (Rectangle2D.Float bar : bars(segment, xMcPx + penMcPx, yMcPx, advanceMcPx))
                    g.fillEffect(bar.x, bar.y, bar.width, bar.height);
            }
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
     * Answers the strikethrough and underline bars over a span of a gradient run, in mcPixels and in
     * the order they are drawn - each from the span's start and as wide as it, with no pad. The
     * per-letter path lays them through {@link MinecraftGraphics#fillEffect(float, float, float, float)},
     * the per-pixel path converts them where it writes, and {@link #inkBounds} reads them.
     *
     * @param segment the segment, supplying which bars it carries
     * @param xMcPx the span's start X in mcPixels
     * @param baselineMcPx the baseline Y in mcPixels
     * @param widthMcPx the span's width in mcPixels
     * @return the bars the span carries, in draw order
     */
    private static @NotNull List<Rectangle2D.Float> bars(@NotNull TextSegment segment, float xMcPx, float baselineMcPx, float widthMcPx) {
        List<Rectangle2D.Float> bars = new ArrayList<>(2);
        if (segment.isStrikethrough())
            bars.add(new Rectangle2D.Float(xMcPx, baselineMcPx + TextKit.STRIKETHROUGH_OFFSET_MCPX, widthMcPx, TextKit.STRIKETHROUGH_THICKNESS_MCPX));
        if (segment.isUnderlined())
            bars.add(new Rectangle2D.Float(xMcPx, baselineMcPx + TextKit.UNDERLINE_OFFSET_MCPX, widthMcPx, TextKit.UNDERLINE_THICKNESS_MCPX));
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
     * {@code BlendMode.NORMAL} would premultiply). Bands slant by the shear, and are output pixels
     * measured from the run's origin rounded once, here.
     * <p>
     * The shadow pass is the segment's own shadow colour where it names one - one colour over the run,
     * laid through the graphics letter by letter as the per-letter path lays its shadow. Otherwise,
     * where the caller asks for one, it is each band's colour at a quarter, sampled at the MAIN
     * letterform position (offset back by {@code delta}) so shadow bands sit exactly under main bands,
     * darkened unswapped and then swapped.
     *
     * @param g the graphics a named shadow colour is laid through, its target the buffer the banded
     * passes are written to
     * @param segment the styled segment, supplying which bars the run carries, its slant and any shadow
     * colour it names
     * @param spec the gradient spec
     * @param font the segment's font
     * @param text the segment text after obfuscation substitution
     * @param widthMcPx the run's width in mcPixels
     * @param xMcPx the run's origin X in mcPixels
     * @param yMcPx the run's baseline Y in mcPixels
     * @param palette the colours a pack's text shader draws in place of the ones the run lays down
     * @param tick the absolute animation tick driving scroll phase
     * @param dropShadow whether the shadow a run derives from its colour is drawn - a segment naming a
     * shadow colour draws it either way
     */
    private static void drawPerPixel(
        @NotNull MinecraftGraphics g,
        @NotNull TextSegment segment,
        @NotNull GradientSpec spec,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float widthMcPx,
        float xMcPx, float yMcPx,
        @NotNull TextPalette palette,
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
        float shadowX = xMcPx + TextKit.SHADOW_OFFSET_MCPX;
        float shadowY = yMcPx + TextKit.SHADOW_OFFSET_MCPX;

        // Band rgb at an absolute frame pixel, relative to the MAIN letterform.
        PixelColor band = (px, py) -> bandColor(spec, px - baseX, baselineY - py, shear, bandPx, segWidthOut, tick);
        PixelColor main = (px, py) -> palette.swap(band.at(px, py));

        // Shadow pass (offset +1,+1 mcPx), then main pass.
        if (segment.getShadowColor().isPresent()) {
            g.setFont(font);
            drawLetterPass(g, segment, spec, font, text.codePoints().toArray(), widthMcPx, shadowX, shadowY, palette, tick,
                argb -> TextKit.shadowColor(segment, argb, dropShadow));
        } else if (dropShadow) {
            // Samples the main position (offset back by -delta), darkens the unswapped band, then swaps it.
            drawPixelPass(g.target(), segment, font, text, widthMcPx, shadowX, shadowY,
                (px, py) -> palette.swap(TextKit.darken(band.at(px - delta, py - delta))));
        }

        drawPixelPass(g.target(), segment, font, text, widthMcPx, xMcPx, yMcPx, main);
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
     * Draws one pass of the per-pixel path at an mcPixel origin: each glyph placed by its font's walk
     * at the origin plus its pen, rounded once, as
     * {@link MinecraftGraphics#drawString(String, float, float)} places it, then the run's bars,
     * converted the same way. Every glyph and bar pixel is tinted by {@code color} and
     * {@code NORMAL}-blended onto {@code target}, at a zero graphics translate.
     *
     * @param target the buffer the pass is written to
     * @param segment the styled segment, supplying which bars the run carries
     * @param font the segment's font
     * @param text the segment text after obfuscation substitution
     * @param widthMcPx the run's width in mcPixels
     * @param xMcPx the pass's origin X in mcPixels
     * @param yMcPx the pass's baseline Y in mcPixels
     * @param color the colour each pixel is tinted, by its frame position
     */
    private static void drawPixelPass(
        @NotNull PixelBuffer target,
        @NotNull TextSegment segment,
        @NotNull MinecraftFont font,
        @NotNull String text,
        float widthMcPx,
        float xMcPx, float yMcPx,
        @NotNull PixelColor color
    ) {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        double originX = (double) xMcPx * scale;
        int baselineY = (int) Math.round((double) yMcPx * scale);
        font.walk(text, (glyph, penX) -> blitGlyphMasked(target, glyph, (int) Math.round(originX + penX), baselineY, color));
        for (Rectangle2D.Float bar : bars(segment, xMcPx, yMcPx, widthMcPx))
            fillMasked(target, bar, color);
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
     * Fills a strikethrough or underline bar given in mcPixels, each pixel tinted opaque by
     * {@code color} at its frame position so decorations follow the per-pixel gradient, and
     * {@code NORMAL}-blended onto {@code target}. Each edge is converted to output pixels and rounded
     * half up once, as {@link MinecraftGraphics#fillEffect(float, float, float, float)} converts it,
     * and the bar is clamped to the target.
     *
     * @param target the buffer the bar is written to
     * @param bar the bar in mcPixels
     * @param color the colour each pixel is tinted, by its frame position
     */
    private static void fillMasked(@NotNull PixelBuffer target, @NotNull Rectangle2D.Float bar, @NotNull PixelColor color) {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        double left = (double) bar.x * scale;
        double top = (double) bar.y * scale;
        int x0 = Math.max(0, (int) Math.round(left));
        int y0 = Math.max(0, (int) Math.round(top));
        int x1 = Math.min(target.width(), (int) Math.round(left + (double) bar.width * scale));
        int y1 = Math.min(target.height(), (int) Math.round(top + (double) bar.height * scale));
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

}
