package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.parity.PinSet;
import lib.minecraft.renderer.parity.Pins;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Pin on the exact size of each shipped corpus a loader reads.
 *
 * <p>Both counts live in one artifact, {@code pin.corpus-count}, so both are captured here rather
 * than one in each loader's own test. A pin-set is written only once every key it declares has a
 * value, and a set whose contributors sit in two classes could never be completed by either of them
 * alone - a partial capture that then promoted as a smaller population is exactly the false green
 * the completeness contract exists to prevent.
 *
 * <p>The loader tests keep the assertions that are about <em>their</em> loader - the
 * empty-versus-absent distinction, the parsed property map, the seven ids by name - and no longer
 * restate a number this pin holds.
 *
 * <p>A number moving here is a real event: the corpus is shipped data, so a count that drifts means
 * a tooling flow emitted a different population. Re-baselining is a promotion of the capture this
 * test already wrote, never an edit to a literal.
 */
@DisplayName("the shipped corpora are the sizes pinned in the parity store")
class CorpusCountPinTest {

    private static final String ARTIFACT = "pin.corpus-count";

    private static final PinSet PINS = PinSet.of(ARTIFACT, Map.of(
        "block_defaults_states",
        "BlockDefaultsLoader.load().size() over the bundled block_defaults.json - every blocks{} "
            + "entry, which is disjoint from unresolved{}",
        "glint_items",
        "GlintItemsLoader.load().size() over the bundled glint_items.json - the intrinsically-foil "
            + "items, those declaring ENCHANTMENT_GLINT_OVERRIDE=true",
        "block_tints",
        "BlockTintsLoader.load().size() over the bundled block_tints.json - every block declaring a "
            + "constant or biome tint target",
        "potion_colors",
        "PotionColorLoader.load().size() over the bundled potion_colors.json - every potion id "
            + "carrying a bottle colour"));

    @Test
    @DisplayName("block_defaults.json holds the pinned number of resolved default states")
    void blockDefaultsCorpusIsPinned() {
        int actual = BlockDefaultsLoader.load(
            ).size();
        assertPinned("block_defaults_states", actual);
    }

    @Test
    @DisplayName("glint_items.json holds the pinned number of always-glinted items")
    void glintItemsCorpusIsPinned() {
        assertPinned("glint_items", GlintItemsLoader.load().size());
    }

    @Test
    @DisplayName("block_tints.json holds the pinned number of tinted blocks")
    void blockTintsCorpusIsPinned() {
        assertPinned("block_tints", BlockTintsLoader.load().size());
    }

    @Test
    @DisplayName("potion_colors.json holds the pinned number of coloured potions")
    void potionColorsCorpusIsPinned() {
        assertPinned("potion_colors", PotionColorLoader.load().size());
    }

    private static void assertPinned(String key, int actual) {
        PINS.count(key, actual);
        PINS.requireBaseline();
        // A key added to an already-baselined pin-set has no stored value on its first capture, and
        // raising here would fail the run that is about to give it one. Skip loudly instead, so the
        // count still reaches the working root and the promote below has something to promote.
        Assumptions.assumeTrue(Pins.holds(ARTIFACT, key),
            () -> "corpus size " + key + " has no baseline yet, so there is nothing to assert against. "
                + "Give it one: " + Pins.bootstrapCommand(ARTIFACT));
        assertThat("corpus size " + key + "; a moved count means a flow emitted a different "
                + "population. If intentional, re-baseline it: " + Pins.rebaselineCommand(ARTIFACT),
            actual, is(Pins.count(ARTIFACT, key)));
    }

}
