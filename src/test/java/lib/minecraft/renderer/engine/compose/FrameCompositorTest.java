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
import static org.hamcrest.Matchers.*;

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

    private static @NotNull PixelBuffer solidImage(int w, int h, int argb) {
        int[] pixels = new int[w * h];
        Arrays.fill(pixels, argb);
        return PixelBuffer.of(pixels, w, h);
    }

    private static @NotNull AnimatedImageData animated(int w, int h, int frameCount, int delayMs) {
        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int i = 0; i < frameCount; i++)
            builder.withFrame(ImageFrame.of(solidImage(w, h, 0xFF000000 | (i * 0x40)), delayMs));
        return builder.build();
    }

}
