package lib.minecraft.renderer.bake.texture;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.draw.SampledTexture;
import lib.minecraft.renderer.engine.draw.Sampler;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * {@link GlintKit}'s constants against the MC 26.1 deobfuscated client source (loop periods, per-type
 * scales, rotation, texture ids), the {@link GlintKit.GlintOptions} preset split -
 * {@link GlintKit.GlintOptions#itemDefault item} and {@link GlintKit.GlintOptions#armorDefault armor}
 * each routing to vanilla's texture and scale - and the 2-second loop's frame count, which tracks
 * {@code framesPerSecond}. The stamp samples the glint through the glint texture's own sampler: hard where
 * it reads nearest, blended where it reads linearly, and the checkerboard standing in for a glint no pack
 * supplies hard, as the client's missing texture is read.
 */
class GlintKitTest {

    /** The opaque base a stamp is laid over. */
    private static final int BASE = 0xFF203040;

    @Test
    @DisplayName("vanilla constants match TextureTransform.setupGlintTexturing from 26.1 client source")
    void vanillaConstants() {
        assertThat("MAX_ENCHANTMENT_GLINT_SPEED_MILLIS", GlintKit.MAX_ENCHANTMENT_GLINT_SPEED_MILLIS, is(8.0));
        assertThat("U loop period", GlintKit.VANILLA_U_LOOP_MILLIS, is(110_000L));
        assertThat("V loop period", GlintKit.VANILLA_V_LOOP_MILLIS, is(30_000L));
        assertThat("item glint scale", (double) GlintKit.ITEM_SCALE, is(closeTo(8.0, 1e-6)));
        assertThat("entity item glint scale", (double) GlintKit.ENTITY_ITEM_SCALE, is(closeTo(0.5, 1e-6)));
        assertThat("armor glint scale", (double) GlintKit.ARMOR_SCALE, is(closeTo(0.16, 1e-6)));
        assertThat("rotation radians ~10 degrees", (double) GlintKit.ROTATION_RADIANS, is(closeTo(Math.toRadians(10), 1e-4)));
    }

    @Test
    @DisplayName("texture ids match the extracted client jar paths")
    void textureIds() {
        assertThat(GlintKit.ITEM_GLINT_TEXTURE_ID, equalTo("minecraft:misc/enchanted_glint_item"));
        assertThat(GlintKit.ARMOR_GLINT_TEXTURE_ID, equalTo("minecraft:misc/enchanted_glint_armor"));
    }

    @Test
    @DisplayName("itemDefault preset uses the item glint texture and ITEM_SCALE")
    void itemDefaultPreset() {
        GlintKit.GlintOptions options = GlintKit.GlintOptions.itemDefault(30);
        assertThat(options.framesPerSecond(), is(30));
        assertThat(options.totalFrames(), is(60));
        assertThat(options.glintTextureId(), equalTo(GlintKit.ITEM_GLINT_TEXTURE_ID));
        assertThat(options.textureScale(), is(GlintKit.ITEM_SCALE));
        assertThat(options.uLoopMillis(), is(GlintKit.VANILLA_U_LOOP_MILLIS));
        assertThat(options.vLoopMillis(), is(GlintKit.VANILLA_V_LOOP_MILLIS));
    }

    @Test
    @DisplayName("armorDefault preset uses the armor glint texture and ARMOR_SCALE")
    void armorDefaultPreset() {
        GlintKit.GlintOptions options = GlintKit.GlintOptions.armorDefault(30);
        assertThat(options.glintTextureId(), equalTo(GlintKit.ARMOR_GLINT_TEXTURE_ID));
        assertThat(options.textureScale(), is(GlintKit.ARMOR_SCALE));
        assertThat(options.glintTextureId(), is(not(equalTo(GlintKit.ITEM_GLINT_TEXTURE_ID))));
    }

    @Test
    @DisplayName("totalFrames scales with framesPerSecond for a 2-second loop")
    void twoSecondLoop() {
        assertThat(GlintKit.GlintOptions.itemDefault(30).totalFrames(), is(60));
        assertThat(GlintKit.GlintOptions.itemDefault(60).totalFrames(), is(120));
        assertThat(GlintKit.GlintOptions.itemDefault(120).totalFrames(), is(240));
    }

    @Test
    @DisplayName("a glint is sampled by its own sampler: hard where it reads nearest, blended where it reads linearly")
    void aGlintIsSampledByItsOwnSampler() {
        int[] colours = { 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00 };
        int[] pixels = new int[16];
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++)
                pixels[y * 4 + x] = colours[x % 2 + 2 * (y % 2)];
        PixelBuffer glint = PixelBuffer.ofStraight(pixels, 4, 4);
        Set<Integer> stamped = new HashSet<>();
        for (int colour : colours)
            stamped.add(foiled(BASE, colour));

        PixelBuffer nearest = stampOnce(new SampledTexture(glint, Sampler.NEAREST_REPEAT));
        PixelBuffer linear = stampOnce(new SampledTexture(glint, new Sampler(Sampler.FilterMode.LINEAR, Sampler.AddressMode.REPEAT)));

        int blended = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertThat("nearest pixel (%d, %d)".formatted(x, y), stamped.contains(nearest.getPixel(x, y)), is(true));
                if (!stamped.contains(linear.getPixel(x, y))) blended++;
            }
        }
        assertThat("linear pixels that are none of the four colours stamped", blended, is(greaterThan(0)));
        assertThat("the two stamps differ", linear.data(), is(not(equalTo(nearest.data()))));
    }

    @Test
    @DisplayName("a glint no pack supplies scrolls as the hard checkerboard, as the client's missing texture reads")
    void aStandInGlintScrollsHard() {
        PixelBuffer frame = PixelBuffer.straight(16, 16);
        frame.fill(BASE);
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(frame);

        GlintKit.Foil.item(RendererContext.builder().build(), true, false, 30).finish(frames, new Timeline.Static(0));

        int magenta = foiled(BASE, MissingSprite.MAGENTA_ARGB);
        Set<Integer> stamped = Set.of(foiled(BASE, MissingSprite.BLACK_ARGB), magenta);
        int magentaPixels = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertThat("pixel (%d, %d)".formatted(x, y), stamped.contains(frame.getPixel(x, y)), is(true));
                if (frame.getPixel(x, y) == magenta) magentaPixels++;
            }
        }
        // The black check adds nothing to the base, so a frame no glint was stamped on passes every
        // pixel's check above; only a magenta pixel shows the checkerboard was laid.
        assertThat("the magenta check differs from the base", magenta, is(not(BASE)));
        assertThat("pixels stamped with the magenta check", magentaPixels, is(greaterThan(0)));
    }

    /**
     * Stamps one glint frame over an opaque 16 x 16 base at a fixed glint time, at a scale that reads many texels.
     *
     * @param glint the glint texture and its sampler
     * @return the stamped frame
     */
    private static PixelBuffer stampOnce(SampledTexture glint) {
        PixelBuffer base = PixelBuffer.straight(16, 16);
        base.fill(BASE);
        GlintKit.GlintOptions options = new GlintKit.GlintOptions(30, 60, GlintKit.ITEM_GLINT_TEXTURE_ID, 3.7f, false,
            GlintKit.VANILLA_U_LOOP_MILLIS, GlintKit.VANILLA_V_LOOP_MILLIS, ColorMath.WHITE);
        return GlintKit.applyGlintAtTimes(base, glint, new long[]{ 1_234L }, options, null).getFirst();
    }

    /**
     * Answers a base pixel with one opaque glint colour laid over it, faded by the glint's alpha and added
     * quadratically, as the stamp lays a texel it reads whole.
     *
     * @param base the base pixel
     * @param glint the glint colour
     * @return the stamped pixel
     */
    private static int foiled(int base, int glint) {
        int faded = ColorMath.pack(ColorMath.alpha(glint), GlintKit.fade(ColorMath.red(glint)),
            GlintKit.fade(ColorMath.green(glint)), GlintKit.fade(ColorMath.blue(glint)));
        return ColorMath.blend(faded, base, BlendMode.QUADRATIC_ADD);
    }

}
