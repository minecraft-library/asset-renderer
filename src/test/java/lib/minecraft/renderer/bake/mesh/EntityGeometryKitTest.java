package lib.minecraft.renderer.bake.mesh;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentArrayList;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.bake.pose.PoseStrips;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.index.StripMemo;
import lib.minecraft.renderer.engine.camera.FitFrame;
import lib.minecraft.renderer.engine.draw.GeometryLayer;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.draw.SurfaceTraits;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.engine.geometry.AxisSigns;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.CornerPhase;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.geometry.Unwrap;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Quaternionf;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.math.Vector4f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Foundation invariants for {@link EntityGeometryKit} verified against a clean single-bone,
 * single-cube fixture - no bone hierarchy, no rotations, no overrides - the one
 * {@link VanillaEntityTransformGoldenTest#buildSingleCube()} builds and pins. Locks down the kit's
 * winding + UV-swap + UV-permutation contract so drift is caught before it propagates to entities.
 * <p>
 * Seven invariants are pinned; the load-bearing one is {@link #winding_geometricNormalAgreesWithStored}:
 * each triangle's emit-order cross product dotted with its stored normal is positive, so the geometric
 * normal agrees with the stored one, camera- and projection-independent. The kit emits positions and
 * normals in the model's native Y-up frame and is internally det = +1; the chirality reflection enters
 * at render time through the model-to-world {@code Placement} and the camera, so nothing here bears on
 * screen-space cull winding.
 * <p>
 * The coupled invariants that must change together: position frame, normal frame, UV face swap
 * (UP / DOWN), UV permutation per face direction, and triangle winding. This test exercises each on the
 * simplest possible input so a defect in one shows as a focused assertion failure rather than a
 * downstream entity-render regression. Guarding against: a Y reflection landing inside the kit without
 * the matching winding reversal, changing UV-permutation arrays without the UP / DOWN face swap, and
 * breaking the atlas-layout coefficients in {@link Unwrap.Atlas#rect}.
 * <p>
 * The UV tests pin that a cube's faces resolve against the texture size of the draw reading them, each
 * cube of a bone against its own atlas origin, and that a mirrored cube's EAST and WEST polygons sample
 * each other's strips, both against a reference resolution held in the test and against the polygon
 * {@link BoneKit#resolvePolygonUv} answers the bounds walk.
 * <p>
 * The normal tests pin how a stored normal turns under a pose scale, on a turned bone below a scaled
 * one: by the chain's inverse-transpose below a non-uniform scale, as vanilla's normal matrix turns it,
 * and by the chain itself, bit for bit, below a uniform scale or none - a carried block's placement
 * included - with the bones whose chain the non-uniform test reads pinned beside them.
 * <p>
 * The anchor tests pin where a carried block stands: on the seated container, then the attached
 * part's own step and none of its ancestors', as vanilla's carrying layers call the part's
 * {@code translateAndRotate} on the stack they were handed - and that every shipped carrying row
 * anchors exactly where the part's whole chain does, its attached part hanging from nothing but the
 * seat.
 * <p>
 * The emission tests pin what the kit hangs on each triangle beside its geometry and how many it
 * emits: the exact {@code bone:direction} debug tag, a back-face cull that is the mesh's own flag
 * whatever the cube samples, a translucency flag decided per cube from the texture each build is
 * handed, the pass each build is handed, and every face a multi-cube bone owes around a plane cube.
 * <p>
 * The shared-geometry tests pin that a pass reading per-face geometry back from the table the builds
 * of one draw share emits, bit for bit, what the same pass emits built alone: over a mesh equal to a
 * held one but posed at another scale, over sheets that disagree on translucency, over a sheet
 * of another size under a mesh declaring no texture size - one of them as wide and less tall - through
 * another frame, over a mesh whose first cube-bearing bone rests hidden, over plane cubes whose leading
 * faces the full build skips, and on the dragon's eyes pass and the villager's overlay passes, posed
 * instances included.
 * <p>
 * The list tests pin the triangle list the kit hands back: a growable concurrent list the layer fold
 * appends to, a full build's and a replay's alike, a new one for every build, holding element for
 * element and in order what a frozen copy of the walk filling a locked list one add at a time builds,
 * over every shipped mesh.
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

    /** The default pass through one frame instance, so every build a shared table sees can hit it. */
    private static final EntityGeometryKit.EntityBuildParams DEFAULT_PASS = new EntityGeometryKit.EntityBuildParams(
        new FitFrame(Vector3f.ZERO, 1f, 1f), PassDeclaration.DEFAULT, ColorMath.WHITE);

    /** The build {@link #theKitBuildsWhatTheLockedWalkBuilt} compares: an off-centre fit, a pre-scale and a tint. */
    private static final EntityGeometryKit.EntityBuildParams WALK_PARAMS = new EntityGeometryKit.EntityBuildParams(
        new FitFrame(new Vector3f(1f, -2f, 3f), 0.05f, 1.25f), PassDeclaration.DEFAULT, 0xFFCC8844);

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
     * Pins that a cube's faces resolve against the texture size of the draw reading them. A mesh that
     * declares no texture size normalises its UVs by the texture it is drawn with, so one mesh drawn
     * against a {@code 64x32} sheet and then a {@code 64x64} one samples two sets of coordinates. Each
     * draw must equal the same draw of a freshly built mesh, and each face must carry the UVs
     * {@link #referencePolygonUv} resolves. The two sheets share a width, so corners held across draws
     * under a key short of both dimensions answer the second draw with the first's, and the reference
     * check catches that even where the fresh mesh, equal to the first by value, is answered the same.
     */
    @Test
    @DisplayName("a mesh without a texture size draws each texture as a fresh mesh does")
    void aMeshWithoutATextureSizeDrawsEachTextureAsAFreshMeshDoes() {
        EntityMesh mesh = uvFixture();
        PixelBuffer shallow = uvTexture(32);
        PixelBuffer deep = uvTexture(64);

        List<VisibleTriangle> first = collect(EntityGeometryKit.buildTriangles(mesh, shallow));
        List<VisibleTriangle> second = collect(EntityGeometryKit.buildTriangles(mesh, deep));

        assertThat("the 64x32 draw", first, equalTo(collect(EntityGeometryKit.buildTriangles(uvFixture(), shallow))));
        assertThat("the 64x64 draw", second, equalTo(collect(EntityGeometryKit.buildTriangles(uvFixture(), deep))));
        assertPolygonUvs(mesh, first, 64f, 32f);
        assertPolygonUvs(mesh, second, 64f, 64f);
        assertThat("the two sheets sample different coordinates", uvs(first), not(equalTo(uvs(second))));
    }

    /**
     * Pins the mirrored cube's strip swap: vanilla's cube constructor swaps a mirrored cube's {@code x}
     * and {@code maxX}, so its EAST polygon samples the WEST strip and its WEST polygon the EAST strip,
     * while the translucency test before them reads every face's own strip. The cube is opaque, so the
     * translucency test reads all six faces before any polygon is drawn. Every polygon is checked bit
     * for bit against {@link #referencePolygonUv}, and the two side polygons against the strip each must
     * sample, so a polygon answered from its own face's strip fails on the swap itself.
     */
    @Test
    @DisplayName("a mirrored cube draws its EAST face from the WEST strip and its WEST face from the EAST strip")
    void aMirroredCubeDrawsEachSideFromTheOppositeStrip() {
        EntityMesh.Cube cube = uvCube(new Vector3f(3f, 5f, 2f), new Vector2f(0f, 16f), true, Concurrent.newMap());
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("limb", uvBone(Vector3f.ZERO, cube));
        EntityMesh mesh = new EntityMesh(TextureSize.DEFAULT, bones, false);

        List<VisibleTriangle> triangles = collect(EntityGeometryKit.buildTriangles(mesh, solidWhite()));

        assertPolygonUvs(mesh, triangles, 64f, 64f);
        Unwrap.Atlas atlas = new Unwrap.Atlas(cube.getUv(), cube.getSize(), cube.isMirror());
        assertThat("the two side strips differ", atlas.rect(Face.EAST), not(equalTo(atlas.rect(Face.WEST))));
        assertUSpan(triangles, "limb:east", atlas.rect(Face.WEST));
        assertUSpan(triangles, "limb:west", atlas.rect(Face.EAST));
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
        PixelBuffer diagonal = PixelBuffer.ofStraight(pixels, 64, 64);
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
        StripMemo strips = new StripMemo();
        for (Entity entity : shipped.values()) {
            if (entity.blockOverlays().isEmpty()) continue;
            Map<String, EntityMesh.Bone> loaded = entity.model().getBones();
            List<PoseStyle> rows = new ArrayList<>(entity.styles().styles());
            rows.add(StyleCatalog.bind());
            rows.add(entity.styles().resolve(PoseStyle.IDLE, row -> false, entity.id().toString()));
            rows.add(entity.styles().resolve(PoseStyle.STRIDE, row -> false, entity.id().toString()));
            for (PoseStyle style : rows) {
                String id = style.id();
                StripFit strip = PoseStrips.of(strips, entity, style, entity.styles().periodTicks());
                for (int frame = 0; frame < strip.frames(); frame++) {
                    int tick = frame * strip.ticksPerFrame();
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

    /**
     * Pins every debug tag the kit emits to its exact spelling, {@code bone:direction} with the direction
     * lowercase, in emission order: bone by bone, cube by cube, and each face's two triangles in face
     * declaration order. The normal tests read a tag back through {@link Face#fromName}, which ignores
     * case, so only an exact comparison holds the spelling. Two cube-bearing bones of two cubes each hold
     * that a tag names the bone whose cube emits it, whichever of the bone's cubes that is.
     */
    @Test
    @DisplayName("every debug tag is spelled bone:direction exactly, for each cube of each bone")
    void debugTagsSpellTheBoneAndTheLowercaseDirection() {
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", cubeBone(unitCubeAt(0f), unitCubeAt(8f)));
        bones.put("head", cubeBone(unitCubeAt(16f), unitCubeAt(24f)));
        EntityMesh mesh = new EntityMesh(TextureSize.DEFAULT, bones, false);

        List<String> expected = new ArrayList<>();
        for (String bone : List.of("body", "head"))
            for (int cube = 0; cube < 2; cube++)
                for (String direction : List.of("down", "up", "north", "south", "west", "east")) {
                    expected.add(bone + ":" + direction);
                    expected.add(bone + ":" + direction);
                }

        assertThat(tags(collect(EntityGeometryKit.buildTriangles(mesh, solidWhite()))), equalTo(expected));
    }

    /**
     * Pins that a cube culls its back faces exactly when its mesh declares {@link EntityMesh#isCull()
     * cull}, and that no texel it samples moves that. One bone holds a cube over the opaque half of a
     * sheet, a cube over its clear half and a plane cube, so a cull read off the texture would cull the
     * first and draw the other two two-sided, and over a wholly opaque or a wholly clear sheet would
     * split the cubes another way again. A mesh declaring no cull draws every triangle two-sided and one
     * declaring it culls every triangle, built alone and replayed through a shared table alike.
     */
    @Test
    @DisplayName("a cube culls its back faces exactly when its mesh declares cull, whatever its texels")
    void aCubeCullsBackFacesExactlyWhenItsMeshDeclaresCull() {
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", cubeBone(unitCubeAt(0f), unitCubeAt(32f), planeCubeAt(8f)));
        Map<String, PixelBuffer> sheets = new LinkedHashMap<>();
        sheets.put("an opaque sheet", solidWhite());
        sheets.put("an opaque half and a clear half", halves(0xFFFFFFFF, 0x00000000));
        sheets.put("a clear sheet", filled(0x00FFFFFF, 64));
        sheets.put("an opaque half and a half-alpha half", halves(0xFFFFFFFF, 0x80FFFFFF));

        for (boolean cull : List.of(false, true)) {
            EntityMesh mesh = new EntityMesh(TextureSize.DEFAULT, bones, cull);
            Map<EntityMesh, Object> table = EntityGeometryKit.sharedGeometry();
            for (Map.Entry<String, PixelBuffer> sheet : sheets.entrySet()) {
                String where = (cull ? "a mesh declaring cull" : "a mesh declaring none") + " over " + sheet.getKey();
                List<VisibleTriangle> alone = freshPass(mesh, sheet.getValue(), DEFAULT_PASS);
                List<VisibleTriangle> shared = collect(EntityGeometryKit.buildTriangles(mesh, sheet.getValue(), DEFAULT_PASS, table));
                for (List<VisibleTriangle> built : List.of(alone, shared)) {
                    assertThat(where + ": every face the bone owes", built.size(), equalTo(12 + 12 + 4));
                    for (VisibleTriangle triangle : built) {
                        assertThat(where + ": " + triangle.debugTag(), triangle.traits().cullBackFaces(), equalTo(cull));
                        // A culled cube is never a translucent shell, on the replayed build as on the fresh one.
                        if (cull) {
                            assertThat(where + ": " + triangle.debugTag() + " is no translucent shell",
                                triangle.traits().translucent(), equalTo(false));
                        }
                    }
                }
            }
        }
    }

    /**
     * Pins the surface traits as decided per cube and per build. One bone's two cubes sample the two
     * halves of one texture and take two traits values; the same mesh drawn again under other textures
     * takes each texture's own, and drawn under another pass takes that pass. The translucency flag reads
     * the texture a build is handed, so traits carried over from a sibling cube, or from an earlier build
     * of the mesh, would draw a pass with another surface's sort, or under another pass. A mesh declaring
     * cull draws every cube culled and none of them as a translucent shell, whatever the texture.
     */
    @Test
    @DisplayName("surface traits follow each cube's texels in each build, not its bone's or an earlier build's")
    void surfaceTraitsAreDecidedPerCubeAndPerBuild() {
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", cubeBone(unitCubeAt(0f), unitCubeAt(32f)));
        EntityMesh mesh = new EntityMesh(TextureSize.DEFAULT, bones, false);
        SurfaceTraits solid = new SurfaceTraits(false, false, false, true, PassDeclaration.DEFAULT);
        SurfaceTraits translucent = new SurfaceTraits(false, true, false, true, PassDeclaration.DEFAULT);

        assertTraitsPerCube(mesh, halves(0xFFFFFFFF, 0x80FFFFFF), solid, translucent);
        assertTraitsPerCube(mesh, halves(0xFFFFFFFF, 0xFFFFFFFF), solid, solid);
        assertTraitsPerCube(mesh, halves(0x80FFFFFF, 0x00000000), translucent, solid);

        EntityMesh culling = new EntityMesh(TextureSize.DEFAULT, bones, true);
        SurfaceTraits culled = new SurfaceTraits(true, false, false, true, PassDeclaration.DEFAULT);
        assertTraitsPerCube(culling, halves(0xFFFFFFFF, 0x80FFFFFF), culled, culled);
        assertTraitsPerCube(culling, halves(0x80FFFFFF, 0x00000000), culled, culled);

        // The pass is each build's own too: one mesh and one texture built under two passes in turn
        // carry the pass each build is handed, never the one before.
        PassDeclaration emissive = PassDeclaration.DEFAULT.withEmissive(true);
        assertThat("the two passes differ", emissive, not(equalTo(PassDeclaration.DEFAULT)));
        FitFrame frame = new FitFrame(Vector3f.ZERO, 1f, 1f);
        PixelBuffer texture = halves(0xFFFFFFFF, 0x80FFFFFF);
        for (PassDeclaration pass : List.of(PassDeclaration.DEFAULT, emissive, PassDeclaration.DEFAULT)) {
            List<VisibleTriangle> built = collect(EntityGeometryKit.buildTriangles(mesh, texture,
                new EntityGeometryKit.EntityBuildParams(frame, pass, ColorMath.WHITE)));
            assertThat("both cubes draw all six faces", built.size(), equalTo(24));
            for (VisibleTriangle triangle : built)
                assertThat("the pass of a triangle", triangle.traits().pass(), sameInstance(pass));
        }
    }

    // --- the triangle list the kit hands back ---

    /**
     * Pins that the kit hands back a {@link ConcurrentArrayList} that stays growable. The list is the
     * sink the entity renderer folds every feature layer into and a caller's layer decorator appends
     * to, so a list resting on a fixed-size or unmodifiable backing refuses the first overlay.
     */
    @Test
    @DisplayName("the kit's triangles are a growable concurrent list a geometry layer appends to")
    void theKitsTrianglesAreAGrowableSinkAGeometryLayerAppendsTo() {
        ConcurrentList<VisibleTriangle> triangles = buildSingleCube().triangles();
        assertThat("the kit's list type", triangles.getClass(), equalTo(ConcurrentArrayList.class));

        List<VisibleTriangle> built = List.copyOf(triangles);
        GeometryLayer overlay = sink -> {
            sink.addAll(built);
            sink.add(built.getFirst());
        };
        overlay.contribute(triangles);

        List<VisibleTriangle> folded = new ArrayList<>(triangles);
        assertThat("triangles after the fold", folded.size(), equalTo(2 * built.size() + 1));
        assertThat("the kit's own triangles lead", folded.subList(0, built.size()), equalTo(built));
        assertThat("the overlay's follow", folded.subList(built.size(), 2 * built.size()), equalTo(built));
        assertThat("the single add lands last", folded.getLast(), equalTo(built.getFirst()));
    }

    /**
     * Pins that every build hands back a list of its own, a full build and a replay through a shared
     * table alike. A later build on the same thread leaves a list an earlier one handed back as it was,
     * because the layer fold may still be appending to it, and a replay's list takes a geometry layer's
     * appends as a full build's does.
     */
    @Test
    @DisplayName("each build hands back a new list, and a later build leaves an earlier one as it was")
    void eachBuildHandsBackANewList() {
        ConcurrentList<VisibleTriangle> first = buildSingleCube().triangles();
        int size = first.size();
        VisibleTriangle head = first.getFirst();

        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", cubeBone(unitCubeAt(0f), unitCubeAt(8f)));
        ConcurrentList<VisibleTriangle> second = EntityGeometryKit.buildTriangles(
            new EntityMesh(TextureSize.DEFAULT, bones, false), solidWhite()).triangles();

        assertThat("the second build's list", second, not(sameInstance(first)));
        assertThat("the second build draws another mesh", second.size(), not(equalTo(size)));
        assertThat("the first list keeps its size", first.size(), equalTo(size));
        assertThat("and its first triangle", first.getFirst(), sameInstance(head));

        EntityMesh mesh = carrier(true);
        Map<EntityMesh, Object> table = EntityGeometryKit.sharedGeometry();
        ConcurrentList<VisibleTriangle> recorded = EntityGeometryKit.buildTriangles(mesh, solidWhite(), DEFAULT_PASS, table)
            .triangles();
        ConcurrentList<VisibleTriangle> replayed = EntityGeometryKit.buildTriangles(mesh, solidWhite(), DEFAULT_PASS, table)
            .triangles();
        assertThat("the replay reads the held corners",
            replayed.getFirst().position0(), sameInstance(recorded.getFirst().position0()));
        assertThat("a replay's list", replayed, not(sameInstance(recorded)));
        assertThat("is a growable concurrent list too", replayed.getClass(), equalTo(ConcurrentArrayList.class));

        List<VisibleTriangle> replay = List.copyOf(replayed);
        GeometryLayer overlay = sink -> {
            sink.addAll(replay);
            sink.add(replay.getFirst());
        };
        overlay.contribute(replayed);

        List<VisibleTriangle> folded = new ArrayList<>(replayed);
        assertThat("replayed triangles after the fold", folded.size(), equalTo(2 * replay.size() + 1));
        assertThat("the replayed triangles lead", folded.subList(0, replay.size()), equalTo(replay));
        assertThat("the overlay's follow", folded.subList(replay.size(), 2 * replay.size()), equalTo(replay));
        assertThat("the single add lands last", folded.getLast(), equalTo(replay.getFirst()));
    }

    /**
     * Pins the kit's triangle list, element for element and in order, against {@link #lockedWalk}, a
     * frozen copy of the walk filling a locked list one add at a time. It runs over every shipped mesh
     * at rest and over {@link #mixedMesh} with and without the mesh-wide cull, each through an opaque
     * texture and through {@link #tiledAlpha}, so the cull and translucency choices a face carries vary
     * across the corpus and a bone, cube or face the list drops, repeats or reorders shows as a
     * difference.
     */
    @Test
    @DisplayName("the kit builds, triangle for triangle and in order, what the locked walk built")
    void theKitBuildsWhatTheLockedWalkBuilt() {
        Map<String, EntityMesh> meshes = new LinkedHashMap<>();
        meshes.put("mixed", mixedMesh(false));
        meshes.put("mixed culled", mixedMesh(true));
        for (Map.Entry<String, Entity> entry : EntityModelLoader.load().entrySet())
            meshes.put(entry.getKey(), entry.getValue().model());
        assertThat("shipped meshes are compared beside the hand-built ones", meshes.size(), greaterThan(2));

        Map<String, PixelBuffer> textures = Map.of("opaque", solidWhite(), "tiled", tiledAlpha());
        int compared = 0;
        for (Map.Entry<String, EntityMesh> mesh : meshes.entrySet()) {
            for (Map.Entry<String, PixelBuffer> texture : textures.entrySet()) {
                String where = mesh.getKey() + " through the " + texture.getKey() + " texture";
                ConcurrentList<VisibleTriangle> kit = EntityGeometryKit.buildTriangles(
                    mesh.getValue(), texture.getValue(), WALK_PARAMS).triangles();
                assertEqualTriangles(where, kit, lockedWalk(mesh.getValue(), texture.getValue(), WALK_PARAMS));
                compared += kit.size();
            }
        }
        assertThat("triangles compared", compared, greaterThan(0));
    }

    /** Asserts two triangle lists hold equal triangles in the same order, naming the first that differs. */
    private static void assertEqualTriangles(String where, List<VisibleTriangle> actual, List<VisibleTriangle> expected) {
        assertThat(where + ": triangle count", actual.size(), equalTo(expected.size()));
        for (int i = 0; i < expected.size(); i++) {
            if (!actual.get(i).equals(expected.get(i)))
                assertThat(where + ": triangle " + i, actual.get(i), equalTo(expected.get(i)));
        }
    }

    /**
     * Pins what a multi-cube bone emits with a plane cube between two whole ones: both triangles of all
     * six faces of each whole cube and of the plane's two faces that carry area, then the next bone in
     * full. The plane's first face is one it skips, so a skip that left the face walk or the build,
     * rather than passing to the next face, drops geometry the mesh is owed.
     */
    @Test
    @DisplayName("a multi-cube bone emits every face it owes around a plane cube, and the next bone in full")
    void aMultiCubeBoneEmitsEveryFaceAroundAPlaneCube() {
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", cubeBone(unitCubeAt(0f), planeCubeAt(8f), unitCubeAt(16f)));
        bones.put("head", cubeBone(unitCubeAt(24f)));
        EntityMesh mesh = new EntityMesh(TextureSize.DEFAULT, bones, false);

        List<String> tags = tags(collect(EntityGeometryKit.buildTriangles(mesh, solidWhite())));

        assertThat("every triangle the mesh owes", tags.size(), equalTo(12 + 4 + 12 + 12));
        assertThat("the plane's two faces, between the whole cubes", tags.subList(12, 16),
            equalTo(List.of("body:north", "body:north", "body:south", "body:south")));
        assertThat("the next bone's cube", tags.subList(28, 40).stream().distinct().count(), equalTo(6L));
    }

    // --- shared geometry ---

    /**
     * Pins that the shared table answers a mesh by reference: a mesh equal to a held one in every field
     * equality reads, but posed at another scale, is built afresh. A table keyed by equality hands the
     * stretched mesh the unstretched one's corners and normals.
     */
    @Test
    @DisplayName("a mesh equal to a held one but posed at another scale is built afresh")
    void sharedGeometry_poseScaleVariantIsBuiltAfresh() {
        EntityMesh unstretched = turnedUnder(new Vector3f(1f, 1f, 1f), PITCH);
        EntityMesh stretched = turnedUnder(STRETCH, PITCH);
        assertThat("equality reads no pose scale", stretched, equalTo(unstretched));
        assertThat("the two are distinct instances", stretched, not(sameInstance(unstretched)));
        assertThat("the pose scale moves the geometry",
            anyPositionDiffers(freshPass(stretched, solidWhite(), DEFAULT_PASS), freshPass(unstretched, solidWhite(), DEFAULT_PASS)),
            equalTo(true));

        Map<EntityMesh, Object> table = EntityGeometryKit.sharedGeometry();
        assertSharedMatchesFresh("unstretched", unstretched, solidWhite(), DEFAULT_PASS, table);
        assertSharedMatchesFresh("stretched", stretched, solidWhite(), DEFAULT_PASS, table);
    }

    /**
     * Pins that every pass over a held mesh decides its own translucency flag from its own texture, and
     * takes its cull from the mesh. One mesh declaring no cull is drawn through one table against an
     * opaque sheet (opaque), a clear one (cutout) and a half-alpha one (translucent), each two-sided, so a
     * translucency flag carried from the first pass to a later one shows on the third.
     */
    @Test
    @DisplayName("each pass over a held mesh reads its translucency off its own texture")
    void sharedGeometry_flagsArePerPass() {
        EntityMesh mesh = carrier(true);
        Map<EntityMesh, Object> table = EntityGeometryKit.sharedGeometry();
        List<VisibleTriangle> opaque = assertSharedMatchesFresh("opaque", mesh, filled(0xFFFFFFFF, 64), DEFAULT_PASS, table);
        List<VisibleTriangle> clear = assertSharedMatchesFresh("clear", mesh, filled(0x00FFFFFF, 64), DEFAULT_PASS, table);
        List<VisibleTriangle> half = assertSharedMatchesFresh("half", mesh, filled(0x80FFFFFF, 64), DEFAULT_PASS, table);

        assertThat("the clear pass replays the opaque pass's corners",
            clear.getFirst().position0(), sameInstance(opaque.getFirst().position0()));
        assertThat("the half-alpha pass replays them too",
            half.getFirst().position0(), sameInstance(opaque.getFirst().position0()));
        assertThat("an opaque sheet draws a mesh declaring no cull two-sided",
            opaque.getFirst().traits().cullBackFaces(), equalTo(false));
        assertThat("and so does a clear sheet", clear.getFirst().traits().cullBackFaces(), equalTo(false));
        assertThat("and is no translucent shell", clear.getFirst().traits().translucent(), equalTo(false));
        assertThat("a half-alpha sheet is one", half.getFirst().traits().translucent(), equalTo(true));
    }

    /**
     * Pins that held polygon UVs are reused only against the dimensions they were resolved at. A mesh
     * declaring no texture size resolves its UVs against the sheet it draws, so an overlay sheet of
     * another size resolves them afresh; a mesh declaring one resolves every sheet against that size and
     * shares them. Two sheets of one width and different heights go through a table of their own, so UVs
     * reused whenever the width alone matches hand the taller sheet the shorter one's.
     */
    @Test
    @DisplayName("a mesh declaring no texture size resolves its UVs afresh against a sheet of another size")
    void sharedGeometry_undeclaredSizeResolvesUvsPerSheet() {
        EntityMesh declared = carrier(true);
        EntityMesh undeclared = new EntityMesh(new TextureSize(0, 0), declared.getBones(), declared.isCull());
        PixelBuffer small = filled(0xFFFFFFFF, 64);
        PixelBuffer large = filled(0xFFFFFFFF, 128);
        assertThat("the undeclared mesh's UVs follow the sheet",
            freshPass(undeclared, small, DEFAULT_PASS).getFirst().uv1(),
            not(equalTo(freshPass(undeclared, large, DEFAULT_PASS).getFirst().uv1())));

        PixelBuffer shallow = uvTexture(32);
        PixelBuffer deep = uvTexture(64);
        assertThat("the undeclared mesh's UVs follow the sheet's height alone",
            uvs(freshPass(undeclared, shallow, DEFAULT_PASS)), not(equalTo(uvs(freshPass(undeclared, deep, DEFAULT_PASS)))));
        Map<EntityMesh, Object> oneWidth = EntityGeometryKit.sharedGeometry();
        assertSharedMatchesFresh("undeclared, 64x32 sheet", undeclared, shallow, DEFAULT_PASS, oneWidth);
        assertSharedMatchesFresh("undeclared, 64x64 sheet", undeclared, deep, DEFAULT_PASS, oneWidth);

        Map<EntityMesh, Object> table = EntityGeometryKit.sharedGeometry();
        assertSharedMatchesFresh("undeclared, small sheet", undeclared, small, DEFAULT_PASS, table);
        assertSharedMatchesFresh("undeclared, large sheet", undeclared, large, DEFAULT_PASS, table);
        List<VisibleTriangle> declaredSmall = assertSharedMatchesFresh("declared, small sheet", declared, small, DEFAULT_PASS, table);
        List<VisibleTriangle> declaredLarge = assertSharedMatchesFresh("declared, large sheet", declared, large, DEFAULT_PASS, table);
        assertThat("a declared size shares its UVs across sheets",
            declaredLarge.getFirst().uv1(), sameInstance(declaredSmall.getFirst().uv1()));
    }

    /**
     * Pins that the table answers a held mesh only for the frame it was fitted through: the same mesh
     * built through another frame is built afresh rather than handed corners transformed through the
     * first.
     */
    @Test
    @DisplayName("a held mesh built through another frame is built afresh")
    void sharedGeometry_anotherFrameIsBuiltAfresh() {
        EntityMesh mesh = carrier(true);
        FitFrame other = new FitFrame(new Vector3f(1f, 2f, 3f), 0.5f, 2f);
        Map<EntityMesh, Object> table = EntityGeometryKit.sharedGeometry();
        assertSharedMatchesFresh("first frame", mesh, solidWhite(), DEFAULT_PASS, table);
        assertSharedMatchesFresh("other frame", mesh, solidWhite(), new EntityGeometryKit.EntityBuildParams(
            other, PassDeclaration.DEFAULT, ColorMath.WHITE), table);
    }

    /**
     * Pins replay over a mesh whose first cube-bearing bone rests hidden ahead of a visible one. The
     * hidden bone holds no slots, so a replay that walked it would read the visible bone's slots for it
     * and then run past the held arrays; skipping it, the visible bone replays its own faces, against an
     * opaque sheet and then a clear one.
     */
    @Test
    @DisplayName("a held mesh with a hidden bone ahead of a visible one replays the visible bone's own faces")
    void sharedGeometry_hiddenBoneIsSkippedOnReplay() {
        EntityMesh carrier = carrier(false);
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", carrier.getBones().get("body").withVisible(false));
        bones.put("head", carrier.getBones().get("head"));
        EntityMesh mesh = new EntityMesh(TextureSize.DEFAULT, bones, false);

        Map<EntityMesh, Object> table = EntityGeometryKit.sharedGeometry();
        List<VisibleTriangle> opaque = assertSharedMatchesFresh("opaque", mesh, filled(0xFFFFFFFF, 64), DEFAULT_PASS, table);
        List<VisibleTriangle> clear = assertSharedMatchesFresh("clear", mesh, filled(0x00FFFFFF, 64), DEFAULT_PASS, table);
        assertThat("only the visible bone draws",
            tags(opaque).stream().allMatch(tag -> tag.startsWith("head:")), equalTo(true));
        assertThat("the clear pass replays the opaque pass's corners",
            clear.getFirst().position0(), sameInstance(opaque.getFirst().position0()));
    }

    /**
     * Pins replay over plane cubes whose first faces are ones the full build skips. A plane facing along
     * z draws only its north and south faces and one facing along x only its west and east, so their
     * leading slots hold nothing; a replay that left a cube's face walk at an empty slot, rather than
     * passing to the next, would drop every face either plane carries while the whole cubes around them
     * still replayed.
     */
    @Test
    @DisplayName("a held mesh replays a plane cube's faces past the slots its full build skipped")
    void sharedGeometry_planeCubeReplaysPastItsSkippedSlots() {
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", cubeBone(unitCubeAt(0f), planeCubeAt(8f), unitCubeAt(16f)));
        bones.put("head", cubeBone(sidePlaneCubeAt(24f), unitCubeAt(32f)));
        EntityMesh mesh = new EntityMesh(TextureSize.DEFAULT, bones, false);

        Map<EntityMesh, Object> table = EntityGeometryKit.sharedGeometry();
        List<VisibleTriangle> opaque = assertSharedMatchesFresh("opaque", mesh, filled(0xFFFFFFFF, 64), DEFAULT_PASS, table);
        List<VisibleTriangle> clear = assertSharedMatchesFresh("clear", mesh, filled(0x00FFFFFF, 64), DEFAULT_PASS, table);
        assertThat("the clear pass replays the opaque pass's corners",
            clear.getFirst().position0(), sameInstance(opaque.getFirst().position0()));

        List<String> tags = tags(clear);
        assertThat("every triangle the mesh owes", tags.size(), equalTo(12 + 4 + 12 + 4 + 12));
        assertThat("the z-facing plane's two faces", tags.subList(12, 16),
            equalTo(List.of("body:north", "body:north", "body:south", "body:south")));
        assertThat("the x-facing plane's two faces", tags.subList(28, 32),
            equalTo(List.of("head:west", "head:west", "head:east", "head:east")));
    }

    /**
     * Pins the shared build on the dragon, whose eyes pass draws the body's own mesh instance through an
     * emissive pass over a mostly transparent sheet, so the two passes draw under different pass
     * declarations while agreeing on every corner, and on every cube's cull, which is the mesh's. A posed
     * subject poses the body and the eyes as two separate rebuilds, so neither is the bind mesh the table
     * holds and both are built afresh.
     */
    @Test
    @DisplayName("the dragon's eyes pass replays the body's geometry under its own pass")
    void sharedGeometry_dragonEyesPass() {
        assumeTrue(ClientAssetsExtension.isCached(), () -> "no cached client jar at '"
            + ClientAssetsExtension.jar() + "' - ClientJarGuardTest names what writes one");
        RendererContext context = ClientAssetsExtension.context();
        Entity dragon = context.findEntity("minecraft:ender_dragon").get();
        Entity.OverlayLayer eyes = dragon.overlays().getFirst();
        assertThat("the eyes pass draws the body's own mesh", eyes.model(), sameInstance(dragon.model()));
        PixelBuffer bodySheet = entityTexture(context, dragon.textureRef().orElseThrow());
        PixelBuffer eyesSheet = entityTexture(context, eyes.textureRef().orElseThrow());
        EntityGeometryKit.EntityBuildParams eyesPass = new EntityGeometryKit.EntityBuildParams(
            DEFAULT_PASS.frame(), eyes.pass(), eyes.tintArgb());

        Map<EntityMesh, Object> table = EntityGeometryKit.sharedGeometry();
        List<VisibleTriangle> body = assertSharedMatchesFresh("body", dragon.model(), bodySheet, DEFAULT_PASS, table);
        List<VisibleTriangle> glow = assertSharedMatchesFresh("eyes", dragon.model(), eyesSheet, eyesPass, table);
        assertThat("the eyes pass replays the body's corners",
            glow.getFirst().position0(), sameInstance(body.getFirst().position0()));
        // The two sheets agree on every cube's translucency, so the pass is what tells the eyes pass's
        // traits from the body's.
        assertThat("the two passes draw under different pass declarations",
            glow.getFirst().traits().pass(), not(equalTo(body.getFirst().traits().pass())));

        PoseStyle idle = dragon.styles().resolve(PoseStyle.IDLE, row -> false, dragon.id().toString());
        Entity posed = PosePlayer.posed(dragon, idle, dragon.styles().periodTicks(), 5);
        EntityMesh posedEyes = posed.overlays().getFirst().model();
        assertThat("the posed body is another instance", posed.model(), not(sameInstance(dragon.model())));
        assertThat("and so are its posed eyes", posedEyes, not(sameInstance(posed.model())));
        assertSharedMatchesFresh("posed body", posed.model(), bodySheet, DEFAULT_PASS, table);
        assertSharedMatchesFresh("posed eyes", posedEyes, eyesSheet, eyesPass, table);
    }

    /**
     * Pins the shared build on the villager, whose type, profession and level passes all draw the
     * body's own mesh instance: each sheet through one table, a sheet twice the size among them, then
     * the same mesh with its texture size undeclared over a sheet of each size, then the mesh as a pose
     * leaves it - each matching the pass built alone.
     */
    @Test
    @DisplayName("the villager's overlay passes replay its body's geometry, over a larger sheet and posed")
    void sharedGeometry_villagerOverlays() {
        assumeTrue(ClientAssetsExtension.isCached(), () -> "no cached client jar at '"
            + ClientAssetsExtension.jar() + "' - ClientJarGuardTest names what writes one");
        RendererContext context = ClientAssetsExtension.context();
        Entity villager = context.findEntity("minecraft:villager").get();
        EntityMesh mesh = villager.model();
        for (Entity.OverlayLayer overlay : villager.overlays())
            assertThat("every villager pass draws the body's own mesh", overlay.model(), sameInstance(mesh));
        PixelBuffer bodySheet = entityTexture(context, villager.textureRef().orElseThrow());
        PixelBuffer doubled = doubled(bodySheet);

        Map<EntityMesh, Object> table = EntityGeometryKit.sharedGeometry();
        List<VisibleTriangle> body = assertSharedMatchesFresh("body", mesh, bodySheet, DEFAULT_PASS, table);
        for (String ref : List.of("villager/type/plains", "villager/profession/librarian", "villager/profession_level/diamond")) {
            List<VisibleTriangle> pass = assertSharedMatchesFresh(ref, mesh, entityTexture(context, ref),
                new EntityGeometryKit.EntityBuildParams(DEFAULT_PASS.frame(), villager.overlays().getFirst().pass(),
                    ColorMath.WHITE), table);
            assertThat(ref + " replays the body's corners", pass.getFirst().position0(), sameInstance(body.getFirst().position0()));
        }
        assertSharedMatchesFresh("a sheet twice the size", mesh, doubled, DEFAULT_PASS, table);

        EntityMesh undeclared = new EntityMesh(new TextureSize(0, 0), mesh.getBones(), mesh.isCull());
        assertSharedMatchesFresh("undeclared size, its own sheet", undeclared, bodySheet, DEFAULT_PASS, table);
        assertSharedMatchesFresh("undeclared size, a sheet twice the size", undeclared, doubled, DEFAULT_PASS, table);

        PoseStyle stride = villager.styles().resolve(PoseStyle.STRIDE, row -> false, villager.id().toString());
        EntityMesh posed = PosePlayer.posed(villager, stride, villager.styles().periodTicks(), 5).model();
        assertThat("the posed mesh is another instance", posed, not(sameInstance(mesh)));
        assertSharedMatchesFresh("posed", posed, bodySheet, DEFAULT_PASS, table);
    }

    /**
     * A frozen copy of {@link EntityGeometryKit#buildTriangles(EntityMesh, PixelBuffer, EntityGeometryKit.EntityBuildParams)}
     * filling a locked {@link Concurrent#newList() concurrent list} one add at a time, with the quad split
     * and the cull and translucency choices copied beside it - the reference the kit's own build is held
     * to.
     */
    private static ConcurrentList<VisibleTriangle> lockedWalk(
        EntityMesh model, PixelBuffer texture, EntityGeometryKit.EntityBuildParams params) {
        FitFrame frame = params.frame();
        Vector3f centre = frame.anchor();
        float scale = frame.ndcScale();
        float modelScale = frame.modelScale();
        PassDeclaration pass = params.pass();
        int tintArgb = params.tintArgb();

        Map<String, Matrix4f> chainTransforms = BoneKit.buildChainTransforms(model.getBones());
        Matrix4f kitFit = Matrix4f.IDENTITY
            .scale(scale, scale, scale)
            .translate(-centre.x(), -centre.y(), -centre.z())
            .scale(modelScale);
        Map<String, Matrix4f> kitFitChainTransforms = BoneKit.buildChainTransformsFrom(kitFit, model.getBones());

        float texW = model.getTextureWidth() > 0 ? model.getTextureWidth() : Math.max(1f, texture.width());
        float texH = model.getTextureHeight() > 0 ? model.getTextureHeight() : Math.max(1f, texture.height());

        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        for (Map.Entry<String, EntityMesh.Bone> boneEntry : model.getBones().entrySet()) {
            String boneName = boneEntry.getKey();
            EntityMesh.Bone bone = boneEntry.getValue();
            if (!bone.isVisible()) continue;
            Matrix4f boneChain = chainTransforms.get(boneName);
            boolean nonUniform = EntityGeometryKit.scalesNonUniformly(model, boneName);
            float s = bone.getScale();
            for (EntityMesh.Cube cube : bone.getCubes()) {
                Vector3f size = cube.getSize();
                Box cubeBounds = BoneKit.scaledCubeBounds(s, cube);
                Matrix4f fullTransform = BoneKit.composeCubeTransform(cube, bone, boneChain);
                Matrix4f perCubeChain = BoneKit.composeCubeTransform(cube, bone, kitFitChainTransforms.get(boneName));
                boolean cull = model.isCull();
                boolean translucent = !cull && lockedPartialAlphaPresent(cube, size, texture, texW, texH);

                Face.forEach(face -> {
                    Vector3f[] corners = CornerPhase.POLYGON.corners(face, cubeBounds);
                    for (int i = 0; i < 4; i++)
                        corners[i] = corners[i].transform(perCubeChain);
                    Vector3f normal = EntityGeometryKit.chainNormal(face.normal(), fullTransform, nonUniform);

                    boolean isPlaneCube = size.x() == 0f || size.y() == 0f || size.z() == 0f;
                    if (isPlaneCube && BoneKit.isDegeneratePlaneFace(size, face)) return;

                    Vector2f[] uv = BoneKit.resolvePolygonUv(face, cube, size, texW, texH);
                    String debugTag = boneName + ":" + face.direction();
                    SurfaceTraits traits = new SurfaceTraits(cull, translucent, false, true, pass);
                    triangles.add(new VisibleTriangle(corners[0], corners[1], corners[2], uv[0], uv[1], uv[2],
                        texture, tintArgb, normal, Shading.UNLIT, traits, debugTag));
                    triangles.add(new VisibleTriangle(corners[0], corners[2], corners[3], uv[0], uv[2], uv[3],
                        texture, tintArgb, normal, Shading.UNLIT, traits, debugTag));
                });
            }
        }
        return triangles;
    }

    /** The kit's translucency choice for a cube drawn without culling, frozen beside {@link #lockedWalk}. */
    private static boolean lockedPartialAlphaPresent(
        EntityMesh.Cube cube, Vector3f size, PixelBuffer texture, float texW, float texH) {
        for (Face face : Face.stream().toList()) {
            if ((size.x() == 0f || size.y() == 0f || size.z() == 0f)
                && BoneKit.isDegeneratePlaneFace(size, face)) continue;
            if (BoneKit.faceHasPartialAlpha(BoneKit.resolveFaceUv(face, cube, size, texW, texH), texture))
                return true;
        }
        return false;
    }

    // --- fixtures ---

    /** A bone at the origin, unturned and unscaled, hung from nothing and owning {@code cubes} in order. */
    private static EntityMesh.Bone cubeBone(EntityMesh.Cube... cubes) {
        ConcurrentList<EntityMesh.Cube> owned = Concurrent.newList();
        owned.addAll(Arrays.asList(cubes));
        return new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f, owned, null);
    }

    /** A 1x1x1 cube centred at the origin whose atlas unwrap starts at {@code (u, 0)}. */
    private static EntityMesh.Cube unitCubeAt(float u) {
        return new EntityMesh.Cube(
            new Vector3f(-0.5f, -0.5f, -0.5f), new Vector3f(1f, 1f, 1f), new Vector2f(u, 0f),
            Vector3f.ZERO, false, Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap());
    }

    /**
     * A 1x1 plane facing along z, centred at the origin, whose atlas unwrap starts at {@code (u, 0)} - its
     * north and south faces carry area and the other four are lines.
     */
    private static EntityMesh.Cube planeCubeAt(float u) {
        return new EntityMesh.Cube(
            new Vector3f(-0.5f, -0.5f, 0f), new Vector3f(1f, 1f, 0f), new Vector2f(u, 0f),
            Vector3f.ZERO, false, Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap());
    }

    /**
     * A 1x1 plane facing along x, centred at the origin, whose atlas unwrap starts at {@code (u, 0)} - its
     * west and east faces carry area and the four ahead of them are lines.
     */
    private static EntityMesh.Cube sidePlaneCubeAt(float u) {
        return new EntityMesh.Cube(
            new Vector3f(0f, -0.5f, -0.5f), new Vector3f(0f, 1f, 1f), new Vector2f(u, 0f),
            Vector3f.ZERO, false, Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap());
    }

    /** The debug tag of each triangle, in emission order. */
    private static List<String> tags(List<VisibleTriangle> triangles) {
        return triangles.stream().map(VisibleTriangle::debugTag).toList();
    }

    /**
     * Asserts the kit draws {@code mesh}'s two cubes under {@code texture} with the first cube's twelve
     * triangles carrying {@code first} and the second's carrying {@code second}.
     */
    private static void assertTraitsPerCube(EntityMesh mesh, PixelBuffer texture, SurfaceTraits first, SurfaceTraits second) {
        List<VisibleTriangle> triangles = collect(EntityGeometryKit.buildTriangles(mesh, texture));
        assertThat("both cubes draw all six faces", triangles.size(), equalTo(24));
        for (int i = 0; i < triangles.size(); i++)
            assertThat("traits of triangle " + i, triangles.get(i).traits(), equalTo(i < 12 ? first : second));
    }

    /** A {@code 64x64} texture painted {@code left} on its left half and {@code right} on its right half. */
    private static PixelBuffer halves(int left, int right) {
        int[] pixels = new int[64 * 64];
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++)
                pixels[y * 64 + x] = x < 32 ? left : right;
        return PixelBuffer.ofStraight(pixels, 64, 64);
    }

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

    /**
     * A mesh holding what the walk skips or sizes past: a cube-less container, a turned bone with a solid
     * cube and a plane cube, an undrawn bone below it, and a child turned on every axis.
     *
     * @param cull whether the mesh culls every cube's back faces
     */
    private static EntityMesh mixedMesh(boolean cull) {
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("root", new EntityMesh.Bone(new Vector3f(0f, 24f, 0f), EulerRotation.NONE,
            EulerRotation.NONE, 1f, Concurrent.newList(), null));
        bones.put("body", new EntityMesh.Bone(new Vector3f(0f, -6f, 0f), new EulerRotation(10f, 0f, 0f),
            EulerRotation.NONE, 1f, cubes(
                cube(new Vector3f(-4f, -6f, -2f), new Vector3f(8f, 12f, 4f), new Vector2f(16f, 16f)),
                cube(new Vector3f(-4f, 0f, 2f), new Vector3f(8f, 6f, 0f), new Vector2f(32f, 0f))), "root"));
        bones.put("hidden", new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f,
            cubes(cube(new Vector3f(-1f, -1f, -1f), new Vector3f(2f, 2f, 2f), new Vector2f(0f, 32f))), "body")
            .withVisible(false));
        bones.put("head", new EntityMesh.Bone(new Vector3f(0f, -6f, 0f), ANY_TURN, EulerRotation.NONE, 1f,
            cubes(cube(new Vector3f(-4f, -8f, -4f), new Vector3f(8f, 8f, 8f), Vector2f.ZERO)), "body"));
        return new EntityMesh(TextureSize.DEFAULT, bones, cull);
    }

    /** An unturned, ungrown, unmirrored cube at an atlas origin, with no per-face overrides. */
    private static EntityMesh.Cube cube(Vector3f origin, Vector3f size, Vector2f uv) {
        return new EntityMesh.Cube(origin, size, uv, Vector3f.ZERO, false, Vector3f.ZERO, EulerRotation.NONE,
            Concurrent.newMap());
    }

    /** The cubes a bone owns, in the order given. */
    private static ConcurrentList<EntityMesh.Cube> cubes(EntityMesh.Cube... cubes) {
        ConcurrentList<EntityMesh.Cube> list = Concurrent.newList();
        for (EntityMesh.Cube cube : cubes) list.add(cube);
        return list;
    }

    /**
     * A {@code 64x64} texture tiled in {@code 4x4} texel squares that run opaque, clear, half-alpha and
     * opaque again along each row, so cubes across the corpus land on every translucency choice the kit
     * makes.
     */
    private static PixelBuffer tiledAlpha() {
        int[] alpha = { 0xFF, 0x00, 0x80, 0xFF };
        int[] pixels = new int[64 * 64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++)
                pixels[y * 64 + x] = alpha[((x >> 2) + (y >> 2)) & 3] << 24 | 0xFFFFFF;
        }
        return PixelBuffer.ofStraight(pixels, 64, 64);
    }

    /** An opaque-white {@code 64x64} texture, so no face of a fixture cube samples a transparent texel. */
    private static PixelBuffer solidWhite() {
        int[] pixels = new int[64 * 64];
        Arrays.fill(pixels, 0xFFFFFFFF);
        return PixelBuffer.ofStraight(pixels, 64, 64);
    }

    /**
     * Builds a mesh of four bones covering each way a face's UV corners resolve - a plain cube followed
     * in its bone by a mirrored cube at an atlas origin of its own, so each cube of a bone must resolve
     * its own corners, a mirrored cube over the half-alpha band of {@link #uvTexture}, so it draws
     * unculled and sorted, a mirrored plane cube, and a mirrored cube whose WEST face carries an
     * override - declaring no texture size, so the corners normalise by the texture each draw passes.
     * Every call builds new cubes.
     *
     * @return the mesh
     */
    private static EntityMesh uvFixture() {
        ConcurrentMap<String, EntityMesh.FaceUv> overridden = Concurrent.newMap();
        overridden.put("west", new EntityMesh.FaceUv(new Vector2f(40f, 20f), new Vector2f(4f, 6f)));
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("plain", uvBone(Vector3f.ZERO,
            uvCube(new Vector3f(4f, 4f, 4f), Vector2f.ZERO, false, Concurrent.newMap()),
            uvCube(new Vector3f(2f, 3f, 2f), new Vector2f(48f, 0f), true, Concurrent.newMap())));
        bones.put("mirrored", uvBone(new Vector3f(8f, 0f, 0f),
            uvCube(new Vector3f(3f, 5f, 2f), new Vector2f(0f, 16f), true, Concurrent.newMap())));
        bones.put("plane", uvBone(new Vector3f(0f, 8f, 0f),
            uvCube(new Vector3f(0f, 4f, 6f), new Vector2f(32f, 0f), true, Concurrent.newMap())));
        bones.put("overridden", uvBone(new Vector3f(0f, 0f, 8f),
            uvCube(new Vector3f(2f, 2f, 2f), new Vector2f(16f, 0f), true, overridden)));
        return new EntityMesh(new TextureSize(0, 0), bones, false);
    }

    /** A root bone at {@code pivot}, unturned and unscaled, holding the cubes in order. */
    private static EntityMesh.Bone uvBone(Vector3f pivot, EntityMesh.Cube... cubes) {
        ConcurrentList<EntityMesh.Cube> held = Concurrent.newList();
        held.addAll(List.of(cubes));
        return new EntityMesh.Bone(pivot, EulerRotation.NONE, EulerRotation.NONE, 1f, held, null);
    }

    /** An unrotated, ungrown cube at the bone's pivot with the given extent, atlas origin, mirror and overrides. */
    private static EntityMesh.Cube uvCube(Vector3f size, Vector2f uv, boolean mirror,
                                          ConcurrentMap<String, EntityMesh.FaceUv> faceUv) {
        return new EntityMesh.Cube(Vector3f.ZERO, size, uv, Vector3f.ZERO, mirror, Vector3f.ZERO,
            EulerRotation.NONE, faceUv);
    }

    /**
     * A {@code 64 x height} sheet whose texels each carry a colour of their own position, opaque except
     * for a half-alpha band at {@code u[0, 10) v[16, 24)} under the strip of {@link #uvFixture}'s
     * mirrored cube.
     */
    private static PixelBuffer uvTexture(int height) {
        int[] pixels = new int[64 * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < 64; x++) {
                int alpha = x < 10 && y >= 16 && y < 24 ? 0x80 : 0xFF;
                pixels[y * 64 + x] = alpha << 24 | (x * 4) << 16 | (y * 4) << 8 | 0x40;
            }
        return PixelBuffer.ofStraight(pixels, 64, height);
    }

    /**
     * The per-vertex UVs of one cube polygon, resolved the way the kit resolved them while every ask
     * recomputed its rectangle: the strip across X on a mirrored cube, that strip's override or its
     * atlas rectangle, normalised by the texture size and permuted into the polygon's corner order.
     */
    private static Vector2f[] referencePolygonUv(Face face, EntityMesh.Cube cube, float texW, float texH) {
        Face strip = cube.isMirror() ? AxisSigns.MIRROR_X.apply(face) : face;
        EntityMesh.FaceUv override = cube.getFaceUv().get(strip.direction());
        Vector4f rect;
        if (override == null) {
            rect = new Unwrap.Atlas(cube.getUv(), cube.getSize(), cube.isMirror()).rect(strip);
        } else {
            Vector2f uv = override.getUv();
            Vector2f uvSize = override.getUvSize();
            rect = new Vector4f(uv.x(), uv.y(), uv.x() + uvSize.x(), uv.y() + uvSize.y());
        }
        return CornerPhase.POLYGON.permuteUv(face, rect.toUvCorners(texW, texH, 0, cube.isMirror()));
    }

    /**
     * Asserts a build of {@code mesh} emitted, face by face in emission order, the UVs
     * {@link #referencePolygonUv} resolves at {@code texW x texH} - corners {@code 0, 1, 2} of the
     * polygon on a face's first triangle and {@code 0, 2, 3} on its second - and that the polygon is
     * the one {@link BoneKit#resolvePolygonUv} answers, which the bounds walk sizing the canvas reads. A
     * record compares its floats as {@link Float#compare} does, so the check is bit for bit.
     */
    private static void assertPolygonUvs(EntityMesh mesh, List<VisibleTriangle> triangles, float texW, float texH) {
        int next = 0;
        for (Map.Entry<String, EntityMesh.Bone> entry : mesh.getBones().entrySet())
            for (EntityMesh.Cube cube : entry.getValue().getCubes())
                for (Face face : Face.stream().toList()) {
                    if (BoneKit.isDegeneratePlaneFace(cube.getSize(), face)) continue;
                    String tag = entry.getKey() + ":" + face.direction();
                    Vector2f[] expected = referencePolygonUv(face, cube, texW, texH);
                    VisibleTriangle first = triangles.get(next++);
                    VisibleTriangle second = triangles.get(next++);
                    assertThat(tag + " first triangle", first.debugTag(), equalTo(tag));
                    assertThat(tag + " second triangle", second.debugTag(), equalTo(tag));
                    assertThat(tag + " first triangle UVs", List.of(first.uv0(), first.uv1(), first.uv2()),
                        equalTo(List.of(expected[0], expected[1], expected[2])));
                    assertThat(tag + " second triangle UVs", List.of(second.uv0(), second.uv1(), second.uv2()),
                        equalTo(List.of(expected[0], expected[2], expected[3])));
                    assertThat(tag + " polygon against resolvePolygonUv",
                        List.of(first.uv0(), first.uv1(), first.uv2(), second.uv2()),
                        equalTo(List.of(BoneKit.resolvePolygonUv(face, cube, cube.getSize(), texW, texH))));
                }
        assertThat("every emitted triangle is checked", next, equalTo(triangles.size()));
    }

    /**
     * Asserts the triangles tagged {@code tag} span exactly the U range of the atlas rectangle
     * {@code strip}, normalised by the {@code 64}-texel width.
     */
    private static void assertUSpan(List<VisibleTriangle> triangles, String tag, Vector4f strip) {
        float uMin = Float.POSITIVE_INFINITY;
        float uMax = Float.NEGATIVE_INFINITY;
        for (VisibleTriangle tri : triangles) {
            if (!tri.debugTag().equals(tag)) continue;
            for (Vector2f uv : new Vector2f[]{ tri.uv0(), tri.uv1(), tri.uv2() }) {
                uMin = Math.min(uMin, uv.x());
                uMax = Math.max(uMax, uv.x());
            }
        }
        assertThat(tag + " lowest u", uMin, equalTo(Math.min(strip.x(), strip.z()) / 64f));
        assertThat(tag + " highest u", uMax, equalTo(Math.max(strip.x(), strip.z()) / 64f));
    }

    /** Every UV the triangles carry, in emission order. */
    private static List<Vector2f> uvs(List<VisibleTriangle> triangles) {
        List<Vector2f> carried = new ArrayList<>();
        for (VisibleTriangle tri : triangles)
            carried.addAll(List.of(tri.uv0(), tri.uv1(), tri.uv2()));
        return carried;
    }

    /** A square {@code size x size} sheet holding one ARGB value in every texel. */
    private static PixelBuffer filled(int argb, int size) {
        int[] pixels = new int[size * size];
        Arrays.fill(pixels, argb);
        return PixelBuffer.ofStraight(pixels, size, size);
    }

    /** The sheet at twice its width and height, each texel repeated over a {@code 2x2} block. */
    private static PixelBuffer doubled(PixelBuffer sheet) {
        int width = sheet.width() * 2;
        int height = sheet.height() * 2;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                pixels[y * width + x] = sheet.getPixel(x / 2, y / 2);
        return PixelBuffer.ofStraight(pixels, width, height);
    }

    /** The vanilla entity sheet at {@code minecraft:entity/<ref>}. */
    private static PixelBuffer entityTexture(RendererContext context, String ref) {
        return context.resolveTexture("minecraft:entity/" + ref).get();
    }

    /** The triangles one pass builds alone, sharing nothing. */
    private static List<VisibleTriangle> freshPass(
        EntityMesh mesh, PixelBuffer texture, EntityGeometryKit.EntityBuildParams params) {
        return collect(EntityGeometryKit.buildTriangles(mesh, texture, params));
    }

    /**
     * Builds one pass through the shared table, asserts it matches the same pass built alone, and
     * answers the shared build's triangles.
     */
    private static List<VisibleTriangle> assertSharedMatchesFresh(
        String label, EntityMesh mesh, PixelBuffer texture, EntityGeometryKit.EntityBuildParams params,
        Map<EntityMesh, Object> table) {
        List<VisibleTriangle> shared = collect(EntityGeometryKit.buildTriangles(mesh, texture, params, table));
        assertSameTriangles(label, shared, freshPass(mesh, texture, params));
        return shared;
    }

    /**
     * Asserts two triangle lists agree triangle for triangle: every position, UV and normal bit for
     * bit, the same texture instance, and the same tint, shade, traits and tag.
     */
    private static void assertSameTriangles(String label, List<VisibleTriangle> actual, List<VisibleTriangle> expected) {
        assertThat(label + " triangle count", actual.size(), equalTo(expected.size()));
        assertThat(label + " draws something", expected.isEmpty(), equalTo(false));
        for (int i = 0; i < expected.size(); i++) {
            VisibleTriangle a = actual.get(i);
            VisibleTriangle e = expected.get(i);
            String at = label + " triangle " + i + " (" + e.debugTag() + ")";
            assertSameBits(at + " p0", a.position0(), e.position0());
            assertSameBits(at + " p1", a.position1(), e.position1());
            assertSameBits(at + " p2", a.position2(), e.position2());
            assertSameBits(at + " uv0", a.uv0(), e.uv0());
            assertSameBits(at + " uv1", a.uv1(), e.uv1());
            assertSameBits(at + " uv2", a.uv2(), e.uv2());
            assertSameBits(at + " normal", a.normal(), e.normal());
            assertThat(at + " texture", a.texture(), sameInstance(e.texture()));
            assertThat(at + " tint", a.tintArgb(), equalTo(e.tintArgb()));
            assertThat(at + " shading", Float.floatToIntBits(a.shading()), equalTo(Float.floatToIntBits(e.shading())));
            assertThat(at + " traits", a.traits(), equalTo(e.traits()));
            assertThat(at + " tag", a.debugTag(), equalTo(e.debugTag()));
        }
    }

    /** Whether any vertex of two index-aligned triangle lists sits at different bits. */
    private static boolean anyPositionDiffers(List<VisibleTriangle> a, List<VisibleTriangle> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++)
            for (int v = 0; v < 3; v++)
                if (!positions(a.get(i))[v].equals(positions(b.get(i))[v])) return true;
        return a.size() != b.size();
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

    /** Asserts two UVs agree bit for bit on both components. */
    private static void assertSameBits(String label, Vector2f actual, Vector2f expected) {
        assertThat(label + " u", Float.floatToIntBits(actual.x()), equalTo(Float.floatToIntBits(expected.x())));
        assertThat(label + " v", Float.floatToIntBits(actual.y()), equalTo(Float.floatToIntBits(expected.y())));
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
        // The cube's mesh declares cull, so each face is lit by its own orientation alone. A two-sided
        // axis-aligned cube shades every face alike under both turns, each face taking whichever of its
        // two orientations faces the camera, and HALF_X only swaps which one that is.
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", cubeBone(unitCubeAt(0f)));
        EntityMesh culling = new EntityMesh(TextureSize.DEFAULT, bones, true);
        ConcurrentList<VisibleTriangle> asFolded = Shading.relightForEntityInUi(
            EntityGeometryKit.buildTriangles(culling, solidWhite()).triangles(),
            LightingFrame.ENTITY_IN_UI, AxisSigns.MIRROR_Y);
        ConcurrentList<VisibleTriangle> asPlayer = Shading.relightForEntityInUi(
            EntityGeometryKit.buildTriangles(culling, solidWhite()).triangles(),
            LightingFrame.ENTITY_IN_UI, AxisSigns.MIRROR_Z);

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
