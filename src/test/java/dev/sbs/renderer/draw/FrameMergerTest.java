package dev.sbs.renderer.draw;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.AnimatedImageData;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFrame;
import dev.simplified.image.StaticImageData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class FrameMergerTest {

    @Test
    @DisplayName("all-static layers produce a StaticImageData fast path")
    void allStaticProducesStatic() {
        ConcurrentList<FrameMerger.Layer> layers = Concurrent.newList();
        layers.add(new FrameMerger.Layer(0, 0, StaticImageData.of(solidImage(4, 4, 0xFFFF0000))));
        layers.add(new FrameMerger.Layer(0, 0, StaticImageData.of(solidImage(4, 4, 0x8000FF00))));

        ImageData result = FrameMerger.merge(layers, 4, 4, 30, 0);
        assertThat(result, is(instanceOf(StaticImageData.class)));
    }

    @Test
    @DisplayName("any animated layer promotes the result to AnimatedImageData")
    void animatedLayerPromotesResult() {
        ConcurrentList<FrameMerger.Layer> layers = Concurrent.newList();
        layers.add(new FrameMerger.Layer(0, 0, StaticImageData.of(solidImage(4, 4, 0xFFFF0000))));
        layers.add(new FrameMerger.Layer(0, 0, animated(4, 4, 4, 50)));

        ImageData result = FrameMerger.merge(layers, 4, 4, 30, 0);
        assertThat(result, is(instanceOf(AnimatedImageData.class)));
        assertThat(((AnimatedImageData) result).getFrames().size(), greaterThan(1));
    }

    private static @org.jetbrains.annotations.NotNull BufferedImage solidImage(int w, int h, int argb) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++)
                image.setRGB(x, y, argb);
        }
        return image;
    }

    private static @org.jetbrains.annotations.NotNull AnimatedImageData animated(int w, int h, int frameCount, int delayMs) {
        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int i = 0; i < frameCount; i++)
            builder.withFrame(ImageFrame.of(solidImage(w, h, 0xFF000000 | (i * 0x40)), delayMs));
        return builder.build();
    }

}
