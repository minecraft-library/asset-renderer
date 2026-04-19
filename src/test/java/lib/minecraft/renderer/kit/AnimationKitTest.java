package lib.minecraft.renderer.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.pack.AnimationData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

class AnimationKitTest {

    @Test
    @DisplayName("single-frame strip returns the same frame regardless of tick")
    void singleFrameStrip_returnsSameFrame() {
        // 1x1 strip with a single frame - width=1, height=1, one red pixel
        PixelBuffer strip = PixelBuffer.of(new int[]{ 0xFFFF0000 }, 1, 1);
        AnimationData animation = animation(1, false, null);

        PixelBuffer frame0 = AnimationKit.sampleFrame(strip, animation, 0);
        PixelBuffer frame5 = AnimationKit.sampleFrame(strip, animation, 5);

        assertThat(frame0.width(), equalTo(1));
        assertThat(frame0.height(), equalTo(1));
        assertThat(frame0.getPixel(0, 0), equalTo(0xFFFF0000));
        assertThat(frame5.getPixel(0, 0), equalTo(0xFFFF0000));
    }

    @Test
    @DisplayName("4-frame vertical strip returns the correct frame for each tick")
    void fourFrameStrip_samplesByTick() {
        // 1-wide, 4-tall strip where each row has a distinctive color
        int[] pixels = { 0xFF000001, 0xFF000002, 0xFF000003, 0xFF000004 };
        PixelBuffer strip = PixelBuffer.of(pixels, 1, 4);
        AnimationData animation = animation(1, false, null);

        PixelBuffer f0 = AnimationKit.sampleFrame(strip, animation, 0);
        PixelBuffer f1 = AnimationKit.sampleFrame(strip, animation, 1);
        PixelBuffer f2 = AnimationKit.sampleFrame(strip, animation, 2);
        PixelBuffer f3 = AnimationKit.sampleFrame(strip, animation, 3);

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
        AnimationData animation = animation(1, false, null);

        PixelBuffer f0 = AnimationKit.sampleFrame(strip, animation, 0);
        PixelBuffer f4 = AnimationKit.sampleFrame(strip, animation, 4);
        PixelBuffer fNeg1 = AnimationKit.sampleFrame(strip, animation, -1);

        assertThat(f0.getPixel(0, 0), equalTo(0xFFAA0000));
        assertThat(f4.getPixel(0, 0), equalTo(0xFFAA0000));
        assertThat(fNeg1.getPixel(0, 0), equalTo(0xFF00BB00));
    }

    @Test
    @DisplayName("frametime holds each frame for the right tick range")
    void frametime_holdsFrameRange() {
        int[] pixels = { 0xFF111111, 0xFF222222 };
        PixelBuffer strip = PixelBuffer.of(pixels, 1, 2);
        AnimationData animation = animation(3, false, null);

        PixelBuffer t0 = AnimationKit.sampleFrame(strip, animation, 0);
        PixelBuffer t2 = AnimationKit.sampleFrame(strip, animation, 2);
        PixelBuffer t3 = AnimationKit.sampleFrame(strip, animation, 3);
        PixelBuffer t5 = AnimationKit.sampleFrame(strip, animation, 5);

        assertThat(t0.getPixel(0, 0), equalTo(0xFF111111));
        assertThat(t2.getPixel(0, 0), equalTo(0xFF111111));
        assertThat(t3.getPixel(0, 0), equalTo(0xFF222222));
        assertThat(t5.getPixel(0, 0), equalTo(0xFF222222));
    }

    @Test
    @DisplayName("interpolation blends linearly between adjacent frames")
    void interpolation_blendsLinearly() {
        // Two single-pixel frames: pure black then pure white
        int[] pixels = { 0xFF000000, 0xFFFFFFFF };
        PixelBuffer strip = PixelBuffer.of(pixels, 1, 2);
        AnimationData animation = animation(4, true, null);

        // Tick 0 sits at the very start of frame 0 -> 0% progress -> near-black
        PixelBuffer t0 = AnimationKit.sampleFrame(strip, animation, 0);
        // Tick 2 sits halfway through frame 0 -> 50% progress -> near-gray
        PixelBuffer t2 = AnimationKit.sampleFrame(strip, animation, 2);

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

        ConcurrentList<AnimationData.FrameEntry> frames = Concurrent.newList();
        frames.add(new AnimationData.FrameEntry(0, 5));
        frames.add(new AnimationData.FrameEntry(1, 2));
        AnimationData animation = animation(1, false, frames);

        // Frame 0 holds for 5 ticks, frame 1 holds for 2 ticks
        assertThat(AnimationKit.sampleFrame(strip, animation, 0).getPixel(0, 0), equalTo(0xFFAAAAAA));
        assertThat(AnimationKit.sampleFrame(strip, animation, 4).getPixel(0, 0), equalTo(0xFFAAAAAA));
        assertThat(AnimationKit.sampleFrame(strip, animation, 5).getPixel(0, 0), equalTo(0xFFBBBBBB));
        assertThat(AnimationKit.sampleFrame(strip, animation, 6).getPixel(0, 0), equalTo(0xFFBBBBBB));
        // Cycle is 7 ticks, wraps back to frame 0
        assertThat(AnimationKit.sampleFrame(strip, animation, 7).getPixel(0, 0), equalTo(0xFFAAAAAA));
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

        PixelBuffer row1 = AnimationKit.extractFrame(strip, 1, 2, 1);

        assertThat(row1.width(), equalTo(2));
        assertThat(row1.height(), equalTo(1));
        assertThat(row1.getPixel(0, 0), equalTo(0xFF00FF00));
        assertThat(row1.getPixel(1, 0), equalTo(0xFF00FF01));
    }

    // --- fixtures ---

    private static AnimationData animation(int frametime, boolean interpolate, ConcurrentList<AnimationData.FrameEntry> frames) {
        try {
            AnimationData data = new AnimationData();
            setField(data, "frametime", frametime);
            setField(data, "interpolate", interpolate);
            if (frames != null)
                setField(data, "frames", frames);
            return data;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to build test AnimationData", ex);
        }
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}
