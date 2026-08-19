package lib.minecraft.renderer.asset;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Unit coverage for {@link ColorMap#sample} - the vanilla-parity biome colormap sampler. Pins the
 * temperature/downfall &rarr; {@code (x, y)} index math so a future change to the coordinate formula
 * fails loudly rather than silently shifting sampled tints, and pins the big-endian channel order the
 * byte-addressed read depends on.
 */
@DisplayName("ColorMap sampling")
class ColorMapTest {

    /**
     * Pins the coordinate math against a two-pixel fixture. For {@code temperature=0.5},
     * {@code downfall=1.0}: {@code adjTemp = 0.5}, {@code adjRain = clamp(1.0)*0.5 = 0.5}, so
     * {@code x = floor((1-0.5)*255) = 127} and {@code y = floor((1-0.5)*255) = 127}. The sampler
     * must therefore read the pixel at {@code 127*256+127} ({@code 0xFF112233}), not the decoy
     * planted at the naive-centre {@code 128*256+128} that a temperature-only formula would hit.
     */
    @Test
    @DisplayName("sample returns the pixel at the expected temp/humidity coordinate")
    void sampleReadsCorrectPixel() {
        byte[] map = new byte[256 * 256 * Integer.BYTES];
        writePixel(map, 128 * 256 + 128, 0xFFAABBCC);
        writePixel(map, 127 * 256 + 127, 0xFF112233);

        assertThat(colormap(map).sample(0.5f, 1.0f), is(equalTo(0xFF112233)));
    }

    /**
     * Pins the channel order. Both fixture values above are chosen so every byte differs, so a
     * little-endian read, or one that sign-extends an unmasked channel, cannot return the expected
     * value by coincidence. {@code 0xFF112233} read little-endian is {@code 0x332211FF}; read with
     * the low three bytes unmasked it is not an ARGB colour at all.
     */
    @Test
    @DisplayName("sample reads each pixel's four bytes big-endian")
    void sampleReadsBigEndian() {
        byte[] map = new byte[256 * 256 * Integer.BYTES];
        writePixel(map, 127 * 256 + 127, 0x8090A0B0);

        assertThat(colormap(map).sample(0.5f, 1.0f), is(equalTo(0x8090A0B0)));
    }

    /**
     * Wraps raw colormap bytes in a colormap, so the sampling cases assert on the pixel arithmetic
     * alone.
     *
     * @param pixels the raw colormap bytes
     * @return the synthesised colormap
     */
    private static @NotNull ColorMap colormap(byte @NotNull [] pixels) {
        return new ColorMap("test:colormap/grass", "test", Block.TintTarget.GRASS, pixels);
    }

    /**
     * Writes one ARGB pixel big-endian at a pixel index, the layout {@code ColorMapLoader} packs.
     *
     * @param map the raw colormap bytes
     * @param pixelIndex the row-major pixel index
     * @param argb the pixel to write
     */
    private static void writePixel(byte @NotNull [] map, int pixelIndex, int argb) {
        int offset = pixelIndex * Integer.BYTES;
        map[offset] = (byte) (argb >>> 24);
        map[offset + 1] = (byte) (argb >>> 16);
        map[offset + 2] = (byte) (argb >>> 8);
        map[offset + 3] = (byte) argb;
    }

}
