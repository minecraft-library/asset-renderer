package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.asset.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link BlockTintsLoader} against the bundled {@code block_tints.json} snapshot: the
 * native read resolves colormap-target and constant tints, and ignores the {@code dropped} rows.
 */
class BlockTintsLoaderTest {

    @Test
    @DisplayName("native load resolves colormap-target tints from the bundled snapshot")
    void loadsColormapTargets() {
        Map<String, Block.Tint> tints = BlockTintsLoader.load();

        assertThat("the bundled tint table must be non-empty", tints.size(), is(greaterThan(0)));
        Block.Tint grass = tints.get("minecraft:grass_block");
        assertThat(grass.target(), is(Block.TintTarget.GRASS));
        assertThat("a colormap-target tint carries no constant", grass.constant().isEmpty(), is(true));
        assertThat(tints.get("minecraft:oak_leaves").target(), is(Block.TintTarget.FOLIAGE));
    }

    @Test
    @DisplayName("native load decodes a constant tint through the Color codec")
    void decodesConstantTint() {
        Block.Tint lilyPad = BlockTintsLoader.load().get("minecraft:lily_pad");

        assertThat(lilyPad.target(), is(Block.TintTarget.CONSTANT));
        assertThat(lilyPad.constant().orElseThrow().getRGB(), is(0xFF71C35C));
    }

    @Test
    @DisplayName("dropped rows (dynamic-source blocks) never enter the tint table")
    void droppedRowsAbsent() {
        assertThat("redstone_wire is a dropped dynamic-source tint, not a tints[] row",
            BlockTintsLoader.load().containsKey("minecraft:redstone_wire"), is(false));
    }
}
