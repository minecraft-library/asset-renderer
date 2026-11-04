package lib.minecraft.renderer.bake.texture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * {@link GlintKit}'s constants against the MC 26.1 deobfuscated client source (loop periods, per-type
 * scales, rotation, texture ids), the {@link GlintKit.GlintOptions} preset split -
 * {@link GlintKit.GlintOptions#itemDefault item} and {@link GlintKit.GlintOptions#armorDefault armor}
 * each routing to vanilla's texture and scale - and the 2-second loop's frame count, which tracks
 * {@code framesPerSecond}.
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
    @DisplayName("totalFrames scales with framesPerSecond for a 2-second loop")
    void twoSecondLoop() {
        assertThat(GlintKit.GlintOptions.itemDefault(30).totalFrames(), is(60));
        assertThat(GlintKit.GlintOptions.itemDefault(60).totalFrames(), is(120));
        assertThat(GlintKit.GlintOptions.itemDefault(120).totalFrames(), is(240));
    }

}
