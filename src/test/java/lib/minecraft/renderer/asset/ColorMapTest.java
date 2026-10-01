package lib.minecraft.renderer.asset;

import lib.minecraft.renderer.vanilla.TintSource;
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
     * Pins the row to vanilla's double arithmetic at meadow's point, {@code (0.5, 0.8)}. In float,
     * {@code 1.0f - 0.4f} rounds to the float nearest {@code 0.6}, and that times {@code 255f} is
     * exactly {@code 153}. In double, the widened product is {@code 0.40000000596}, and
     * {@code (1.0 - 0.40000000596) * 255.0} is {@code 152.9999985}, which truncates to {@code 152}.
     */
    @Test
    @DisplayName("sample truncates the row in double, as vanilla does")
    void sampleRowIsVanillasDoubleTruncation() {
        byte[] map = new byte[256 * 256 * Integer.BYTES];
        writePixel(map, 152 * 256 + 127, 0xFF112233);
        writePixel(map, 153 * 256 + 127, 0xFFAABBCC);

        assertThat(colormap(map).sample(0.5f, 0.8f), is(equalTo(0xFF112233)));
    }

    /**
     * Pins the column to vanilla's double arithmetic at windswept hills' point, {@code (0.2, 0.3)}.
     * In float, {@code 1.0f - 0.2f} rounds to the float nearest {@code 0.8}, and that times
     * {@code 255f} rounds to exactly {@code 204}. In double, {@code (1.0 - 0.20000000298) * 255.0} is
     * {@code 203.9999992}, which truncates to {@code 203}. The row is {@code 239} either way.
     */
    @Test
    @DisplayName("sample truncates the column in double, as vanilla does")
    void sampleColumnIsVanillasDoubleTruncation() {
        byte[] map = new byte[256 * 256 * Integer.BYTES];
        writePixel(map, 239 * 256 + 203, 0xFF112233);
        writePixel(map, 239 * 256 + 204, 0xFFAABBCC);

        assertThat(colormap(map).sample(0.2f, 0.3f), is(equalTo(0xFF112233)));
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
        return new ColorMap("test:colormap/grass", "test", TintSource.GRASS, pixels);
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
