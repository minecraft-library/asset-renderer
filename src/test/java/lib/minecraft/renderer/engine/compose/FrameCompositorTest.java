package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Coverage for {@link FrameCompositor#merge}'s static-versus-animated result-type dispatch.
 * Verifies the two branches of the merger:
 * <ul>
 * <li><b>All-static fast path</b> - when every {@link FramePlacement} wraps a
 *     {@link StaticImageData}, the result collapses to a single-frame {@code StaticImageData}.</li>
 * <li><b>Animated promotion</b> - a single animated placement promotes the whole composite to
 *     a multi-frame {@link AnimatedImageData}, whose loop is sampled at the requested frame
 *     rate.</li>
 * </ul>
 * The merged loop math is pinned exactly: the LCM of the animated layer periods over the
 * output-fps frame delay ceils to a known frame count, and an animated loop shorter than one output
 * frame still produces a one-frame {@code AnimatedImageData} - never a {@code StaticImageData}.
 * <p>
 * Layer periods come from each child's declared frame delays, so a child faster than the output
 * cadence is sampled at its own rate rather than a rounded one, and periods that share few factors
 * can drive the merged loop past {@link Timeline#MAX_LOOP_MS}. Both are pinned below, the second
 * because it is a way the merge can now degrade that uniform 50 ms periods could not reach.
 */
class FrameCompositorTest {

    @Test
    @DisplayName("all-static layers produce a StaticImageData fast path")
    void allStaticProducesStatic() {
        ConcurrentList<FramePlacement> layers = Concurrent.newList();
        layers.add(new FramePlacement(0, 0, StaticImageData.of(solidImage(4, 4, 0xFFFF0000))));
        layers.add(new FramePlacement(0, 0, StaticImageData.of(solidImage(4, 4, 0x8000FF00))));

        ImageData result = FrameCompositor.merge(layers, 4, 4, 30, Background.TRANSPARENT);
        assertThat(result, is(instanceOf(StaticImageData.class)));
    }

    @Test
    @DisplayName("any animated layer promotes the result to AnimatedImageData")
    void animatedLayerPromotesResult() {
        ConcurrentList<FramePlacement> layers = Concurrent.newList();
        layers.add(new FramePlacement(0, 0, StaticImageData.of(solidImage(4, 4, 0xFFFF0000))));
        layers.add(new FramePlacement(0, 0, animated(4, 4, 4, 50)));

        ImageData result = FrameCompositor.merge(layers, 4, 4, 30, Background.TRANSPARENT);
        assertThat(result, is(instanceOf(AnimatedImageData.class)));
        assertThat(((AnimatedImageData) result).getFrames().size(), greaterThan(1));
    }

    @Test
    @DisplayName("merged loop is the LCM of the animated layer periods; frame count ceils at the output fps")
    void exactMergedFrameCount() {
        ConcurrentList<FramePlacement> layers = Concurrent.newList();
        layers.add(new FramePlacement(0, 0, animated(4, 4, 4, 50)));   // 200 ms loop
        layers.add(new FramePlacement(0, 0, animated(4, 4, 6, 50)));   // 300 ms loop

        ImageData result = FrameCompositor.merge(layers, 4, 4, 30, Background.TRANSPARENT);
        assertThat(result, is(instanceOf(AnimatedImageData.class)));
        // lcm(200, 300) = 600 ms; delay = round(1000 / 30) = 33 ms; ceil(600 / 33) = 19 frames.
        assertThat(((AnimatedImageData) result).getFrames().size(), is(19));
    }

    @Test
    @DisplayName("an animated loop shorter than one output frame stays a 1-frame AnimatedImageData")
    void oneFrameAnimatedContainerEdge() {
        ConcurrentList<FramePlacement> layers = Concurrent.newList();
        layers.add(new FramePlacement(0, 0, StaticImageData.of(solidImage(4, 4, 0xFFFF0000))));
        layers.add(new FramePlacement(0, 0, animated(4, 4, 1, 50)));   // 50 ms loop period

        // At 15 fps the output frame delay is round(1000 / 15) = 67 ms, longer than the 50 ms loop,
        // so ceil(50 / 67) == 1. The animated branch keys on the input type, not the output count:
        // this must stay an AnimatedImageData with exactly one frame, never collapse to a StaticImageData.
        ImageData result = FrameCompositor.merge(layers, 4, 4, 15, Background.TRANSPARENT);
        assertThat(result, is(instanceOf(AnimatedImageData.class)));
        assertThat(((AnimatedImageData) result).getFrames().size(), is(1));
    }

    @Test
    @DisplayName("a child faster than 50 ms is sampled at its own cadence, once per child frame")
    void fastChildKeepsItsOwnCadence() {
        ConcurrentList<FramePlacement> layers = Concurrent.newList();
        layers.add(new FramePlacement(0, 0, animated(4, 4, 4, 33)));   // 132 ms loop

        ImageData result = FrameCompositor.merge(layers, 4, 4, 30, Background.TRANSPARENT);
        AnimatedImageData merged = (AnimatedImageData) result;

        // 4 x 33 ms = 132 ms; delay = round(1000 / 30) = 33 ms; ceil(132 / 33) = 4 output frames,
        // one per child frame. A 50 ms per-frame floor would have called the loop 200 ms and
        // stretched it to 7, sampling the child as 0,0,1,1,2,3,3 - the same frame twice over.
        assertThat(merged.getFrames().size(), is(4));

        // Each output frame carries a different child frame, so the ramp appears exactly once.
        for (int index = 0; index < 4; index++)
            assertThat(merged.getFrames().get(index).pixels().data()[0], is(0xFF000000 | (index * 0x40)));
    }

    @Test
    @DisplayName("declared periods sharing few factors can outgrow the loop cap")
    void mixedCadenceCanExceedTheLoopCap() {
        ConcurrentList<FramePlacement> layers = Concurrent.newList();
        layers.add(new FramePlacement(0, 0, animated(4, 4, 60, 33)));   // 1980 ms = 2^2 * 3^2 * 5 * 11
        layers.add(new FramePlacement(0, 0, animated(4, 4, 60, 50)));   // 3000 ms = 2^3 * 3 * 5^3

        ImageData result = FrameCompositor.merge(layers, 4, 4, 30, Background.TRANSPARENT);

        // lcm(1980, 3000) = 99000 ms - ten times MAX_LOOP_MS - so the merge clamps to the cap and
        // the composite closes mid-loop rather than on a whole number of child loops. Declared
        // periods make this reachable: a floor that rounded every period up to a multiple of 50 ms
        // kept the factors shared and the LCM small, and the 11 here shares nothing with its
        // sibling. ceil(10000 / 33) = 304.
        assertThat(((AnimatedImageData) result).getFrames().size(), is(304));
    }

    /** Builds a {@code w}x{@code h} buffer filled with a single ARGB colour. */
    private static @NotNull PixelBuffer solidImage(int w, int h, int argb) {
        int[] pixels = new int[w * h];
        Arrays.fill(pixels, argb);
        return PixelBuffer.of(pixels, w, h);
    }

    /**
     * Builds an animated fixture of {@code frameCount} solid frames, each held for {@code delayMs}.
     * Frame {@code i} is opaque with blue ramped by {@code i * 0x40} so successive frames differ,
     * guaranteeing the source registers as genuinely multi-frame.
     */
    private static @NotNull AnimatedImageData animated(int w, int h, int frameCount, int delayMs) {
        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int i = 0; i < frameCount; i++)
            builder.withFrame(ImageFrame.of(solidImage(w, h, 0xFF000000 | (i * 0x40)), delayMs));
        return builder.build();
    }

}
