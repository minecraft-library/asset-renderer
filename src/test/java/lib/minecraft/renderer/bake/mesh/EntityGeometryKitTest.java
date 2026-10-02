package lib.minecraft.renderer.bake.mesh;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.AxisSigns;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.geometry.Unwrap;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Quaternionf;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.bake.mesh.VanillaEntityTransformGoldenTest.buildSingleCube;
import static lib.minecraft.renderer.bake.mesh.VanillaEntityTransformGoldenTest.collect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Foundation invariants for {@link EntityGeometryKit} verified against a clean single-bone,
 * single-cube fixture - no bone hierarchy, no rotations, no overrides - the one
 * {@link VanillaEntityTransformGoldenTest#buildSingleCube()} builds and pins. Locks down the kit's
 * winding + UV-swap + UV-permutation contract so drift is caught before it propagates to entities.
 *
 * <p>Seven invariants are pinned; the load-bearing one is {@link #winding_geometricNormalAgreesWithStored}:
 * each triangle's emit-order cross product dotted with its stored normal is positive, so the geometric
 * normal agrees with the stored one, camera- and projection-independent. The kit emits positions and
 * normals in the model's native Y-up frame and is internally det = +1; the chirality reflection enters
 * at render time through the model-to-world {@code Placement} and the camera, so nothing here bears on
 * screen-space cull winding.
 *
 * <p>The coupled invariants that must change together: position frame, normal frame, UV face swap
 * (UP / DOWN), UV permutation per face direction, and triangle winding. This test exercises each on the
 * simplest possible input so a defect in one shows as a focused assertion failure rather than a
 * downstream entity-render regression. Guarding against: a Y reflection landing inside the kit without
 * the matching winding reversal, changing UV-permutation arrays without the UP / DOWN face swap, and
 * breaking the atlas-layout coefficients in {@link Unwrap.Atlas#rect}.
 *
 * <p>The normal tests pin how a stored normal turns under a pose scale, on a turned bone below a scaled
 * one: by the chain's inverse-transpose below a non-uniform scale, as vanilla's normal matrix turns it,
 * and by the chain itself, bit for bit, below a uniform scale or none - a carried block's placement
 * included - with the bones whose chain the non-uniform test reads pinned beside them.
 *
 * <p>The anchor tests pin where a carried block stands: on the seated container, then the attached
 * part's own step and none of its ancestors', as vanilla's carrying layers call the part's
 * {@code translateAndRotate} on the stack they were handed - and that every shipped carrying row
 * anchors exactly where the part's whole chain does, its attached part hanging from nothing but the
 * seat.
 */
class EntityGeometryKitTest {

    /** The pose scale stretching z alone, which a face turned about x leaves the axes of. */
    private static final Vector3f STRETCH = new Vector3f(1f, 1f, 2f);

    /** The stretched {@code body} of {@link #turnedUnder}, standing as the seat a carried block rides. */
    private static final Optional<String> SEAT = Optional.of("body");

    /** The part of {@link #turnedUnder} a carried block is attached to. */
    private static final Optional<String> MOUTH = Optional.of("mouth");

    /** The part of {@link #carrier} and {@link #seatedChainScaling} a carried block is attached to. */
    private static final Optional<String> HEAD = Optional.of("head");

    /** The appearance predicate an adult index form resolves a style row through. */
    private static final EntityOptions ADULT = EntityOptions.of("minecraft:subject");

    /** Degrees the child {@code mouth} is turned about x. */
    private static final float TURN = 30f;

    /** The child's turn about x alone, which the hand-derived normals assume. */
    private static final EulerRotation PITCH = new EulerRotation(TURN, 0f, 0f);

    /** A child turn on all three axes, under which the two readings of a normal round apart. */
    private static final EulerRotation ANY_TURN = new EulerRotation(30f, 20f, 10f);

    @Test
    @DisplayName("single cube emits 12 triangles, one pair per cardinal face")
    void singleCube_emits12Triangles() {
        EntityGeometryKit.BuildResult result = buildSingleCube();
        assertThat(result.triangles().size(), equalTo(12));
    }

    /**
     * Pins the auto-fit envelope: every emitted vertex Y lands inside the fit bounds. The kit scales
     * the model's longest axis to {@code [-0.45, +0.45]} ({@code ENTITY_MODEL_FIT_EXTENT / extent}),
     * so no fit-scale regression can push geometry off-canvas.
     */
    @Test
    @DisplayName("Y-flip applied: every vertex lands within auto-fit bounds")
    void positionYFlip_keepsVerticesInBounds() {
        List<VisibleTriangle> triangles = collect(buildSingleCube());
        // Auto-fit scales longest axis to [-0.45, +0.45] (= ENTITY_MODEL_FIT_EXTENT / extent = 0.9 / 2).
        for (VisibleTriangle tri : triangles)
            for (Vector3f pos : positions(tri))
                assertThat(pos.y(),
                    both(greaterThanOrEqualTo(-0.46f)).and(lessThanOrEqualTo(0.46f)));
    }

    /**
     * Pins outward-facing normals: each triangle's stored normal, dotted with the vector from the
     * cube center to the triangle centroid, is positive. Guards the kit's Y-up normal frame - a sign
     * flip on the normal Y-axis would invert this on the top / bottom faces.
     */
    @Test
    @DisplayName("post-flip stored normals point outward relative to cube center")
    void normals_pointOutwardPostFlip() {
        List<VisibleTriangle> triangles = collect(buildSingleCube());
        for (VisibleTriangle tri : triangles) {
            Vector3f centroid = new Vector3f(
                (tri.position0().x() + tri.position1().x() + tri.position2().x()) / 3f,
                (tri.position0().y() + tri.position1().y() + tri.position2().y()) / 3f,
                (tri.position0().z() + tri.position1().z() + tri.position2().z()) / 3f
            );
            float radial = Vector3f.dot(tri.normal(), centroid);
            assertThat("normal must point away from cube center", radial, greaterThan(0f));
        }
    }

    /**
     * The load-bearing foundation invariant: for every emitted triangle, the emit-order cross product
     * {@code (p1 - p0) x (p2 - p0)} dotted with the stored normal is {@code > 0} - the geometric normal
     * agrees with the stored normal. Camera- and projection-independent.
     *
     * <p>Agreement rather than opposition is the whole point: the kit is internally det = +1, so no
     * reflection inside it reverses emit order relative to the stored normal.
     */
    @Test
    @DisplayName("emit-order geometric normal agrees with stored normal (kit det=+1 internally)")
    void winding_geometricNormalAgreesWithStored() {
        // The kit emits positions and stored normals in the model's native Y-up frame (det = +1) and
        // winds its triangles counter-clockwise, (0, 1, 2) and (0, 2, 3), so their emit-order cross
        // product agrees with the stored face normal. The screen-space winding the rasterizer culls on
        // is decided later, by the model-to-world Placement and the camera.
        //
        // A failure here means the emission winding or the stored-normal frame has drifted away from
        // the kit contract.
        //
        // The failures are collected and thrown at the end rather than asserted per triangle so one run
        // names every offending face instead of stopping at the first.
        StringBuilder errors = new StringBuilder();
        for (VisibleTriangle tri : collect(buildSingleCube())) {
            Vector3f edge1 = subtract(tri.position1(), tri.position0());
            Vector3f edge2 = subtract(tri.position2(), tri.position0());
            Vector3f geomNormal = Vector3f.cross(edge1, edge2);
            float alignment = Vector3f.dot(geomNormal, tri.normal());
            if (alignment <= 0f) {
                Face face = cardinalFor(tri.normal());
                errors.append("face ").append(face)
                    .append(": emit-order cross ").append(formatVec(geomNormal))
                    .append(" should agree with stored normal ").append(formatVec(tri.normal()))
                    .append(" (dot=").append(alignment).append(")\n");
            }
        }
        if (errors.length() > 0)
            throw new AssertionError("winding-vs-normal failures:\n" + errors);
    }

    @Test
    @DisplayName("each cardinal face direction is represented by exactly two triangles")
    void faceCoverage_eachFaceHasTwoTriangles() {
        Map<Face, Integer> faceCount = new HashMap<>();
        Face.forEach(face -> faceCount.put(face, 0));
        for (VisibleTriangle tri : collect(buildSingleCube())) {
            Face face = cardinalFor(tri.normal());
            faceCount.put(face, faceCount.get(face) + 1);
        }
        Face.forEach(face ->
            assertThat("face " + face + " triangle count", faceCount.get(face), equalTo(2)));
    }

    /**
     * Pins the atlas footprint: no UV escapes the cube's box-unwrap strip. A 2x2x2 cube at
     * {@code texOffs(0, 0)} on a 64x64 texture occupies exactly {@code u[0, 8/64], v[0, 4/64]}. Catches
     * a broken {@link Unwrap.Atlas#rect} coefficient that would sample outside the authored region.
     */
    @Test
    @DisplayName("UV mapping covers only the cube's UV strip atlas region")
    void uvLayout_coversStripRegion() {
        // For a 2-unit cube (size 2x2x2) at texOffs(0,0) on a 64x64 texture, the UV strip
        // occupies u[0, 8/64], v[0, 4/64] - two rows: top (TOP+BOTTOM), bottom (E+N+W+S).
        float maxU = 8f / 64f;
        float maxV = 4f / 64f;
        for (VisibleTriangle tri : collect(buildSingleCube()))
            for (Vector2f uv : new Vector2f[]{ tri.uv0(), tri.uv1(), tri.uv2() }) {
                assertThat("UV.u out of UV-strip region", uv.x(),
                    both(greaterThanOrEqualTo(-1e-4f)).and(lessThanOrEqualTo(maxU + 1e-4f)));
                assertThat("UV.v out of UV-strip region", uv.y(),
                    both(greaterThanOrEqualTo(-1e-4f)).and(lessThanOrEqualTo(maxV + 1e-4f)));
            }
    }

    /**
     * Pins the UP / DOWN UV swap: the two triangles carrying the {@code (0, +1, 0)} UP normal sample
     * from the DOWN strip slot ({@code u[4/64, 6/64]}), not the TOP slot. The model-to-world Y-flip
     * lands the UP cube vertices at the visual screen-bottom, so the kit compensates by swapping which
     * strip slot those triangles read - a coupled invariant that must move in lockstep with any
     * winding / normal-frame change.
     */
    @Test
    @DisplayName("UP-cube-face triangles sample from the DOWN strip slot (Y-flip swap compensation)")
    void uvSwap_upFaceLandsInDownSlot() {
        // UV strip row 1: TOP at u[2/64, 4/64], BOTTOM at u[4/64, 6/64], both v[0, 2/64].
        // The model-to-world Y-flip puts the UP cube vertices visually at the screen-bottom; the kit
        // compensates by sampling the DOWN strip slot for those triangles. Stored normals stay in the
        // model's Y-up frame, so the UP cube face carries normal (0, +1, 0).
        List<VisibleTriangle> upwardNormalTris = new ArrayList<>();
        for (VisibleTriangle tri : collect(buildSingleCube()))
            if (tri.normal().y() > 0.9f) upwardNormalTris.add(tri);

        assertThat(upwardNormalTris.size(), equalTo(2));

        for (VisibleTriangle tri : upwardNormalTris)
            for (Vector2f uv : new Vector2f[]{ tri.uv0(), tri.uv1(), tri.uv2() }) {
                assertThat("UP-face UV.u should sit in DOWN strip slot",
                    uv.x(), both(greaterThanOrEqualTo(4f / 64f - 1e-4f))
                        .and(lessThanOrEqualTo(6f / 64f + 1e-4f)));
                assertThat("UP-face UV.v should sit in row 1",
                    uv.y(), both(greaterThanOrEqualTo(-1e-4f))
                        .and(lessThanOrEqualTo(2f / 64f + 1e-4f)));
            }
    }

    /**
     * Pins the parent {@code ->} child compose order of the bone hierarchy: a child bone under a
     * parent that is both offset and rotated must land where {@code T(parent.pivot) *
     * R(parent.rot) * T(child.pivot)} places it (vanilla {@code ModelPart.translateAndRotate}
     * order). Verified by asserting the hierarchical model's bounds equal an equivalent pre-flattened
     * single-bone model whose world pivot is the hand-composed
     * {@code parent.pivot + R(parent.rot) * child.pivot} (computed here via the tensor primitives,
     * independent of the kit's chain), which is the equivalence a bone-relative cube and an absolute
     * one rest on.
     *
     * <p>A regression that drops the {@code parent} link, swaps the compose order, or fails to
     * propagate the parent rotation into the child's pivot + cubes shifts the bounds and fails
     * here - the multi-bone guard the single-cube fixtures cannot provide.
     */
    @Test
    @DisplayName("child bone composes parent pivot + rotation (translateAndRotate order)")
    void hierarchy_composesParentPivotAndRotation() {
        Vector3f parentPivot = new Vector3f(5f, 6f, 7f);
        EulerRotation parentRot = new EulerRotation(0f, 90f, 0f); // yaw 90 about Y - non-trivial
        Vector3f childPivot = new Vector3f(2f, 3f, 0f);

        // Hierarchical: cube-bearing child "head" parented to the rotated + offset "body".
        Box hier = EntityGeometryKit.computeBounds(twoBoneModel(parentPivot, parentRot, childPivot));

        // Pre-flattened equivalent: single "head" bone at the hand-composed world pivot with the
        // parent's rotation, derived via the tensor primitives (NOT the kit's chain composition).
        Matrix4f r = Quaternionf.rotationZYX(
            parentRot.rollRadians(), parentRot.yawRadians(), parentRot.pitchRadians()).toMatrix4f();
        Vector3f rotatedChild = childPivot.transformNormal(r);
        Vector3f worldPivot = new Vector3f(
            parentPivot.x() + rotatedChild.x(),
            parentPivot.y() + rotatedChild.y(),
            parentPivot.z() + rotatedChild.z());
        Box flat = EntityGeometryKit.computeBounds(singleChildAtWorld(worldPivot, parentRot));

        assertBoxEquals("hierarchical vs pre-flattened bounds", hier, flat, 1e-3f);

        // Sanity guard: the parent link must actually do something - a flat child that ignores its
        // parent (pivot = local childPivot, no rotation) lands elsewhere.
        Box ignored = EntityGeometryKit.computeBounds(singleChildAtWorld(childPivot, EulerRotation.NONE));
        assertThat("parent composition must move the child off its bare local pivot",
            Math.abs(hier.minX() - ignored.minX()) + Math.abs(hier.minZ() - ignored.minZ()),
            greaterThan(1f));
    }

    // --- normals under a pose scale ---

    /**
     * Pins vanilla's normal matrix under a non-uniform pose scale: a child turned {@link #TURN} about x
     * under a parent stretched to {@link #STRETCH} shades its faces by the inverse-transpose of the
     * chain. With {@code R} the child's turn and {@code S} the stretch, the chain's linear part is
     * {@code S R}, and vanilla's {@code PoseStack.Pose} turns a normal {@code n} to
     * {@code normalize(S^-1 R n)} - so the NORTH face {@code (0, 0, -1)} lands on
     * {@code normalize(0, sin, -cos / 2)} and the UP face {@code (0, 1, 0)} on
     * {@code normalize(0, cos, sin / 2)}, derived here by hand. The chain itself would lean both toward
     * the stretched z axis, {@code normalize(0, sin, -2 cos)} and {@code normalize(0, cos, 2 sin)}, far
     * enough away that a tolerance cannot mistake one for the other. EAST lies on the turn's own axis,
     * an axis of the stretch, so both readings keep it where it is.
     */
    @Test
    @DisplayName("a child turned under a non-uniformly scaled parent shades by the inverse-transpose")
    void aChildTurnedUnderANonUniformParentShadesByTheInverseTranspose() {
        Map<String, Vector3f> normals = storedNormals(turnedUnder(STRETCH, PITCH));
        double sin = Math.sin(Math.toRadians(TURN));
        double cos = Math.cos(Math.toRadians(TURN));

        assertDirection("NORTH", normals.get("mouth:north"), 0d, sin, -cos / 2d);
        assertDirection("UP", normals.get("mouth:up"), 0d, cos, sin / 2d);
        assertDirection("EAST", normals.get("mouth:east"), 1d, 0d, 0d);

        Vector3f chainNorth = new Vector3f(0f, (float) sin, (float) (-2d * cos)).normalize();
        assertThat("the chain's own reading must sit far from vanilla's on this fixture",
            Vector3f.dot(chainNorth, normals.get("mouth:north")), lessThan(0.9f));
    }

    /**
     * Pins that a uniform pose scale changes nothing about how a normal turns: every face of a child
     * turned {@link #ANY_TURN} under a parent scaled {@code 1.5} on every axis stores, bit for bit, the
     * chain's own turn of its face normal, normalised. A uniform scale never untrusts vanilla's normal
     * matrix, which then turns a normal to the chain's direction, so nothing under one may take the
     * inverse-transpose and its different rounding - which on this fixture lands a few ulps away.
     */
    @Test
    @DisplayName("a uniform parent scale leaves every normal on the chain's own turn, bit for bit")
    void aUniformParentScaleLeavesEveryNormalOnTheChain() {
        assertChainTurnBitForBit(turnedUnder(new Vector3f(1.5f, 1.5f, 1.5f), ANY_TURN));
    }

    /**
     * Pins that a chain no pose scales leaves every normal on the chain's own turn, bit for bit - the
     * turn every still render takes. The child is turned {@link #ANY_TURN}, where the inverse-transpose
     * rounds a few ulps away from the chain; a turn about one axis alone can round the two alike.
     */
    @Test
    @DisplayName("an unscaled chain leaves every normal on the chain's own turn, bit for bit")
    void anUnscaledChainLeavesEveryNormalOnTheChain() {
        assertChainTurnBitForBit(turnedUnder(new Vector3f(1f, 1f, 1f), ANY_TURN));
    }

    /**
     * Pins the carried-block path the way {@code EntityRenderer} composes it: the block is placed by
     * {@code entityFit * anchor * scale(16) * blockChain} on a part seated under a step stretched to
     * {@link #STRETCH}, and its normal turns by the inverse-transpose of that placement. The block chain
     * here is the snow golem's carved_pumpkin row, whose {@code scale(0.625, -0.625, -0.625)} is
     * uniform in magnitude and so turns a normal as its signs alone do; the stretch is what parts the
     * two readings. By hand, the top face {@code (0, 1, 0)} flips to {@code (0, -1, 0)}, survives the
     * half turn about y, turns to {@code (0, -cos, -sin)} and lands on
     * {@code normalize(0, -cos, -sin / 2)}; the north face lands on {@code normalize(0, sin, -cos / 2)}.
     */
    @Test
    @DisplayName("a carried block on a part seated under a non-uniform scale turns its normals by the inverse-transpose")
    void aCarriedBlockUnderANonUniformAnchorTurnsByTheInverseTranspose() {
        EntityMesh mesh = turnedUnder(STRETCH, PITCH);
        Matrix4f placement = carriedPlacement(mesh);
        boolean nonUniform = EntityGeometryKit.anchorScalesNonUniformly(mesh, SEAT, MOUTH);
        double sin = Math.sin(Math.toRadians(TURN));
        double cos = Math.cos(Math.toRadians(TURN));

        assertDirection("block top",
            EntityGeometryKit.chainNormal(Face.UP.normal(), placement, nonUniform), 0d, -cos, -sin / 2d);
        assertDirection("block north",
            EntityGeometryKit.chainNormal(Face.NORTH.normal(), placement, nonUniform), 0d, sin, -cos / 2d);
    }

    /**
     * Pins that a carried block on a bone under a uniform scale keeps the placement's own turn of every
     * face normal bit for bit, which is the turn {@code EntityRenderer} gives a block whose anchor
     * carries no non-uniform scale.
     */
    @Test
    @DisplayName("a carried block on a uniformly scaled bone keeps the placement's own turn, bit for bit")
    void aCarriedBlockUnderAUniformAnchorKeepsThePlacementsTurn() {
        assertPlacementTurnBitForBit(turnedUnder(new Vector3f(1.5f, 1.5f, 1.5f), ANY_TURN));
    }

    /**
     * Pins that a carried block on a bone no pose scales keeps the placement's own turn of every face
     * normal bit for bit - the turn every carried block a still render draws takes.
     */
    @Test
    @DisplayName("a carried block on an unscaled bone keeps the placement's own turn, bit for bit")
    void aCarriedBlockUnderAnUnscaledAnchorKeepsThePlacementsTurn() {
        assertPlacementTurnBitForBit(turnedUnder(new Vector3f(1f, 1f, 1f), ANY_TURN));
    }

    /**
     * Pins the test that decides which turn a face takes. It reads the bone's own pose scale and every
     * ancestor's, so a child of a stretched bone answers as the bone does while a sibling does not; it
     * compares magnitudes, as vanilla's {@code PoseStack.Pose.scale} does, so a scale that only flips a
     * sign answers {@code false}; and a bone the mesh does not declare, or one whose parent it does not,
     * answers off what the chain composes.
     */
    @Test
    @DisplayName("the non-uniform test reads magnitudes over the bone's whole chain")
    void theNonUniformTestReadsMagnitudesOverTheWholeChain() {
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("root", posedBone(null, new Vector3f(1f, 1f, 1f)));
        bones.put("body", posedBone("root", new Vector3f(1f, 1f, 1.2f)));
        bones.put("head", posedBone("body", new Vector3f(1f, 1f, 1f)));
        bones.put("tail", posedBone("root", new Vector3f(1f, 1f, 1f)));
        bones.put("flipped", posedBone("root", new Vector3f(-1.5f, 1.5f, 1.5f)));
        bones.put("dangling", posedBone("nobody", new Vector3f(1f, 1f, 1f)));
        EntityMesh mesh = new EntityMesh(TextureSize.DEFAULT, bones, false);

        assertThat("the stretched bone itself", EntityGeometryKit.scalesNonUniformly(mesh, "body"), equalTo(true));
        assertThat("a child of the stretched bone", EntityGeometryKit.scalesNonUniformly(mesh, "head"), equalTo(true));
        assertThat("the root above it", EntityGeometryKit.scalesNonUniformly(mesh, "root"), equalTo(false));
        assertThat("a sibling beside it", EntityGeometryKit.scalesNonUniformly(mesh, "tail"), equalTo(false));
        assertThat("a scale flipping only a sign", EntityGeometryKit.scalesNonUniformly(mesh, "flipped"), equalTo(false));
        assertThat("a bone naming an undeclared parent", EntityGeometryKit.scalesNonUniformly(mesh, "dangling"), equalTo(false));
        assertThat("a bone the mesh does not declare", EntityGeometryKit.scalesNonUniformly(mesh, "absent"), equalTo(false));
    }

    /**
     * Pins that the textured bounds walk measures a face at its opaque-texel sub-rectangle rather than
     * per texel. The fixture is an 8x8 plane whose front face is opaque at exactly two diagonally
     * opposite corner texels of its UV box, so its sub-rectangle is the whole box, and whose back face
     * is transparent and measures nothing. Turned 45 degrees
     * about the plane's normal, the square stands on a corner, and the two transparent corners are
     * screen extremes: the sub-rectangle walk reaches them, and so measures the same bounds as the raw
     * corner walk, where a walk of the opaque texels alone would stop a whole half-diagonal short on
     * one axis.
     */
    @Test
    @DisplayName("a face opaque on all four edges of its UV box is measured to its full corners, transparent ones included")
    void aFaceOpaqueOnAllFourEdgesIsMeasuredToItsFullCorners() {
        EntityMesh.Cube plane = new EntityMesh.Cube(
            new Vector3f(-4f, -4f, 0f), new Vector3f(8f, 8f, 0f), Vector2f.ZERO,
            Vector3f.ZERO, false, Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap());
        ConcurrentList<EntityMesh.Cube> cubes = Concurrent.newList();
        cubes.add(plane);
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f, cubes, null));
        EntityMesh mesh = new EntityMesh(TextureSize.DEFAULT, bones, false);

        // Opaque at the two diagonal corners of the front face's 8x8 box, u 0-7, and transparent
        // everywhere else - the back face's box, u 8-15, included.
        int[] pixels = new int[64 * 64];
        for (int[] texel : new int[][]{{0, 0}, {7, 7}})
            pixels[texel[1] * 64 + texel[0]] = 0xFFFFFFFF;
        PixelBuffer diagonal = PixelBuffer.of(pixels, 64, 64);
        Matrix4f onItsCorner = Matrix4f.IDENTITY.rotateZ((float) Math.toRadians(45d));

        Box textured = EntityGeometryKit.computeScreenBounds(mesh, onItsCorner, 1f, diagonal);
        Box corners = EntityGeometryKit.computeScreenBounds(mesh, onItsCorner, 1f, null);
        assertBoxEquals("the sub-rectangle walk against the full corners", textured, corners, 1e-4f);
    }

    // --- the carried-block anchor ---

    /**
     * Pins that a carried block takes the attached part's own step and none of its ancestors': on an
     * unposed mesh whose {@code head} hangs from a turned and offset {@code body}, the anchor is,
     * bit for bit, the chain of {@code head} alone, hung from nothing - the step vanilla's carrying
     * layer applies with the part's {@code translateAndRotate}. The part's whole chain lands
     * elsewhere on this fixture.
     */
    @Test
    @DisplayName("an attached part anchors its block on its own step and on no ancestor's")
    void anAttachedPartTakesItsOwnStepAndNoAncestors() {
        EntityMesh mesh = carrier(true);
        Map<String, EntityMesh.Bone> alone = Map.of("head", mesh.getBones().get("head").withParent(null));
        Matrix4f anchor = EntityGeometryKit.resolveBoneAnchorMatrix(mesh, Optional.empty(), HEAD);

        assertSameBits("the anchor", anchor, BoneKit.buildChainTransform(alone, "head"));
        assertThat("the part's whole chain must land elsewhere on this fixture",
            sameBits(anchor, BoneKit.buildChainTransform(mesh.getBones(), "head")), equalTo(false));
    }

    /**
     * Pins that a carried block rides the seated container: a mesh nesting its {@code head} under a
     * turned {@code body} and a twin hanging {@code head} from the root, posed under one pose whose
     * container turns about z and drops along y, anchor the head's block bit for bit alike - and
     * alike with the twin's whole chain, which is the arithmetic a top-level part always took. A
     * block attached to no part stands on the seat's own chain, which moves it.
     */
    @Test
    @DisplayName("an attached part anchors on the seated container, then its own step")
    void anAttachedPartRidesTheSeatedContainer() {
        EntityPose seats = new EntityPose(
            Concurrent.newUnmodifiableList(
                Map.of(PoseChannel.Z_ROT, new PoseExpr.Constant(0.3d, PoseWidth.FLOAT)),
                Map.of(PoseChannel.Y, new PoseExpr.Constant(-3d, PoseWidth.FLOAT))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty());
        EntityMesh nested = idlePosed(carrier(true), seats);
        EntityMesh twin = idlePosed(carrier(false), seats);
        Optional<String> seat = PosePlayer.seat(nested);
        assertThat("the pose seats the mesh", seat.isPresent(), equalTo(true));
        assertThat("both meshes are seated alike", PosePlayer.seat(twin), equalTo(seat));

        Matrix4f anchor = EntityGeometryKit.resolveBoneAnchorMatrix(nested, seat, HEAD);
        Matrix4f twinAnchor = EntityGeometryKit.resolveBoneAnchorMatrix(twin, seat, HEAD);
        assertSameBits("the nested part against its top-level twin", anchor, twinAnchor);
        assertSameBits("the top-level twin against its whole chain",
            twinAnchor, BoneKit.buildChainTransform(twin.getBones(), "head"));
        assertThat("the seat must move the anchor on this fixture",
            sameBits(anchor, EntityGeometryKit.resolveBoneAnchorMatrix(nested, Optional.empty(), HEAD)),
            equalTo(false));

        Matrix4f onTheSeat = EntityGeometryKit.resolveBoneAnchorMatrix(nested, seat, Optional.empty());
        assertSameBits("a block attached to no part", onTheSeat,
            BoneKit.buildChainTransform(nested.getBones(), seat.get()));
        assertThat("and the seat moves it", sameBits(onTheSeat, Matrix4f.IDENTITY), equalTo(false));
    }

    /**
     * Pins that the carried block's normal decision reads the steps its anchor composes: a
     * non-uniform pose scale on an ancestor of the attached part leaves it {@code false}, while the
     * same scale on the part itself or on the seat answers {@code true}. A face of the part's own
     * cubes is drawn through the whole chain, so its test still answers {@code true} for the ancestor.
     */
    @Test
    @DisplayName("an ancestor's non-uniform scale leaves the carried block's normals alone")
    void anAncestorsNonUniformScaleLeavesTheBlocksNormalsAlone() {
        Optional<String> seat = Optional.of("seat");

        EntityMesh ancestor = seatedChainScaling("body");
        assertThat("an ancestor of the part", EntityGeometryKit.anchorScalesNonUniformly(ancestor, seat, HEAD), equalTo(false));
        assertThat("the part's own faces still see it", EntityGeometryKit.scalesNonUniformly(ancestor, "head"), equalTo(true));
        assertThat("the part itself",
            EntityGeometryKit.anchorScalesNonUniformly(seatedChainScaling("head"), seat, HEAD), equalTo(true));
        assertThat("the seat",
            EntityGeometryKit.anchorScalesNonUniformly(seatedChainScaling("seat"), seat, HEAD), equalTo(true));
        assertThat("the seat alone, for a block attached to no part",
            EntityGeometryKit.anchorScalesNonUniformly(seatedChainScaling("seat"), seat, Optional.empty()), equalTo(true));
        assertThat("a part the mesh does not declare",
            EntityGeometryKit.anchorScalesNonUniformly(seatedChainScaling("seat"), seat, Optional.of("absent")), equalTo(false));
    }

    /**
     * Pins that every shipped carrying row anchors exactly where the part's whole chain does, which is
     * what keeps every stored render the same. Each attached part hangs from no bone of its mesh, so
     * its only parent in a posed mesh is the seat; each block attached to no part sits on a subject no
     * pose seats; and under {@code bind}, every row the subject ships whichever appearance it applies
     * to, and the universal {@code idle} and {@code stride} rows, at every strip tick, the
     * anchor and its normal decision agree bit for bit with the whole chain's. It reddens the day a
     * regenerated table nests an attached part or seats an unattached block's subject - the day a
     * stored render starts to see the anchor's narrowing.
     */
    @Test
    @DisplayName("every shipped attached part is top-level and anchors as its whole chain does")
    void everyShippedAnchoredPartIsTopLevelAndAnchorsAsBefore() {
        ConcurrentMap<String, Entity> shipped = EntityModelLoader.load();
        assumeTrue(!shipped.isEmpty(), "bundled entity tables are present");

        int attached = 0;
        int seated = 0;
        for (Entity entity : shipped.values()) {
            if (entity.blockOverlays().isEmpty()) continue;
            Map<String, EntityMesh.Bone> loaded = entity.model().getBones();
            List<PoseStyle> rows = new ArrayList<>(entity.styles().styles());
            rows.add(StyleCatalog.bind());
            rows.add(entity.styles().resolve(PoseStyle.IDLE, row -> false, entity.id().toString()));
            rows.add(entity.styles().resolve(PoseStyle.STRIDE, row -> false, entity.id().toString()));
            for (PoseStyle style : rows) {
                String id = style.id();
                int ticksPerFrame = Math.max(1, entity.styles().stripTicksPerFrame(style));
                for (int frame = 0; frame < StyleCatalog.STRIP_FRAMES; frame++) {
                    int tick = frame * ticksPerFrame;
                    EntityMesh posed = PosePlayer.posed(entity, style, entity.styles().periodTicks(), tick).model();
                    Optional<String> seat = PosePlayer.seat(posed);
                    for (Entity.BlockOverlayLayer overlay : entity.blockOverlays()) {
                        String where = entity.id() + " " + overlay.blockId() + " under " + id + " at tick " + tick;
                        if (overlay.attachedBone() == null) {
                            assertThat(where + ": a block attached to no part sits on an unseated subject",
                                seat, equalTo(Optional.empty()));
                            continue;
                        }

                        String part = overlay.attachedBone();
                        assertThat(where + ": the mesh declares the part", loaded.containsKey(part), equalTo(true));
                        String parent = loaded.get(part).getParent();
                        assertThat(where + ": the part hangs from no bone of its mesh",
                            parent == null || parent.equals(part) || !loaded.containsKey(parent), equalTo(true));
                        assertSameBits(where, EntityGeometryKit.resolveBoneAnchorMatrix(posed, seat, Optional.of(part)),
                            BoneKit.buildChainTransform(posed.getBones(), part));
                        assertThat(where + ": the normal decision",
                            EntityGeometryKit.anchorScalesNonUniformly(posed, seat, Optional.of(part)),
                            equalTo(EntityGeometryKit.scalesNonUniformly(posed, part)));
                        attached++;
                        if (seat.isPresent()) seated++;
                    }
                }
            }
        }
        assertThat("the mooshroom, snow golem and iron golem anchors are compared", attached, greaterThanOrEqualTo(3));
        assertThat("and a seated one among them", seated, greaterThan(0));
    }

    // --- fixtures ---

    /** A single 1x1x1 bone-local cube centred at the origin (no UV overrides). */
    private static ConcurrentList<EntityMesh.Cube> unitChildCube() {
        EntityMesh.Cube cube = new EntityMesh.Cube(
            new Vector3f(-0.5f, -0.5f, -0.5f), new Vector3f(1f, 1f, 1f), Vector2f.ZERO,
            Vector3f.ZERO, false, Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap());
        ConcurrentList<EntityMesh.Cube> cubes = Concurrent.newList();
        cubes.add(cube);
        return cubes;
    }

    /**
     * Two-bone hierarchy fixture: a cube-less pose-only {@code body} (offset + rotated) parenting a
     * cube-bearing {@code head}, exercising the kit's parent-chain composition.
     */
    private static EntityMesh twoBoneModel(Vector3f parentPivot, EulerRotation parentRot, Vector3f childPivot) {
        EntityMesh.Bone body = new EntityMesh.Bone(
            parentPivot, parentRot, EulerRotation.NONE, 1f, Concurrent.newList(), null);
        EntityMesh.Bone head = new EntityMesh.Bone(
            childPivot, EulerRotation.NONE, EulerRotation.NONE, 1f, unitChildCube(), "body");
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", body);
        bones.put("head", head);
        return new EntityMesh(TextureSize.DEFAULT, bones, false);
    }

    /** Single root {@code head} bone at a pre-flattened world pivot + rotation (no parent). */
    private static EntityMesh singleChildAtWorld(Vector3f worldPivot, EulerRotation rot) {
        EntityMesh.Bone head = new EntityMesh.Bone(
            worldPivot, rot, EulerRotation.NONE, 1f, unitChildCube(), null);
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("head", head);
        return new EntityMesh(TextureSize.DEFAULT, bones, false);
    }

    /**
     * A cube-bearing {@code mouth} turned by {@code turn} under a cube-less {@code body} posed at
     * {@code scale} - the nautilus's mouths under its stretched body, reduced to one bone each.
     */
    private static EntityMesh turnedUnder(Vector3f scale, EulerRotation turn) {
        EntityMesh.Bone body = new EntityMesh.Bone(
            new Vector3f(0f, 4f, 0f), EulerRotation.NONE, EulerRotation.NONE, 1f, Concurrent.newList(), null)
            .withPoseScale(scale);
        EntityMesh.Bone mouth = new EntityMesh.Bone(
            new Vector3f(0f, -2f, -3f), turn, EulerRotation.NONE, 1f, unitChildCube(), "body");
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", body);
        bones.put("mouth", mouth);
        return new EntityMesh(TextureSize.DEFAULT, bones, false);
    }

    /**
     * A cube-bearing {@code head} turned {@link #ANY_TURN} at {@code (0, -4, -6)}, hung from a
     * cube-bearing {@code body} at {@code (0, 10, 0)} pitched 30 degrees, or from the root.
     */
    private static EntityMesh carrier(boolean nested) {
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", new EntityMesh.Bone(new Vector3f(0f, 10f, 0f), new EulerRotation(30f, 0f, 0f),
            EulerRotation.NONE, 1f, unitChildCube(), null));
        bones.put("head", new EntityMesh.Bone(new Vector3f(0f, -4f, -6f), ANY_TURN,
            EulerRotation.NONE, 1f, unitChildCube(), nested ? "body" : null));
        return new EntityMesh(TextureSize.DEFAULT, bones, false);
    }

    /** The mesh where its own idle row leaves it under {@code pose} at tick zero. */
    private static EntityMesh idlePosed(EntityMesh mesh, EntityPose pose) {
        Entity subject = Entity.builder().id(ResourceId.parse("minecraft:test")).model(mesh).pose(pose).build();
        PoseStyle idle = subject.styles().resolve(PoseStyle.IDLE, ADULT.getAppearance()::applies, ADULT.getEntityId());
        return PosePlayer.posed(subject, idle, subject.styles().periodTicks(), 0).model();
    }

    /**
     * A cube-less {@code seat} holding a {@code body} holding a {@code head}, the one named posed at a
     * scale stretching y alone.
     */
    private static EntityMesh seatedChainScaling(String scaled) {
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("seat", posedBone(null, new Vector3f(1f, 1f, 1f)));
        bones.put("body", posedBone("seat", new Vector3f(1f, 1f, 1f)));
        bones.put("head", posedBone("body", new Vector3f(1f, 1f, 1f)));
        bones.put(scaled, bones.get(scaled).withPoseScale(new Vector3f(1f, 2f, 1f)));
        return new EntityMesh(TextureSize.DEFAULT, bones, false);
    }

    /** A cube-less bone at the origin, unturned, posed at {@code poseScale}. */
    private static EntityMesh.Bone posedBone(String parent, Vector3f poseScale) {
        return new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f,
            Concurrent.newList(), parent).withPoseScale(poseScale);
    }

    /**
     * The kit's stored normal for each face, keyed by its debug tag ({@code bone:direction}). A face's
     * two triangles store one normal, so either answers for it.
     */
    private static Map<String, Vector3f> storedNormals(EntityMesh mesh) {
        Map<String, Vector3f> normals = new HashMap<>();
        for (VisibleTriangle tri : collect(EntityGeometryKit.buildTriangles(mesh, solidWhite())))
            normals.put(tri.debugTag(), tri.normal());
        return normals;
    }

    /**
     * Asserts every triangle the kit emits for {@code mouth} stores its face normal turned by the
     * bone's own chain and normalised, bit for bit. The mouth's cube is unrotated, so its chain is the
     * whole cube transform.
     */
    private static void assertChainTurnBitForBit(EntityMesh mesh) {
        Matrix4f chain = BoneKit.buildChainTransform(mesh.getBones(), "mouth");
        int checked = 0;
        for (VisibleTriangle tri : collect(EntityGeometryKit.buildTriangles(mesh, solidWhite()))) {
            Face face = Face.fromName(tri.debugTag().substring("mouth:".length()));
            assertSameBits(tri.debugTag(), tri.normal(), face.normal().transformNormal(chain).normalize());
            checked++;
        }
        assertThat("every face of the cube is checked", checked, equalTo(12));
    }

    /**
     * Asserts every face normal a carried block turns through {@link #carriedPlacement} on
     * {@code mouth} lands, bit for bit, on the placement's own turn of it, normalised.
     */
    private static void assertPlacementTurnBitForBit(EntityMesh mesh) {
        Matrix4f placement = carriedPlacement(mesh);
        boolean nonUniform = EntityGeometryKit.anchorScalesNonUniformly(mesh, SEAT, MOUTH);
        Face.forEach(face -> assertSameBits(face.direction(),
            EntityGeometryKit.chainNormal(face.normal(), placement, nonUniform),
            face.normal().transformNormal(placement).normalize()));
    }

    /**
     * The carried-block placement {@code EntityRenderer} composes: an entity fit, the bone's anchor
     * chain, the block-to-pixel {@code scale(16)}, and the snow golem's carved_pumpkin ops with the
     * renderer's corner-at-origin translate appended.
     */
    private static Matrix4f carriedPlacement(EntityMesh mesh) {
        Matrix4f entityFit = EntityGeometryKit.buildEntityFitMatrix(new Vector3f(1f, 2f, 3f), 0.05f);
        Matrix4f blockChain = Matrix4f.IDENTITY
            .translate(0f, -0.34375f, 0f)
            .rotateY((float) Math.toRadians(180f))
            .scale(0.625f, -0.625f, -0.625f)
            .translate(-0.5f, -0.5f, -0.5f)
            .translate(0.5f, 0.5f, 0.5f);
        return entityFit.multiply(EntityGeometryKit.resolveBoneAnchorMatrix(mesh, SEAT, MOUTH))
            .scale(16f, 16f, 16f)
            .multiply(blockChain);
    }

    /** An opaque-white {@code 64x64} texture, so no face of a fixture cube samples a transparent texel. */
    private static PixelBuffer solidWhite() {
        int[] pixels = new int[64 * 64];
        Arrays.fill(pixels, 0xFFFFFFFF);
        return PixelBuffer.of(pixels, 64, 64);
    }

    /** Asserts a unit vector points along {@code (x, y, z)}, which is normalised here. */
    private static void assertDirection(String label, Vector3f actual, double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        assertThat(label + " x", (double) Math.abs((float) (x / length) - actual.x()), lessThan(1e-5d));
        assertThat(label + " y", (double) Math.abs((float) (y / length) - actual.y()), lessThan(1e-5d));
        assertThat(label + " z", (double) Math.abs((float) (z / length) - actual.z()), lessThan(1e-5d));
    }

    /** Asserts two vectors agree bit for bit on all three components. */
    private static void assertSameBits(String label, Vector3f actual, Vector3f expected) {
        assertThat(label + " x", Float.floatToIntBits(actual.x()), equalTo(Float.floatToIntBits(expected.x())));
        assertThat(label + " y", Float.floatToIntBits(actual.y()), equalTo(Float.floatToIntBits(expected.y())));
        assertThat(label + " z", Float.floatToIntBits(actual.z()), equalTo(Float.floatToIntBits(expected.z())));
    }

    /** Asserts two matrices agree bit for bit on all sixteen entries. */
    private static void assertSameBits(String label, Matrix4f actual, Matrix4f expected) {
        for (int col = 1; col <= 4; col++)
            for (int row = 1; row <= 4; row++)
                assertThat(label + " (" + col + ", " + row + ")", Float.floatToIntBits(actual.get(col, row)),
                    equalTo(Float.floatToIntBits(expected.get(col, row))));
    }

    /** Whether two matrices agree bit for bit on all sixteen entries. */
    private static boolean sameBits(Matrix4f a, Matrix4f b) {
        for (int col = 1; col <= 4; col++)
            for (int row = 1; row <= 4; row++)
                if (Float.floatToIntBits(a.get(col, row)) != Float.floatToIntBits(b.get(col, row))) return false;
        return true;
    }

    /** Asserts two boxes match on all six extents within {@code eps}. */
    private static void assertBoxEquals(String label, Box actual, Box expected, float eps) {
        assertThat(label + " minX", Math.abs(actual.minX() - expected.minX()), lessThan(eps));
        assertThat(label + " minY", Math.abs(actual.minY() - expected.minY()), lessThan(eps));
        assertThat(label + " minZ", Math.abs(actual.minZ() - expected.minZ()), lessThan(eps));
        assertThat(label + " maxX", Math.abs(actual.maxX() - expected.maxX()), lessThan(eps));
        assertThat(label + " maxY", Math.abs(actual.maxY() - expected.maxY()), lessThan(eps));
        assertThat(label + " maxZ", Math.abs(actual.maxZ() - expected.maxZ()), lessThan(eps));
    }

    private static Vector3f[] positions(VisibleTriangle tri) {
        return new Vector3f[]{ tri.position0(), tri.position1(), tri.position2() };
    }

    private static Vector3f subtract(Vector3f a, Vector3f b) {
        return new Vector3f(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    private static String formatVec(Vector3f v) {
        return String.format("(%.3f, %.3f, %.3f)", v.x(), v.y(), v.z());
    }

    @Test
    @DisplayName("kit bakes no shade - every triangle carries the unlit scalar")
    void kitEmitsUnlitGeometry() {
        for (VisibleTriangle t : buildSingleCube().triangles())
            assertThat("kit-emitted shade for " + t.debugTag(), t.shading(), equalTo(Shading.UNLIT));
    }

    @Test
    @DisplayName("the fold's turn is load-bearing - MIRROR_Z lights the same cube differently")
    void relightTurnSelectsTheFrame() {
        ConcurrentList<VisibleTriangle> asFolded = Shading.relightForEntityInUi(
            buildSingleCube().triangles(), LightingFrame.ENTITY_IN_UI, AxisSigns.MIRROR_Y);
        ConcurrentList<VisibleTriangle> asPlayer = Shading.relightForEntityInUi(
            buildSingleCube().triangles(), LightingFrame.ENTITY_IN_UI, AxisSigns.MIRROR_Z);

        // The two turns are one HALF_X apart, so a cube lit through the wrong one shades its Y and Z
        // faces by the opposite hemisphere. Nothing about the kit's own geometry makes them agree.
        long differing = 0;
        for (int i = 0; i < asFolded.size(); i++)
            if (asFolded.get(i).shading() != asPlayer.get(i).shading()) differing++;
        assertThat("faces whose shade depends on the turn", differing, greaterThan(0L));
    }

    /**
     * Maps a (mostly-)axis-aligned normal back to its source face. The kit stores normals in the
     * model's native Y-up frame, so {@code +Y} is UP and {@code -Y} is DOWN directly.
     */
    private static Face cardinalFor(Vector3f normal) {
        float ax = Math.abs(normal.x());
        float ay = Math.abs(normal.y());
        float az = Math.abs(normal.z());
        if (ay > ax && ay > az)
            return normal.y() > 0 ? Face.UP : Face.DOWN;
        if (ax > az)
            return normal.x() > 0 ? Face.EAST : Face.WEST;
        return normal.z() > 0 ? Face.SOUTH : Face.NORTH;
    }

}
