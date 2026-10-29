package lib.minecraft.renderer.bake.gui;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.FontId;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Coverage of {@link GradientKit}'s pure color evaluator (sRGB piecewise / HSV rainbow, per-mode
 * position handling), the scroll phase / shear math, the segment's advance and its fractional origin,
 * the bars and bands over a run ending on half an mcPixel on either path, and the per-letter
 * advance-center draw. A draw leaving the shadow to its default is the draw asking for it, in the font
 * it is handed. A run naming its own shadow colour draws its shadow and its bars' shadow in it
 * on either path, where the derived shadow is declined too, and none for {@code 0} or for a colour the
 * text shader discards.
 * <p>
 * A gradient run's bars open one mcPixel left of the run, as a solid run's do, and a one-colour
 * gradient lays what the solid run in its colour lays, bars and their order included. A banded run's
 * bars read the bands its glyphs read, and every colour a run lays down, its bars' included, is drawn in
 * the palette's swap.
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
            GradientKit.drawSegment(g, segment, spec, segment.resolveFont(), "WWWW", DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L);

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
    @DisplayName("a gradient segment drawn without saying whether it casts a shadow is drawn as one asking for it, in the font it is handed")
    void aDrawWithItsShadowByDefaultKeepsTheFontItIsHanded() {
        GradientSpec spec = startEnd(0x000000, 0xFFFFFF);
        TextSegment segment = TextSegment.builder().text("Galactic").font(FontId.parse("minecraft:alt")).gradient(spec).build();

        PixelBuffer byDefault = PixelBuffer.create(160, 40);
        GradientKit.drawSegment(new MinecraftGraphics(byDefault), segment, spec, segment.resolveFont(), "Galactic", DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L);
        PixelBuffer asked = PixelBuffer.create(160, 40);
        GradientKit.drawSegment(new MinecraftGraphics(asked), segment, spec, segment.resolveFont(), "Galactic", DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L, true);

        int differing = 0, painted = 0;
        for (int y = 0; y < asked.height(); y++)
            for (int x = 0; x < asked.width(); x++) {
                if (byDefault.getPixel(x, y) != asked.getPixel(x, y)) differing++;
                if ((asked.getPixel(x, y) >>> 24) != 0) painted++;
            }

        assertThat("pixels painted", painted, is(greaterThan(0)));
        assertThat("pixels that differ", differing, is(0));
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
            TextKit.drawLine(new MinecraftGraphics(whole), line(segment), DRAW_COLUMN_MC_PX, 16, 0xFFFFFFFF, TextPalette.VANILLA, 0, 0L);
            PixelBuffer half = PixelBuffer.create(96, 40);
            half.fill(0xFF000000);
            TextKit.drawLine(new MinecraftGraphics(half), line(segment), DRAW_COLUMN_MC_PX + 0.5f, 16, 0xFFFFFFFF, TextPalette.VANILLA, 0, 0L);

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
    @DisplayName("a gradient underline runs from one mcPixel left of a letter ending on a half pixel to its end, per letter and per pixel")
    void perLetterBarSpansAHalfPixelLetter() {
        MinecraftFont font = MinecraftFont.Vanilla.REGULAR;
        assertThat("'✦' ends on half an mcPixel", font.metrics().stringAdvanceMcPixels("✦") % 1f, is(0.5f));
        GradientSpec spec = startEnd(0x000000, 0xFFFFFF);
        // A flat gradient makes a banded bar one colour, so its columns can be found as a letter's are.
        GradientSpec flat = GradientSpec.builder(GradientSpec.Mode.START_END).addStop(0xAAAAAA).addStop(0xAAAAAA).bandPx(1).build();
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int row = rowBelowTop(9.0F - 1.0F);
        int opens = (DRAW_COLUMN_MC_PX - 1) * scale;
        int closes = DRAW_COLUMN_MC_PX * scale + font.glyph('✦').advanceWidth() - 1;

        // A lone letter samples the gradient's middle; the shadow's bar sits an mcPixel lower and darker.
        int[] perLetter = litColumns(underlinedStar(spec), row, 0xFF000000 | GradientKit.sample(spec, 0.5f));
        int[] perPixel = litColumns(underlinedStar(flat), row, 0xFFAAAAAA);

        assertAll(
            () -> assertThat("per letter, the bar opens an mcPixel left of the letter", perLetter[0], is(opens)),
            () -> assertThat("per letter, the bar closes on its advance's last pixel", perLetter[1], is(closes)),
            () -> assertThat("per pixel, the bar opens an mcPixel left of the letter", perPixel[0], is(opens)),
            () -> assertThat("per pixel, the bar closes on its advance's last pixel", perPixel[1], is(closes))
        );
    }

    /**
     * Draws a line of an underlined {@code ✦} carrying a gradient, with its shadow, over an opaque black
     * buffer.
     *
     * @param spec the gradient the letter is drawn in
     * @return the buffer drawn on
     */
    private static PixelBuffer underlinedStar(GradientSpec spec) {
        TextSegment segment = TextSegment.builder().text("✦").underlined(true).gradient(spec).build();
        PixelBuffer buffer = PixelBuffer.create(48, 40);
        buffer.fill(0xFF000000);
        TextKit.drawLine(new MinecraftGraphics(buffer), line(segment), DRAW_COLUMN_MC_PX, 16, 0xFFFFFFFF, TextPalette.VANILLA, 0, 0L);
        return buffer;
    }

    @Test
    @DisplayName("a one-colour gradient lays what a solid run in its colour lays, bars and their order included")
    void aOneColourGradientLaysWhatTheSolidRunLays() {
        TextSegment solid = TextSegment.builder().text("Struck").strikethrough(true).underlined(true).build();
        PixelBuffer solidDrawn = PixelBuffer.create(120, 48);
        TextKit.drawLine(new MinecraftGraphics(solidDrawn), line(solid, TextSegment.literal("H")), DRAW_COLUMN_MC_PX, 16, 0xFFFFFFFF, TextPalette.VANILLA, 0, 0L);

        List<Executable> cases = new ArrayList<>();
        for (int bandPx : new int[] { GradientSpec.PER_LETTER, 1 }) {
            String path = bandPx == GradientSpec.PER_LETTER ? "per letter" : "per pixel";
            GradientSpec white = GradientSpec.builder(GradientSpec.Mode.START_END).addStop(0xFFFFFF).addStop(0xFFFFFF).bandPx(bandPx).build();
            TextSegment gradient = TextSegment.builder().text("Struck").strikethrough(true).underlined(true).gradient(white).build();
            PixelBuffer gradientDrawn = PixelBuffer.create(120, 48);
            TextKit.drawLine(new MinecraftGraphics(gradientDrawn), line(gradient, TextSegment.literal("H")), DRAW_COLUMN_MC_PX, 16, 0xFFFFFFFF, TextPalette.VANILLA, 0, 0L);

            List<String> differing = new ArrayList<>();
            for (int y = 0; y < solidDrawn.height(); y++)
                for (int x = 0; x < solidDrawn.width(); x++)
                    if (gradientDrawn.getPixel(x, y) != solidDrawn.getPixel(x, y))
                        differing.add(String.format("%d,%d %08X/%08X", x, y, gradientDrawn.getPixel(x, y), solidDrawn.getPixel(x, y)));
            cases.add(() -> assertThat(path + ", pixels the gradient lays apart from the solid run, as x,y gradient/solid", differing, is(empty())));
        }

        assertAll(cases);
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
        GradientKit.drawSegment(new MinecraftGraphics(buffer), segment, spec, segment.resolveFont(), "✦", DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L, false);

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
        return GradientKit.drawSegment(new MinecraftGraphics(buffer), segment, spec, segment.resolveFont(), text, DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L);
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
        GradientKit.drawSegment(g, segment, segment.getGradient().orElseThrow(), segment.resolveFont(), "MM", DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L);

        // Left M samples ~0.25 (gray ~64), right M ~0.75 (gray ~191): left strictly darker.
        int leftMax = maxRed(buffer, baseX, baseX + advM);
        int rightMax = maxRed(buffer, baseX + advM, baseX + 2 * advM);
        assertThat(leftMax, is(lessThan(rightMax)));
        assertThat((double) leftMax, is(closeTo(64, 20)));
        assertThat((double) rightMax, is(closeTo(191, 20)));
    }

    @Test
    @DisplayName("a gradient draws each colour it lays down, its bars' included, and its shadow in the colours the palette swaps them for, "
        + "the shadow derived from the unswapped colour")
    void gradientDrawsInThePalettesSwaps() {
        TextPalette gray = palette(Map.of(0xAAAAAA, 0xA8BFD2, 0x2A2A2A, 0x2A2F34));

        List<Executable> cases = new ArrayList<>();
        for (int bandPx : new int[] { GradientSpec.PER_LETTER, 1 }) {
            String path = bandPx == GradientSpec.PER_LETTER ? "per letter" : "per pixel";
            GradientSpec flat = GradientSpec.builder(GradientSpec.Mode.START_END).addStop(0xAAAAAA).addStop(0xAAAAAA).bandPx(bandPx).build();
            cases.add(() -> assertThat(path + ", every main pixel and every shadow pixel, the bars and their shadow copies included, in its swap",
                drawnColours(barred(flat).build(), gray, true), is(Set.of(0xFFA8BFD2, 0xFF2A2F34))));
            cases.add(() -> assertThat(path + ", the shadow darkens the colour before it is swapped, so a palette swapping the colour alone leaves it",
                drawnColours(barred(flat).build(), palette(Map.of(0xAAAAAA, 0x123456)), true), is(Set.of(0xFF123456, 0xFF2A2A2A))));
            cases.add(() -> assertThat(path + ", a shadow colour the run names is drawn in its swap, the bars' shadow copies included",
                drawnColours(shadowed(barred(flat), 0xFF2A2A2A), gray, false), is(Set.of(0xFFA8BFD2, 0xFF2A2F34))));
        }

        assertAll(cases);
    }

    @Test
    @DisplayName("a banded gradient's strikethrough is tinted at each column by the band its glyphs read there, its lead included, "
        + "and its shadow copy by that band darkened, an mcPixel right and down")
    void aBandedBarReadsTheBandsItsGlyphsRead() {
        GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END)
            .addStop(0x000000).addStop(0xFFFFFF).bandPx(1).build();
        TextSegment segment = TextSegment.builder().text("MM").strikethrough(true).gradient(spec).build();
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int baseX = DRAW_COLUMN_MC_PX * scale;
        int baselineY = 16 * scale;
        int widthOut = Math.round(segment.resolveFont().metrics().stringAdvanceMcPixels("MM") * scale);
        int top = rowBelowTop(4.5F - 1.0F);

        // A clear buffer, so the lead's black - the first stop, which the columns left of the run clamp to - is the bar's own.
        PixelBuffer buffer = PixelBuffer.create(64, 40);
        TextKit.drawLine(new MinecraftGraphics(buffer), line(segment), DRAW_COLUMN_MC_PX, 16, 0xFFFFFFFF, TextPalette.VANILLA, 0, 0L);

        List<String> bar = new ArrayList<>();
        List<String> copy = new ArrayList<>();
        for (int y = top; y < top + scale; y++)
            for (int x = baseX - scale; x < baseX + widthOut; x++) {
                int band = 0xFF000000 | GradientKit.sample(spec, GradientKit.bandCenterT(x - baseX, baselineY - y, 0f, 1, widthOut));
                int shadow = 0xFF000000 | TextKit.darken(band);
                if (buffer.getPixel(x, y) != band)
                    bar.add(String.format("%d,%d %08X/%08X", x, y, buffer.getPixel(x, y), band));
                if (buffer.getPixel(x + scale, y + scale) != shadow)
                    copy.add(String.format("%d,%d %08X/%08X", x + scale, y + scale, buffer.getPixel(x + scale, y + scale), shadow));
            }

        assertAll(
            () -> assertThat("bar pixels apart from the band the glyph path reads at their column, as x,y drawn/band", bar, is(empty())),
            () -> assertThat("shadow copy pixels apart from the band an mcPixel up and left darkened, as x,y drawn/shadow", copy, is(empty()))
        );
    }

    @Test
    @DisplayName("a gradient run draws a shadow colour it names on either path - where the derived shadow is declined, "
        + "nothing for 0 or for a colour the text shader discards - and its bars' shadow in it too")
    void aShadowColourIsDrawnOnEitherPath() {
        // The sprite tooltip's fill as its canvas holds it, premultiplied. The client's translucent
        // blend lays 80FF0000 over it as F8870007.
        int fill = 0xF00F000F;
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        // The underline's shadow copy, an mcPixel lower and right of the bar the client opens an mcPixel
        // left of the run.
        int shadowRow = rowBelowTop(9.0F - 1.0F) + scale;
        int shadowOpens = DRAW_COLUMN_MC_PX * scale;
        int shadowCloses = (DRAW_COLUMN_MC_PX + 1) * scale + MinecraftFont.Vanilla.REGULAR.glyph('!').advanceWidth() - 1;

        List<Executable> cases = new ArrayList<>();
        for (int bandPx : new int[] { GradientSpec.PER_LETTER, 1 }) {
            String path = bandPx == GradientSpec.PER_LETTER ? "per letter" : "per pixel";
            GradientSpec white = GradientSpec.builder(GradientSpec.Mode.START_END).addStop(0xFFFFFF).addStop(0xFFFFFF).bandPx(bandPx).build();
            cases.add(() -> assertThat(path + ", a see-through shadow colour is drawn where the derived shadow is declined, 80FF0000 over the fill",
                colours(drawnOver(fill, shadowed(gradient(white), 0x80FF0000), false)), is(Set.of(fill, 0xFFFFFFFF, 0xF8870007))));
            cases.add(() -> assertThat(path + ", an opaque shadow colour is drawn in place of the derived shadow",
                colours(drawnOver(fill, shadowed(gradient(white), 0xFF0000FF), true)), is(Set.of(fill, 0xFFFFFFFF, 0xFF0000FF))));
            cases.add(() -> assertThat(path + ", a shadow colour at alpha 25 lays nothing, as the text shader discards it",
                colours(drawnOver(fill, shadowed(gradient(white), 0x19FF0000), true)), is(Set.of(fill, 0xFFFFFFFF))));
            cases.add(() -> assertThat(path + ", a shadow colour of 0 draws no shadow",
                colours(drawnOver(fill, shadowed(gradient(white), 0), true)), is(Set.of(fill, 0xFFFFFFFF))));
            cases.add(() -> assertThat(path + ", the underline's shadow is drawn in the shadow colour, from its lead to the letter's advance",
                litColumns(lineDrawnOver(fill, shadowed(gradient(white).underlined(true), 0x80FF0000), false), shadowRow, 0xF8870007),
                is(new int[] { shadowOpens, shadowCloses })));
        }

        assertAll(cases);
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
     * Starts a struck and underlined run of {@code MM} carrying a gradient.
     *
     * @param spec the gradient the run is drawn in
     * @return the run's builder
     */
    private static TextSegment.Builder barred(GradientSpec spec) {
        return TextSegment.builder().text("MM").strikethrough(true).underlined(true).gradient(spec);
    }

    /**
     * Draws a line of one run carrying a gradient on a clear canvas, its bars included, and collects the
     * colours it lays down.
     *
     * @param segment the run
     * @param palette the palette the line is drawn through
     * @param dropShadow whether the shadow a run derives from its colour is asked for
     * @return each distinct colour of a pixel whose alpha is not zero
     */
    private static Set<Integer> drawnColours(TextSegment segment, TextPalette palette, boolean dropShadow) {
        PixelBuffer buffer = PixelBuffer.create(96, 40);
        TextKit.drawLine(new MinecraftGraphics(buffer), line(segment), DRAW_COLUMN_MC_PX, 16, 0xFFFFFFFF, palette, 0, 0L, 0L, dropShadow);

        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < buffer.height(); y++)
            for (int x = 0; x < buffer.width(); x++)
                if ((buffer.getPixel(x, y) >>> 24) != 0) colours.add(buffer.getPixel(x, y));

        return colours;
    }

    /**
     * Starts a run of {@code !} carrying a gradient. A {@code !} is one Minecraft pixel wide, so its
     * shadow, one Minecraft pixel right, never lands under its glyph, and each pass lays one colour.
     *
     * @param spec the gradient the run is drawn in
     * @return the run's builder
     */
    private static TextSegment.Builder gradient(GradientSpec spec) {
        return TextSegment.builder().text("!").gradient(spec);
    }

    /**
     * Finishes a run naming its own shadow colour, as the text library reads a component's
     * {@code shadow_color}.
     *
     * @param run the run's builder
     * @param argb the shadow colour as packed ARGB, its alpha included
     * @return the run
     */
    private static TextSegment shadowed(TextSegment.Builder run, int argb) {
        return run.shadowColor(ChatColor.builder().color(new Color(argb, true)).build()).build();
    }

    /**
     * Draws a run carrying a gradient over a filled buffer.
     *
     * @param fill the colour the buffer is filled with
     * @param segment the run
     * @param dropShadow whether the shadow a run derives from its colour is asked for
     * @return the buffer drawn on
     */
    private static PixelBuffer drawnOver(int fill, TextSegment segment, boolean dropShadow) {
        PixelBuffer buffer = PixelBuffer.create(40, 40);
        buffer.fill(fill);
        GradientKit.drawSegment(new MinecraftGraphics(buffer), segment, segment.getGradient().orElseThrow(), segment.resolveFont(), segment.getText(),
            DRAW_COLUMN_MC_PX, 16, TextPalette.VANILLA, 0L, dropShadow);
        return buffer;
    }

    /**
     * Draws a line of one run carrying a gradient over a filled buffer, its bars included. The line's
     * colour reaches no gradient pixel.
     *
     * @param fill the colour the buffer is filled with
     * @param segment the run
     * @param dropShadow whether the shadow a run derives from its colour is asked for
     * @return the buffer drawn on
     */
    private static PixelBuffer lineDrawnOver(int fill, TextSegment segment, boolean dropShadow) {
        PixelBuffer buffer = PixelBuffer.create(40, 40);
        buffer.fill(fill);
        TextKit.drawLine(new MinecraftGraphics(buffer), line(segment), DRAW_COLUMN_MC_PX, 16, 0xFFFFFFFF, TextPalette.VANILLA, 0, 0L, 0L, dropShadow);
        return buffer;
    }

    /**
     * Answers the output row a line drawn on baseline 16 lays at a distance below its top, the
     * {@code y} the client's font lays the line's effects from - the regular font's ascent above the
     * baseline.
     *
     * @param mcPx the distance below the line's top in Minecraft pixels
     * @return the row in output pixels
     */
    private static int rowBelowTop(float mcPx) {
        return (int) ((16 - MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels() + mcPx) * MinecraftFont.MC_PIXEL_SCALE);
    }

    private static LineSegment line(TextSegment... segments) {
        return LineSegment.builder().segments(Concurrent.newList(segments)).build();
    }

    /**
     * Collects every colour a buffer holds.
     *
     * @param buffer the buffer
     * @return each distinct colour it holds
     */
    private static Set<Integer> colours(PixelBuffer buffer) {
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < buffer.height(); y++)
            for (int x = 0; x < buffer.width(); x++)
                colours.add(buffer.getPixel(x, y));

        return colours;
    }

    /**
     * Finds the first and last column of a row holding exactly one colour.
     *
     * @param buffer the buffer to read
     * @param row the row to read
     * @param argb the colour to find
     * @return the first and last column holding it, {@code -1} for both where none does
     */
    private static int[] litColumns(PixelBuffer buffer, int row, int argb) {
        int first = -1, last = -1;
        for (int x = 0; x < buffer.width(); x++)
            if (buffer.getPixel(x, row) == argb) {
                if (first < 0) first = x;
                last = x;
            }
        return new int[] { first, last };
    }

    private static int maxRed(PixelBuffer buffer, int x0, int x1) {
        int max = 0;
        for (int y = 0; y < buffer.height(); y++)
            for (int x = Math.max(0, x0); x < Math.min(buffer.width(), x1); x++)
                max = Math.max(max, ColorMath.red(buffer.getPixel(x, y)));
        return max;
    }
}
