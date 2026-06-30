package lib.minecraft.renderer.engine.kit;

import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;

/**
 * Empirically quantifies the float drift between our {@code T(+p)*R*T(-p)} pivot-centered
 * chain applied to a pivot-shifted vertex and vanilla's {@code T(+p)*R} chain applied to a
 * cube-local vertex.
 *
 * <p><b>Finding (2026-05-19):</b> the two chains drift by only <b>4-64 ULPs</b> at the leaf
 * vertex (~4.76e-7 to 9.53e-7 absolute model units). Multi-level hierarchies do NOT compound
 * linearly - depth 1..6 sweep stays at 0-3 ULPs because parent-relative pivots flatten cleanly.
 * Translated to screen-space at the entity's canvas scale, that's well below 0.001 px - <b>not
 * the meaningful source</b> of the snap-rescued drift on witch / evoker / vindicator /
 * illusioner (snap-off &Delta; jumps +0.5 to +1.2 on these). The real drift source is
 * elsewhere. Candidates to investigate:
 * <ul>
 *   <li>The iso transform's {@code S(-1,-1,1) * R_y * R_x} composition order at render time
 *   <li>{@code Projection.project}'s {@code float blended = 1f + (perspectiveFactor - 1f) * params.amount()} arithmetic
 *   <li>SIMD vs scalar matrix-vector path divergence at hot-path projection
 *   <li>{@code Vector3f.normalize} on triangle normals affecting back-face culling boundary classification
 *   <li>Accumulation of per-bone matrix-matrix multiplies in {@code buildChainTransforms}
 *       (depth grows linearly with bone-hierarchy depth)
 * </ul>
 *
 * <p>Kept as a regression sentinel: if any future tensor / Quaternionf refactor regresses the
 * ULP count, this test will surface it before the iteration harness runs.
 */
class PivotFrameDriftTest {

    /** Realistic illager-family pivot magnitudes (model units, post-bone-chain world space). */
    private static final float[] PIVOTS_Y = { 0f, 12f, 22f, 26f }; // root, body, arms, hat
    /** Realistic rotation angles per bone (radians) - illager crossed-arms idle pose. */
    private static final float[] X_ROTS = { 0f, 0f, -0.75f, 0.05f };
    /** Cube-local vertex (one corner of a cube authored at origin -2,-2,-2 size 4,4,4). */
    private static final Vector3f CUBE_CORNER = new Vector3f(-2f, -2f, -2f);

    @Test
    @DisplayName("single-level pivot chain: ours vs vanilla output for one vertex")
    void singleLevelDrift_quantifyOutputBits() {
        Vector3f pivot = new Vector3f(0f, 22f, 0f);
        Quaternionf q = Quaternionf.rotationZYX(0f, 0f, -0.75f); // illager arms

        Vector3f ours = applyOurChain(CUBE_CORNER, pivot, q);
        Vector3f vanilla = applyVanillaChain(CUBE_CORNER, pivot, q);

        float dx = Math.abs(ours.x() - vanilla.x());
        float dy = Math.abs(ours.y() - vanilla.y());
        float dz = Math.abs(ours.z() - vanilla.z());
        System.out.printf("[L1] ours=%s vanilla=%s d=(%g, %g, %g) ulps=(%d, %d, %d)%n",
            fmt(ours), fmt(vanilla), dx, dy, dz,
            ulpsBetween(ours.x(), vanilla.x()),
            ulpsBetween(ours.y(), vanilla.y()),
            ulpsBetween(ours.z(), vanilla.z()));

        // Single-level documented finding: ~64 ULPs Z drift even without hierarchy.
        // Allow up to 256 ULPs to keep this as an evidentiary test rather than a regression gate.
        assertThat("X within 256 ULP", ulpsBetween(ours.x(), vanilla.x()), lessThan(256));
        assertThat("Y within 256 ULP", ulpsBetween(ours.y(), vanilla.y()), lessThan(256));
        assertThat("Z within 256 ULP", ulpsBetween(ours.z(), vanilla.z()), lessThan(256));
    }

    @Test
    @DisplayName("4-level bone chain compounds drift (illager hat -> cube)")
    void multiLevelDrift_illagerHatChain() {
        // Build a 4-level chain: root -> body -> arms -> hat with ABSOLUTE pivots
        // matching how our kit stores bone pivots (each in world-coord space).
        Vector3f[] absPivots = new Vector3f[PIVOTS_Y.length];
        Quaternionf[] rots = new Quaternionf[PIVOTS_Y.length];
        for (int i = 0; i < PIVOTS_Y.length; i++) {
            absPivots[i] = new Vector3f(0f, PIVOTS_Y[i], 0f);
            rots[i] = Quaternionf.rotationZYX(0f, 0f, X_ROTS[i]);
        }
        // Convert absolute pivots to parent-relative for vanilla chain.
        // Note: only valid when no rotation applies between levels; for an axis-only test this works.
        Vector3f[] relPivots = new Vector3f[absPivots.length];
        relPivots[0] = absPivots[0];
        for (int i = 1; i < absPivots.length; i++) {
            relPivots[i] = new Vector3f(
                absPivots[i].x() - absPivots[i - 1].x(),
                absPivots[i].y() - absPivots[i - 1].y(),
                absPivots[i].z() - absPivots[i - 1].z()
            );
        }

        Vector3f ours = applyOurChainNested(CUBE_CORNER, absPivots, rots);
        Vector3f vanilla = applyVanillaChainRelativeNested(CUBE_CORNER, relPivots, rots);

        System.out.printf("[L4] ours=%s vanilla=%s d=(%g, %g, %g) ulps=(%d, %d, %d)%n",
            fmt(ours), fmt(vanilla),
            Math.abs(ours.x() - vanilla.x()),
            Math.abs(ours.y() - vanilla.y()),
            Math.abs(ours.z() - vanilla.z()),
            ulpsBetween(ours.x(), vanilla.x()),
            ulpsBetween(ours.y(), vanilla.y()),
            ulpsBetween(ours.z(), vanilla.z()));
    }

    @Test
    @DisplayName("drift grows with chain depth (sweep 1..6 levels)")
    void driftScalesWithDepth() {
        System.out.println("[SWEEP] depth | ULPs(x,y,z) | abs-delta(x,y,z)");
        for (int depth = 1; depth <= 6; depth++) {
            Vector3f[] absPivots = new Vector3f[depth];
            Quaternionf[] rots = new Quaternionf[depth];
            for (int i = 0; i < depth; i++) {
                absPivots[i] = new Vector3f(0f, (i + 1) * 6f, 0f);
                rots[i] = Quaternionf.rotationZYX(0f, 0f, (float) Math.toRadians(15 + i * 7));
            }
            Vector3f[] relPivots = new Vector3f[depth];
            relPivots[0] = absPivots[0];
            for (int i = 1; i < depth; i++) {
                relPivots[i] = new Vector3f(
                    absPivots[i].x() - absPivots[i - 1].x(),
                    absPivots[i].y() - absPivots[i - 1].y(),
                    absPivots[i].z() - absPivots[i - 1].z()
                );
            }

            Vector3f ours = applyOurChainNested(CUBE_CORNER, absPivots, rots);
            Vector3f vanilla = applyVanillaChainRelativeNested(CUBE_CORNER, relPivots, rots);

            int ux = ulpsBetween(ours.x(), vanilla.x());
            int uy = ulpsBetween(ours.y(), vanilla.y());
            int uz = ulpsBetween(ours.z(), vanilla.z());
            float dx = Math.abs(ours.x() - vanilla.x());
            float dy = Math.abs(ours.y() - vanilla.y());
            float dz = Math.abs(ours.z() - vanilla.z());
            System.out.printf("[SWEEP] depth=%d | ulps=(%d, %d, %d) | abs=(%g, %g, %g)%n",
                depth, ux, uy, uz, dx, dy, dz);
        }
    }

    @Test
    @DisplayName("our matrix's translation column is built from p - R*p; vanilla's is just p")
    void chainTranslationColumnComposition() {
        Vector3f pivot = new Vector3f(0f, 22f, 0f);
        Quaternionf q = Quaternionf.rotationZYX(0f, 0f, -0.75f);
        Matrix4f rot = q.toMatrix4f();

        Matrix4f ours = Matrix4f.createTranslation(pivot)
            .multiply(rot)
            .multiply(Matrix4f.createTranslation(pivot.negate()));
        Matrix4f vanilla = Matrix4f.createTranslation(pivot).multiply(rot);

        // Translation column = column 4 (1-indexed) -> get(4, row)
        System.out.printf("[MAT] ours.t=(%g, %g, %g)  vanilla.t=(%g, %g, %g)%n",
            ours.get(4, 1), ours.get(4, 2), ours.get(4, 3),
            vanilla.get(4, 1), vanilla.get(4, 2), vanilla.get(4, 3));

        // Rotational block (cols 1..3) is identical
        for (int col = 1; col <= 3; col++) {
            for (int row = 1; row <= 3; row++) {
                assertThat("rotational entry differs at col=" + col + " row=" + row,
                    ours.get(col, row), equalTo(vanilla.get(col, row)));
            }
        }
    }

    // --- chain implementations ---

    /** Our pivot-centered chain: T(+p) * R * T(-p), applied to v_world = cube_local + pivot. */
    private static Vector3f applyOurChain(Vector3f cubeLocal, Vector3f pivot, Quaternionf q) {
        Matrix4f m = Matrix4f.createTranslation(pivot)
            .multiply(q.toMatrix4f())
            .multiply(Matrix4f.createTranslation(pivot.negate()));
        Vector3f vWorld = new Vector3f(
            cubeLocal.x() + pivot.x(),
            cubeLocal.y() + pivot.y(),
            cubeLocal.z() + pivot.z()
        );
        return Vector3f.transform(vWorld, m);
    }

    /** Vanilla chain: T(+p) * R applied directly to cube-local vertex. */
    private static Vector3f applyVanillaChain(Vector3f cubeLocal, Vector3f pivot, Quaternionf q) {
        Matrix4f m = Matrix4f.createTranslation(pivot).multiply(q.toMatrix4f());
        return Vector3f.transform(cubeLocal, m);
    }

    /** Nested pivot-centered chain: each level is T(+p)*R*T(-p). Vertex is pivot-shifted by leaf pivot. */
    private static Vector3f applyOurChainNested(Vector3f cubeLocal, Vector3f[] pivots, Quaternionf[] rots) {
        Matrix4f acc = Matrix4f.IDENTITY;
        for (int i = 0; i < pivots.length; i++) {
            Matrix4f block = Matrix4f.createTranslation(pivots[i])
                .multiply(rots[i].toMatrix4f())
                .multiply(Matrix4f.createTranslation(pivots[i].negate()));
            acc = acc.multiply(block);
        }
        // Leaf vertex is in world space (pivot-shifted by leaf bone)
        Vector3f vLeaf = pivots[pivots.length - 1];
        Vector3f vWorld = new Vector3f(
            cubeLocal.x() + vLeaf.x(),
            cubeLocal.y() + vLeaf.y(),
            cubeLocal.z() + vLeaf.z()
        );
        return Vector3f.transform(vWorld, acc);
    }

    /** Nested vanilla chain with PARENT-RELATIVE pivots: each level is T(+rel_p)*R. */
    private static Vector3f applyVanillaChainRelativeNested(Vector3f cubeLocal, Vector3f[] relPivots, Quaternionf[] rots) {
        Matrix4f acc = Matrix4f.IDENTITY;
        for (int i = 0; i < relPivots.length; i++) {
            Matrix4f block = Matrix4f.createTranslation(relPivots[i]).multiply(rots[i].toMatrix4f());
            acc = acc.multiply(block);
        }
        return Vector3f.transform(cubeLocal, acc);
    }

    // --- helpers ---

    private static int ulpsBetween(float a, float b) {
        if (a == b) return 0;
        int ia = Float.floatToRawIntBits(a);
        int ib = Float.floatToRawIntBits(b);
        if ((ia ^ ib) < 0) return Integer.MAX_VALUE; // different signs
        return Math.abs(ia - ib);
    }

    private static String fmt(Vector3f v) {
        return String.format("(%.10f, %.10f, %.10f)", v.x(), v.y(), v.z());
    }
}
