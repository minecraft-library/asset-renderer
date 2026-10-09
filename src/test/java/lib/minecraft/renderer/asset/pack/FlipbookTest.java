package lib.minecraft.renderer.asset.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link Flipbook#frameAt} playback of a resolved {@link Flipbook} over a
 * vertically-stacked strip: single-frame passthrough, per-tick frame selection, modulo-cycle
 * looping (including negative ticks), {@link MCMeta.Animation#frametime() frametime} frame holds,
 * linear {@link MCMeta.Animation#interpolate() interpolation}, and explicit
 * {@link MCMeta.Frame} durations, plus {@link Flipbook#extractFrame} cropping row by row, the
 * {@link Flipbook.FrameSize frame rectangle} vanilla sizes and the strips it divides, and the
 * {@link Flipbook#of resolution} that decides a strip plays back at all. Each frame is authored as a
 * distinctive solid colour so the sampled pixel identifies which frame was selected.
 */
class FlipbookTest {

    @Test
    @DisplayName("single-frame strip returns the same frame regardless of tick")
    void singleFrameStrip_returnsSameFrame() {
        // 1x1 strip with a single frame - width=1, height=1, one red pixel
        PixelBuffer strip = PixelBuffer.of(new int[]{ 0xFFFF0000 }, 1, 1);
        Flipbook flipbook = flipbook(strip, 1, false, null);

        PixelBuffer frame0 = flipbook.frameAt(strip, 0);
        PixelBuffer frame5 = flipbook.frameAt(strip, 5);

        assertThat(frame0.width(), equalTo(1));
        assertThat(frame0.height(), equalTo(1));
        assertThat(frame0.getPixel(0, 0), equalTo(0xFFFF0000));
        assertThat(frame5.getPixel(0, 0), equalTo(0xFFFF0000));
    }

    @Test
    @DisplayName("4-frame vertical strip returns the correct frame for each tick")
    void fourFrameStrip_samplesByTick() {
        // 1-wide, 4-tall strip where each row is a distinctive color. With no width/height
        // override the frame is a square of the strip's shorter side (1), so height 4 / 1 = 4 frames.
        int[] pixels = { 0xFF000001, 0xFF000002, 0xFF000003, 0xFF000004 };
        PixelBuffer strip = PixelBuffer.of(pixels, 1, 4);
        Flipbook flipbook = flipbook(strip, 1, false, null);

        PixelBuffer f0 = flipbook.frameAt(strip, 0);
        PixelBuffer f1 = flipbook.frameAt(strip, 1);
        PixelBuffer f2 = flipbook.frameAt(strip, 2);
        PixelBuffer f3 = flipbook.frameAt(strip, 3);

        assertThat(f0.getPixel(0, 0), equalTo(0xFF000001));
        assertThat(f1.getPixel(0, 0), equalTo(0xFF000002));
        assertThat(f2.getPixel(0, 0), equalTo(0xFF000003));
        assertThat(f3.getPixel(0, 0), equalTo(0xFF000004));
    }

    @Test
    @DisplayName("tick wraps modulo total cycle length")
    void tick_wrapsModuloCycle() {
        int[] pixels = { 0xFFAA0000, 0xFF00BB00 };
        PixelBuffer strip = PixelBuffer.of(pixels, 1, 2);
        Flipbook flipbook = flipbook(strip, 1, false, null);

        PixelBuffer f0 = flipbook.frameAt(strip, 0);
        PixelBuffer f4 = flipbook.frameAt(strip, 4);
        PixelBuffer fNeg1 = flipbook.frameAt(strip, -1);

        assertThat(f0.getPixel(0, 0), equalTo(0xFFAA0000));
        assertThat(f4.getPixel(0, 0), equalTo(0xFFAA0000));
        assertThat(fNeg1.getPixel(0, 0), equalTo(0xFF00BB00));
    }

    @Test
    @DisplayName("frametime holds each frame for the right tick range")
    void frametime_holdsFrameRange() {
        int[] pixels = { 0xFF111111, 0xFF222222 };
        PixelBuffer strip = PixelBuffer.of(pixels, 1, 2);
        Flipbook flipbook = flipbook(strip, 3, false, null);

        PixelBuffer t0 = flipbook.frameAt(strip, 0);
        PixelBuffer t2 = flipbook.frameAt(strip, 2);
        PixelBuffer t3 = flipbook.frameAt(strip, 3);
        PixelBuffer t5 = flipbook.frameAt(strip, 5);

        assertThat(t0.getPixel(0, 0), equalTo(0xFF111111));
        assertThat(t2.getPixel(0, 0), equalTo(0xFF111111));
        assertThat(t3.getPixel(0, 0), equalTo(0xFF222222));
        assertThat(t5.getPixel(0, 0), equalTo(0xFF222222));
    }

    @Test
    @DisplayName("interpolation blends linearly between adjacent frames")
    void interpolation_blendsLinearly() {
        // Two single-pixel frames: pure black then pure white, held 4 ticks each with interpolate on.
        int[] pixels = { 0xFF000000, 0xFFFFFFFF };
        PixelBuffer strip = PixelBuffer.of(pixels, 1, 2);
        Flipbook flipbook = flipbook(strip, 4, true, null);

        // The blend alpha is the tick's progress into frame 0's 4-tick duration, toward frame 1.
        // Tick 0 sits at the very start of frame 0 -> 0% progress -> near-black.
        PixelBuffer t0 = flipbook.frameAt(strip, 0);
        // Tick 2 sits halfway through frame 0 -> 50% progress -> near-gray (blend of black + white).
        PixelBuffer t2 = flipbook.frameAt(strip, 2);

        int gray0 = t0.getPixel(0, 0) & 0xFF;
        int gray2 = t2.getPixel(0, 0) & 0xFF;
        assertThat((double) gray0, closeTo(0, 2));
        assertThat((double) gray2, closeTo(128, 2));
    }

    @Test
    @DisplayName("explicit frames list with custom time overrides respects entry durations")
    void explicitFrameList_respectsEntryDurations() {
        int[] pixels = { 0xFFAAAAAA, 0xFFBBBBBB };
        PixelBuffer strip = PixelBuffer.of(pixels, 1, 2);

        ConcurrentList<MCMeta.Frame> frames = Concurrent.newList();
        frames.add(new MCMeta.Frame(0, 5));
        frames.add(new MCMeta.Frame(1, 2));
        Flipbook flipbook = flipbook(strip, 1, false, frames);

        // Frame 0 holds for 5 ticks, frame 1 holds for 2 ticks
        assertThat(flipbook.frameAt(strip, 0).getPixel(0, 0), equalTo(0xFFAAAAAA));
        assertThat(flipbook.frameAt(strip, 4).getPixel(0, 0), equalTo(0xFFAAAAAA));
        assertThat(flipbook.frameAt(strip, 5).getPixel(0, 0), equalTo(0xFFBBBBBB));
        assertThat(flipbook.frameAt(strip, 6).getPixel(0, 0), equalTo(0xFFBBBBBB));
        // Cycle is 7 ticks, wraps back to frame 0
        assertThat(flipbook.frameAt(strip, 7).getPixel(0, 0), equalTo(0xFFAAAAAA));
    }

    @Test
    @DisplayName("a strip holding no whole frame resolves to no flipbook at all")
    void unplayableStrip_resolvesEmpty() {
        // A 4x4 strip whose sidecar declares 8-pixel-tall frames holds no whole frame, and a caller
        // that resolves nothing renders the strip unchanged rather than cropping a partial row.
        PixelBuffer strip = PixelBuffer.of(new int[16], 4, 4);
        assertThat(Flipbook.of(strip, new MCMeta.Animation(1, false, -1, 8, Concurrent.newList())).isPresent(), is(false));
        // A zero-width strip has no rectangle to crop either.
        assertThat(Flipbook.of(PixelBuffer.of(new int[0], 0, 0), animation(1, false, null)).isPresent(), is(false));
    }

    @Test
    @DisplayName("extractFrame returns only the requested row of a vertical strip")
    void extractFrame_returnsSingleRow() {
        int[] pixels = {
            0xFFFF0000, 0xFFFF0001,
            0xFF00FF00, 0xFF00FF01,
            0xFF0000FF, 0xFF0000FE
        };
        PixelBuffer strip = PixelBuffer.of(pixels, 2, 3);

        Flipbook table = Flipbook.of(strip,
            new MCMeta.Animation(1, false, 2, 1, Concurrent.newList())).orElseThrow();

        PixelBuffer row1 = table.extractFrame(strip, 1);

        assertThat(row1.width(), equalTo(2));
        assertThat(row1.height(), equalTo(1));
        assertThat(row1.getPixel(0, 0), equalTo(0xFF00FF00));
        assertThat(row1.getPixel(1, 0), equalTo(0xFF00FF01));
    }

    @Test
    @DisplayName("a strip wider than tall with no declared size plays its square frames left to right")
    void wideStrip_playsAcross() {
        // A 4x2 strip whose undeclared frame is a square of its shorter side: two 2x2 frames side by side.
        PixelBuffer strip = PixelBuffer.of(new int[]{
            0xFF0000AA, 0xFF0000AA, 0xFF0000BB, 0xFF0000BB,
            0xFF0000AA, 0xFF0000AA, 0xFF0000BB, 0xFF0000BB
        }, 4, 2);
        Flipbook flipbook = flipbook(strip, 1, false, null);

        assertThat(flipbook.frameWidth(), equalTo(2));
        assertThat(flipbook.frameHeight(), equalTo(2));
        assertThat(flipbook.entries().size(), equalTo(2));
        assertThat(flipbook.frameAt(strip, 0).getPixel(1, 1), equalTo(0xFF0000AA));
        assertThat(flipbook.frameAt(strip, 1).getPixel(0, 0), equalTo(0xFF0000BB));
    }

    @Test
    @DisplayName("a strip several frames wide and tall numbers its frames row by row")
    void gridStrip_numbersRowByRow() {
        // A 4x4 strip of four declared 2x2 frames, one colour each: top-left, top-right, bottom-left,
        // bottom-right.
        int a = 0xFF000001, b = 0xFF000002, c = 0xFF000003, d = 0xFF000004;
        PixelBuffer strip = PixelBuffer.of(new int[]{
            a, a, b, b,
            a, a, b, b,
            c, c, d, d,
            c, c, d, d
        }, 4, 4);
        Flipbook flipbook = Flipbook.of(strip, new MCMeta.Animation(1, false, 2, 2, Concurrent.newList())).orElseThrow();

        assertThat(flipbook.entries().size(), equalTo(4));
        assertThat(flipbook.extractFrame(strip, 0).getPixel(0, 0), equalTo(a));
        assertThat(flipbook.extractFrame(strip, 1).getPixel(0, 0), equalTo(b));
        assertThat(flipbook.extractFrame(strip, 2).getPixel(1, 1), equalTo(c));
        assertThat(flipbook.extractFrame(strip, 3).getPixel(1, 1), equalTo(d));
    }

    @Test
    @DisplayName("a declared side takes the strip's own size for the other, and neither declared is the shorter side square")
    void frameSize_followsVanilla() {
        PixelBuffer strip = PixelBuffer.create(8, 4);

        assertThat("width alone takes the strip's height",
            Flipbook.FrameSize.of(new MCMeta.Animation(1, false, 2, -1, Concurrent.newList()), strip),
            equalTo(new Flipbook.FrameSize(2, 4)));
        assertThat("height alone takes the strip's width",
            Flipbook.FrameSize.of(new MCMeta.Animation(1, false, -1, 2, Concurrent.newList()), strip),
            equalTo(new Flipbook.FrameSize(8, 2)));
        assertThat("both declared are taken as declared",
            Flipbook.FrameSize.of(new MCMeta.Animation(1, false, 3, 5, Concurrent.newList()), strip),
            equalTo(new Flipbook.FrameSize(3, 5)));
        assertThat("neither declared is a square of the shorter side",
            Flipbook.FrameSize.of(animation(1, false, null), strip), equalTo(new Flipbook.FrameSize(4, 4)));
    }

    @Test
    @DisplayName("a frame size divides a strip only when it divides both of its sides")
    void frameSize_dividesBothAxes() {
        assertThat("a tall strip of whole square frames",
            new Flipbook.FrameSize(16, 16).divides(PixelBuffer.create(16, 48)), is(true));
        assertThat("a height the frame does not divide",
            new Flipbook.FrameSize(16, 16).divides(PixelBuffer.create(16, 40)), is(false));
        assertThat("a width the frame does not divide",
            new Flipbook.FrameSize(16, 16).divides(PixelBuffer.create(24, 32)), is(false));
        assertThat("a strip shorter than its frame",
            new Flipbook.FrameSize(16, 16).divides(PixelBuffer.create(16, 8)), is(false));
        assertThat("a frame with no area divides nothing",
            new Flipbook.FrameSize(0, 0).divides(PixelBuffer.create(16, 16)), is(false));
    }

    @Test
    @DisplayName("a ragged strip handed over directly plays the whole frames it holds")
    void raggedStrip_playsItsWholeFrames() {
        // A pack serves such a strip as a texture with no pixels before it reaches here; one handed over
        // directly plays the two whole frames of its 2x5 strip and leaves the ragged row unplayed.
        PixelBuffer strip = PixelBuffer.of(new int[10], 2, 5);
        assertThat(flipbook(strip, 1, false, null).entries().size(), equalTo(2));
    }

    // --- fixtures ---

    /**
     * Resolves a {@link Flipbook} over the strip from the given frametime, interpolation flag and
     * optional explicit frame list, raising where the strip holds no playable frame.
     */
    private static Flipbook flipbook(PixelBuffer strip, int frametime, boolean interpolate, ConcurrentList<MCMeta.Frame> frames) {
        return Flipbook.of(strip, animation(frametime, interpolate, frames)).orElseThrow();
    }

    /**
     * Builds {@link MCMeta.Animation} with the given frametime, interpolation flag, and optional
     * explicit frame list ({@code null} yields an empty list so playback walks the strip in order).
     * Width and height are left at {@code -1} so the frame dimensions are inferred from the strip.
     */
    private static MCMeta.Animation animation(int frametime, boolean interpolate, ConcurrentList<MCMeta.Frame> frames) {
        return new MCMeta.Animation(frametime, interpolate, -1, -1, frames != null ? frames : Concurrent.newList());
    }

}
