package lib.minecraft.renderer.face;

import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Pins both corner phases - the one thing a merge of the two face vocabularies would most easily
 * destroy, and the one thing nothing else in the suite covers.
 * <p>
 * A face's corner phase is which of its four corners the quad starts at. That single choice fixes two
 * downstream facts at once: the diagonal the {@code (0,1,2)+(0,2,3)} fan splits the quad on, and which
 * UV slot pairs with which vertex. The two phases genuinely disagree - every one of the six index
 * arrays is a cyclic rotation of its counterpart, so both name the same four points and hand them back
 * in a different order, and the two therefore split on <b>opposite</b> diagonals. Re-rotating either
 * array onto the other's phase preserves the position-to-UV pairing only if the UV order is
 * counter-rotated with it, which moves the fan diagonal and changes which triangle a pixel lands in on
 * every non-planar face.
 * <p>
 * Four things are checked, and the last is the one that catches a half-applied rotation:
 * <ul>
 * <li><b>The index arrays verbatim</b>, per constant per phase - the data itself.</li>
 * <li><b>Winding</b> - {@code cross(v1 - v0, v2 - v0)} equals the stored normal on all twelve pairs,
 *     so both phases wind counter-clockwise seen from the outward normal. A cyclic rotation traverses
 *     the same cycle and cannot reverse this; a reversal would.</li>
 * <li><b>The fan diagonal</b>, as the {@code (index 0, index 2)} box-corner pair - a genuine diagonal
 *     on each phase, and a different one on each.</li>
 * <li><b>The position-to-UV pairing</b> - walking the real composition (corner order, then the
 *     unwrap, then {@link Vector4f#toUvCorners}, then the phase's own permutation), {@code u} must
 *     depend only on the face's width-axis coordinate and {@code v} only on its height-axis
 *     coordinate, with the per-face sign pair pinned. Rotating one array without the other turns that
 *     axis-aligned map into a transpose, which fails here rather than silently re-texturing every
 *     cube.</li>
 * </ul>
 */
@DisplayName("Face corner phase, winding, fan diagonal and UV pairing")
class FacePhaseTest {

    /**
     * A deliberately asymmetric element box - three distinct extents, so no two axes can stand in for
     * one another and a mis-permuted rectangle cannot coincide with the right one.
     */
    private static final Box BOX = new Box(1f, 2f, 4f, 2f, 4f, 7f);

    /**
     * The unit box, on which {@code cross(v1 - v0, v2 - v0)} is the face normal exactly rather than a
     * positive multiple of it.
     */
    private static final Box UNIT = new Box(0f, 0f, 0f, 1f, 1f, 1f);

    /**
     * A cube origin and size for the entity atlas unwrap, sized to match {@link #BOX}'s extents so the
     * strip and the geometry describe one cube.
     */
    private static final Vector2f CUBE_UV = new Vector2f(3f, 5f);
    private static final Vector3f CUBE_SIZE = new Vector3f(1f, 2f, 3f);

    /** Texture dimensions the entity strip is normalized against. */
    private static final float SHEET = 64f;

    @Test
    @DisplayName("the bakery phase carries its vertex indices verbatim")
    void bakeryVertexIndices() {
        assertThat(CornerPhase.BAKERY.vertexIndices(Face.DOWN), equalTo(new int[]{ 4, 0, 1, 5 }));
        assertThat(CornerPhase.BAKERY.vertexIndices(Face.UP), equalTo(new int[]{ 3, 7, 6, 2 }));
        assertThat(CornerPhase.BAKERY.vertexIndices(Face.NORTH), equalTo(new int[]{ 2, 1, 0, 3 }));
        assertThat(CornerPhase.BAKERY.vertexIndices(Face.SOUTH), equalTo(new int[]{ 7, 4, 5, 6 }));
        assertThat(CornerPhase.BAKERY.vertexIndices(Face.WEST), equalTo(new int[]{ 3, 0, 4, 7 }));
        assertThat(CornerPhase.BAKERY.vertexIndices(Face.EAST), equalTo(new int[]{ 6, 5, 1, 2 }));
    }

    @Test
    @DisplayName("the polygon phase carries its vertex indices verbatim")
    void polygonVertexIndices() {
        assertThat(CornerPhase.POLYGON.vertexIndices(Face.DOWN), equalTo(new int[]{ 5, 4, 0, 1 }));
        assertThat(CornerPhase.POLYGON.vertexIndices(Face.UP), equalTo(new int[]{ 2, 3, 7, 6 }));
        assertThat(CornerPhase.POLYGON.vertexIndices(Face.NORTH), equalTo(new int[]{ 1, 0, 3, 2 }));
        assertThat(CornerPhase.POLYGON.vertexIndices(Face.SOUTH), equalTo(new int[]{ 4, 5, 6, 7 }));
        assertThat(CornerPhase.POLYGON.vertexIndices(Face.WEST), equalTo(new int[]{ 0, 4, 7, 3 }));
        assertThat(CornerPhase.POLYGON.vertexIndices(Face.EAST), equalTo(new int[]{ 5, 1, 2, 6 }));
    }

    @Test
    @DisplayName("the polygon phase places TR first, and UP alone inverts")
    void polygonUvSlots() {
        assertThat(CornerPhase.POLYGON.uvSlots(Face.DOWN), equalTo(new int[]{ 3, 0, 1, 2 }));
        assertThat(CornerPhase.POLYGON.uvSlots(Face.UP), equalTo(new int[]{ 2, 1, 0, 3 }));
        assertThat(CornerPhase.POLYGON.uvSlots(Face.NORTH), equalTo(new int[]{ 3, 0, 1, 2 }));
        assertThat(CornerPhase.POLYGON.uvSlots(Face.SOUTH), equalTo(new int[]{ 3, 0, 1, 2 }));
        assertThat(CornerPhase.POLYGON.uvSlots(Face.WEST), equalTo(new int[]{ 3, 0, 1, 2 }));
        assertThat(CornerPhase.POLYGON.uvSlots(Face.EAST), equalTo(new int[]{ 3, 0, 1, 2 }));
    }

    @Test
    @DisplayName("the bakery phase's UV pairing is the identity on every face")
    void bakeryUvSlotsAreTheIdentity() {
        for (Face face : Face.CACHED_VALUES)
            assertThat("bakery slots of " + face,
                CornerPhase.BAKERY.uvSlots(face), equalTo(new int[]{ 0, 1, 2, 3 }));
    }

    @Test
    @DisplayName("every index set names the same four box corners on both phases")
    void indexSetsCoincide() {
        for (Face face : Face.CACHED_VALUES) {
            int[] bakery = CornerPhase.BAKERY.vertexIndices(face).clone();
            int[] polygon = CornerPhase.POLYGON.vertexIndices(face).clone();
            Arrays.sort(bakery);
            Arrays.sort(polygon);
            assertThat("corner set of " + face, polygon, equalTo(bakery));
        }
    }

    @Test
    @DisplayName("the bakery phase winds CCW - the emit-order cross agrees with the stored normal")
    void bakeryWinding() {
        for (Face face : Face.CACHED_VALUES)
            assertSameVector("winding of BAKERY." + face,
                crossOf(CornerPhase.BAKERY.corners(face, UNIT)), face.normal());
    }

    @Test
    @DisplayName("the polygon phase winds CCW - the emit-order cross agrees with the stored normal")
    void polygonWinding() {
        for (Face face : Face.CACHED_VALUES)
            assertSameVector("winding of POLYGON." + face,
                crossOf(CornerPhase.POLYGON.corners(face, UNIT)), face.normal());
    }

    @Test
    @DisplayName("the fan diagonal is the corner-0 to corner-2 pair, and the two phases split opposite ways")
    void fanDiagonal() {
        int[][] bakery = { { 4, 1 }, { 3, 6 }, { 2, 0 }, { 7, 5 }, { 3, 4 }, { 6, 1 } };
        int[][] polygon = { { 5, 0 }, { 2, 7 }, { 1, 3 }, { 4, 6 }, { 0, 7 }, { 5, 2 } };

        for (int i = 0; i < Face.CACHED_VALUES.length; i++) {
            Face face = Face.CACHED_VALUES[i];
            assertThat("bakery diagonal of " + face,
                diagonalOf(CornerPhase.BAKERY.vertexIndices(face)), equalTo(bakery[i]));
            assertThat("polygon diagonal of " + face,
                diagonalOf(CornerPhase.POLYGON.vertexIndices(face)), equalTo(polygon[i]));
            // A quad has two diagonals; each phase must take one, and they must take different ones.
            assertThat("the two phases split " + face + " on opposite diagonals",
                sorted(polygon[i]), is(not(equalTo(sorted(bakery[i])))));
        }
    }

    @Test
    @DisplayName("the fan diagonal really is a diagonal - it moves on both of the face's own axes")
    void diagonalCrossesBothAxes() {
        for (Face face : Face.CACHED_VALUES) {
            assertDiagonal("BAKERY." + face, CornerPhase.BAKERY.corners(face, BOX),
                face.widthAxis(), face.heightAxis());
            assertDiagonal("POLYGON." + face, CornerPhase.POLYGON.corners(face, BOX),
                face.widthAxis(), face.heightAxis());
        }
    }

    @Test
    @DisplayName("the bakery phase pairs each vertex with the UV corner on its own axes, signs pinned")
    void bakeryUvPairing() {
        int[][] signs = { { 1, -1 }, { 1, 1 }, { -1, -1 }, { 1, -1 }, { 1, -1 }, { -1, -1 } };

        for (int i = 0; i < Face.CACHED_VALUES.length; i++) {
            Face face = Face.CACHED_VALUES[i];
            Vector4f rect = new Unwrap.Element(BOX).rect(face);
            Vector2f[] uv = CornerPhase.BAKERY.permuteUv(face, rect.toUvCorners(
                BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK,
                BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK, 0, false));
            assertPairing("BAKERY." + face, CornerPhase.BAKERY.corners(face, BOX), uv,
                face.widthAxis(), face.heightAxis(), signs[i]);
        }
    }

    @Test
    @DisplayName("the polygon phase pairs each vertex with the UV corner on its own axes, signs pinned")
    void polygonUvPairing() {
        int[][] signs = { { 1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 }, { -1, 1 }, { 1, 1 } };

        for (int i = 0; i < Face.CACHED_VALUES.length; i++) {
            Face face = Face.CACHED_VALUES[i];
            Vector4f rect = new Unwrap.Atlas(CUBE_UV, CUBE_SIZE, false).rect(face);
            Vector2f[] uv = CornerPhase.POLYGON.permuteUv(face, rect.toUvCorners(SHEET, SHEET, 0, false));
            assertPairing("POLYGON." + face, CornerPhase.POLYGON.corners(face, BOX), uv,
                face.widthAxis(), face.heightAxis(), signs[i]);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------------------------------

    /** The geometric normal of a quad in emit order - {@code (v1 - v0) x (v2 - v0)}. */
    private static Vector3f crossOf(Vector3f[] corners) {
        return corners[1].subtract(corners[0]).cross(corners[2].subtract(corners[0]));
    }

    /**
     * Asserts two vectors agree componentwise under float comparison rather than record equality. A
     * cross product produces {@code -0.0f} wherever the operands cancel, which is numerically the
     * stored {@code 0.0f} but a different {@code Float}.
     */
    private static void assertSameVector(String label, Vector3f actual, Vector3f expected) {
        assertThat(label, withoutNegativeZero(actual), equalTo(withoutNegativeZero(expected)));
    }

    /** Adding {@code +0.0f} settles a negative zero onto the positive one and moves nothing else. */
    private static Vector3f withoutNegativeZero(Vector3f vector) {
        return new Vector3f(vector.x() + 0f, vector.y() + 0f, vector.z() + 0f);
    }

    /** The box-corner pair the {@code (0,1,2)+(0,2,3)} fan shares between its two triangles. */
    private static int[] diagonalOf(int[] vertexIndices) {
        return new int[]{ vertexIndices[0], vertexIndices[2] };
    }

    private static int[] sorted(int[] pair) {
        int[] copy = pair.clone();
        Arrays.sort(copy);
        return copy;
    }

    /** The sign of a float as an int, so a zero difference cannot arrive carrying a sign bit. */
    private static int sign(float value) {
        if (value > 0f) return 1;
        return value < 0f ? -1 : 0;
    }

    /** Asserts that corners 0 and 2 differ on both of the face's own in-plane axes. */
    private static void assertDiagonal(String label, Vector3f[] corners, int widthAxis, int heightAxis) {
        assertThat(label + " diagonal moves on its width axis",
            corners[0].get(widthAxis) != corners[2].get(widthAxis), is(true));
        assertThat(label + " diagonal moves on its height axis",
            corners[0].get(heightAxis) != corners[2].get(heightAxis), is(true));
    }

    /**
     * Asserts that the position-to-UV correspondence is an axis-aligned affine map with the given
     * signs - {@code u} a function of the width-axis coordinate alone, {@code v} of the height-axis
     * coordinate alone, each monotone in the pinned direction.
     * <p>
     * This is what a half-applied rotation breaks. Shifting one of the two arrays by an odd number of
     * places makes {@code u} depend on the height axis, which no sign pair can satisfy; shifting both
     * by two flips both signs, which the pinned pair rejects.
     */
    private static void assertPairing(
        String label, Vector3f[] corners, Vector2f[] uv, int widthAxis, int heightAxis, int[] signs) {
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                int da = sign(corners[b].get(widthAxis) - corners[a].get(widthAxis));
                int db = sign(corners[b].get(heightAxis) - corners[a].get(heightAxis));
                int du = sign(uv[b].x() - uv[a].x());
                int dv = sign(uv[b].y() - uv[a].y());
                assertThat(label + ": u tracks the width axis only, corners " + a + " to " + b,
                    du, equalTo(da * signs[0]));
                assertThat(label + ": v tracks the height axis only, corners " + a + " to " + b,
                    dv, equalTo(db * signs[1]));
            }
        }
    }

}
