package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.DyeColor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Coverage of {@link BlockModelLoader} against the bundled block resources: the two-file
 * models+geometry join, the {@code texture_size} adaptation, the runtime texture-path strip, the
 * icon open bag, DyeColor tints, and the no-{@code blocks[]} models (which carry no block binding).
 */
class BlockModelLoaderTest {

    private static @NotNull BlockModelLoader.LoadResult load() {
        return BlockModelLoader.load();
    }

    @Test
    @DisplayName("joins geometry, strips the texture path, and adapts texture_size")
    void joinsGeometryAndStripsTexture() {
        Block.Entity sign = load().models().get("minecraft:oak_sign");

        assertThat("oak_sign binds to the sign model", sign, is(notNullValue()));
        assertThat("the full path is stripped to the runtime id", sign.textureId(), is("minecraft:entity/signs/oak"));
        assertThat("the geometry coordinate resolved to a bone tree",
            sign.boneModel().model().getBones().isEmpty(), is(false));
        assertThat("texture_size [64,32] populates textureHeight",
            sign.boneModel().model().getTextureHeight(), is(32));
    }

    @Test
    @DisplayName("carries multi-part composition (bed head + foot) with stripped textures")
    void carriesParts() {
        Block.Entity bed = load().models().get("minecraft:white_bed");

        assertThat(bed, is(notNullValue()));
        assertThat(bed.textureId(), is("minecraft:entity/bed/white"));
        assertThat("the bed head carries its foot as a part", bed.parts().isEmpty(), is(false));
    }

    @Test
    @DisplayName("reads the icon open bag: bell is additive")
    void iconAdditive() {
        assertThat("bell_body's icon.additive is honoured", load().models().get("minecraft:bell").additive(), is(true));
    }

    @Test
    @DisplayName("resolves a banner DyeColor-name tint to its ARGB")
    void bannerTint() {
        Block.Entity banner = load().models().get("minecraft:white_banner");

        assertThat(banner.tintArgb(), is(DyeColor.ofName("WHITE").argb()));
        assertThat(banner.textureId(), is("minecraft:entity/banner/banner_base"));
    }

    @Test
    @DisplayName("models with no blocks[] carry no block binding (enchanting_table, lectern)")
    void unboundModelsAbsent() {
        BlockModelLoader.LoadResult result = load();

        assertThat("enchanting_table's book model has no blocks[] binding, so no block maps to it",
            result.models().containsKey("minecraft:enchanting_table"), is(false));
        assertThat(result.models().containsKey("minecraft:lectern"), is(false));
    }
}
