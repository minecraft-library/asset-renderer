package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * The three operand orders {@link BoneKit} carries in prose and nothing else enforces, plus the two
 * predicates it hands both geometry kits.
 * <p>
 * Each of the three is a float-arithmetic claim rather than an algebraic one, so wherever a grouping is
 * the whole point the assertion compares {@link Float#floatToIntBits} rather than a tolerance, and is
 * paired with the algebraically-equal rewrite it must <b>not</b> equal - a claim that only one grouping
 * is correct is worth nothing unless the other grouping is shown to differ on the fixture. A tolerance
 * appears only where the assertion is about where a vector lands, not about which float it lands on:
 * <ul>
 *   <li><b>{@link BoneKit#scaledCubeBounds}</b> multiplies the bone's uniform scale into the origin,
 *   the size and the grow separately, and {@link Box#grown} then assembles the upper corner as
 *   {@code (origin + size) + grow} off the un-grown origin. Scaling the assembled corner instead, or
 *   growing the lower corner first and adding the growth back twice, lands one ULP away.</li>
 *   <li><b>The bone chain</b> is {@code base * T(pivot) * R} with no un-translate, so a rotated bone's
 *   translation column is exactly its pivot. The cube-level rotation keeps the un-translate, so its
 *   own pivot is a fixed point. Those two are the observable difference between the two shapes.</li>
 *   <li><b>The rotation</b> is built by {@link Quaternionf#rotationZYX}, which applies X, then Y, then
 *   Z to a vector. The fixture rotation is picked so the {@code XYZ} reading sends the same vector
 *   somewhere else entirely rather than merely rounding differently.</li>
 * </ul>
 * <p>
 * {@link BoneKit#isDegeneratePlaneFace} and {@link BoneKit#faceHasPartialAlpha} are pinned as observed:
 * the first answers off the <em>first</em> zero-extent axis alone, so a cube with two zero axes reports
 * the faces on the first of them as full-area; the second walks a half-open texel window, so a texel
 * whose leading edge sits exactly on the rectangle's upper bound is never sampled.
 */
class BoneKitTest {

    /** Cube lower corner whose X component is chosen so both operand-order rewrites round differently. */
    private static final Vector3f ORIGIN = new Vector3f(-8f, -5f, 2.5f);

    /** Cube extent before growth, paired with {@link #ORIGIN} and {@link #GROW} on the X axis. */
    private static final Vector3f SIZE = new Vector3f(3f, 12f, 6f);

    /** Per-axis growth, non-representable in binary so the assembly order is observable. */
    private static final Vector3f GROW = new Vector3f(0.1f, 0.1f, 0.1f);

    /** Bone scale that is neither one nor a power of two, so per-operand scaling is observable. */
    private static final float SCALE = 0.75f;

    /** Bone pivot with three distinct non-zero components, so a dropped or swapped axis shows. */
    private static final Vector3f PIVOT = new Vector3f(3f, -2.5f, 7f);

    /**
     * Quarter turns on pitch and yaw - the rotation that separates the ZYX reading from the XYZ one:
     * ZYX sends {@code +Y} to {@code +X}, XYZ sends it to {@code +Z}.
     */
    private static final EulerRotation QUARTER_PITCH_AND_YAW = new EulerRotation(90f, 90f, 0f);

    // --- scaledCubeBounds: the scale multiplies each operand ---

    /**
     * Pins that a unit scale costs nothing: every component of {@code scaledCubeBounds(1f, cube)} is
     * bit-identical to the bounds assembled from the raw operands, which holds only because the scale
     * meets each operand on its own ({@code x * 1f == x}) rather than the assembled corners.
     */
    @Test
    @DisplayName("unit scale leaves every operand bit-identical")
    void unitScaleLeavesEveryOperandUntouched() {
        Box scaled = BoneKit.scaledCubeBounds(1f, cube(ORIGIN, SIZE, GROW));
        Box raw = Box.grown(ORIGIN, SIZE, GROW);
        assertThat("minX", bits(scaled.minX()), equalTo(bits(raw.minX())));
        assertThat("minY", bits(scaled.minY()), equalTo(bits(raw.minY())));
        assertThat("minZ", bits(scaled.minZ()), equalTo(bits(raw.minZ())));
        assertThat("maxX", bits(scaled.maxX()), equalTo(bits(raw.maxX())));
        assertThat("maxY", bits(scaled.maxY()), equalTo(bits(raw.maxY())));
        assertThat("maxZ", bits(scaled.maxZ()), equalTo(bits(raw.maxZ())));
    }

    /**
     * Pins the upper corner's assembly order: {@code (origin + size) + grow} off the un-grown origin,
     * as vanilla's cube constructor forms it. Also asserts that growing the lower corner first and
     * adding the growth back twice - algebraically the same expression - lands on a different float
     * for this fixture, so the first assertion is not a tautology.
     */
    @Test
    @DisplayName("upper corner adds grow once to the un-grown origin")
    void grownUpperCornerAddsGrowOnceToTheUngrownOrigin() {
        Box bounds = BoneKit.scaledCubeBounds(1f, cube(ORIGIN, SIZE, GROW));
        float ox = ORIGIN.x();
        float sx = SIZE.x();
        float gx = GROW.x();

        float vanillaOrder = (ox + sx) + gx;
        float lowerCornerFirst = ((ox - gx) + sx) + gx + gx;

        assertThat("maxX assembles as (origin + size) + grow",
            bits(bounds.maxX()), equalTo(bits(vanillaOrder)));
        assertThat("the re-associated rewrite must differ on this fixture",
            bits(lowerCornerFirst), not(equalTo(bits(vanillaOrder))));
    }

    /**
     * Pins that the bone's uniform scale meets the origin, the size and the grow separately and never
     * the assembled corner. Both corners are checked on all three axes against the per-operand
     * expression, and the X axis additionally against the scale-the-assembled-corner rewrite, which is
     * a different float for this fixture on both the lower and the upper corner.
     */
    @Test
    @DisplayName("scale multiplies each operand rather than the assembled corners")
    void scaleMultipliesEachOperandNotTheAssembledCorners() {
        Box bounds = BoneKit.scaledCubeBounds(SCALE, cube(ORIGIN, SIZE, GROW));

        assertThat("minX", bits(bounds.minX()), equalTo(bits(scaledLower(ORIGIN.x(), GROW.x()))));
        assertThat("minY", bits(bounds.minY()), equalTo(bits(scaledLower(ORIGIN.y(), GROW.y()))));
        assertThat("minZ", bits(bounds.minZ()), equalTo(bits(scaledLower(ORIGIN.z(), GROW.z()))));
        assertThat("maxX", bits(bounds.maxX()),
            equalTo(bits(scaledUpper(ORIGIN.x(), SIZE.x(), GROW.x()))));
        assertThat("maxY", bits(bounds.maxY()),
            equalTo(bits(scaledUpper(ORIGIN.y(), SIZE.y(), GROW.y()))));
        assertThat("maxZ", bits(bounds.maxZ()),
            equalTo(bits(scaledUpper(ORIGIN.z(), SIZE.z(), GROW.z()))));

        float ox = ORIGIN.x();
        float sx = SIZE.x();
        float gx = GROW.x();
        float cornerThenScaleUpper = ((ox + sx) + gx) * SCALE;
        float cornerThenScaleLower = (ox - gx) * SCALE;
        assertThat("scaling the assembled upper corner must differ on this fixture",
            bits(cornerThenScaleUpper), not(equalTo(bits(bounds.maxX()))));
        assertThat("scaling the assembled lower corner must differ on this fixture",
            bits(cornerThenScaleLower), not(equalTo(bits(bounds.minX()))));
    }

    /**
     * Pins that the grow expands the corner box only: a cube built with the same origin and size but
     * no grow differs from the grown one by exactly the scaled grow on each side, so nothing in the
     * scaling path folds the growth into the size.
     */
    @Test
    @DisplayName("grow moves both corners and never the size operand")
    void growExpandsOnlyTheCornerBox() {
        Box grown = BoneKit.scaledCubeBounds(SCALE, cube(ORIGIN, SIZE, GROW));
        Box bare = BoneKit.scaledCubeBounds(SCALE, cube(ORIGIN, SIZE, Vector3f.ZERO));
        float scaledGrow = GROW.x() * SCALE;

        assertThat("lower corner drops by the scaled grow",
            bits(grown.minX()), equalTo(bits(bare.minX() - scaledGrow)));
        assertThat("upper corner rises by the scaled grow",
            bits(grown.maxX()), equalTo(bits(bare.maxX() + scaledGrow)));
    }

    // --- the bone rotation is ZYX ---

    /**
     * Pins that a bone's rotation is built by {@link Quaternionf#rotationZYX} taking roll, yaw and
     * pitch in that argument order, bit for bit, and that the {@link Quaternionf#rotationXYZ} reading
     * of the same three angles is a different matrix on this fixture.
     */
    @Test
    @DisplayName("bone rotation is built ZYX, not XYZ")
    void boneRotationUsesZyxQuaternionOrder() {
        Matrix4f chain = BoneKit.buildChainTransform(
            oneBone("bone", bone(Vector3f.ZERO, QUARTER_PITCH_AND_YAW, null)), "bone");

        Matrix4f zyx = Matrix4f.IDENTITY.rotate(Quaternionf.rotationZYX(
            QUARTER_PITCH_AND_YAW.rollRadians(),
            QUARTER_PITCH_AND_YAW.yawRadians(),
            QUARTER_PITCH_AND_YAW.pitchRadians()));
        Matrix4f xyz = Matrix4f.IDENTITY.rotate(Quaternionf.rotationXYZ(
            QUARTER_PITCH_AND_YAW.pitchRadians(),
            QUARTER_PITCH_AND_YAW.yawRadians(),
            QUARTER_PITCH_AND_YAW.rollRadians()));

        assertSameBits("bone chain", chain, zyx);
        assertThat("the XYZ reading must differ on this fixture", sameBits(zyx, xyz), is(false));
    }

    /**
     * Pins the vector-application order the ZYX product implies: pitch reaches the vector first, then
     * yaw, then roll. A quarter turn on each of pitch and yaw carries {@code +Y} to {@code +X}; the
     * XYZ reading of the same angles would carry it to {@code +Z}, so the Z assertion is what fails
     * if the product order is reversed.
     */
    @Test
    @DisplayName("bone rotation applies pitch, then yaw, then roll to a vector")
    void boneRotationAppliesPitchThenYawThenRollToAVector() {
        Matrix4f chain = BoneKit.buildChainTransform(
            oneBone("bone", bone(Vector3f.ZERO, QUARTER_PITCH_AND_YAW, null)), "bone");
        Vector3f turned = new Vector3f(0f, 1f, 0f).transformNormal(chain);

        assertThat("+Y lands on +X", Math.abs(turned.x() - 1f), lessThan(1e-5f));
        assertThat("no residual Y", Math.abs(turned.y()), lessThan(1e-5f));
        assertThat("+Y must not land on +Z, which is where the XYZ reading sends it",
            Math.abs(turned.z()), lessThan(1e-5f));
    }

    // --- the T(p)R bone chain has no un-translate ---

    /**
     * Pins the bone chain as {@code base * T(pivot) * R} with no un-translate. The load-bearing half is
     * the translation column: {@link Matrix4f#rotate} preserves it, so a rotated bone's column four is
     * exactly its pivot, which is true only because the {@code T(-pivot)} step was dropped. The
     * pivot-centred shape is asserted to differ on all three components of that column.
     */
    @Test
    @DisplayName("bone chain translates by the pivot and never un-translates")
    void boneChainTranslatesByPivotAndNeverUnTranslates() {
        Matrix4f chain = BoneKit.buildChainTransform(
            oneBone("bone", bone(PIVOT, QUARTER_PITCH_AND_YAW, null)), "bone");
        Matrix4f expected = Matrix4f.IDENTITY
            .translate(PIVOT.x(), PIVOT.y(), PIVOT.z())
            .rotate(quaternionOf(QUARTER_PITCH_AND_YAW));

        assertSameBits("T(pivot) * R", chain, expected);
        assertThat("column four x is the pivot", bits(chain.get(4, 1)), equalTo(bits(PIVOT.x())));
        assertThat("column four y is the pivot", bits(chain.get(4, 2)), equalTo(bits(PIVOT.y())));
        assertThat("column four z is the pivot", bits(chain.get(4, 3)), equalTo(bits(PIVOT.z())));

        Matrix4f pivotCentred = expected.translate(-PIVOT.x(), -PIVOT.y(), -PIVOT.z());
        assertThat("the pivot-centred shape must differ", sameBits(chain, pivotCentred), is(false));
        assertThat("and its column four is no longer the pivot",
            bits(pivotCentred.get(4, 1)), not(equalTo(bits(PIVOT.x()))));
    }

    /**
     * Pins the counterpart at cube level: a cube rotation carries its {@code T(-pivot)}, so the cube's
     * own pivot is a fixed point of the composed transform. The same pivot fed through the bone-level
     * shape moves by more than a unit, which is the whole observable difference between the two.
     */
    @Test
    @DisplayName("cube rotation keeps its own pivot fixed where the bone shape moves it")
    void cubeRotationKeepsItsOwnPivotFixed() {
        EntityModelData.Cube rotated = rotatedCube(PIVOT, QUARTER_PITCH_AND_YAW);
        Matrix4f cubeShape = BoneKit.composeCubeTransform(
            rotated, bone(Vector3f.ZERO, EulerRotation.NONE, null), Matrix4f.IDENTITY);
        Vector3f held = PIVOT.transform(cubeShape);

        assertThat("pivot x held", Math.abs(held.x() - PIVOT.x()), lessThan(1e-4f));
        assertThat("pivot y held", Math.abs(held.y() - PIVOT.y()), lessThan(1e-4f));
        assertThat("pivot z held", Math.abs(held.z() - PIVOT.z()), lessThan(1e-4f));

        Matrix4f boneShape = BoneKit.buildChainTransform(
            oneBone("bone", bone(PIVOT, QUARTER_PITCH_AND_YAW, null)), "bone");
        Vector3f swung = PIVOT.transform(boneShape);
        assertThat("the bone shape must move the same point",
            Math.abs(swung.x() - PIVOT.x()), greaterThan(1f));
    }

    /**
     * Pins that a bone-local origin lands on the pivot: the bone chain's image of {@code (0, 0, 0)} is
     * the pivot itself, which is what lets a cube store its corners bone-local and be placed by the
     * chain alone.
     */
    @Test
    @DisplayName("bone-local origin lands on the bone pivot")
    void boneLocalOriginLandsOnThePivot() {
        Matrix4f chain = BoneKit.buildChainTransform(
            oneBone("bone", bone(PIVOT, QUARTER_PITCH_AND_YAW, null)), "bone");
        Vector3f placed = Vector3f.ZERO.transform(chain);

        assertThat("x", Math.abs(placed.x() - PIVOT.x()), lessThan(1e-5f));
        assertThat("y", Math.abs(placed.y() - PIVOT.y()), lessThan(1e-5f));
        assertThat("z", Math.abs(placed.z() - PIVOT.z()), lessThan(1e-5f));
    }

    // --- chain resolution ---

    /**
     * Pins that a pivot-free unrotated bone carries no arithmetic at all: the chain handed back is the
     * base instance itself, so an intermediate group anchor adds no rounding to its subtree.
     */
    @Test
    @DisplayName("a pivot-free unrotated bone hands back the base instance")
    void pivotFreeUnrotatedBoneReturnsTheBaseUnchanged() {
        Matrix4f base = Matrix4f.IDENTITY.translate(1f, 2f, 3f);
        Map<String, Matrix4f> chains = BoneKit.buildChainTransformsFrom(
            base, oneBone("plain", bone(Vector3f.ZERO, EulerRotation.NONE, null)));

        assertThat(chains.get("plain"), sameInstance(base));
    }

    /**
     * Pins that the single-bone walk and the whole-map build agree bit for bit on a tree, on every
     * entry of every bone's matrix. The two run the same recursion but reach it from different
     * starting points, and the map memoizes where the walk does not, so nothing but an operand-order
     * divergence can separate them.
     */
    @Test
    @DisplayName("single-bone walk agrees bit for bit with the whole-map build")
    void singleBoneWalkAgreesWithTheWholeMapOnATree() {
        Map<String, EntityModelData.Bone> bones = threeDeepChain();
        Map<String, Matrix4f> all = BoneKit.buildChainTransforms(bones);

        assertThat("every bone is resolved", all.size(), equalTo(bones.size()));
        for (String name : bones.keySet())
            assertSameBits(name, BoneKit.buildChainTransform(bones, name), all.get(name));
    }

    /**
     * Pins that a deeper bone's chain is not its own pose alone: the leaf of a three-deep chain differs
     * from the same bone resolved as a root, so the ancestor poses genuinely compose in.
     */
    @Test
    @DisplayName("a leaf chain carries its ancestors and not its own pose alone")
    void leafChainCarriesItsAncestors() {
        Map<String, EntityModelData.Bone> bones = threeDeepChain();
        Matrix4f leaf = BoneKit.buildChainTransform(bones, "leaf");
        Matrix4f asRoot = BoneKit.buildChainTransform(
            oneBone("leaf", bone(new Vector3f(0.5f, 1.5f, -2f), new EulerRotation(15f, 0f, 0f), null)),
            "leaf");

        assertThat("the ancestors must move the leaf", sameBits(leaf, asRoot), is(false));
    }

    /**
     * Pins the absent-bone answer: a name no bone carries resolves to the base matrix instance itself
     * rather than to a fresh identity or a throw.
     */
    @Test
    @DisplayName("an unknown bone name answers the identity instance")
    void unknownBoneNameAnswersTheIdentityInstance() {
        Matrix4f chain = BoneKit.buildChainTransform(
            oneBone("bone", bone(PIVOT, QUARTER_PITCH_AND_YAW, null)), "absent");

        assertThat(chain, sameInstance(Matrix4f.IDENTITY));
    }

    /**
     * Pins that a self-parented bone and one naming a parent no bone map carries both degrade to the
     * root shape rather than looping or dropping their own pose.
     */
    @Test
    @DisplayName("self-parented and dangling-parent bones degrade to the root shape")
    void degenerateParentLinksDegradeToTheRootShape() {
        Matrix4f rootShape = BoneKit.buildChainTransform(
            oneBone("bone", bone(PIVOT, QUARTER_PITCH_AND_YAW, null)), "bone");

        assertSameBits("self-parented", BoneKit.buildChainTransform(
            oneBone("bone", bone(PIVOT, QUARTER_PITCH_AND_YAW, "bone")), "bone"), rootShape);
        assertSameBits("dangling parent", BoneKit.buildChainTransform(
            oneBone("bone", bone(PIVOT, QUARTER_PITCH_AND_YAW, "nobody")), "bone"), rootShape);
    }

    /**
     * Pins that a two-bone parent cycle terminates and resolves both members rather than recursing
     * until the stack runs out. Which of the two the recursion breaks at depends on iteration order,
     * so only termination and coverage are claimed here.
     */
    @Test
    @DisplayName("a parent cycle terminates and resolves every member")
    void parentCycleTerminatesAndResolvesEveryMember() {
        Map<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
        bones.put("a", bone(PIVOT, QUARTER_PITCH_AND_YAW, "b"));
        bones.put("b", bone(new Vector3f(1f, 1f, 1f), EulerRotation.NONE, "a"));

        Map<String, Matrix4f> chains = BoneKit.buildChainTransforms(bones);

        assertThat(chains.size(), equalTo(2));
        assertThat(chains.get("a"), is(notNullValue()));
        assertThat(chains.get("b"), is(notNullValue()));
    }

    // --- composeCubeTransform ---

    /**
     * Pins that a cube with neither its own rotation nor a bind pose is handed the bone chain instance
     * back untouched, so a rotation-free cube pays no extra rounding. The bone here carries a non-zero
     * {@code rotation}, which this method does not read - only the bind pose and the cube's own
     * rotation reach it.
     */
    @Test
    @DisplayName("an unrotated cube hands back the bone chain instance")
    void unrotatedCubeReturnsTheBoneChainInstance() {
        Matrix4f boneChain = Matrix4f.IDENTITY.translate(1f, 2f, 3f);
        Matrix4f composed = BoneKit.composeCubeTransform(
            cube(ORIGIN, SIZE, GROW), bone(PIVOT, QUARTER_PITCH_AND_YAW, null), boneChain);

        assertThat(composed, sameInstance(boneChain));
    }

    /**
     * Pins the cube transform's composition: the bind pose is applied first about the <b>bone</b>
     * pivot, then the cube's own rotation about the <b>cube</b> pivot, each as
     * {@code T(+pivot) * R * T(-pivot)}. Both the swapped order and anchoring the bind pose on the
     * cube pivot are asserted to produce a different matrix, so neither reading can pass silently.
     */
    @Test
    @DisplayName("bind pose composes first about the bone pivot, cube rotation second about its own")
    void bindPoseComposesBeforeTheCubeRotationOnItsOwnPivot() {
        EulerRotation bindPose = new EulerRotation(0f, 30f, 0f);
        EulerRotation cubeRotation = new EulerRotation(45f, 0f, 0f);
        Vector3f cubePivot = new Vector3f(-1f, 4f, 0.5f);
        Matrix4f boneChain = Matrix4f.IDENTITY.translate(2f, 0.5f, -3f);

        Matrix4f composed = BoneKit.composeCubeTransform(
            rotatedCube(cubePivot, cubeRotation), bindPosedBone(PIVOT, bindPose), boneChain);

        Matrix4f expected = pivotCentred(pivotCentred(boneChain, PIVOT, bindPose), cubePivot, cubeRotation);
        assertSameBits("bind then cube", composed, expected);

        Matrix4f swapped = pivotCentred(pivotCentred(boneChain, cubePivot, cubeRotation), PIVOT, bindPose);
        assertThat("the swapped order must differ", sameBits(composed, swapped), is(false));

        Matrix4f bindOnCubePivot =
            pivotCentred(pivotCentred(boneChain, cubePivot, bindPose), cubePivot, cubeRotation);
        assertThat("anchoring the bind pose on the cube pivot must differ",
            sameBits(composed, bindOnCubePivot), is(false));
    }

    // --- isDegeneratePlaneFace ---

    /**
     * Pins that a plane cube collapses every face except the two on its own zero-extent axis, checked
     * across all six directions for each of the three axes in turn.
     */
    @Test
    @DisplayName("a plane cube collapses every face off its zero-extent axis")
    void planeCubeCollapsesEveryFaceOffItsZeroAxis() {
        Vector3f[] planes = {
            new Vector3f(0f, 6f, 8f),
            new Vector3f(6f, 0f, 8f),
            new Vector3f(6f, 8f, 0f)
        };
        for (int zeroAxis = 0; zeroAxis < planes.length; zeroAxis++)
            for (Face face : Face.CACHED_VALUES)
                assertThat("axis " + zeroAxis + " plane, face " + face.direction(),
                    BoneKit.isDegeneratePlaneFace(planes[zeroAxis], face),
                    is(face.axis() != zeroAxis));
    }

    /**
     * Pins the observed answer for a cube with two zero axes, which the class javadoc does not cover:
     * the first zero axis found decides alone, so the two faces on <em>that</em> axis are reported as
     * full-area even though a cube with a second zero extent collapses those faces to a line too. The
     * ordering is X, then Y, then Z.
     */
    @Test
    @DisplayName("two zero axes are answered by the first of them alone")
    void twoZeroAxesAreAnsweredByTheFirstAxisAlone() {
        Vector3f lineAlongZ = new Vector3f(0f, 0f, 8f);

        assertThat("WEST reads as full-area although its own extent is zero on Y",
            BoneKit.isDegeneratePlaneFace(lineAlongZ, Face.WEST), is(false));
        assertThat("EAST likewise", BoneKit.isDegeneratePlaneFace(lineAlongZ, Face.EAST), is(false));
        assertThat("UP collapses", BoneKit.isDegeneratePlaneFace(lineAlongZ, Face.UP), is(true));
        assertThat("NORTH collapses", BoneKit.isDegeneratePlaneFace(lineAlongZ, Face.NORTH), is(true));
    }

    /**
     * Pins that a cube with three non-zero extents collapses nothing.
     */
    @Test
    @DisplayName("a cube with three non-zero extents collapses no face")
    void solidCubeCollapsesNoFace() {
        Vector3f solid = new Vector3f(4f, 6f, 8f);
        for (Face face : Face.CACHED_VALUES)
            assertThat(face.direction(), BoneKit.isDegeneratePlaneFace(solid, face), is(false));
    }

    // --- faceHasPartialAlpha ---

    /**
     * Pins the two ends of the alpha range as not partial: a fully opaque sheet and a fully erased one
     * both answer {@code false}, so a pure cutout face keeps the emission-order pass rather than being
     * routed to the sorted one.
     */
    @Test
    @DisplayName("neither a fully opaque nor a fully erased face is partial")
    void neitherEndOfTheAlphaRangeIsPartial() {
        Vector2f[] whole = uvRect(0f, 0f, 1f, 1f);

        assertThat("opaque", BoneKit.faceHasPartialAlpha(whole, filled(0xFFFFFFFF)), is(false));
        assertThat("erased", BoneKit.faceHasPartialAlpha(whole, filled(ColorMath.pack(0, 0, 0, 0))),
            is(false));
    }

    /**
     * Pins that the walk is bounded by the face's own UV rectangle: one half-transparent texel is found
     * when the rectangle covers it and ignored when the rectangle sits elsewhere on the same sheet.
     */
    @Test
    @DisplayName("only texels under the face's own UV rectangle are sampled")
    void onlyTexelsUnderTheUvRectangleAreSampled() {
        PixelBuffer sheet = filledWithTexel(0xFFFFFFFF, 2, 3, ColorMath.pack(128, 255, 255, 255));

        assertThat("rectangle over the partial texel",
            BoneKit.faceHasPartialAlpha(uvRect(2f / 16f, 3f / 16f, 3f / 16f, 4f / 16f), sheet),
            is(true));
        assertThat("rectangle elsewhere on the same sheet",
            BoneKit.faceHasPartialAlpha(uvRect(5f / 16f, 5f / 16f, 6f / 16f, 6f / 16f), sheet),
            is(false));
    }

    /**
     * Pins the half-open upper bound of the texel window. The walk runs to
     * {@code ceil(uMax * width)} exclusive, so a texel whose leading edge lands exactly on the
     * rectangle's upper U bound is outside it; widening the rectangle by half a texel pulls the same
     * texel in.
     */
    @Test
    @DisplayName("a texel whose leading edge is exactly the upper bound is outside the window")
    void texelAtTheExactUpperBoundIsOutsideTheWindow() {
        PixelBuffer sheet = filledWithTexel(0xFFFFFFFF, 4, 0, ColorMath.pack(128, 255, 255, 255));

        assertThat("upper bound exactly on the texel's leading edge",
            BoneKit.faceHasPartialAlpha(uvRect(2f / 16f, 0f, 4f / 16f, 1f / 16f), sheet), is(false));
        assertThat("upper bound half a texel further",
            BoneKit.faceHasPartialAlpha(uvRect(2f / 16f, 0f, 4.5f / 16f, 1f / 16f), sheet), is(true));
    }

    // --- fixtures ---

    /** An unrotated cube at the given corner operands, with no UV overrides and no mirror. */
    private static EntityModelData.Cube cube(Vector3f origin, Vector3f size, Vector3f grow) {
        return new EntityModelData.Cube(origin, size, Vector2f.ZERO, grow, false,
            Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap());
    }

    /** A cube carrying its own pivot-anchored rotation, the corner operands being irrelevant here. */
    private static EntityModelData.Cube rotatedCube(Vector3f pivot, EulerRotation rotation) {
        return new EntityModelData.Cube(ORIGIN, SIZE, Vector2f.ZERO, Vector3f.ZERO, false,
            pivot, rotation, Concurrent.newMap());
    }

    /** A cube-less bone at unit scale with no bind pose. */
    private static EntityModelData.Bone bone(Vector3f pivot, EulerRotation rotation, String parent) {
        return new EntityModelData.Bone(pivot, rotation, EulerRotation.NONE, 1f,
            Concurrent.newList(), parent);
    }

    /** A cube-less root bone carrying a bind pose and no rotation of its own. */
    private static EntityModelData.Bone bindPosedBone(Vector3f pivot, EulerRotation bindPose) {
        return new EntityModelData.Bone(pivot, EulerRotation.NONE, bindPose, 1f,
            Concurrent.newList(), null);
    }

    /** A one-entry bone map in a deterministic-iteration container. */
    private static Map<String, EntityModelData.Bone> oneBone(String name, EntityModelData.Bone bone) {
        Map<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
        bones.put(name, bone);
        return bones;
    }

    /**
     * A three-deep parent chain, every level both offset and rotated so no ancestor contributes an
     * identity that could hide a dropped link.
     */
    private static Map<String, EntityModelData.Bone> threeDeepChain() {
        Map<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
        bones.put("root", bone(PIVOT, new EulerRotation(0f, 40f, 0f), null));
        bones.put("mid", bone(new Vector3f(-2f, 3.5f, 1f), new EulerRotation(25f, 0f, 10f), "root"));
        bones.put("leaf", bone(new Vector3f(0.5f, 1.5f, -2f), new EulerRotation(15f, 0f, 0f), "mid"));
        return bones;
    }

    /** A {@code 16x16} sheet filled with one ARGB value. */
    private static PixelBuffer filled(int argb) {
        int[] pixels = new int[16 * 16];
        Arrays.fill(pixels, argb);
        return PixelBuffer.of(pixels, 16, 16);
    }

    /** A {@code 16x16} sheet filled with {@code argb}, with one texel replaced by {@code texel}. */
    private static PixelBuffer filledWithTexel(int argb, int x, int y, int texel) {
        int[] pixels = new int[16 * 16];
        Arrays.fill(pixels, argb);
        pixels[y * 16 + x] = texel;
        return PixelBuffer.of(pixels, 16, 16);
    }

    /** The four corners of a UV rectangle in the {@code TL, BL, BR, TR} order the kit emits. */
    private static Vector2f[] uvRect(float u0, float v0, float u1, float v1) {
        return new Vector2f[]{
            new Vector2f(u0, v0), new Vector2f(u0, v1), new Vector2f(u1, v1), new Vector2f(u1, v0)
        };
    }

    // --- assertions ---

    /**
     * The lower corner on one axis with {@link #SCALE} met by the origin and the grow separately.
     *
     * @param origin the axis component of the un-grown lower corner
     * @param grow the axis component of the growth
     * @return the scaled lower corner on that axis
     */
    private static float scaledLower(float origin, float grow) {
        return (origin * SCALE) - (grow * SCALE);
    }

    /**
     * The upper corner on one axis with {@link #SCALE} met by each operand separately, assembled in
     * vanilla's order: the growth is added to the un-grown far corner rather than to a corner that has
     * already been moved.
     *
     * @param origin the axis component of the un-grown lower corner
     * @param size the axis component of the extent
     * @param grow the axis component of the growth
     * @return the scaled upper corner on that axis
     */
    private static float scaledUpper(float origin, float size, float grow) {
        return ((origin * SCALE) + (size * SCALE)) + (grow * SCALE);
    }

    /** The rotation quaternion for an Euler triple in the order the bone chain builds it. */
    private static Quaternionf quaternionOf(EulerRotation rotation) {
        return Quaternionf.rotationZYX(
            rotation.rollRadians(), rotation.yawRadians(), rotation.pitchRadians());
    }

    /** {@code base * T(+pivot) * R * T(-pivot)}, the cube-level pivot-centred rotation shape. */
    private static Matrix4f pivotCentred(Matrix4f base, Vector3f pivot, EulerRotation rotation) {
        return base
            .translate(pivot.x(), pivot.y(), pivot.z())
            .rotate(quaternionOf(rotation))
            .translate(-pivot.x(), -pivot.y(), -pivot.z());
    }

    /** Whether two matrices agree on all sixteen entries bit for bit. */
    private static boolean sameBits(Matrix4f a, Matrix4f b) {
        for (int col = 1; col <= 4; col++)
            for (int row = 1; row <= 4; row++)
                if (bits(a.get(col, row)) != bits(b.get(col, row))) return false;
        return true;
    }

    /** Asserts two matrices agree on all sixteen entries bit for bit, naming the entry that parts. */
    private static void assertSameBits(String label, Matrix4f actual, Matrix4f expected) {
        for (int col = 1; col <= 4; col++)
            for (int row = 1; row <= 4; row++)
                assertThat(label + " entry (" + col + ", " + row + ")",
                    bits(actual.get(col, row)), equalTo(bits(expected.get(col, row))));
    }

    /** The raw bit pattern of a float, so an assertion cannot pass on a value one ULP away. */
    private static int bits(float value) {
        return Float.floatToIntBits(value);
    }

}
