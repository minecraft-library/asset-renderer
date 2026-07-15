package lib.minecraft.renderer.engine.kit;

import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Pins {@link GradientKit}'s pure color evaluator (sRGB piecewise / HSV rainbow, per-mode position
 * handling), the scroll phase / shear math, the surrogate-safe advance measurement, and the
 * per-letter advance-center draw (06 §2.2-2.8).
 */
class GradientKitTest {

    private static GradientSpec startEnd(int from, int to) {
        return GradientSpec.builder(GradientSpec.Mode.START_END).addStop(from).addStop(to).build();
    }

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
        @ExtendWith(MinecraftFontsExtension.class)
        void perPixelSmoothSweep() {
            MinecraftFont font = MinecraftFont.REGULAR;
            int advW = font.glyph('W').advanceWidth();

            PixelBuffer buffer = PixelBuffer.create(4 * advW + 8, 40);
            buffer.fill(0xFF000000);
            MinecraftGraphics g = new MinecraftGraphics(buffer);

            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END)
                .addStop(0x000000).addStop(0xFFFFFF).bandPx(1).build();
            ColorSegment segment = ColorSegment.builder().withText("WWWW").withGradient(spec).build();
            int baseX = 1 * MinecraftFont.MC_PIXEL_SCALE;
            GradientKit.drawSegment(g, segment, spec, "WWWW", 1, 16, 0L);

            int leftMax = maxRed(buffer, baseX, baseX + advW);
            int rightMax = maxRed(buffer, baseX + 3 * advW, baseX + 4 * advW);
            assertThat(leftMax, is(lessThan(rightMax)));
            assertThat(leftMax, is(lessThan(110)));
            assertThat(rightMax, is(greaterThan(150)));
        }
    }

    @Test
    @DisplayName("measureCodepoints walks codepoints, so a surrogate pair is one glyph")
    @ExtendWith(MinecraftFontsExtension.class)
    void measureCodepointsIsSurrogateSafe() {
        MinecraftFont font = MinecraftFont.REGULAR;
        int astral = 0x1D400; // MATHEMATICAL BOLD CAPITAL A (needs a surrogate pair in UTF-16)
        String pair = new String(Character.toChars(astral));
        assertThat(pair.length(), is(2)); // two UTF-16 code units...
        assertThat(GradientKit.measureCodepoints(pair, font), is(font.glyph(astral).advanceWidth())); // ...one glyph advance
    }

    @Test
    @DisplayName("per-letter samples one flat color per glyph at its advance center")
    @ExtendWith(MinecraftFontsExtension.class)
    void perLetterAdvanceCenter() {
        MinecraftFont font = MinecraftFont.REGULAR;
        int advM = font.glyph('M').advanceWidth();

        // Opaque black canvas so partial-coverage edges composite onto a known background (a bare
        // transparent buffer would premultiply the tint at anti-aliased edges).
        PixelBuffer buffer = PixelBuffer.create(96, 40);
        buffer.fill(0xFF000000);
        MinecraftGraphics g = new MinecraftGraphics(buffer);

        ColorSegment segment = ColorSegment.builder()
            .withText("MM")
            .withGradient(startEnd(0x000000, 0xFFFFFF)) // bandPx 0 = per-letter, black -> white
            .build();
        int baseX = 1 * MinecraftFont.MC_PIXEL_SCALE;
        GradientKit.drawSegment(g, segment, segment.getGradient().orElseThrow(), "MM", 1, 16, 0L);

        // Left M samples ~0.25 (gray ~64), right M ~0.75 (gray ~191): left strictly darker.
        int leftMax = maxRed(buffer, baseX, baseX + advM);
        int rightMax = maxRed(buffer, baseX + advM, baseX + 2 * advM);
        assertThat(leftMax, is(lessThan(rightMax)));
        assertThat((double) leftMax, is(closeTo(64, 20)));
        assertThat((double) rightMax, is(closeTo(191, 20)));
    }

    private static int maxRed(PixelBuffer buffer, int x0, int x1) {
        int max = 0;
        for (int y = 0; y < buffer.height(); y++)
            for (int x = Math.max(0, x0); x < Math.min(buffer.width(), x1); x++)
                max = Math.max(max, ColorMath.red(buffer.getPixel(x, y)));
        return max;
    }
}
