package dev.sbs.renderer;

import dev.sbs.renderer.options.TextOptions;
import dev.sbs.renderer.text.ColorSegment;
import dev.sbs.renderer.text.LineSegment;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Smoke tests for {@link TextRenderer} covering the vanilla tooltip gradient border, the
 * background/border alpha defaults ({@code 240}/{@code 80}), and integer supersampling.
 */
class TextRendererTest {

    private static TextOptions singleLineLore(int sampling) {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder()
            .withSegments(ColorSegment.builder().withText("Test").build())
            .build());
        return TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .sampling(sampling)
            .build();
    }

    @Test
    @DisplayName("default background alpha is 240 (0xF0) matching vanilla")
    void defaultBackgroundAlphaIsVanilla() {
        TextOptions opts = TextOptions.defaults();
        assertThat(opts.getBackgroundAlpha(), is(240));
    }

    @Test
    @DisplayName("default border alpha is 80 (0x50) matching vanilla")
    void defaultBorderAlphaIsVanilla() {
        TextOptions opts = TextOptions.defaults();
        assertThat(opts.getBorderAlpha(), is(80));
    }

    @Test
    @DisplayName("lore background fill pixel matches 0xF0100010 at native sampling")
    void backgroundFillMatchesVanilla() {
        TextOptions opts = singleLineLore(1);
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Sample inside the padding area above the text row - inside the border stroke
        // but above the first glyph's top-of-caps line.
        int cx = buf.width() / 2;
        int cy = 3;
        int px = buf.getPixel(cx, cy);
        assertThat("alpha in padding", ColorMath.alpha(px), is(0xF0));
        assertThat("RGB in padding", px & 0xFFFFFF, is(0x100010));
    }

    @Test
    @DisplayName("lore border top row uses gradient top color (α=80, RGB=0x5000FF)")
    void borderTopMatchesVanillaGradientTop() {
        TextOptions opts = singleLineLore(1);
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Border stroke is 1 mcPixel thick inset 1 mcPixel from edge.
        // Sample a pixel within the top stroke, well inside (away from corners).
        int px = buf.getPixel(buf.width() / 2, 1);
        assertThat("top border alpha", ColorMath.alpha(px), is(0x50));
        assertThat("top border RGB", px & 0xFFFFFF, is(0x5000FF));
    }

    @Test
    @DisplayName("lore border bottom row uses gradient bottom color (α=80, RGB=0x28007F)")
    void borderBottomMatchesVanillaGradientBottom() {
        TextOptions opts = singleLineLore(1);
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        int px = buf.getPixel(buf.width() / 2, buf.height() - 2);
        assertThat("bottom border alpha", ColorMath.alpha(px), is(0x50));
        assertThat("bottom border RGB", px & 0xFFFFFF, is(0x28007F));
    }

    @Test
    @DisplayName("left edge interior row interpolates between gradient endpoints")
    void borderLeftEdgeInterpolates() {
        TextOptions opts = singleLineLore(1);
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Middle row of left edge - should be roughly halfway between 0x5000FF and 0x28007F.
        int px = buf.getPixel(1, buf.height() / 2);
        int r = ColorMath.red(px);
        int b = ColorMath.blue(px);
        assertThat("red is between endpoints", r, is(greaterThan(0x28 - 1)));
        assertThat("red is below top", r, is(equalTo(Math.min(r, 0x50))));
        assertThat("blue is between endpoints", b, is(greaterThan(0x7F - 1)));
        assertThat("border alpha preserved", ColorMath.alpha(px), is(0x50));
    }

    @Test
    @DisplayName("supersampling=2 produces a buffer with the same logical dimensions as 1x")
    void supersamplingPreservesCanvasDimensions() {
        TextOptions opts1 = singleLineLore(1);
        TextOptions opts2 = singleLineLore(2);

        ImageData image1 = new TextRenderer().render(opts1);
        ImageData image2 = new TextRenderer().render(opts2);

        PixelBuffer buf1 = image1.getFrames().getFirst().pixels();
        PixelBuffer buf2 = image2.getFrames().getFirst().pixels();

        // Logical canvas is identical, supersampling only affects rendering precision.
        assertThat(buf2.width(), is(buf1.width()));
        assertThat(buf2.height(), is(buf1.height()));
    }

    @Test
    @DisplayName("supersampling=2 produces a visually equivalent lore tooltip (center pixel stable)")
    void supersamplingCenterPixelMatches() {
        TextOptions opts1 = singleLineLore(1);
        TextOptions opts2 = singleLineLore(2);

        PixelBuffer buf1 = new TextRenderer().render(opts1).getFrames().getFirst().pixels();
        PixelBuffer buf2 = new TextRenderer().render(opts2).getFrames().getFirst().pixels();

        // Sample a pixel definitely inside the padding area above the text row.
        int x = buf1.width() / 2;
        int y = 3;
        int px1 = buf1.getPixel(x, y);
        int px2 = buf2.getPixel(x, y);
        assertThat("1x padding pixel", px1 & 0xFFFFFF, is(0x100010));
        assertThat("2x padding pixel", px2 & 0xFFFFFF, is(0x100010));
        assertThat("1x padding alpha", ColorMath.alpha(px1), is(0xF0));
        assertThat("2x padding alpha", ColorMath.alpha(px2), is(0xF0));
    }
}
