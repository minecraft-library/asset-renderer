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
 * The merged loop math (LCM of animated layer periods, delay-per-frame) is exercised indirectly
 * via the promoted frame count rather than pinned here.
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
