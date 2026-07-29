package lib.minecraft.renderer.engine.kit;

import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.face.EntityFace;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Ties together the half turn about X that three separate places in the renderer each spell for
 * themselves.
 * <p>
 * A shell's unwrap is authored in vanilla's Y-down model frame while the boxes built from it are
 * upright, so a site that builds geometry with one enum's corner order and textures it with the
 * other's unwrap has to name the face-level shadow of that turn. Three do, and no symbol is shared
 * between them: the armour kit holds it as a six-entry map, the shield kit as a {@code switch} in
 * another package, and the armour kit's own point turn as {@code (x, -y, -z)}. Nothing relates the
 * three, so either could be edited alone and every armoured entity would be mis-textured with no
 * compile error and no other test failing.
 * <p>
 * The three are asserted to agree face for face, and the map is asserted to be the bijection a turn
 * has to be. The point turn is checked to be its own inverse, which is what makes it a half turn
 * rather than a quarter one.
 * <p>
 * The three members are private and are reached by reflection, so <b>renaming any of them fails this
 * test at runtime rather than at compile time</b>. The names are {@code MESH_FACES},
 * {@code turnAboutX} and {@code entityFaceFor}.
 */
@DisplayName("The armour and shield frame turns agree on all six faces")
class FrameTurnTest {

    @Test
    @DisplayName("the armour map and the shield switch are the same total function on six values")
    void meshFacesMatchesShieldSwitch() throws ReflectiveOperationException {
        Map<BlockFace, EntityFace> meshFaces = meshFaces();

        for (BlockFace face : BlockFace.CACHED_VALUES)
            assertThat("frame turn of " + face, meshFaces.get(face), is(entityFaceFor(face)));
    }

    @Test
    @DisplayName("the face map is the face-level shadow of the point turn")
    void meshFacesMatchesPointTurn() throws ReflectiveOperationException {
        Map<BlockFace, EntityFace> meshFaces = meshFaces();

        for (BlockFace face : BlockFace.CACHED_VALUES)
            assertThat("turned normal of " + face,
                withoutNegativeZero(turnAboutX(face.normal())),
                equalTo(withoutNegativeZero(meshFaces.get(face).normal())));
    }

    @Test
    @DisplayName("the face map is a bijection - every entity face is reached exactly once")
    void meshFacesIsABijection() throws ReflectiveOperationException {
        Map<BlockFace, EntityFace> meshFaces = meshFaces();

        assertThat("every block face is mapped", meshFaces.size(), is(BlockFace.CACHED_VALUES.length));
        assertThat("every entity face is reached", EnumSet.copyOf(meshFaces.values()),
            is(EnumSet.allOf(EntityFace.class)));
    }

    @Test
    @DisplayName("the turn is axis-preserving - each face pairs with itself or its own opposite")
    void meshFacesIsAxisPreserving() throws ReflectiveOperationException {
        Map<BlockFace, EntityFace> meshFaces = meshFaces();

        for (BlockFace face : BlockFace.CACHED_VALUES) {
            Vector3f from = face.normal();
            Vector3f to = meshFaces.get(face).normal();
            assertThat("axis of " + face + " is preserved",
                Math.abs(from.x()) == Math.abs(to.x())
                    && Math.abs(from.y()) == Math.abs(to.y())
                    && Math.abs(from.z()) == Math.abs(to.z()), is(true));
        }
    }

    @Test
    @DisplayName("the point turn negates Y and Z and is its own inverse")
    void pointTurnIsAHalfTurnAboutX() throws ReflectiveOperationException {
        Vector3f point = new Vector3f(2f, 3f, 5f);
        Vector3f turned = turnAboutX(point);

        assertThat("turned point", turned, equalTo(new Vector3f(2f, -3f, -5f)));
        assertThat("turning twice is the identity", turnAboutX(turned), equalTo(point));
    }

    /**
     * Settles a negative zero onto the positive one so the comparison is numeric rather than by
     * record identity. Negating a zero component leaves {@code -0.0f}, which equals {@code 0.0f}
     * under float comparison but is a different {@code Float}.
     */
    private static Vector3f withoutNegativeZero(Vector3f vector) {
        return new Vector3f(vector.x() + 0f, vector.y() + 0f, vector.z() + 0f);
    }

    // ------------------------------------------------------------------------------------------
    // Reflective access to the three private members this test exists to relate.
    // ------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<BlockFace, EntityFace> meshFaces() throws ReflectiveOperationException {
        Field field = ArmorKit.class.getDeclaredField("MESH_FACES");
        field.setAccessible(true);
        return (Map<BlockFace, EntityFace>) field.get(null);
    }

    private static EntityFace entityFaceFor(BlockFace face) throws ReflectiveOperationException {
        Method method = ShieldKit.class.getDeclaredMethod("entityFaceFor", BlockFace.class);
        method.setAccessible(true);
        return (EntityFace) method.invoke(null, face);
    }

    private static Vector3f turnAboutX(Vector3f point) throws ReflectiveOperationException {
        Method method = ArmorKit.class.getDeclaredMethod("turnAboutX", Vector3f.class);
        method.setAccessible(true);
        return (Vector3f) method.invoke(null, point);
    }

}
