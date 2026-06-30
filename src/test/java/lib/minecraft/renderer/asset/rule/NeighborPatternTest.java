package lib.minecraft.renderer.asset.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@DisplayName("NeighborPattern bitmask")
class NeighborPatternTest {

    @Test
    @DisplayName("EMPTY has no neighbors set")
    void empty() {
        assertThat(NeighborPattern.EMPTY.bits(), equalTo(0));
        assertThat(NeighborPattern.EMPTY.has(NeighborPattern.N), is(false));
        // The empty mask is vacuously contained in every pattern.
        assertThat(NeighborPattern.EMPTY.has(0), is(true));
    }

    @Test
    @DisplayName("has() requires every bit of the mask to be set")
    void hasRequiresAllBits() {
        NeighborPattern p = new NeighborPattern(NeighborPattern.N | NeighborPattern.E);
        assertThat(p.has(NeighborPattern.N), is(true));
        assertThat(p.has(NeighborPattern.E), is(true));
        assertThat(p.has(NeighborPattern.N | NeighborPattern.E), is(true));
        assertThat(p.has(NeighborPattern.S), is(false));
        // N is set but S is not, so the combined mask is not fully present.
        assertThat(p.has(NeighborPattern.N | NeighborPattern.S), is(false));
    }

    @Test
    @DisplayName("with() ORs in additional bits without clearing existing ones")
    void with() {
        NeighborPattern p = NeighborPattern.EMPTY.with(NeighborPattern.N).with(NeighborPattern.SE);
        assertThat(p.has(NeighborPattern.N), is(true));
        assertThat(p.has(NeighborPattern.SE), is(true));
        assertThat(p.bits(), equalTo(NeighborPattern.N | NeighborPattern.SE));
    }

    @Test
    @DisplayName("cardinals() sets only the four cardinal bits")
    void cardinals() {
        NeighborPattern p = NeighborPattern.cardinals(true, false, true, false);
        assertThat(p.has(NeighborPattern.N), is(true));
        assertThat(p.has(NeighborPattern.S), is(true));
        assertThat(p.has(NeighborPattern.E), is(false));
        assertThat(p.has(NeighborPattern.W), is(false));
        // No diagonal bit is ever set by cardinals().
        assertThat(p.has(NeighborPattern.NE), is(false));
        assertThat(p.bits(), equalTo(NeighborPattern.N | NeighborPattern.S));
    }

}
