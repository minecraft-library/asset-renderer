package lib.minecraft.renderer.bake.armor;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import lib.minecraft.renderer.vanilla.mesh.PlayerLattice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The 2D canvas layout each scope lays its parts out on - one rectangle per part, the canvas
 * counted downward where the lattice counts upward, and the horizontal offset centring the scope's
 * own union width rather than the full body's.
 */
@DisplayName("PlayerLayout2D - the canvas rectangles per scope")
class PlayerLayout2DTest {

    @Test
    @DisplayName("the 2D layout counts the canvas downward where the lattice counts upward")
    void layoutInvertsTheLatticeYAxis() {
        // Scale is the canvas over the scope's 32-pixel union height, and the horizontal offset
        // centres its 16-pixel union width, so 64 pixels give a scale of 2 and an offset of 16.
        ConcurrentList<PlayerLayout2D.BodyPart2D> layout = PlayerLayout2D.of(PlayerLattice.FULL, 64);

        assertThat("one rectangle per part", layout.size(), is(6));
        assertRect("head", layout.getFirst(), HumanoidPart.HEAD, 24, 0, 16, 16);
        assertRect("torso", layout.get(1), HumanoidPart.TORSO, 24, 16, 16, 24);
        assertRect("right arm", layout.get(2), HumanoidPart.RIGHT_ARM, 16, 16, 8, 24);
        assertRect("left arm", layout.get(3), HumanoidPart.LEFT_ARM, 40, 16, 8, 24);
        assertRect("right leg", layout.get(4), HumanoidPart.RIGHT_LEG, 24, 40, 8, 24);
        assertRect("left leg", layout.getLast(), HumanoidPart.LEFT_LEG, 32, 40, 8, 24);

        // The head owns the lattice's highest pixels and lands at the top of the canvas; the legs own
        // its lowest and land at the bottom, ending exactly on the canvas edge.
        assertThat("the legs close the canvas", layout.getLast().y() + layout.getLast().h(), is(64));
    }

    @Test
    @DisplayName("the bust layout is centred on its own union width, not on the full body's")
    void bustLayoutIsCentredOnItsOwnUnionWidth() {
        // The bust's union spans y -4..16, so 40 pixels give a scale of 2 over a 20-pixel height, and
        // its 16-pixel width leaves 4 pixels of margin on each side.
        ConcurrentList<PlayerLayout2D.BodyPart2D> layout = PlayerLayout2D.of(PlayerLattice.BUST, 40);

        assertThat("one rectangle per part", layout.size(), is(4));
        assertRect("head", layout.getFirst(), HumanoidPart.HEAD, 12, 0, 16, 16);
        assertRect("torso", layout.get(1), HumanoidPart.TORSO, 12, 16, 16, 24);
        assertRect("right arm", layout.get(2), HumanoidPart.RIGHT_ARM, 4, 16, 8, 24);
        assertRect("left arm", layout.getLast(), HumanoidPart.LEFT_ARM, 28, 16, 8, 24);
    }

    /** Asserts one laid-out rectangle's part and its canvas placement and extent. */
    private static void assertRect(String label, PlayerLayout2D.BodyPart2D rect, HumanoidPart part,
                                   int x, int y, int w, int h) {
        assertThat(label + " part", rect.part(), is(part));
        assertThat(label + " x", rect.x(), is(x));
        assertThat(label + " y", rect.y(), is(y));
        assertThat(label + " width", rect.w(), is(w));
        assertThat(label + " height", rect.h(), is(h));
    }

}
