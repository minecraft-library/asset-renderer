package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.bake.mesh.EntityGeometryKit;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

/**
 * Normal coverage for a carried block, driven through {@link EntityRenderer}'s own overlay build on a
 * fixture bone the block hangs from: a {@code head} turned on all three axes under a {@code body} posed
 * at a pose scale.
 *
 * <p>Below a non-uniform scale the block's normals must turn by the placement's inverse-transpose, as
 * vanilla's normal matrix does. That direction needs no derivation here: a placed triangle's edges cross
 * to it whatever the placement, so each stored normal is read against its own triangle's cross product,
 * which the placement's own turn misses on every face the stretch leans. Below a uniform scale or none,
 * every normal must be the placement's own turn of the block's, bit for bit.
 */
@DisplayName("Entity carried-block normals")
@ExtendWith(ClientAssetsExtension.class)
class EntityRendererCarriedBlockNormalTest {

    /** A full-cube block, so every face is a cardinal plane before it is placed. */
    private static final String BLOCK = "minecraft:carved_pumpkin";

    /** The fixture bone the block hangs from. */
    private static final String BONE = "head";

    /** A turn on all three axes, which leaves no face of the block on an axis of the stretch. */
    private static final EulerRotation TURN = new EulerRotation(30f, 20f, 10f);

    /** An entity fit with an off-origin centre and a non-unit scale. */
    private static final Matrix4f FIT = EntityGeometryKit.buildEntityFitMatrix(new Vector3f(1f, 2f, 3f), 0.05f);

    /**
     * Pins that a block hung below a non-uniform pose scale turns its normals by the placement's
     * inverse-transpose: every stored normal lies along its own triangle's cross product. The fixture
     * is checked to discriminate - the placement's own turn of some face must part from it.
     */
    @Test
    @DisplayName("a block below a non-uniform scale turns every normal by the placement's inverse-transpose")
    void aBlockBelowANonUniformScaleTurnsByTheInverseTranspose() {
        EntityMesh carrier = carrier(new Vector3f(1f, 1f, 2f));
        ConcurrentList<VisibleTriangle> placed = placed(carrier);
        List<Vector3f> own = blockNormals();
        Matrix4f placement = placement(carrier);
        assertThat("every face of the block is placed", placed.size(), equalTo(12));

        float worstOwnTurn = 1f;
        for (int i = 0; i < placed.size(); i++) {
            VisibleTriangle tri = placed.get(i);
            Vector3f across = crossNormal(tri);
            assertThat("triangle " + i + " stores the normal its edges cross to",
                Math.abs(tri.normal().dot(across)), greaterThan(1f - 1e-5f));
            float ownTurn = Math.abs(own.get(i).transformNormal(placement).normalize().dot(across));
            worstOwnTurn = Math.min(worstOwnTurn, ownTurn);
        }
        assertThat("the placement's own turn must miss some face on this fixture", worstOwnTurn, lessThan(0.99f));
    }

    /**
     * Pins that a block hung below a uniform pose scale keeps the placement's own turn of every
     * normal, bit for bit: a uniform scale never untrusts vanilla's normal matrix.
     */
    @Test
    @DisplayName("a block below a uniform scale keeps the placement's own turn, bit for bit")
    void aBlockBelowAUniformScaleKeepsThePlacementsTurn() {
        assertPlacementTurnBitForBit(carrier(new Vector3f(1.5f, 1.5f, 1.5f)));
    }

    /**
     * Pins that a block hung where no pose scale reaches keeps the placement's own turn of every
     * normal, bit for bit - the turn every carried block a still render draws takes.
     */
    @Test
    @DisplayName("a block below no pose scale keeps the placement's own turn, bit for bit")
    void aBlockBelowNoScaleKeepsThePlacementsTurn() {
        assertPlacementTurnBitForBit(carrier(new Vector3f(1f, 1f, 1f)));
    }

    // --- fixtures ---

    /**
     * A cube-less {@code body} posed at {@code scale}, holding a cube-less {@link #BONE} turned
     * {@link #TURN} - all the overlay build reads of the mesh is the chain down to the bone.
     */
    private static EntityMesh carrier(Vector3f scale) {
        EntityMesh.Bone body = new EntityMesh.Bone(
            new Vector3f(0f, 4f, 0f), EulerRotation.NONE, EulerRotation.NONE, 1f, Concurrent.newList(), null)
            .withPoseScale(scale);
        EntityMesh.Bone head = new EntityMesh.Bone(
            new Vector3f(0f, -2f, -3f), TURN, EulerRotation.NONE, 1f, Concurrent.newList(), "body");
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", body);
        bones.put(BONE, head);
        return new EntityMesh(TextureSize.DEFAULT, bones, false);
    }

    /** The block's triangles as the overlay build places them on {@link #BONE}, with no placement of its own. */
    private static ConcurrentList<VisibleTriangle> placed(EntityMesh carrier) {
        Entity.BlockOverlayLayer overlay = new Entity.BlockOverlayLayer(BLOCK, BONE, Matrix4f.IDENTITY, false);
        return EntityRenderer.buildBlockOverlayTriangles(ClientAssetsExtension.context(), overlay, carrier, FIT, 0);
    }

    /**
     * The block's own face normals, in the order the overlay build emits them: the block placed on no
     * bone, under no fit and no placement of its own, which leaves only the build's {@code scale(16)}
     * and corner translate - a turn that hands an axis-aligned normal back unchanged.
     */
    private static List<Vector3f> blockNormals() {
        Entity.BlockOverlayLayer unplaced = new Entity.BlockOverlayLayer(BLOCK, null, Matrix4f.IDENTITY, false);
        return EntityRenderer.buildBlockOverlayTriangles(
                ClientAssetsExtension.context(), unplaced, carrier(new Vector3f(1f, 1f, 1f)), Matrix4f.IDENTITY, 0)
            .stream().map(VisibleTriangle::normal).toList();
    }

    /** The placement the overlay build composes for {@link #BONE}: fit, anchor, {@code scale(16)}, corner translate. */
    private static Matrix4f placement(EntityMesh carrier) {
        return FIT.multiply(EntityGeometryKit.resolveBoneAnchorMatrix(carrier, BONE))
            .scale(16f, 16f, 16f)
            .multiply(Matrix4f.IDENTITY.translate(0.5f, 0.5f, 0.5f));
    }

    /** The unit normal a triangle's placed edges cross to. */
    private static Vector3f crossNormal(VisibleTriangle tri) {
        return tri.position1().subtract(tri.position0())
            .cross(tri.position2().subtract(tri.position0()))
            .normalize();
    }

    /** Asserts every placed normal is the placement's own turn of the block's, normalised, bit for bit. */
    private static void assertPlacementTurnBitForBit(EntityMesh carrier) {
        ConcurrentList<VisibleTriangle> placed = placed(carrier);
        List<Vector3f> own = blockNormals();
        Matrix4f placement = placement(carrier);
        assertThat("every face of the block is placed", placed.size(), equalTo(12));

        for (int i = 0; i < placed.size(); i++) {
            Vector3f actual = placed.get(i).normal();
            Vector3f expected = own.get(i).transformNormal(placement).normalize();
            assertThat("triangle " + i + " x", Float.floatToIntBits(actual.x()), equalTo(Float.floatToIntBits(expected.x())));
            assertThat("triangle " + i + " y", Float.floatToIntBits(actual.y()), equalTo(Float.floatToIntBits(expected.y())));
            assertThat("triangle " + i + " z", Float.floatToIntBits(actual.z()), equalTo(Float.floatToIntBits(expected.z())));
        }
    }

}
