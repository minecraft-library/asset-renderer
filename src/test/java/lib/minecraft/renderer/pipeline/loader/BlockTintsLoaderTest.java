package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.asset.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link BlockTintsLoader} against the bundled {@code block_tints.json} snapshot: the
 * native read resolves colormap-target and constant tints, and ignores the {@code dropped} rows.
 */
class BlockTintsLoaderTest {

    @Test
    @DisplayName("native load resolves colormap-target tints from the bundled snapshot")
    void loadsColormapTargets() {
        Map<String, Block.Tint> tints = BlockTintsLoader.load();

        assertTrue(tints.size() > 0, "the bundled tint table must be non-empty");
        Block.Tint grass = tints.get("minecraft:grass_block");
        assertEquals(Block.TintTarget.GRASS, grass.target());
        assertTrue(grass.constant().isEmpty(), "a colormap-target tint carries no constant");
        assertEquals(Block.TintTarget.FOLIAGE, tints.get("minecraft:oak_leaves").target());
    }

    @Test
    @DisplayName("native load decodes a constant tint through the Color codec")
    void decodesConstantTint() {
        Block.Tint lilyPad = BlockTintsLoader.load().get("minecraft:lily_pad");

        assertEquals(Block.TintTarget.CONSTANT, lilyPad.target());
        assertEquals(0xFF71C35C, lilyPad.constant().orElseThrow().getRGB());
    }

    @Test
    @DisplayName("dropped rows (dynamic-source blocks) never enter the tint table")
    void droppedRowsAbsent() {
        assertFalse(BlockTintsLoader.load().containsKey("minecraft:redstone_wire"),
            "redstone_wire is a dropped dynamic-source tint, not a tints[] row");
    }
}
