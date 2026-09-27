package lib.minecraft.renderer.bake.mesh;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Vector2f;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.store.PinSet;
import lib.minecraft.renderer.store.Pins;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;

/**
 * Characterization golden that pins the entity model-to-screen transform - the single-cube kit
 * fixture built by {@link EntityGeometryKit} and posed by the {@link Projection#VANILLA_ISO} camera -
 * so accidental drift in the kit fixture or in its composition with the iso pose trips this assertion.
 *
 * <p>The value lives in {@code pin.kit-corners}, captured by this test and read back by it. The iso
 * pose on its own is pinned into {@code pin.vanilla-iso-pose} beside the camera, by
 * {@code engine.camera}'s {@code VanillaIsoPoseGoldenTest}. A deliberate change to the kit fixture or
 * to that pose re-baselines this pin - which is a promotion of the capture this test already wrote,
 * never a paste into Java source.
 */
class VanillaEntityTransformGoldenTest {

    /** Half-extent of the cube fixture in model units (cube spans {@code [-HALF, +HALF]} per axis). */
    private static final float HALF = 1f;

    private static final String CORNERS_ARTIFACT = "pin.kit-corners";

    /** How to read the flattened corners back, stored beside them. */
    private static final String CORNERS_ORDER = "8 corners, each (x,y,z), sorted by x then y then z";

    private static final PinSet CORNERS_PIN = PinSet.of(CORNERS_ARTIFACT, Map.of(
        "corners", "the single-bone single-cube kit fixture ([-1,+1] cube, solid-white 64x64 "
            + "texture) built by EntityGeometryKit.buildTriangles, its 8 unique corners transformed "
            + "by Projection.VANILLA_ISO's camera pose"));

    /** Tolerance for the golden float compares - exact-ish, guards against ULP-scale drift creeping in. */
    private static final float EPS = 1e-6f;

    /**
     * Pins the composed transform: the single-cube kit fixture built then posed by {@code VANILLA_ISO}
     * must land its 8 corners on the baseline. Catches drift in the kit fixture, which the pose-only
     * {@code pin.vanilla-iso-pose} cannot see, as well as drift in the pose itself.
     */
    @Test
    @DisplayName("golden: single-cube fixture corners, kit-built then camera-posed, match the baseline")
    void fixtureCorners_matchGolden() {
        Matrix4f pose = Projection.VANILLA_ISO.resolve().camera().pose();
        float[] actual = fixtureCornerSample(pose);
        CORNERS_PIN.floats("corners", actual, CORNERS_ORDER);
        CORNERS_PIN.requireBaseline();

        // 24 as an argument rather than the stored array's own length: an emptied golden used to
        // return rather than fail, which turned the pin into a no-op that still reported green.
        float[] expected = Pins.floats(CORNERS_ARTIFACT, "corners", 24);
        for (int i = 0; i < expected.length; i++)
            assertThat("corner sample [" + i + "]", (double) actual[i], closeTo(expected[i], EPS));
    }

    // ------------------------------------------------------------------------------------------
    // Fixture + helpers.
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the single-cube kit fixture, collects its unique corner positions in a deterministic
     * order, and transforms each by {@code pose}. This is the de-flipped-kit ⊕ camera composition the
     * Placement / Camera split's no-op seam must preserve bit-for-bit - the kit emits Y-up (det=+1) geometry and the camera is a plain det=+1 display pose,
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

    /**
     * Drains the build result's triangle stream into a random-access list for repeated iteration.
     *
     * @param result the kit's build result
     * @return its triangles, in emission order
     */
    static List<VisibleTriangle> collect(EntityGeometryKit.BuildResult result) {
        List<VisibleTriangle> out = new ArrayList<>();
        for (VisibleTriangle tri : result.triangles()) out.add(tri);
        return out;
    }

    /**
     * Builds the single-cube kit fixture this golden pins and {@link EntityGeometryKitTest} checks its
     * invariants on: one {@code body} bone (no rotation, unit scale, no parent) holding one axis-aligned
     * cube spanning {@code [-HALF, +HALF]} per axis, at atlas origin {@code (0, 0)} on a solid-white
     * 64x64 texture. No hierarchy, no overrides - the simplest input that still exercises all six
     * cardinal faces, so a defect surfaces as a focused assertion rather than a downstream entity
     * regression.
     *
     * <p>It lives in this file because this file computes the value {@code pin.kit-corners} stores: an
     * edit to the fixture moves that pin, and is then an edit to the file the pin is declared in.
     *
     * @return the kit's build of the fixture
     */
    static EntityGeometryKit.BuildResult buildSingleCube() {
        ConcurrentMap<String, EntityMesh.FaceUv> faceUv = Concurrent.newMap();
        EntityMesh.Cube cube = new EntityMesh.Cube(
            new Vector3f(-HALF, -HALF, -HALF),
            new Vector3f(2f * HALF, 2f * HALF, 2f * HALF),
            Vector2f.ZERO,
            Vector3f.ZERO,
            false,
            Vector3f.ZERO,
            EulerRotation.NONE,
            faceUv
        );
        ConcurrentList<EntityMesh.Cube> cubes = Concurrent.newList();
        cubes.add(cube);
        EntityMesh.Bone bone = new EntityMesh.Bone(
            Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f, cubes, null);
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", bone);
        EntityMesh model = new EntityMesh(TextureSize.DEFAULT, bones, false);
        return EntityGeometryKit.buildTriangles(model, solidTexture(64, 64));
    }

    /** Opaque-white {@code w x h} texture ({@code 0xFFFFFFFF} everywhere) so UV sampling never drops texels. */
    private static PixelBuffer solidTexture(int w, int h) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFFFFFFFF;
        return PixelBuffer.of(pixels, w, h);
    }

}
