package lib.minecraft.renderer.pipeline.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link GlintItemsLoader} against the bundled {@code glint_items.json} snapshot. The
 * vanilla MC 26.1 set is the seven intrinsically-foil items; the test pins those ids and a couple of
 * non-glint negatives so regressions in either the ASM tooling or the JSON loader get caught early.
 */
class GlintItemsLoaderTest {

    @Test
    @DisplayName("loads the bundled snapshot with exactly the seven vanilla always-glinted items")
    void loadsTheSevenGlintItems() {
        Set<String> ids = GlintItemsLoader.load();
        assertThat(ids.size(), is(7));
        assertThat(ids.contains("minecraft:enchanted_book"), is(true));
        assertThat(ids.contains("minecraft:written_book"), is(true));
        assertThat(ids.contains("minecraft:enchanted_golden_apple"), is(true));
        assertThat(ids.contains("minecraft:experience_bottle"), is(true));
        assertThat(ids.contains("minecraft:nether_star"), is(true));
        assertThat(ids.contains("minecraft:debug_stick"), is(true));
        assertThat(ids.contains("minecraft:end_crystal"), is(true));
    }

    @Test
    @DisplayName("plain items are absent from the always-glinted set")
    void plainItemsAbsent() {
        Set<String> ids = GlintItemsLoader.load();
        assertThat(ids.contains("minecraft:stick"), is(false));
        assertThat(ids.contains("minecraft:diamond_sword"), is(false));
        assertThat(ids.contains("minecraft:golden_apple"), is(false));
    }

}
