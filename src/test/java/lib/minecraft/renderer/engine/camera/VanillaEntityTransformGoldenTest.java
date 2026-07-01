package lib.minecraft.renderer.engine.camera;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.kit.EntityGeometryKit;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.request.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Characterization golden (from the Placement/Camera split, {@code notes/placement-camera-split.md})
 * that pins the entity model→screen transform - the {@link Projection#VANILLA_ENTITY} camera pose and
 * its composition with the single-cube kit fixture - so accidental drift in the iso pose or the kit
 * fixture trips these assertions.
 *
 * <p>The values baked below are captured once via {@link #writeSnapshot}. Now that the split has landed,
 * {@code VANILLA_ENTITY} resolves to the plain {@code rotationXYZ(210, 45, 0)} iso display pose (det=+1);
 * the entity's model-to-world facing / chirality lives on the {@code ENTITY_FLIP} {@code Placement} in
 * {@code EntityRenderer}, not the camera. A deliberate change to that pose or the kit fixture re-baselines
 * these values (regenerate via {@link #writeSnapshot}).
 *
 * <p>The load-bearing structural assertion is {@link #pose_isDet_positive}: the camera is a plain det=+1
 * rotation. A det≤0 would mean chirality leaked back onto the camera (regressing the split).
 */
class VanillaEntityTransformGoldenTest {

    /** Half-extent of the cube fixture (matches {@code EntityGeometryKitTest}). */
    private static final float HALF = 1f;

    /** Tolerance for the golden float compares - exact-ish, guards against ULP-scale drift creeping in. */
    private static final float EPS = 1e-6f;

    @Test
    @DisplayName("VANILLA_ENTITY camera pose is det=+1 (a plain iso display pose; chirality is on the Placement)")
    void pose_isDet_positive() {
        Matrix4f pose = Projection.VANILLA_ENTITY.resolve().pose();
        assertThat("VANILLA_ENTITY resolves to rotationXYZ(210,45,0), a det=+1 display pose; the entity "
            + "chirality lives on the ENTITY_FLIP Placement in EntityRenderer, not the camera", det3(pose), greaterThan(0f));
    }

    @Test
    @DisplayName("golden: VANILLA_ENTITY pose 16 floats match the captured fused-chain baseline")
    void pose_matchesGolden() {
        Matrix4f pose = Projection.VANILLA_ENTITY.resolve().pose();
        assertMatrix(GOLDEN_POSE, pose);
    }

    @Test
    @DisplayName("golden: single-cube fixture corners, kit-built then camera-posed, match the baseline")
    void fixtureCorners_matchGolden() {
        if (GOLDEN_CORNERS.length == 0) return; // placeholder not yet captured - skip until baked
        Matrix4f pose = Projection.VANILLA_ENTITY.resolve().pose();
        float[] actual = fixtureCornerSample(pose);
        for (int i = 0; i < GOLDEN_CORNERS.length; i++)
            assertThat("corner sample [" + i + "]", (double) actual[i],
                org.hamcrest.Matchers.closeTo(GOLDEN_CORNERS[i], EPS));
    }

    // ------------------------------------------------------------------------------------------
    // Golden values - captured from the current fused chain. Re-baseline (with delta documented)
    // only as a conscious Phase 2 step. Regenerate via writeSnapshot() below.
    // ------------------------------------------------------------------------------------------

    /** {@link Projection#VANILLA_ENTITY} pose in get(col,row) order: [c1r1,c1r2,c1r3,c1r4, c2r1,...]. */
    private static final float[] GOLDEN_POSE = {
        -0.70710695f, -0.3535533f, 0.61237234f, 0.0f,
        -1.4901161E-8f, 0.86602545f, 0.49999997f, 0.0f,
        -0.70710665f, 0.35355344f, -0.6123725f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f,
    };

    /** Flattened (x,y,z) of the 8 fixture-cube corners transformed by the pose. */
    private static final float[] GOLDEN_CORNERS = {
        0.6363961f, -0.3897115f, -0.22499989f,
        1.1920929E-7f, -0.071513414f, -0.77613515f,
        0.6363961f, 0.38971138f, 0.22500008f,
        1.1920929E-7f, 0.70790946f, -0.32613516f,
        -1.1920929E-7f, -0.70790946f, 0.32613516f,
        -0.6363961f, -0.38971138f, -0.22500008f,
        -1.1920929E-7f, 0.071513414f, 0.77613515f,
        -0.6363961f, 0.3897115f, 0.22499989f,
    };

    /**
     * Regeneration harness: writes the current fused-chain snapshot to {@code build/golden/} in
     * copy-paste-ready Java-array form. Not an assertion - run once to (re-)capture the golden, paste
     * the arrays above. Kept in the suite so the golden is reproducible from source, not folklore.
     */
    @Test
    @DisplayName("capture: write current fused-chain snapshot to build/golden/ (regeneration aid)")
    void writeSnapshot() {
        Matrix4f pose = Projection.VANILLA_ENTITY.resolve().pose();
        StringBuilder sb = new StringBuilder();
        sb.append("// GOLDEN_POSE\n");
        for (int col = 1; col <= 4; col++) {
            for (int row = 1; row <= 4; row++)
                sb.append(fmt(pose.get(col, row))).append("f, ");
            sb.append('\n');
        }
        sb.append("// GOLDEN_CORNERS\n");
        float[] corners = fixtureCornerSample(pose);
        for (int i = 0; i < corners.length; i += 3)
            sb.append(fmt(corners[i])).append("f, ").append(fmt(corners[i + 1]))
                .append("f, ").append(fmt(corners[i + 2])).append("f,\n");
        try {
            Path out = Path.of("build/golden/vanilla-entity-transform.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fixture + helpers.
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the single-cube kit fixture (identical to {@code EntityGeometryKitTest}), collects its
     * unique corner positions in a deterministic order, and transforms each by {@code pose}. This is
     * the kit-FLIP_Y ⊕ camera composition the Phase 1 no-op seam must preserve bit-for-bit.
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

    private static void assertMatrix(float[] expected, Matrix4f actual) {
        if (expected.length == 0) return; // placeholder not yet captured - skip until baked
        int k = 0;
        for (int col = 1; col <= 4; col++)
            for (int row = 1; row <= 4; row++, k++)
                assertThat("pose(" + col + "," + row + ")",
                    (double) actual.get(col, row), org.hamcrest.Matchers.closeTo(expected[k], EPS));
    }

    private static String fmt(float v) {
        return Float.toString(v);
    }

    private static List<VisibleTriangle> collect(EntityGeometryKit.BuildResult result) {
        List<VisibleTriangle> out = new ArrayList<>();
        for (VisibleTriangle tri : result.triangles()) out.add(tri);
        return out;
    }

    private static EntityGeometryKit.BuildResult buildSingleCube() {
        ConcurrentMap<String, EntityModelData.FaceUv> faceUv = Concurrent.newMap();
        EntityModelData.Cube cube = new EntityModelData.Cube(
            new Vector3f(-HALF, -HALF, -HALF),
            new Vector3f(2f * HALF, 2f * HALF, 2f * HALF),
            Vector2f.ZERO,
            0f,
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
        EntityModelData model = new EntityModelData(64, 64, 0f, bones, false);
        return EntityGeometryKit.buildTriangles(model, solidTexture(64, 64));
    }

    private static PixelBuffer solidTexture(int w, int h) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFFFFFFFF;
        return PixelBuffer.of(pixels, w, h);
    }

}
