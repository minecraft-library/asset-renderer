package lib.minecraft.renderer.bake.gui;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Coverage of {@link GradientKit}'s pure color evaluator (sRGB piecewise / HSV rainbow, per-mode
 * position handling), the scroll phase / shear math, the segment's advance and its fractional origin,
 * the bars and bands over a run ending on half an mcPixel, and the per-letter advance-center draw.
 */
@ExtendWith(MinecraftFontsExtension.class)
class GradientKitTest {

    /** Column the draws start at, in Minecraft pixels, shared by every draw and the reads that check it. */
    private static final int DRAW_COLUMN_MC_PX = 1;

    private static GradientSpec startEnd(int from, int to) {
        return GradientSpec.builder(GradientSpec.Mode.START_END).addStop(from).addStop(to).build();
    }

    /** Cases for the pure color evaluator, one per {@link GradientSpec.Mode}. */
    @Nested
    @DisplayName("sample(t)")
    class Sample {

        @Test
        @DisplayName("START_END interpolates end to end in sRGB")
        void startEndInterpolates() {
            GradientSpec spec = startEnd(0x000000, 0xFFFFFF);
            assertThat(GradientKit.sample(spec, 0f), is(0x000000));
            assertThat(GradientKit.sample(spec, 1f), is(0xFFFFFF));
            assertThat(GradientKit.sample(spec, 0.5f), is(0x808080)); // round(255*0.5) = 128
        }

        @Test
        @DisplayName("START_END flat-clamps outside [0,1]")
        void startEndClamps() {
            GradientSpec spec = startEnd(0x000000, 0xFFFFFF);
            assertThat(GradientKit.sample(spec, -0.5f), is(0x000000));
            assertThat(GradientKit.sample(spec, 1.5f), is(0xFFFFFF));
        }

        @Test
        @DisplayName("RANGE auto-spread samples the midpoint stop and its neighbours")
        void rangeAutoSpread() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.RANGE)
                .addStop(0xFF0000).addStop(0x00FF00).addStop(0x0000FF).build();
            assertThat(GradientKit.sample(spec, 0.5f), is(0x00FF00));          // middle stop at 0.5
            assertThat(GradientKit.sample(spec, 0.25f), is(0x808000));         // halfway red->green
        }

        @Test
        @DisplayName("SPECIFIC sorts, interpolates between, and flat-extends past the span")
        void specificFlatExtends() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.SPECIFIC)
                .addStop(0x0000FF, 0.75f).addStop(0xFF0000, 0.25f).build(); // out of order on purpose
            assertThat(GradientKit.sample(spec, 0.0f), is(0xFF0000));  // before first -> first
            assertThat(GradientKit.sample(spec, 1.0f), is(0x0000FF));  // after last -> last
            assertThat(GradientKit.sample(spec, 0.5f), is(0x800080));  // midway red->blue
        }

        @Test
        @DisplayName("RAINBOW sweeps hue and wraps seamlessly at t=1")
        void rainbowHueWrap() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.RAINBOW).hueCycles(1f).build();
            assertThat(GradientKit.sample(spec, 0f), is(0xFF0000));                 // hue 0 = red
            assertThat(GradientKit.sample(spec, 1f), is(GradientKit.sample(spec, 0f))); // wrap
            int green = GradientKit.sample(spec, 1f / 3f);
            assertThat(ColorMath.green(green), is(greaterThan(200)));
            assertThat(ColorMath.red(green), is(lessThan(60)));
            int blue = GradientKit.sample(spec, 2f / 3f);
            assertThat(ColorMath.blue(blue), is(greaterThan(200)));
        }
    }

    /** Cases for the scroll phase, the sampled position it slides, and the italic shear. */
    @Nested
    @DisplayName("scroll + shear math")
    class Motion {

        @Test
        @DisplayName("phase is floorMod(tick, cycleTicks)/cycleTicks")
        void phase() {
            assertThat((double) GradientKit.phase(0, 40), is(closeTo(0.0, 1e-6)));
            assertThat((double) GradientKit.phase(20, 40), is(closeTo(0.5, 1e-6)));
            assertThat((double) GradientKit.phase(40, 40), is(closeTo(0.0, 1e-6))); // wraps
            assertThat((double) GradientKit.phase(60, 40), is(closeTo(0.5, 1e-6)));
        }

        @Test
        @DisplayName("scrolledT slides LEFT (+) / RIGHT (-) and loops seamlessly over a cycle")
        void scrolledT() {
            GradientSpec.Scroll left = new GradientSpec.Scroll(40, GradientSpec.Scroll.Direction.LEFT);
            assertThat((double) GradientKit.scrolledT(0.5f, left, 0), is(closeTo(0.5, 1e-6)));
            assertThat((double) GradientKit.scrolledT(0.5f, left, 20), is(closeTo(0.0, 1e-6)));
            assertThat((double) GradientKit.scrolledT(0.5f, left, 10), is(closeTo(0.25, 1e-6)));

            GradientSpec.Scroll right = new GradientSpec.Scroll(40, GradientSpec.Scroll.Direction.RIGHT);
            assertThat((double) GradientKit.scrolledT(0.5f, right, 10), is(closeTo(0.75, 1e-6)));

            // seamless: tick and tick + cycleTicks map to the same position
            assertThat(GradientKit.scrolledT(0.3f, left, 7), is(GradientKit.scrolledT(0.3f, left, 47)));
            // null scroll passes t through untouched
            assertThat(GradientKit.scrolledT(0.42f, null, 999), is(0.42f));
        }

        @Test
        @DisplayName("resolveShear: auto -> ITALIC_SHEAR when italic, 0 otherwise; explicit wins")
        void resolveShear() {
            GradientSpec auto = startEnd(0x000000, 0xFFFFFF); // shear defaults to AUTO (NaN)
            assertThat(GradientKit.resolveShear(auto, true), is(MinecraftFont.ITALIC_SHEAR));
            assertThat(GradientKit.resolveShear(auto, false), is(0f));

            GradientSpec explicit = GradientSpec.builder(GradientSpec.Mode.START_END)
                .addStop(0x000000).addStop(0xFFFFFF).shear(0.5f).build();
            assertThat(GradientKit.resolveShear(explicit, true), is(0.5f));
        }
    }

    /** Cases for per-column band quantisation, its baseline-anchored shear, and the swept draw. */
    @Nested
    @DisplayName("per-pixel banding + shear")
    class PerPixel {

        @Test
        @DisplayName("bandCenterT quantizes columns to band centers")
        void bandCenterQuantizes() {
            assertThat((double) GradientKit.bandCenterT(0, 0, 0f, 1, 100), is(closeTo(0.005, 1e-6)));
            assertThat((double) GradientKit.bandCenterT(10, 0, 0f, 1, 100), is(closeTo(0.105, 1e-6)));
            // bandPx 8: columns 0 and 7 fall in band 0 (center 4/100), column 8 in band 1 (12/100)
            assertThat(GradientKit.bandCenterT(0, 0, 0f, 8, 100), is(GradientKit.bandCenterT(7, 0, 0f, 8, 100)));
            assertThat((double) GradientKit.bandCenterT(0, 0, 0f, 8, 100), is(closeTo(0.04, 1e-6)));
            assertThat((double) GradientKit.bandCenterT(8, 0, 0f, 8, 100), is(closeTo(0.12, 1e-6)));
        }

        @Test
        @DisplayName("shear leaves the baseline unchanged and shifts sampling left above it")
        void shearIsBaselineAnchored() {
            // at the baseline (height 0) shear has no effect
            assertThat(GradientKit.bandCenterT(20, 0, 0.2f, 1, 100), is(GradientKit.bandCenterT(20, 0, 0f, 1, 100)));
            // above the baseline the same column samples an earlier band (bands lean right going up)
            assertThat(GradientKit.bandCenterT(20, 10, 0.2f, 1, 100),
                is(lessThan(GradientKit.bandCenterT(20, 0, 0.2f, 1, 100))));
        }

        @Test
        @DisplayName("per-pixel band 1 renders a smooth left-dark to right-light sweep")
        void perPixelSmoothSweep() {
            MinecraftFont font = MinecraftFont.Vanilla.REGULAR;
            int advW = font.glyph('W').advanceWidth();

            PixelBuffer buffer = PixelBuffer.create(4 * advW + 8, 40);
            buffer.fill(0xFF000000);
            MinecraftGraphics g = new MinecraftGraphics(buffer);

            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END)
                .addStop(0x000000).addStop(0xFFFFFF).bandPx(1).build();
            TextSegment segment = TextSegment.builder().text("WWWW").gradient(spec).build();
            int baseX = DRAW_COLUMN_MC_PX * MinecraftFont.MC_PIXEL_SCALE;
            GradientKit.drawSegment(g, segment, spec, "WWWW", DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L);

            int leftMax = maxRed(buffer, baseX, baseX + advW);
            int rightMax = maxRed(buffer, baseX + 3 * advW, baseX + 4 * advW);
            assertThat(leftMax, is(lessThan(rightMax)));
            assertThat(leftMax, is(lessThan(110)));
            assertThat(rightMax, is(greaterThan(150)));
        }
    }

    @Test
    @DisplayName("a gradient segment's advance walks codepoints and keeps a half pixel")
    void drawSegmentMeasuresCodepoints() {
        MinecraftFont font = MinecraftFont.Vanilla.REGULAR;
        GradientSpec spec = startEnd(0x000000, 0xFFFFFF);
        int astral = 0x1D400; // MATHEMATICAL BOLD CAPITAL A (needs a surrogate pair in UTF-16)
        String pair = new String(Character.toChars(astral));
        assertThat(pair.length(), is(2)); // two UTF-16 code units...
        assertThat(advanceOf(pair, spec), is(font.glyph(astral).advanceWidth() / (float) MinecraftFont.MC_PIXEL_SCALE)); // ...one glyph advance

        assertThat("'✦' ends on half an mcPixel", font.metrics().stringAdvanceMcPixels("✦") % 1f, is(0.5f));
        assertThat(advanceOf("✦", spec), is(font.glyph('✦').advanceWidth() / (float) MinecraftFont.MC_PIXEL_SCALE));
    }

    @Test
    @DisplayName("a gradient and its underline drawn half an mcPixel right are the same draw one output pixel right")
    void halfPixelOriginShiftsOneOutputPixel() {
        for (int bandPx : new int[] { GradientSpec.PER_LETTER, 1, 8 }) {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END)
                .addStop(0x000000).addStop(0xFFFFFF).bandPx(bandPx).build();
            TextSegment segment = TextSegment.builder().text("MW").underlined(true).gradient(spec).build();

            PixelBuffer whole = PixelBuffer.create(96, 40);
            whole.fill(0xFF000000);
            GradientKit.drawSegment(new MinecraftGraphics(whole), segment, spec, "MW", DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L);
            PixelBuffer half = PixelBuffer.create(96, 40);
            half.fill(0xFF000000);
            GradientKit.drawSegment(new MinecraftGraphics(half), segment, spec, "MW", DRAW_COLUMN_MC_PX + 0.5f, 16, TextPalette.VANILLA, 0L);

            int differing = 0, painted = 0;
            for (int y = 0; y < whole.height(); y++)
                for (int x = 0; x < whole.width() - 1; x++) {
                    if (half.getPixel(x + 1, y) != whole.getPixel(x, y)) differing++;
                    if (whole.getPixel(x, y) != 0xFF000000) painted++;
                }

            assertThat("bandPx " + bandPx + " paints", painted, is(greaterThan(0)));
            assertThat("bandPx " + bandPx + " pixels that differ one pixel along", differing, is(0));
        }
    }

    @Test
    @DisplayName("a per-letter underline spans the whole advance of a letter ending on a half pixel")
    void perLetterBarSpansAHalfPixelLetter() {
        MinecraftFont font = MinecraftFont.Vanilla.REGULAR;
        assertThat("'✦' ends on half an mcPixel", font.metrics().stringAdvanceMcPixels("✦") % 1f, is(0.5f));
        GradientSpec spec = startEnd(0x000000, 0xFFFFFF);
        TextSegment segment = TextSegment.builder().text("✦").underlined(true).gradient(spec).build();
        int scale = MinecraftFont.MC_PIXEL_SCALE;

        PixelBuffer buffer = PixelBuffer.create(48, 40);
        buffer.fill(0xFF000000);
        GradientKit.drawSegment(new MinecraftGraphics(buffer), segment, spec, "✦", DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L);

        // A lone letter samples the gradient's middle; the shadow's bar sits an mcPixel lower and darker.
        int row = 16 * scale + TextKit.UNDERLINE_OFFSET_MCPX * scale;
        int bar = 0xFF000000 | GradientKit.sample(spec, 0.5f);
        int first = -1, last = -1;
        for (int x = 0; x < buffer.width(); x++)
            if (buffer.getPixel(x, row) == bar) {
                if (first < 0) first = x;
                last = x;
            }

        assertThat("the bar opens at the letter's origin", first, is(DRAW_COLUMN_MC_PX * scale));
        assertThat("and closes on its advance's last pixel", last, is(DRAW_COLUMN_MC_PX * scale + font.glyph('✦').advanceWidth() - 1));
    }

    @Test
    @DisplayName("per-pixel bands spread over the whole of a run ending on a half pixel")
    void perPixelBandsSpanAHalfPixelRun() {
        MinecraftFont font = MinecraftFont.Vanilla.REGULAR;
        assertThat("'✦' ends on half an mcPixel", font.metrics().stringAdvanceMcPixels("✦") % 1f, is(0.5f));
        GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END)
            .addStop(0x000000).addStop(0xFFFFFF).bandPx(1).build();
        TextSegment segment = TextSegment.builder().text("✦").gradient(spec).build();
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int baseX = DRAW_COLUMN_MC_PX * scale;
        int baselineY = 16 * scale;

        PixelBuffer buffer = PixelBuffer.create(48, 40);
        buffer.fill(0xFF000000);
        GradientKit.drawSegment(new MinecraftGraphics(buffer), segment, spec, "✦", DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L, false);

        // The run's last lit column, and the topmost pixel lit in it.
        int lastX = -1, litY = -1;
        for (int x = 0; x < buffer.width(); x++)
            for (int y = 0; y < buffer.height(); y++)
                if (buffer.getPixel(x, y) != 0xFF000000) {
                    lastX = x;
                    litY = y;
                    break;
                }

        int band = GradientKit.sample(spec, GradientKit.bandCenterT(lastX - baseX, baselineY - litY, 0f, 1, font.glyph('✦').advanceWidth()));
        assertThat("the last column's band, normalised over the run's whole width", buffer.getPixel(lastX, litY), is(0xFF000000 | band));
    }

    /**
     * Draws a gradient segment and answers the advance it reports.
     *
     * @param text the segment's text
     * @param spec the gradient it carries
     * @return the advance in mcPixels
     */
    private static float advanceOf(String text, GradientSpec spec) {
        PixelBuffer buffer = PixelBuffer.create(64, 32);
        TextSegment segment = TextSegment.builder().text(text).gradient(spec).build();
        return GradientKit.drawSegment(new MinecraftGraphics(buffer), segment, spec, text, DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L);
    }

    @Test
    @DisplayName("per-letter samples one flat color per glyph at its advance center")
    void perLetterAdvanceCenter() {
        MinecraftFont font = MinecraftFont.Vanilla.REGULAR;
        int advM = font.glyph('M').advanceWidth();

        // Opaque black canvas so partial-coverage edges composite onto a known background (a bare
        // transparent buffer would premultiply the tint at anti-aliased edges).
        PixelBuffer buffer = PixelBuffer.create(96, 40);
        buffer.fill(0xFF000000);
        MinecraftGraphics g = new MinecraftGraphics(buffer);

        TextSegment segment = TextSegment.builder()
            .text("MM")
            .gradient(startEnd(0x000000, 0xFFFFFF)) // bandPx 0 = per-letter, black -> white
            .build();
        int baseX = DRAW_COLUMN_MC_PX * MinecraftFont.MC_PIXEL_SCALE;
        GradientKit.drawSegment(g, segment, segment.getGradient().orElseThrow(), "MM", DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L);

        // Left M samples ~0.25 (gray ~64), right M ~0.75 (gray ~191): left strictly darker.
        int leftMax = maxRed(buffer, baseX, baseX + advM);
        int rightMax = maxRed(buffer, baseX + advM, baseX + 2 * advM);
        assertThat(leftMax, is(lessThan(rightMax)));
        assertThat((double) leftMax, is(closeTo(64, 20)));
        assertThat((double) rightMax, is(closeTo(191, 20)));
    }

    @Test
    @DisplayName("a gradient draws each colour it lays down and its shadow in the colours the palette swaps them for, "
        + "the shadow derived from the unswapped colour")
    void gradientDrawsInThePalettesSwaps() {
        TextPalette gray = palette(Map.of(0xAAAAAA, 0xA8BFD2, 0x2A2A2A, 0x2A2F34));
        GradientSpec perLetter = startEnd(0xAAAAAA, 0xAAAAAA);
        GradientSpec perPixel = GradientSpec.builder(GradientSpec.Mode.START_END).addStop(0xAAAAAA).addStop(0xAAAAAA).bandPx(1).build();

        assertThat("per letter, every main pixel and every shadow pixel in its swap",
            drawnColours(perLetter, gray), is(Set.of(0xFFA8BFD2, 0xFF2A2F34)));
        assertThat("per letter, the shadow darkens the letter's colour before it is swapped, so a palette swapping the colour alone leaves it",
            drawnColours(perLetter, palette(Map.of(0xAAAAAA, 0x123456))), is(Set.of(0xFF123456, 0xFF2A2A2A)));
        assertThat("per pixel, every main pixel and every shadow pixel in its swap",
            drawnColours(perPixel, gray), is(Set.of(0xFFA8BFD2, 0xFF2A2F34)));
        assertThat("per pixel, the shadow darkens the band before it is swapped, so a palette swapping the band alone leaves it",
            drawnColours(perPixel, palette(Map.of(0xAAAAAA, 0x123456))), is(Set.of(0xFF123456, 0xFF2A2A2A)));
    }

    /**
     * Builds a palette holding the given swaps.
     *
     * @param swaps each swapped colour's 24-bit rgb and the rgb drawn in its place
     * @return the palette
     */
    private static TextPalette palette(Map<Integer, Integer> swaps) {
        return new TextPalette(new ResourceId("minecraft", "shaders/core/rendertype_text.vsh"), new PackId("pack"),
            Concurrent.newUnmodifiableMap(swaps));
    }

    /**
     * Draws a gradient segment with its shadow on a clear canvas and collects the colours it lays down.
     *
     * @param spec the gradient
     * @param palette the palette the segment is drawn through
     * @return each distinct colour of a pixel whose alpha is not zero
     */
    private static Set<Integer> drawnColours(GradientSpec spec, TextPalette palette) {
        PixelBuffer buffer = PixelBuffer.create(96, 40);
        TextSegment segment = TextSegment.builder().text("MM").gradient(spec).build();
        GradientKit.drawSegment(new MinecraftGraphics(buffer), segment, spec, "MM", DRAW_COLUMN_MC_PX, 16, palette, 0L);

        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < buffer.height(); y++)
            for (int x = 0; x < buffer.width(); x++)
                if ((buffer.getPixel(x, y) >>> 24) != 0) colours.add(buffer.getPixel(x, y));

        return colours;
    }

    private static int maxRed(PixelBuffer buffer, int x0, int x1) {
        int max = 0;
        for (int y = 0; y < buffer.height(); y++)
            for (int x = Math.max(0, x0); x < Math.min(buffer.width(), x1); x++)
                max = Math.max(max, ColorMath.red(buffer.getPixel(x, y)));
        return max;
    }
}
