package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.parity.PinSet;
import lib.minecraft.renderer.parity.Pins;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Pins the exact size of each shipped corpus a loader reads.
 *
 * <p>Both counts live in one artifact, {@code pin.corpus-count}, so both are captured here rather
 * than one in each loader's own test. A pin-set is written only once every key it declares has a
 * value, and a set whose contributors sit in two classes could never be completed by either of them
 * alone - a partial capture that then promoted as a smaller population is exactly the false green
 * the completeness contract exists to prevent.
 *
 * <p>The loader tests keep the assertions that are about <em>their</em> loader - the empty-versus
 * -absent distinction, the parsed property map, the seven ids by name - and no longer restate a
 * number this pin holds.
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
            + "items, those declaring ENCHANTMENT_GLINT_OVERRIDE=true"));

    @Test
    @DisplayName("block_defaults.json holds the pinned number of resolved default states")
    void blockDefaultsCorpusIsPinned() {
        int actual = BlockDefaultsLoader.load(
            Diagnostics.root("test", Diagnostics.Output.NONE, null)).size();
        assertPinned("block_defaults_states", actual);
    }

    @Test
    @DisplayName("glint_items.json holds the pinned number of always-glinted items")
    void glintItemsCorpusIsPinned() {
        assertPinned("glint_items", GlintItemsLoader.load().size());
    }

    private static void assertPinned(String key, int actual) {
        PINS.count(key, actual);
        PINS.requireBaseline();
        assertThat("corpus size " + key + "; a moved count means a flow emitted a different "
                + "population. If intentional, re-baseline it: " + Pins.rebaselineCommand(ARTIFACT),
            actual, is(Pins.count(ARTIFACT, key)));
    }

}
