package lib.minecraft.renderer.pipeline.load.block;

import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.option.spec.DyeColor;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link BlockModelLoader} against the bundled block resources: the two-file
 * models+geometry join, the {@code texture_size} adaptation, the runtime texture-path strip, the
 * icon open bag, DyeColor tints, and the no-{@code blocks[]} models (which carry no block binding).
 */
class BlockModelReaderTest {

    private static @NotNull BlockModelLoader.LoadResult load() {
        return BlockModelLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
    }

    @Test
    @DisplayName("joins geometry, strips the texture path, and adapts texture_size")
    void joinsGeometryAndStripsTexture() {
        Block.Entity sign = load().models().get("minecraft:oak_sign");

        assertNotNull(sign, "oak_sign binds to the sign model");
        assertEquals("minecraft:entity/signs/oak", sign.textureId(), "the full path is stripped to the runtime id");
        assertFalse(sign.boneModel().model().getBones().isEmpty(), "the geometry coordinate resolved to a bone tree");
        assertEquals(32, sign.boneModel().model().getTextureHeight(), "texture_size [64,32] populates textureHeight");
    }

    @Test
    @DisplayName("carries multi-part composition (bed head + foot) with stripped textures")
    void carriesParts() {
        Block.Entity bed = load().models().get("minecraft:white_bed");

        assertNotNull(bed);
        assertEquals("minecraft:entity/bed/white", bed.textureId());
        assertFalse(bed.parts().isEmpty(), "the bed head carries its foot as a part");
    }

    @Test
    @DisplayName("reads the icon open bag: bell is additive")
    void iconAdditive() {
        assertTrue(load().models().get("minecraft:bell").additive(), "bell_body's icon.additive is honoured");
    }

    @Test
    @DisplayName("resolves a banner DyeColor-name tint to its ARGB")
    void bannerTint() {
        Block.Entity banner = load().models().get("minecraft:white_banner");

        assertEquals(DyeColor.ofName("WHITE").argb(), banner.tintArgb());
        assertEquals("minecraft:entity/banner/banner_base", banner.textureId());
    }

    @Test
    @DisplayName("models with no blocks[] carry no block binding (enchanting_table, lectern)")
    void unboundModelsAbsent() {
        BlockModelLoader.LoadResult result = load();

        assertFalse(result.models().containsKey("minecraft:enchanting_table"),
            "enchanting_table's book model has no blocks[] binding, so no block maps to it");
        assertFalse(result.models().containsKey("minecraft:lectern"));
    }
}
