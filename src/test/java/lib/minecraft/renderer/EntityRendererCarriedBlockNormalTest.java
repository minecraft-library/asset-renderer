package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.bake.mesh.BoneKit;
import lib.minecraft.renderer.bake.mesh.EntityGeometryKit;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

/**
 * Placement and normal coverage for a carried block, driven through {@link EntityRenderer}'s own
 * overlay build on a fixture {@code head} the block hangs from, turned on all three axes and posed at a
 * pose scale, with the block turned off the scale's axes by a placement of its own.
 *
 * <p>Below a non-uniform scale the block's normals must turn by the placement's inverse-transpose, as
 * vanilla's normal matrix does. That direction needs no derivation here: a placed triangle's edges cross
 * to it whatever the placement, so each stored normal is read against its own triangle's cross product,
 * which the placement's own turn misses on every face the stretch leans. Below a uniform scale or none,
 * every normal must be the placement's own turn of the block's, bit for bit.
 *
 * <p>Where the block stands is the seated container, then the attached part's own step: a part hung
 * under a stretched ancestor draws its block exactly where the same part hung from the root does, and a
 * block attached to no part stands on the seat.
 */
@DisplayName("Entity carried-block placement and normals")
@ExtendWith(ClientAssetsExtension.class)
class EntityRendererCarriedBlockNormalTest {

    /** A full-cube block, so every face is a cardinal plane before it is placed. */
    private static final String BLOCK = "minecraft:carved_pumpkin";

    /** The fixture bone the block hangs from. */
    private static final String BONE = "head";

    /** A turn on all three axes, which the head stands at. */
    private static final EulerRotation TURN = new EulerRotation(30f, 20f, 10f);

    /** The block's own placement, a turn that leaves no face of it on an axis of the head's scale. */
    private static final Matrix4f BLOCK_TURN = Matrix4f.IDENTITY
        .rotateX((float) Math.toRadians(25d))
        .rotateY((float) Math.toRadians(40d));

    /** The pose scale stretching z alone. */
    private static final Vector3f STRETCH = new Vector3f(1f, 1f, 2f);

    /** No pose scale at all. */
    private static final Vector3f UNSCALED = new Vector3f(1f, 1f, 1f);

    /** An entity fit with an off-origin centre and a non-unit scale. */
    private static final Matrix4f FIT = EntityGeometryKit.buildEntityFitMatrix(new Vector3f(1f, 2f, 3f), 0.05f);

    /** The appearance predicate an adult index form resolves a style row through. */
    private static final EntityOptions ADULT = EntityOptions.of("minecraft:subject");

    /**
     * Pins that a block on a part posed at a non-uniform pose scale turns its normals by the placement's
     * inverse-transpose: every stored normal lies along its own triangle's cross product. The fixture
     * is checked to discriminate - the placement's own turn of some face must part from it.
     */
    @Test
    @DisplayName("a block on a non-uniformly scaled part turns every normal by the placement's inverse-transpose")
    void aBlockBelowANonUniformScaleTurnsByTheInverseTranspose() {
        EntityMesh carrier = carrier(STRETCH, false);
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
     * Pins that a block on a part posed at a uniform pose scale keeps the placement's own turn of every
     * normal, bit for bit: a uniform scale never untrusts vanilla's normal matrix.
     */
    @Test
    @DisplayName("a block on a uniformly scaled part keeps the placement's own turn, bit for bit")
    void aBlockBelowAUniformScaleKeepsThePlacementsTurn() {
        assertPlacementTurnBitForBit(carrier(new Vector3f(1.5f, 1.5f, 1.5f), false));
    }

    /**
     * Pins that a block where no pose scale reaches keeps the placement's own turn of every normal, bit
     * for bit - the turn every carried block a still render draws takes.
     */
    @Test
    @DisplayName("a block below no pose scale keeps the placement's own turn, bit for bit")
    void aBlockBelowNoScaleKeepsThePlacementsTurn() {
        assertPlacementTurnBitForBit(carrier(UNSCALED, false));
    }

    /**
     * Pins that the block takes the attached part's own step and none of its ancestors': a head hung
     * under a {@code body} offset, turned and stretched non-uniformly draws its block, positions and
     * normals bit for bit, where the same head hung from the root draws it. Vanilla's carrying layer
     * calls the part's own {@code translateAndRotate} on a stack no ancestor's step is on, so neither
     * the ancestor's placement nor its stretch reaches the block.
     */
    @Test
    @DisplayName("a block on a part under a stretched ancestor stands and shades as on the part alone")
    void aBlockOnAPartUnderAStretchedAncestorIgnoresTheAncestor() {
        ConcurrentList<VisibleTriangle> nested = placed(carrier(UNSCALED, true));
        ConcurrentList<VisibleTriangle> alone = placed(carrier(UNSCALED, false));
        assertThat("every face of the block is placed", nested.size(), equalTo(12));

        for (int i = 0; i < nested.size(); i++) {
            assertSameBits("triangle " + i + " position 0", nested.get(i).position0(), alone.get(i).position0());
            assertSameBits("triangle " + i + " position 1", nested.get(i).position1(), alone.get(i).position1());
            assertSameBits("triangle " + i + " position 2", nested.get(i).position2(), alone.get(i).position2());
            assertSameBits("triangle " + i + " normal", nested.get(i).normal(), alone.get(i).normal());
        }
    }

    /**
     * Pins that a block attached to no part stands on the seated container, as a layer drawing on the
     * stack it was handed does: on a mesh a pose seats under a step turning about z and dropping along
     * y, the block lands where the unseated mesh's block lands under a fit carrying the seat's chain,
     * and away from where it lands under the fit alone.
     */
    @Test
    @DisplayName("a block attached to no part stands on the seated container")
    void aBlockAttachedToNoPartStandsOnTheSeat() {
        EntityMesh mesh = carrier(UNSCALED, false);
        EntityPose seats = new EntityPose(
            Concurrent.newUnmodifiableList(
                Map.of(PoseChannel.Z_ROT, new PoseExpr.Constant(0.3d, PoseWidth.FLOAT)),
                Map.of(PoseChannel.Y, new PoseExpr.Constant(-3d, PoseWidth.FLOAT))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty());
        Entity subject = Entity.builder().id(ResourceId.parse("minecraft:test")).model(mesh).pose(seats).build();
        PoseStyle idle = subject.styles().resolve(PoseStyle.IDLE, ADULT.getAppearance()::applies, ADULT.getEntityId());
        EntityMesh posed = PosePlayer.posed(subject, idle, subject.styles().periodTicks(), 0).model();
        Optional<String> seat = PosePlayer.seat(posed);
        assertThat("the pose seats the mesh", seat.isPresent(), equalTo(true));

        Entity.BlockOverlayLayer unattached = new Entity.BlockOverlayLayer(BLOCK, null, BLOCK_TURN, false);
        ConcurrentList<VisibleTriangle> onTheSeat = EntityRenderer.buildBlockOverlayTriangles(
            ClientAssetsExtension.context(), unattached, posed, FIT, 0);
        ConcurrentList<VisibleTriangle> seatInTheFit = EntityRenderer.buildBlockOverlayTriangles(
            ClientAssetsExtension.context(), unattached, mesh,
            FIT.multiply(BoneKit.buildChainTransform(posed.getBones(), seat.get())), 0);
        ConcurrentList<VisibleTriangle> unseated = EntityRenderer.buildBlockOverlayTriangles(
            ClientAssetsExtension.context(), unattached, mesh, FIT, 0);
        assertThat("every face of the block is placed", onTheSeat.size(), equalTo(12));

        float furthest = 0f;
        for (int i = 0; i < onTheSeat.size(); i++) {
            assertThat("triangle " + i + " stands on the seat",
                distance(onTheSeat.get(i).position0(), seatInTheFit.get(i).position0()), lessThan(1e-5f));
            furthest = Math.max(furthest, distance(onTheSeat.get(i).position0(), unseated.get(i).position0()));
        }
        assertThat("the seat must move the block on this fixture", furthest, greaterThan(1e-2f));
    }

    // --- fixtures ---

    /**
     * A cube-less {@link #BONE} turned {@link #TURN} and posed at {@code scale}, hung from the root or
     * from a cube-less {@code body} offset, turned and stretched to {@link #STRETCH} - all the overlay
     * build reads of the mesh is the chain down to the bone.
     */
    private static EntityMesh carrier(Vector3f scale, boolean nested) {
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        if (nested)
            bones.put("body", new EntityMesh.Bone(new Vector3f(0f, 4f, 0f), new EulerRotation(0f, 0f, 35f),
                EulerRotation.NONE, 1f, Concurrent.newList(), null).withPoseScale(STRETCH));
        bones.put(BONE, new EntityMesh.Bone(new Vector3f(0f, -2f, -3f), TURN, EulerRotation.NONE, 1f,
            Concurrent.newList(), nested ? "body" : null).withPoseScale(scale));
        return new EntityMesh(TextureSize.DEFAULT, bones, false);
    }

    /** The block's triangles as the overlay build places them on {@link #BONE}, turned {@link #BLOCK_TURN}. */
    private static ConcurrentList<VisibleTriangle> placed(EntityMesh carrier) {
        Entity.BlockOverlayLayer overlay = new Entity.BlockOverlayLayer(BLOCK, BONE, BLOCK_TURN, false);
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
                ClientAssetsExtension.context(), unplaced, carrier(UNSCALED, false), Matrix4f.IDENTITY, 0)
            .stream().map(VisibleTriangle::normal).toList();
    }

    /**
     * The placement the overlay build composes for {@link #BONE}: fit, anchor, {@code scale(16)}, the
     * block's own turn and the corner translate.
     */
    private static Matrix4f placement(EntityMesh carrier) {
        return FIT.multiply(EntityGeometryKit.resolveBoneAnchorMatrix(carrier, PosePlayer.seat(carrier), Optional.of(BONE)))
            .scale(16f, 16f, 16f)
            .multiply(BLOCK_TURN.translate(0.5f, 0.5f, 0.5f));
    }

    /** The unit normal a triangle's placed edges cross to. */
    private static Vector3f crossNormal(VisibleTriangle tri) {
        return tri.position1().subtract(tri.position0())
            .cross(tri.position2().subtract(tri.position0()))
            .normalize();
    }

    /** The straight-line distance between two points. */
    private static float distance(Vector3f a, Vector3f b) {
        return a.subtract(b).length();
    }

    /** Asserts every placed normal is the placement's own turn of the block's, normalised, bit for bit. */
    private static void assertPlacementTurnBitForBit(EntityMesh carrier) {
        ConcurrentList<VisibleTriangle> placed = placed(carrier);
        List<Vector3f> own = blockNormals();
        Matrix4f placement = placement(carrier);
        assertThat("every face of the block is placed", placed.size(), equalTo(12));

        for (int i = 0; i < placed.size(); i++)
            assertSameBits("triangle " + i, placed.get(i).normal(), own.get(i).transformNormal(placement).normalize());
    }

    /** Asserts two vectors agree bit for bit on all three components. */
    private static void assertSameBits(String label, Vector3f actual, Vector3f expected) {
        assertThat(label + " x", Float.floatToIntBits(actual.x()), equalTo(Float.floatToIntBits(expected.x())));
        assertThat(label + " y", Float.floatToIntBits(actual.y()), equalTo(Float.floatToIntBits(expected.y())));
        assertThat(label + " z", Float.floatToIntBits(actual.z()), equalTo(Float.floatToIntBits(expected.z())));
    }

}
