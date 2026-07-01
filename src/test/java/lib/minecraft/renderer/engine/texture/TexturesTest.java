package lib.minecraft.renderer.engine.texture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Unit coverage for {@link Textures#sampleColormap} - the vanilla-parity biome colormap sampler.
 * Pins the temperature/downfall &rarr; {@code (x, y)} index math so a future change to the
 * coordinate formula fails loudly rather than silently shifting sampled tints.
 */
class TexturesTest {

    /**
     * Pins the coordinate math against a two-pixel fixture. For {@code temperature=0.5},
     * {@code downfall=1.0}: {@code adjTemp = 0.5}, {@code adjRain = clamp(1.0)*0.5 = 0.5}, so
     * {@code x = floor((1-0.5)*255) = 127} and {@code y = floor((1-0.5)*255) = 127}. The sampler
     * must therefore read {@code map[127*256+127]} ({@code 0xFF112233}), not the decoy planted at
     * the naive-centre {@code map[128*256+128]} that a temperature-only formula would hit.
     */
    @Test
    @DisplayName("sampleColormap returns the pixel at the expected temp/humidity coordinate")
    void sampleColormapReadsCorrectPixel() {
        int[] map = new int[256 * 256];
        map[128 * 256 + 128] = 0xFFAABBCC;
        map[127 * 256 + 127] = 0xFF112233;

        int sampled = Textures.sampleColormap(map, 0.5f, 1.0f);
        assertThat(sampled, is(equalTo(0xFF112233)));
    }

}
