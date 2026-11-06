package lib.minecraft.renderer.engine.draw;

import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * {@link Sampler} and {@link SampledTexture} against the client's sampler state: the sampler the two flags of a
 * texture section select, both address modes, both filters, and the arithmetic every float fetch shares - the
 * linear filter the glint's to the bit, power-of-two mask included, and the repeating nearest read the end portal's
 * to the bit, at the coordinates that tell its fraction-first spelling from a floor-then-wrap one.
 * <p>
 * No stored artifact draws a plain texture through the linear filter, so this class is the filter's witness: every
 * expected value is a literal worked from the client's rule, the coordinate less half a texel and the two texels
 * either side weighted by the fraction, each addressed by the mode.
 */
@DisplayName("A sampler reads a texture as the client's sampler state does")
class SamplerTest {

    private static final @NotNull Sampler NEAREST_CLAMP = new Sampler(Sampler.FilterMode.NEAREST, Sampler.AddressMode.CLAMP_TO_EDGE);
    private static final @NotNull Sampler LINEAR_REPEAT = new Sampler(Sampler.FilterMode.LINEAR, Sampler.AddressMode.REPEAT);
    private static final @NotNull Sampler LINEAR_CLAMP = new Sampler(Sampler.FilterMode.LINEAR, Sampler.AddressMode.CLAMP_TO_EDGE);

    @Test
    @DisplayName("a texture section's two flags select the client's filter and address mode")
    void twoFlagsSelectTheClientsFilterAndAddress() {
        assertThat("neither flag", Sampler.of(false, false), is(Sampler.NEAREST_REPEAT));
        assertThat("blur", Sampler.of(true, false), is(LINEAR_REPEAT));
        assertThat("clamp", Sampler.of(false, true), is(NEAREST_CLAMP));
        assertThat("both", Sampler.of(true, true), is(LINEAR_CLAMP));
        assertThat("the missing texture's", Sampler.NEAREST_REPEAT,
            is(new Sampler(Sampler.FilterMode.NEAREST, Sampler.AddressMode.REPEAT)));
    }

    @Test
    @DisplayName("an index past either edge wraps under REPEAT and holds at the edge under CLAMP_TO_EDGE")
    void anAddressWrapsOrHoldsAnIndexPastAnEdge() {
        int[] indices = { -5, -1, 0, 3, 4, 100 };
        assertAddresses(Sampler.NEAREST_REPEAT, 4, indices, new int[]{ 3, 3, 0, 3, 0, 0 });
        assertAddresses(NEAREST_CLAMP, 4, indices, new int[]{ 0, 0, 0, 3, 3, 3 });
        assertAddresses(Sampler.NEAREST_REPEAT, 3, indices, new int[]{ 1, 2, 0, 0, 1, 1 });
        assertAddresses(NEAREST_CLAMP, 3, indices, new int[]{ 0, 0, 0, 2, 2, 2 });
    }

    @Test
    @DisplayName("a nearest sample reads the texel under the coordinate, through the address mode")
    void aNearestSampleReadsTheTexelUnderTheCoordinate() {
        PixelBuffer four = coded(4, 1);
        float[] us = { 0.1f, 0.3f, 1.1f, -0.1f, -1e-9f };
        // -1e-9 less its floor rounds to 1, which reads past the last texel and is held there.
        assertNearest(Sampler.NEAREST_REPEAT, four, us, new int[]{ 0, 1, 0, 3, 3 });
        assertNearest(NEAREST_CLAMP, four, us, new int[]{ 0, 1, 3, 0, 0 });

        // The fraction 0.33333325 times 3 is 0.99999976, texel 0, where flooring 2.3333333 * 3 = 7.0 and wrapping
        // reads texel 1 - the row that tells the fraction-first spelling from a floor-then-wrap one.
        PixelBuffer three = coded(3, 1);
        assertNearest(Sampler.NEAREST_REPEAT, three, new float[]{ 2.3333333f }, new int[]{ 0 });
        assertNearest(NEAREST_CLAMP, three, new float[]{ 2.3333333f }, new int[]{ 2 });
    }

    @Test
    @DisplayName("a linear sample blends the two texels either side of the coordinate less half a texel")
    void aLinearSampleBlendsTheTexelsAboutTheCoordinate() {
        PixelBuffer blackWhite = PixelBuffer.ofStraight(new int[]{ 0xFF000000, 0xFFFFFFFF }, 2, 1);
        // Texel-space 0.25, 0.75, 1.25 and 1.75.
        float[] us = { 0.125f, 0.375f, 0.625f, 0.875f };
        assertSamples(LINEAR_REPEAT, blackWhite, us, new int[]{ 0xFF404040, 0xFF404040, 0xFFBFBFBF, 0xFFBFBFBF });
        assertSamples(LINEAR_CLAMP, blackWhite, us, new int[]{ 0xFF000000, 0xFF404040, 0xFFBFBFBF, 0xFFFFFFFF });
    }

    @Test
    @DisplayName("a linear sample blends straight colour, alpha among the channels, a clear texel's black weighed in")
    void aLinearSampleBlendsStraightColourEveryChannelAlike() {
        PixelBuffer redClear = PixelBuffer.ofStraight(new int[]{ 0xFFFF0000, 0x00000000 }, 2, 1);
        assertSamples(LINEAR_REPEAT, redClear, new float[]{ 0.375f }, new int[]{ 0xBFBF0000 });
    }

    @Test
    @DisplayName("the linear repeating arithmetic is the glint's sampler to the bit, its power-of-two mask included")
    void theLinearArithmeticIsTheGlintsToTheBit() {
        Random random = new Random(0x5A3D1E);
        PixelBuffer texture = noisy(random, 128, 128);
        for (int i = 0; i < 4096; i++) {
            float u = random.nextFloat() * 6f - 3f;
            float v = random.nextFloat() * 6f - 3f;
            assertThat("(%s, %s)".formatted(u, v), LINEAR_REPEAT.sample(texture, u, v), is(glintSample(texture, u, v)));
        }
    }

    @Test
    @DisplayName("the nearest repeating arithmetic is the end portal's sampler to the bit")
    void theNearestRepeatArithmeticIsThePortalsToTheBit() {
        Random random = new Random(0x7E4B02);
        for (int size : new int[]{ 256, 128, 100 }) {
            PixelBuffer texture = noisy(random, size, size);
            for (int i = 0; i < 4096; i++) {
                float u = random.nextFloat() * 6f - 3f;
                float v = random.nextFloat() * 6f - 3f;
                assertThat("%d: (%s, %s)".formatted(size, u, v), Sampler.NEAREST_REPEAT.sample(texture, u, v), is(portalSample(texture, u, v)));
            }
        }

        // Two coordinates on which the portal's fraction-first read and a floor-then-wrap one part on a 100-texel
        // axis: 3 and 57 against 4 and 58.
        PixelBuffer hundred = coded(100, 100);
        float[] split = { 1.04f, 1.5799999f };
        int[] texels = { 3, 57 };
        for (int i = 0; i < split.length; i++) {
            float c = split[i];
            assertThat("u " + c, Sampler.NEAREST_REPEAT.sample(hundred, c, 0.5f), is(hundred.getPixel(texels[i], 50)));
            assertThat("v " + c, Sampler.NEAREST_REPEAT.sample(hundred, 0.5f, c), is(hundred.getPixel(50, texels[i])));
            assertThat("u " + c + " as the portal reads it", portalSample(hundred, c, 0.5f), is(hundred.getPixel(texels[i], 50)));
        }
    }

    @Test
    @DisplayName("interpolate addresses every tap by the sampler's mode")
    void interpolateAddressesEveryTap() {
        PixelBuffer texture = coded(4, 4);
        assertThat("repeat", LINEAR_REPEAT.interpolate(texture, -1, 0f, 3, 0f), is(texture.getPixel(3, 3)));
        assertThat("clamp", LINEAR_CLAMP.interpolate(texture, -1, 0f, 3, 0f), is(texture.getPixel(0, 3)));
        assertThat("repeat past the far edge", LINEAR_REPEAT.interpolate(texture, 3, 1f, 4, 0f), is(texture.getPixel(0, 0)));
        assertThat("clamp past the far edge", LINEAR_CLAMP.interpolate(texture, 3, 1f, 4, 0f), is(texture.getPixel(3, 3)));
    }

    @Test
    @DisplayName("a sampled texture samples through its own sampler, and a texture of no sampler reads nearest and repeating")
    void aSampledTextureSamplesThroughItsSampler() {
        PixelBuffer texture = PixelBuffer.ofStraight(new int[]{ 0xFF000000, 0xFFFFFFFF }, 2, 1);
        assertThat(new SampledTexture(texture, LINEAR_CLAMP).sample(0.875f, 0.5f), is(LINEAR_CLAMP.sample(texture, 0.875f, 0.5f)));
        assertThat(new SampledTexture(texture, LINEAR_REPEAT).sample(0.875f, 0.5f), is(0xFFBFBFBF));
        assertThat(SampledTexture.of(texture).sampler(), is(Sampler.NEAREST_REPEAT));
        assertThat(SampledTexture.of(texture).pixels(), equalTo(texture));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Asserts the texel each index addresses on an axis of one size.
     *
     * @param sampler the sampler addressing
     * @param size the axis size
     * @param indices the indices
     * @param expected the texel each addresses
     */
    private static void assertAddresses(@NotNull Sampler sampler, int size, int @NotNull [] indices, int @NotNull [] expected) {
        for (int i = 0; i < indices.length; i++)
            assertThat("%s, size %d, index %d".formatted(sampler.addressMode(), size, indices[i]), sampler.address(indices[i], size), is(expected[i]));
    }

    /**
     * Asserts the texel a one-row texture answers at each horizontal coordinate.
     *
     * @param sampler the sampler reading
     * @param texture the one-row texture
     * @param us the horizontal coordinates
     * @param texels the texel each reads
     */
    private static void assertNearest(@NotNull Sampler sampler, @NotNull PixelBuffer texture, float @NotNull [] us, int @NotNull [] texels) {
        for (int i = 0; i < us.length; i++)
            assertThat("%s at u %s".formatted(sampler.addressMode(), us[i]), sampler.sample(texture, us[i], 0.5f), is(texture.getPixel(texels[i], 0)));
    }

    /**
     * Asserts the colour a one-row texture answers at each horizontal coordinate.
     *
     * @param sampler the sampler reading
     * @param texture the one-row texture
     * @param us the horizontal coordinates
     * @param expected the colour each reads
     */
    private static void assertSamples(@NotNull Sampler sampler, @NotNull PixelBuffer texture, float @NotNull [] us, int @NotNull [] expected) {
        for (int i = 0; i < us.length; i++) {
            int sampled = sampler.sample(texture, us[i], 0.5f);
            assertThat("%s at u %s: 0x%08X".formatted(sampler.addressMode(), us[i], sampled), sampled, is(expected[i]));
        }
    }

    /**
     * Builds a texture whose every texel holds a colour of its own, opaque.
     *
     * @param width the width
     * @param height the height
     * @return the texture
     */
    private static @NotNull PixelBuffer coded(int width, int height) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++)
            pixels[i] = 0xFF000000 | (i * 0x010305 + 0x102030) & 0xFFFFFF;
        return PixelBuffer.ofStraight(pixels, width, height);
    }

    /**
     * Builds a texture of seeded noise on every channel, alpha among them.
     *
     * @param random the seeded source
     * @param width the width
     * @param height the height
     * @return the texture
     */
    private static @NotNull PixelBuffer noisy(@NotNull Random random, int width, int height) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++)
            pixels[i] = random.nextInt();
        return PixelBuffer.ofStraight(pixels, width, height);
    }

    /**
     * The glint's sampler as it stood before the sampler held it, kept here as the reference the linear arm is
     * pinned to: the coordinate times the size less half a texel, the four texels wrapped by a power-of-two mask or
     * {@code Math.floorMod}, and each channel blended along the row and down the column, rounded.
     *
     * @param texture the texture
     * @param u the horizontal coordinate, normalised
     * @param v the vertical coordinate, normalised
     * @return the sample
     */
    private static int glintSample(@NotNull PixelBuffer texture, float u, float v) {
        int w = texture.width();
        int h = texture.height();
        float texelX = u * w;
        float texelY = v * h;
        float fx = texelX - 0.5f;
        float fy = texelY - 0.5f;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float dx = fx - x0;
        float dy = fy - y0;
        boolean powerOfTwoW = w > 0 && (w & (w - 1)) == 0;
        boolean powerOfTwoH = h > 0 && (h & (h - 1)) == 0;
        int xa = powerOfTwoW ? x0 & (w - 1) : Math.floorMod(x0, w);
        int xb = powerOfTwoW ? (x0 + 1) & (w - 1) : Math.floorMod(x0 + 1, w);
        int ya = powerOfTwoH ? y0 & (h - 1) : Math.floorMod(y0, h);
        int yb = powerOfTwoH ? (y0 + 1) & (h - 1) : Math.floorMod(y0 + 1, h);
        int p00 = texture.getPixel(xa, ya);
        int p10 = texture.getPixel(xb, ya);
        int p01 = texture.getPixel(xa, yb);
        int p11 = texture.getPixel(xb, yb);
        return ColorMath.pack(
            glintBilerp(ColorMath.alpha(p00), ColorMath.alpha(p10), ColorMath.alpha(p01), ColorMath.alpha(p11), dx, dy),
            glintBilerp(ColorMath.red(p00), ColorMath.red(p10), ColorMath.red(p01), ColorMath.red(p11), dx, dy),
            glintBilerp(ColorMath.green(p00), ColorMath.green(p10), ColorMath.green(p01), ColorMath.green(p11), dx, dy),
            glintBilerp(ColorMath.blue(p00), ColorMath.blue(p10), ColorMath.blue(p01), ColorMath.blue(p11), dx, dy));
    }

    /**
     * The glint's channel blend, as it stood.
     *
     * @param c00 the first row's first texel
     * @param c10 the first row's second texel
     * @param c01 the second row's first texel
     * @param c11 the second row's second texel
     * @param dx the weight of the second column
     * @param dy the weight of the second row
     * @return the blended channel
     */
    private static int glintBilerp(int c00, int c10, int c01, int c11, float dx, float dy) {
        float top = c00 + (c10 - c00) * dx;
        float bottom = c01 + (c11 - c01) * dx;
        return Math.clamp(Math.round(top + (bottom - top) * dy), 0, 255);
    }

    /**
     * The end portal's sampler as it stood before the sampler held it, kept here as the reference the nearest
     * repeating arm is pinned to: the coordinate's fraction taken before the scale.
     *
     * @param texture the texture
     * @param u the horizontal coordinate, normalised
     * @param v the vertical coordinate, normalised
     * @return the texel read
     */
    private static int portalSample(@NotNull PixelBuffer texture, float u, float v) {
        int w = texture.width();
        int h = texture.height();
        float uw = u - (float) Math.floor(u);
        float vw = v - (float) Math.floor(v);
        int tx = Math.clamp((int) (uw * w), 0, w - 1);
        int ty = Math.clamp((int) (vw * h), 0, h - 1);
        return texture.getPixel(tx, ty);
    }

}
