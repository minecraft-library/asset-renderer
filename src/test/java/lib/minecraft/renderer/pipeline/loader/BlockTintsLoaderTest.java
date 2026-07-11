package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link BlockTintsLoader} against the bundled {@code v2/block_tints.json} snapshot: the
 * native read resolves colormap-target and constant tints, ignores {@code dropped} rows, and the
 * mapping helper skips an unknown {@link Block.TintTarget} with a diagnostic rather than aborting.
 */
class BlockTintsLoaderTest {

    private static @NotNull Diagnostics diagnostics() {
        return Diagnostics.root("test", Diagnostics.Output.NONE, null);
    }

    @Test
    @DisplayName("native load resolves colormap-target tints from the bundled v2 snapshot")
    void loadsColormapTargets() {
        ConcurrentMap<String, Block.Tint> tints = BlockTintsLoader.load();

        assertTrue(tints.size() > 0, "the bundled tint table must be non-empty");
        Block.Tint grass = tints.get("minecraft:grass_block");
        assertEquals(Block.TintTarget.GRASS, grass.target());
        assertTrue(grass.constant().isEmpty(), "a colormap-target tint carries no constant");
        assertEquals(Block.TintTarget.FOLIAGE, tints.get("minecraft:oak_leaves").target());
    }

    @Test
    @DisplayName("native load decodes a constant tint through ArgbHex")
    void decodesConstantTint() {
        Block.Tint lilyPad = BlockTintsLoader.load().get("minecraft:lily_pad");

        assertEquals(Block.TintTarget.CONSTANT, lilyPad.target());
        assertEquals(0xFF71C35C, lilyPad.constant().orElseThrow());
    }

    @Test
    @DisplayName("dropped rows (dynamic-source blocks) never enter the tint table")
    void droppedRowsAbsent() {
        assertFalse(BlockTintsLoader.load().containsKey("minecraft:redstone_wire"),
            "redstone_wire is a dropped dynamic-source tint, not a tints[] row");
    }

    @Test
    @DisplayName("toTints skips an unknown target with a diagnostic instead of aborting")
    void skipsUnknownTarget() {
        Diagnostics diag = diagnostics();
        ConcurrentMap<String, Block.Tint> tints = BlockTintsLoader.toTints(
            List.of(new BlockTintsLoader.V2TintRow("minecraft:mystery", "NOT_A_TARGET", null)), diag);

        assertEquals(0, tints.size(), "the unknown-target row is skipped");
        assertEquals(1, diag.count(Diagnostics.Severity.WARN), "the skip is recorded as a warning");
    }

    @Test
    @DisplayName("toTints decodes an 8-digit constant identically to the legacy parseUnsignedInt")
    void constantMatchesLegacy() {
        ConcurrentMap<String, Block.Tint> tints = BlockTintsLoader.toTints(
            List.of(new BlockTintsLoader.V2TintRow("minecraft:x", "CONSTANT", "0xFF00FF00")), diagnostics());

        assertEquals(0xFF00FF00, tints.get("minecraft:x").constant().orElseThrow());
    }
}
