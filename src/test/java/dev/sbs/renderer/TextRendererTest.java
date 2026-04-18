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
 * Smoke tests for {@link TextRenderer} covering the vanilla tooltip gradient border and the
 * background/border alpha defaults ({@code 240}/{@code 80}).
 */
class TextRendererTest {

    private static TextOptions singleLineLore() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder()
            .withSegments(ColorSegment.builder().withText("Test").build())
            .build());
        return TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
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
    @DisplayName("lore background fill pixel matches 0xF0100010")
    void backgroundFillMatchesVanilla() {
        TextOptions opts = singleLineLore();
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Border occupies y in [2, 4) (1 mcPixel inset + 1 mcPixel stroke = 4 output pixels).
        // Sample at y=5 which is well inside the padding interior above the first glyph.
        int cx = buf.width() / 2;
        int cy = 5;
        int px = buf.getPixel(cx, cy);
        assertThat("alpha in padding", ColorMath.alpha(px), is(0xF0));
        assertThat("RGB in padding", px & 0xFFFFFF, is(0x100010));
    }

    @Test
    @DisplayName("lore border top row uses gradient top color (α=80, RGB=0x5000FF)")
    void borderTopMatchesVanillaGradientTop() {
        TextOptions opts = singleLineLore();
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Border stroke is 1 mcPixel (2 output pixels) thick, inset 1 mcPixel from edge.
        // So the top stroke spans y in [2, 4). Sample at y=2.
        int px = buf.getPixel(buf.width() / 2, 2);
        assertThat("top border alpha", ColorMath.alpha(px), is(0x50));
        assertThat("top border RGB", px & 0xFFFFFF, is(0x5000FF));
    }

    @Test
    @DisplayName("lore border bottom row uses gradient bottom color (α=80, RGB=0x28007F)")
    void borderBottomMatchesVanillaGradientBottom() {
        TextOptions opts = singleLineLore();
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Bottom stroke spans y in [h-4, h-2). Sample at y = h - 3.
        int px = buf.getPixel(buf.width() / 2, buf.height() - 3);
        assertThat("bottom border alpha", ColorMath.alpha(px), is(0x50));
        assertThat("bottom border RGB", px & 0xFFFFFF, is(0x28007F));
    }

    @Test
    @DisplayName("left edge interior row interpolates between gradient endpoints")
    void borderLeftEdgeInterpolates() {
        TextOptions opts = singleLineLore();
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Left edge spans x in [2, 4). Sample at x=2 on the middle row.
        int px = buf.getPixel(2, buf.height() / 2);
        int r = ColorMath.red(px);
        int b = ColorMath.blue(px);
        assertThat("red is between endpoints", r, is(greaterThan(0x28 - 1)));
        assertThat("red is below top", r, is(equalTo(Math.min(r, 0x50))));
        assertThat("blue is between endpoints", b, is(greaterThan(0x7F - 1)));
        assertThat("border alpha preserved", ColorMath.alpha(px), is(0x50));
    }

}
