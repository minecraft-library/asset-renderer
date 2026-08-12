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
 * {@link Turn} against the frame relations the renderer performs, and against the point turn each of
 * them is the face-level shadow of.
 * <p>
 * A shell's unwrap is authored in vanilla's Y-down model frame while the boxes built from it are
 * upright, so a site that builds geometry with one enum's corner order and textures it with the
 * other's unwrap has to name that turn. The two halves of the enum cannot drift apart: on all eight
 * turns and all six faces, turning a face and reading its normal agrees with turning that face's
 * normal directly, so the face map is pinned by the arithmetic rather than by a table beside it.
 * <p>
 * The group properties are checked as well, because they are what let one symbol serve every
 * combination: closure under {@link Turn#then}, so a mirrored shell cube can read
 * {@code HALF_X.then(MIRROR_X)} rather than needing a relation minted for the combination; the
 * identity and inverse laws; and that {@link Turn#reflects} agrees with the determinant.
 * <b>Reflections are not excluded</b> - four of the relations in use are reflections, so a
 * rotation-only set would express none of them.
 * <p>
 * An opposite is one bit of the ordinal, so {@link Face} declaring its constants in opposing pairs is
 * load-bearing and is asserted here rather than assumed.
 */
@DisplayName("Turn - the frame relations, their group laws, and the half turn about X")
class FrameTurnTest {

    @Test
    @DisplayName("the six faces are declared in opposing pairs, which is what makes ordinal ^ 1 the opposite")
    void facesAreDeclaredInOpposingPairs() {
        assertThat("six cardinal directions", Face.CACHED_VALUES.length, is(6));

        // Pairs share an axis and differ only in sign, which is what makes ordinal ^ 1 the opposite.
        for (int i = 0; i < Face.CACHED_VALUES.length; i += 2) {
            Face low = Face.CACHED_VALUES[i];
            Face high = Face.CACHED_VALUES[i + 1];
            assertThat(low + " and " + high + " share an axis", high.axis(), is(low.axis()));
            assertThat(low + " and " + high + " are opposites", high.normal(),
                equalTo(new Vector3f(-low.normal().x() + 0f, -low.normal().y() + 0f, -low.normal().z() + 0f)));
            assertThat(low + "'s opposite", low.opposite(), is(high));
            assertThat(high + "'s opposite", high.opposite(), is(low));
        }
    }

    @Test
    @DisplayName("HALF_X maps the model frame onto the upright frame")
    void halfTurnCrossesToTheModelFrame() {
        assertThat(Turn.HALF_X.apply(Face.DOWN), is(Face.UP));
        assertThat(Turn.HALF_X.apply(Face.UP), is(Face.DOWN));
        assertThat(Turn.HALF_X.apply(Face.NORTH), is(Face.SOUTH));
        assertThat(Turn.HALF_X.apply(Face.SOUTH), is(Face.NORTH));
        assertThat(Turn.HALF_X.apply(Face.WEST), is(Face.WEST));
        assertThat(Turn.HALF_X.apply(Face.EAST), is(Face.EAST));
    }

    @Test
    @DisplayName("the face map is the face-level shadow of the point turn, on every turn and face")
    void faceMapShadowsThePointTurn() {
        for (Turn turn : Turn.CACHED_VALUES)
            for (Face face : Face.CACHED_VALUES) {
                assertThat(turn + " on " + face,
                    settled(turn.apply(face).normal()), equalTo(settled(turn.apply(face.normal()))));
            }
    }

    @Test
    @DisplayName("MIRROR_X swaps the two sides and leaves the other four faces alone")
    void sagittalMirrorSwapsTheSidesOnly() {
        assertThat(Turn.MIRROR_X.apply(Face.WEST), is(Face.EAST));
        assertThat(Turn.MIRROR_X.apply(Face.EAST), is(Face.WEST));

        for (Face face : new Face[]{ Face.DOWN, Face.UP, Face.NORTH, Face.SOUTH })
            assertThat(face + " keeps its slot under a sagittal mirror", Turn.MIRROR_X.apply(face), is(face));
    }

    @Test
    @DisplayName("every turn is axis-preserving - a face pairs with itself or its own opposite")
    void everyTurnIsAxisPreserving() {
        for (Turn turn : Turn.CACHED_VALUES)
            for (Face face : Face.CACHED_VALUES) {
                Face turned = turn.apply(face);
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
        assertThat(Turn.HALF_X.then(Turn.MIRROR_X).apply(Face.WEST), is(Face.EAST));
        assertThat(Turn.HALF_X.then(Turn.MIRROR_X).apply(Face.DOWN), is(Face.UP));
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
