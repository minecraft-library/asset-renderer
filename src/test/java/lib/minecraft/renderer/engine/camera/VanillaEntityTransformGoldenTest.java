package lib.minecraft.renderer.engine.camera;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.TextureSize;
import lib.minecraft.renderer.engine.kit.EntityGeometryKit;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.parity.PinSet;
import lib.minecraft.renderer.parity.Pins;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Characterization golden that pins the entity model→screen transform - the
 * {@link Projection#VANILLA_ISO} camera pose and its composition with the single-cube kit fixture -
 * so accidental drift in the iso pose or the kit fixture trips these assertions.
 *
 * <p>The values live in {@code pin.vanilla-iso-pose} and {@code pin.kit-corners}, captured by this
 * test and read back by it. {@code VANILLA_ISO} resolves to the plain {@code rotationXYZ(30, 225, 0)}
 * iso display pose (det=+1); the entity's model-to-world facing / chirality lives on the
 * {@code ENTITY_FLIP} {@code Placement} in {@code EntityRenderer}, not the camera. A deliberate change
 * to that pose or the kit fixture re-baselines those pins - which is a promotion of the capture this
 * test already wrote, never a paste into Java source.
 *
 * <p>The load-bearing structural assertion is {@link #pose_isDet_positive}: the camera is a plain det=+1
 * rotation. A det≤0 would mean chirality leaked back onto the camera (regressing the split).
 */
class VanillaEntityTransformGoldenTest {

    /** Half-extent of the cube fixture (matches {@code EntityGeometryKitTest}). */
    private static final float HALF = 1f;

    private static final String POSE_ARTIFACT = "pin.vanilla-iso-pose";
    private static final String CORNERS_ARTIFACT = "pin.kit-corners";

    /** How to read the flattened pose back, stored beside it. */
    private static final String POSE_ORDER = "get(col,row): [c1r1,c1r2,c1r3,c1r4, c2r1,...]";

    /** How to read the flattened corners back, stored beside them. */
    private static final String CORNERS_ORDER = "8 corners, each (x,y,z), sorted by x then y then z";

    private static final PinSet POSE_PIN = PinSet.of(POSE_ARTIFACT, Map.of(
        "pose", "Projection.VANILLA_ISO.resolve().camera().pose()"));

    private static final PinSet CORNERS_PIN = PinSet.of(CORNERS_ARTIFACT, Map.of(
        "corners", "the single-bone single-cube kit fixture ([-1,+1] cube, solid-white 64x64 "
            + "texture) built by EntityGeometryKit.buildTriangles, its 8 unique corners transformed "
            + "by Projection.VANILLA_ISO's camera pose"));

    /** Tolerance for the golden float compares - exact-ish, guards against ULP-scale drift creeping in. */
    private static final float EPS = 1e-6f;

    @Test
    @DisplayName("VANILLA_ISO camera pose is det=+1 (a plain iso display pose; chirality is on the Placement)")
    void pose_isDet_positive() {
        Matrix4f pose = Projection.VANILLA_ISO.resolve().camera().pose();
        assertThat("VANILLA_ISO resolves to rotationXYZ(30,225,0), a det=+1 display pose; the entity "
            + "chirality lives on the ENTITY_FLIP Placement in EntityRenderer, not the camera", det3(pose), greaterThan(0f));
    }

    /** Pins all 16 floats of the {@code VANILLA_ISO} pose to the captured baseline within {@link #EPS}. */
    @Test
    @DisplayName("golden: VANILLA_ISO pose 16 floats match the captured display-pose baseline")
    void pose_matchesGolden() {
        Matrix4f pose = Projection.VANILLA_ISO.resolve().camera().pose();
        POSE_PIN.floats("pose", flatten(pose), POSE_ORDER);
        POSE_PIN.requireBaseline();

        // The expected length is an ARGUMENT, which is what carries the old anti-vacuity guard: an
        // emptied golden array once returned rather than failing, so an emptied pin throws here.
        float[] expected = Pins.floats(POSE_ARTIFACT, "pose", 16);
        int index = 0;
        for (int col = 1; col <= 4; col++)
            for (int row = 1; row <= 4; row++, index++)
                assertThat("pose(" + col + "," + row + ")",
                    (double) pose.get(col, row), closeTo(expected[index], EPS));
    }

    /**
     * Pins the composed transform: the single-cube kit fixture built then posed by {@code VANILLA_ISO}
     * must land its 8 corners on the baseline. Catches drift in either the kit fixture or the pose that
     * the pose-only {@link #pose_matchesGolden} check alone would miss.
     */
    @Test
    @DisplayName("golden: single-cube fixture corners, kit-built then camera-posed, match the baseline")
    void fixtureCorners_matchGolden() {
        Matrix4f pose = Projection.VANILLA_ISO.resolve().camera().pose();
        float[] actual = fixtureCornerSample(pose);
        CORNERS_PIN.floats("corners", actual, CORNERS_ORDER);
        CORNERS_PIN.requireBaseline();

        // 24 as an argument, for the same reason the pose pin passes 16: an emptied golden used to
        // return rather than fail, which turned the pin into a no-op that still reported green.
        float[] expected = Pins.floats(CORNERS_ARTIFACT, "corners", 24);
        for (int i = 0; i < expected.length; i++)
            assertThat("corner sample [" + i + "]", (double) actual[i], closeTo(expected[i], EPS));
    }

    // ------------------------------------------------------------------------------------------
    // Fixture + helpers.
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the single-cube kit fixture (identical to {@code EntityGeometryKitTest}), collects its
     * unique corner positions in a deterministic order, and transforms each by {@code pose}. This is
     * the de-flipped-kit ⊕ camera composition the Placement / Camera split's no-op seam must preserve
     * bit-for-bit - the kit emits Y-up (det=+1) geometry and the camera is a plain det=+1 display pose,
     * so this sample fixes the two together as a golden.
     */
    private static float[] fixtureCornerSample(Matrix4f pose) {
        List<VisibleTriangle> tris = collect(buildSingleCube());
        // Deterministic corner set: dedupe the 8 cube corners by rounded key, ordered by first
        // appearance, so the sample is stable across runs regardless of triangle emission order.
        List<Vector3f> corners = new ArrayList<>();
        for (VisibleTriangle t : tris)
            for (Vector3f p : new Vector3f[]{ t.position0(), t.position1(), t.position2() }) {
                boolean seen = false;
                for (Vector3f c : corners)
                    if (near(c, p)) { seen = true; break; }
                if (!seen) corners.add(p);
            }
        corners.sort((a, b) -> {
            int cx = Float.compare(a.x(), b.x());
            if (cx != 0) return cx;
            int cy = Float.compare(a.y(), b.y());
            if (cy != 0) return cy;
            return Float.compare(a.z(), b.z());
        });
        float[] out = new float[corners.size() * 3];
        for (int i = 0; i < corners.size(); i++) {
            Vector3f p = corners.get(i).transform(pose);
            out[i * 3] = p.x();
            out[i * 3 + 1] = p.y();
            out[i * 3 + 2] = p.z();
        }
        return out;
    }

    private static boolean near(Vector3f a, Vector3f b) {
        return Math.abs(a.x() - b.x()) < 1e-4f && Math.abs(a.y() - b.y()) < 1e-4f && Math.abs(a.z() - b.z()) < 1e-4f;
    }

    /** Determinant of the upper-left 3×3 of {@code m} (sign = handedness of the linear part). */
    private static float det3(Matrix4f m) {
        float a = m.get(1, 1), b = m.get(2, 1), c = m.get(3, 1);
        float d = m.get(1, 2), e = m.get(2, 2), f = m.get(3, 2);
        float g = m.get(1, 3), h = m.get(2, 3), i = m.get(3, 3);
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    /**
     * Flattens a matrix in {@code get(col,row)} order - the order {@link #POSE_ORDER} states and the
     * pin stores.
     *
     * @param matrix the matrix to flatten
     * @return its 16 floats
     */
    private static float[] flatten(Matrix4f matrix) {
        float[] out = new float[16];
        int index = 0;
        for (int col = 1; col <= 4; col++)
            for (int row = 1; row <= 4; row++, index++)
                out[index] = matrix.get(col, row);
        return out;
    }

    /** Drains the build result's triangle stream into a random-access list. */
    private static List<VisibleTriangle> collect(EntityGeometryKit.BuildResult result) {
        List<VisibleTriangle> out = new ArrayList<>();
        for (VisibleTriangle tri : result.triangles()) out.add(tri);
        return out;
    }

    /**
     * Builds the same single-bone single-cube fixture as {@code EntityGeometryKitTest}: one
     * {@code body} bone holding one {@code [-HALF, +HALF]} cube at atlas origin on a solid-white 64×64
     * texture. Kept identical so both suites pin the same geometry.
     */
    private static EntityGeometryKit.BuildResult buildSingleCube() {
        ConcurrentMap<String, EntityModelData.FaceUv> faceUv = Concurrent.newMap();
        EntityModelData.Cube cube = new EntityModelData.Cube(
            new Vector3f(-HALF, -HALF, -HALF),
            new Vector3f(2f * HALF, 2f * HALF, 2f * HALF),
            Vector2f.ZERO,
            Vector3f.ZERO,
            false,
            Vector3f.ZERO,
            EulerRotation.NONE,
            faceUv
        );
        ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();
        cubes.add(cube);
        EntityModelData.Bone bone = new EntityModelData.Bone(
            Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f, cubes, null);
        ConcurrentLinkedMap<String, EntityModelData.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", bone);
        EntityModelData model = new EntityModelData(TextureSize.DEFAULT, 0f, bones, false);
        return EntityGeometryKit.buildTriangles(model, solidTexture(64, 64));
    }

    private static PixelBuffer solidTexture(int w, int h) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFFFFFFFF;
        return PixelBuffer.of(pixels, w, h);
    }

}
