package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.TextureSize;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Foundation invariants for {@link EntityGeometryKit} verified against a clean single-bone,
 * single-cube fixture - no bone hierarchy, no rotations, no overrides. Locks down the kit's
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
 */
class EntityGeometryKitTest {

    /** Half-extent of the cube fixture in model units (cube spans {@code [-HALF, +HALF]} per axis). */
    private static final float HALF = 1f;

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
        for (Face face : Face.CACHED_VALUES) faceCount.put(face, 0);
        for (VisibleTriangle tri : collect(buildSingleCube())) {
            Face face = cardinalFor(tri.normal());
            faceCount.put(face, faceCount.get(face) + 1);
        }
        for (Face face : Face.CACHED_VALUES)
            assertThat("face " + face + " triangle count", faceCount.get(face), equalTo(2));
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

    // --- fixtures ---

    /**
     * Builds the canonical fixture: one {@code body} bone (no rotation, unit scale, no parent) holding
     * one axis-aligned cube spanning {@code [-HALF, +HALF]} per axis, at atlas origin {@code (0, 0)} on
     * a solid-white 64x64 texture. No hierarchy, no overrides - the simplest input that still exercises
     * all six cardinal faces, so a defect surfaces as a focused assertion rather than a downstream
     * entity regression.
     */
    private static EntityGeometryKit.BuildResult buildSingleCube() {
        ConcurrentMap<String, EntityModelData.FaceUv> faceUv = Concurrent.newMap();
        EntityModelData.Cube cube = new EntityModelData.Cube(
            new Vector3f(-HALF, -HALF, -HALF), // origin
            new Vector3f(2f * HALF, 2f * HALF, 2f * HALF), // size
            Vector2f.ZERO, // uv (atlas origin)
            Vector3f.ZERO, // grow
            false, // mirror
            Vector3f.ZERO, // pivot
            EulerRotation.NONE, // rotation
            faceUv
        );
        ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();
        cubes.add(cube);

        EntityModelData.Bone bone = new EntityModelData.Bone(
            Vector3f.ZERO, // pivot
            EulerRotation.NONE, // rotation
            EulerRotation.NONE, // bindPoseRotation
            1f, // scale
            cubes,
            null // parent
        );

        ConcurrentLinkedMap<String, EntityModelData.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", bone);

        EntityModelData model = new EntityModelData(TextureSize.DEFAULT, bones, false);
        return EntityGeometryKit.buildTriangles(model, solidTexture(64, 64));
    }

    /** A single 1x1x1 bone-local cube centred at the origin (no UV overrides). */
    private static ConcurrentList<EntityModelData.Cube> unitChildCube() {
        EntityModelData.Cube cube = new EntityModelData.Cube(
            new Vector3f(-0.5f, -0.5f, -0.5f), new Vector3f(1f, 1f, 1f), Vector2f.ZERO,
            Vector3f.ZERO, false, Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap());
        ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();
        cubes.add(cube);
        return cubes;
    }

    /**
     * Two-bone hierarchy fixture: a cube-less pose-only {@code body} (offset + rotated) parenting a
     * cube-bearing {@code head}, exercising the kit's parent-chain composition.
     */
    private static EntityModelData twoBoneModel(Vector3f parentPivot, EulerRotation parentRot, Vector3f childPivot) {
        EntityModelData.Bone body = new EntityModelData.Bone(
            parentPivot, parentRot, EulerRotation.NONE, 1f, Concurrent.newList(), null);
        EntityModelData.Bone head = new EntityModelData.Bone(
            childPivot, EulerRotation.NONE, EulerRotation.NONE, 1f, unitChildCube(), "body");
        ConcurrentLinkedMap<String, EntityModelData.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", body);
        bones.put("head", head);
        return new EntityModelData(TextureSize.DEFAULT, bones, false);
    }

    /** Single root {@code head} bone at a pre-flattened world pivot + rotation (no parent). */
    private static EntityModelData singleChildAtWorld(Vector3f worldPivot, EulerRotation rot) {
        EntityModelData.Bone head = new EntityModelData.Bone(
            worldPivot, rot, EulerRotation.NONE, 1f, unitChildCube(), null);
        ConcurrentLinkedMap<String, EntityModelData.Bone> bones = Concurrent.newLinkedMap();
        bones.put("head", head);
        return new EntityModelData(TextureSize.DEFAULT, bones, false);
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

    /** Opaque-white {@code w x h} texture ({@code 0xFFFFFFFF} everywhere) so UV sampling never drops texels. */
    private static PixelBuffer solidTexture(int w, int h) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFFFFFFFF;
        return PixelBuffer.of(pixels, w, h);
    }

    /** Drains the build result's triangle stream into a random-access list for repeated iteration. */
    private static List<VisibleTriangle> collect(EntityGeometryKit.BuildResult result) {
        List<VisibleTriangle> out = new ArrayList<>();
        for (VisibleTriangle tri : result.triangles()) out.add(tri);
        return out;
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
            buildSingleCube().triangles(), EntityGeometryKit.DEFAULT_ENTITY_LIGHTING, Turn.MIRROR_Y);
        ConcurrentList<VisibleTriangle> asPlayer = Shading.relightForEntityInUi(
            buildSingleCube().triangles(), EntityGeometryKit.DEFAULT_ENTITY_LIGHTING, Turn.MIRROR_Z);

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
