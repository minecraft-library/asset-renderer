package dev.sbs.renderer.draw;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Verifies {@link GlintKit} constants against the MC 26.1 deobfuscated client source and checks
 * that the item/armor preset split matches vanilla's texture routing.
 */
class GlintKitTest {

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
    @DisplayName("entityItemDefault preset uses the item texture with ENTITY_ITEM_SCALE")
    void entityItemDefaultPreset() {
        GlintKit.GlintOptions options = GlintKit.GlintOptions.entityItemDefault(30);
        assertThat(options.glintTextureId(), equalTo(GlintKit.ITEM_GLINT_TEXTURE_ID));
        assertThat(options.textureScale(), is(GlintKit.ENTITY_ITEM_SCALE));
    }

    @Test
    @DisplayName("totalFrames scales with framesPerSecond for a 2-second loop")
    void twoSecondLoop() {
        assertThat(GlintKit.GlintOptions.itemDefault(30).totalFrames(), is(60));
        assertThat(GlintKit.GlintOptions.itemDefault(60).totalFrames(), is(120));
        assertThat(GlintKit.GlintOptions.itemDefault(120).totalFrames(), is(240));
    }

    @Test
    @DisplayName("apply emits the expected frame count and preserves canvas dimensions")
    void applyEmitsFrames() {
        PixelBuffer base = solidBuffer(16, 16, 0xFFFF0000);
        PixelBuffer glint = solidBuffer(32, 32, 0x40FFFFFF);
        GlintKit.GlintOptions options = GlintKit.GlintOptions.itemDefault(10);

        ConcurrentList<PixelBuffer> frames = GlintKit.apply(base, glint, options);

        assertThat(frames.size(), is(options.totalFrames()));
        for (PixelBuffer frame : frames) {
            assertThat(frame.getWidth(), is(16));
            assertThat(frame.getHeight(), is(16));
        }
    }

    @Test
    @DisplayName("apply masks the glint to the base image's opaque pixels")
    void applyMasksByBaseAlpha() {
        // Half-opaque base: left column opaque red, right column fully transparent.
        int[] pixels = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                pixels[y * 16 + x] = x < 8 ? 0xFFFF0000 : 0x00000000;
            }
        }
        PixelBuffer base = PixelBuffer.of(pixels, 16, 16);
        PixelBuffer glint = solidBuffer(32, 32, 0xFFFFFFFF);

        ConcurrentList<PixelBuffer> frames = GlintKit.apply(base, glint, GlintKit.GlintOptions.itemDefault(1));
        PixelBuffer first = frames.get(0);

        // Left half must be opaque (base red possibly brightened by glint).
        assertThat(ColorKit.alpha(first.getPixel(0, 0)), is(0xFF));
        // Right half must still be transparent - the glint is masked out.
        assertThat(ColorKit.alpha(first.getPixel(15, 0)), is(0x00));
    }

    private static PixelBuffer solidBuffer(int width, int height, int argb) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, argb);
        return PixelBuffer.of(pixels, width, height);
    }

}
