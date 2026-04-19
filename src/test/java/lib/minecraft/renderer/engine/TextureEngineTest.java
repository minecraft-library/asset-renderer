package lib.minecraft.renderer.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class TextureEngineTest {

    @Test
    @DisplayName("sampleColormap returns the pixel at the expected temp/humidity coordinate")
    void sampleColormapReadsCorrectPixel() {
        int[] map = new int[256 * 256];
        map[128 * 256 + 128] = 0xFFAABBCC;
        // For temp=0.5, adjRain = 0.5*0.5 = 0.25 -> y = (1 - 0.25) * 255 = 191 (not 128)
        // Use temp=1.0, downfall=0.0 -> adjRain = 0 -> y = 255. Use temp=0.5, downfall=1.0 -> adjRain = 0.5 -> y = 127, x = 127.
        map[127 * 256 + 127] = 0xFF112233;

        int sampled = TextureEngine.sampleColormap(map, 0.5f, 1.0f);
        assertThat(sampled, is(equalTo(0xFF112233)));
    }

}
