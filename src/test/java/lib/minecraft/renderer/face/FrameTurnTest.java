package lib.minecraft.renderer.face;

import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Pins {@link Turn} against the frame relations the renderer performs, and against the values the
 * three separate spellings of the half turn about X each carried before there was one symbol for it.
 * <p>
 * A shell's unwrap is authored in vanilla's Y-down model frame while the boxes built from it are
 * upright, so a site that builds geometry with one enum's corner order and textures it with the
 * other's unwrap has to name that turn. Three did, in three syntaxes, in two packages, with no shared
 * symbol: a six-entry map in the armour kit, a {@code switch} in the shield kit, and the armour kit's
 * own {@code (x, -y, -z)} point turn. The six pairings below are those tables' contents transcribed,
 * so this test still fails if the one surviving definition drifts from what all three agreed on.
 * <p>
 * The group properties are checked as well, because they are what let one symbol replace six tables:
 * closure under {@link Turn#then}, so a mirrored shell cube can read {@code HALF_X.then(MIRROR_X)}
 * rather than needing a relation minted for the combination; the identity and inverse laws; and that
 * {@link Turn#reflects} agrees with the determinant. <b>Reflections are not excluded</b> - four of the
 * relations in use are reflections, so a rotation-only set would express none of them.
 * <p>
 * The face mapping reads a face's axis off its ordinal, so the two enums declaring their constants in
 * the same opposing-pair order is load-bearing and is asserted here rather than assumed.
 */
@DisplayName("Turn - the frame relations, their group laws, and the half turn about X")
class FrameTurnTest {

    @Test
    @DisplayName("both face enums declare the same six names in the same opposing-pair order")
    void faceEnumsShareTheirOrdinalLayout() {
        assertThat("the two enums must agree constant for constant",
            EntityFace.CACHED_VALUES.length, is(BlockFace.CACHED_VALUES.length));

        for (int i = 0; i < BlockFace.CACHED_VALUES.length; i++)
            assertThat("ordinal " + i, EntityFace.CACHED_VALUES[i].name(),
                equalTo(BlockFace.CACHED_VALUES[i].name()));

        // Pairs share an axis and differ only in sign, which is what makes ordinal ^ 1 the opposite.
        for (int i = 0; i < BlockFace.CACHED_VALUES.length; i += 2) {
            Vector3f low = BlockFace.CACHED_VALUES[i].normal();
            Vector3f high = BlockFace.CACHED_VALUES[i + 1].normal();
            assertThat(low + " and " + high + " are opposites",
                high, equalTo(new Vector3f(-low.x() + 0f, -low.y() + 0f, -low.z() + 0f)));
        }
    }

    @Test
    @DisplayName("HALF_X crosses to the entity frame exactly as the three deleted tables did")
    void halfTurnCrossesFramesAsTheTablesDid() {
        assertThat(Turn.HALF_X.entityFace(BlockFace.DOWN), is(EntityFace.UP));
        assertThat(Turn.HALF_X.entityFace(BlockFace.UP), is(EntityFace.DOWN));
        assertThat(Turn.HALF_X.entityFace(BlockFace.NORTH), is(EntityFace.SOUTH));
        assertThat(Turn.HALF_X.entityFace(BlockFace.SOUTH), is(EntityFace.NORTH));
        assertThat(Turn.HALF_X.entityFace(BlockFace.WEST), is(EntityFace.WEST));
        assertThat(Turn.HALF_X.entityFace(BlockFace.EAST), is(EntityFace.EAST));
    }

    @Test
    @DisplayName("the face map is the face-level shadow of the point turn, on every turn and face")
    void faceMapShadowsThePointTurn() {
        for (Turn turn : Turn.CACHED_VALUES)
            for (BlockFace face : BlockFace.CACHED_VALUES) {
                assertThat(turn + " on " + face,
                    settled(turn.apply(face).normal()), equalTo(settled(turn.apply(face.normal()))));
                assertThat(turn + " on " + face + " crossing frames",
                    settled(turn.entityFace(face).normal()), equalTo(settled(turn.apply(face.normal()))));
            }
    }

    @Test
    @DisplayName("MIRROR_X swaps the two sides and leaves the other four faces alone")
    void sagittalMirrorSwapsTheSidesOnly() {
        assertThat(Turn.MIRROR_X.apply(BlockFace.WEST), is(BlockFace.EAST));
        assertThat(Turn.MIRROR_X.apply(BlockFace.EAST), is(BlockFace.WEST));
        assertThat(Turn.MIRROR_X.apply(EntityFace.WEST), is(EntityFace.EAST));
        assertThat(Turn.MIRROR_X.apply(EntityFace.EAST), is(EntityFace.WEST));

        for (BlockFace face : new BlockFace[]{ BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH })
            assertThat(face + " keeps its slot under a sagittal mirror", Turn.MIRROR_X.apply(face), is(face));
    }

    @Test
    @DisplayName("every turn is axis-preserving - a face pairs with itself or its own opposite")
    void everyTurnIsAxisPreserving() {
        for (Turn turn : Turn.CACHED_VALUES)
            for (BlockFace face : BlockFace.CACHED_VALUES) {
                BlockFace turned = turn.apply(face);
                assertThat(turn + " keeps " + face + " on its own axis",
                    turned == face || turned.normal().equals(
                        new Vector3f(-face.normal().x() + 0f, -face.normal().y() + 0f, -face.normal().z() + 0f)),
                    is(true));
            }
    }

    @Test
    @DisplayName("the eight turns are closed under composition and each is its own inverse")
    void compositionIsClosedAndSelfInverse() {
        Set<Turn> all = EnumSet.allOf(Turn.class);
        assertThat("the group has eight members", all.size(), is(8));

        for (Turn a : Turn.CACHED_VALUES) {
            assertThat(a + " composed with itself is the identity", a.then(a), is(Turn.NONE));
            assertThat("NONE is the identity for " + a, a.then(Turn.NONE), is(a));

            for (Turn b : Turn.CACHED_VALUES) {
                assertThat(a + " then " + b + " stays in the group", all.contains(a.then(b)), is(true));
                assertThat(a + " then " + b + " commutes", a.then(b), is(b.then(a)));
                // Composition on the group must agree with composition on a point.
                Vector3f point = new Vector3f(2f, 3f, 5f);
                assertThat(a + " then " + b + " agrees on a point",
                    a.then(b).apply(point), equalTo(b.apply(a.apply(point))));
            }
        }
    }

    @Test
    @DisplayName("a mirrored shell cube is one composition, not a relation of its own")
    void mirroredShellCubeComposes() {
        assertThat(Turn.HALF_X.then(Turn.MIRROR_X), is(Turn.INVERT));
        assertThat(Turn.HALF_X.then(Turn.MIRROR_X).entityFace(BlockFace.WEST), is(EntityFace.EAST));
        assertThat(Turn.HALF_X.then(Turn.MIRROR_X).entityFace(BlockFace.DOWN), is(EntityFace.UP));
    }

    @Test
    @DisplayName("reflects agrees with the determinant, and four of the group reflect")
    void reflectsAgreesWithDeterminant() {
        int reflecting = 0;
        for (Turn turn : Turn.CACHED_VALUES) {
            Vector3f x = turn.apply(new Vector3f(1f, 0f, 0f));
            Vector3f y = turn.apply(new Vector3f(0f, 1f, 0f));
            Vector3f z = turn.apply(new Vector3f(0f, 0f, 1f));
            float determinant = x.x() * y.y() * z.z();
            assertThat(turn + " reflects when its determinant is negative",
                turn.reflects(), is(determinant < 0f));
            if (turn.reflects()) reflecting++;
        }
        assertThat("half the group reflects", reflecting, is(4));
    }

    @Test
    @DisplayName("the point turn negates the axes it names and nothing else")
    void pointTurnNegatesItsOwnAxes() {
        Vector3f point = new Vector3f(2f, 3f, 5f);
        assertThat(Turn.NONE.apply(point), equalTo(point));
        assertThat(Turn.HALF_X.apply(point), equalTo(new Vector3f(2f, -3f, -5f)));
        assertThat(Turn.MIRROR_X.apply(point), equalTo(new Vector3f(-2f, 3f, 5f)));
        assertThat(Turn.MIRROR_Y.apply(point), equalTo(new Vector3f(2f, -3f, 5f)));
        assertThat(Turn.INVERT.apply(point), equalTo(new Vector3f(-2f, -3f, -5f)));
    }

    /** Settles a negated zero onto the positive one, so the comparison is numeric not by identity. */
    private static Vector3f settled(Vector3f vector) {
        return new Vector3f(vector.x() + 0f, vector.y() + 0f, vector.z() + 0f);
    }

}
