package lib.minecraft.renderer.engine.kit;

import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Multi-hypothesis precision tests for the snap-rescued vertex drift on humanoid entities
 * (witch / evoker / vindicator / illusioner + silverfish + slime). The
 * {@link PivotFrameDriftTest} falsified the pivot-frame hypothesis from
 * {@code [[project_p5_col_vec_convention]]}; this suite continues the hunt by quantifying
 * other candidate drift sources side-by-side.
 *
 * <p>Each {@code @Test} stands alone as a report-only probe. None of them assert hard bounds;
 * they print {@code [HYP_X]} prefixed lines so the test report acts as a precision spec sheet.
 * The aim is to identify which transform step (chain multiply, iso compose order, normalize,
 * SIMD vs scalar) introduces the pixel-class divergence that snap quantizes away.
 */
class PrecisionHuntTest {

    private static final Vector3f CUBE_CORNER = new Vector3f(-2f, -2f, -2f);

    // --- Hypothesis B: per-bone Matrix4f.multiply accumulation ---

    @Test
    @DisplayName("[HYP_B] chain multiply accumulation: 1-vs-N matrix builds for same effective transform")
    void hypB_chainMultiplyAccumulation() {
        // Build the SAME composite transform as either (a) one fused matrix or (b) a chain of
        // 6 incremental Matrix4f.multiply calls. The accumulated path should drift by some ULPs.

        // 6-level rotation around Y by 30° each = total 180° rotation.
        float angle = (float) Math.toRadians(30);

        Matrix4f chained = Matrix4f.IDENTITY;
        for (int i = 0; i < 6; i++) {
            chained = chained.multiply(Matrix4f.createRotationY(angle));
        }

        Matrix4f fused = Matrix4f.createRotationY(angle * 6);

        Vector3f v = new Vector3f(1f, 0f, 0f);
        Vector3f vChain = Vector3f.transform(v, chained);
        Vector3f vFused = Vector3f.transform(v, fused);

        report("HYP_B(6xR_Y(30)) vs R_Y(180)", vChain, vFused);
    }

    @Test
    @DisplayName("[HYP_B'] mixed rotation+translation chain: 6-level incremental matrix accumulates differently")
    void hypB_mixedChainAccumulation() {
        // 6 levels of T(0,1,0)*R_X(15) compared to one fused build.
        float ty = 1f;
        float rx = (float) Math.toRadians(15);

        Matrix4f acc = Matrix4f.IDENTITY;
        for (int i = 0; i < 6; i++) {
            acc = acc.multiply(Matrix4f.createTranslation(new Vector3f(0f, ty, 0f)))
                     .multiply(Matrix4f.createRotationX(rx));
        }

        Vector3f v = new Vector3f(0.5f, 0.5f, 0.5f);
        Vector3f vChain = Vector3f.transform(v, acc);

        // Expected = repeatedly apply T*R: cube-local in nested frames.
        // Build the equivalent fused by precomputed compound transform values.
        Matrix4f explicit = Matrix4f.IDENTITY;
        Matrix4f singleLevel = Matrix4f.createTranslation(new Vector3f(0f, ty, 0f))
            .multiply(Matrix4f.createRotationX(rx));
        for (int i = 0; i < 6; i++) {
            explicit = explicit.multiply(singleLevel);
        }
        Vector3f vExplicit = Vector3f.transform(v, explicit);

        report("HYP_B'(6x(T*R) inline vs preComputed)", vChain, vExplicit);
    }

    // --- Hypothesis C: Iso compose chain order ---

    @Test
    @DisplayName("[HYP_C] vanilla harness iso chain vs our entityIsoChain")
    void hypC_isoChainCompare() {
        // Vanilla harness chain (per EntityFrameRenderer):
        //   pose.translate(tx, ty, 0)
        //   pose.scale(scale, scale, scale)
        //   pose.scale(1, 1, -1)
        //   pose.mulPose(rotationXYZ(210, 45, 0))
        // = T * S * S(1,1,-1) * R, applied to model vertex.

        float scale = 17.5f; // typical canvas scale
        float tx = 113.0f;
        float ty = 153.0f;
        float pitch = (float) Math.toRadians(210);
        float yaw = (float) Math.toRadians(45);

        // Build via JOML-equivalent path: T * S * S(1,1,-1) * R
        Matrix4f vanillaIso = Matrix4f.createTranslation(new Vector3f(tx, ty, 0f))
            .multiply(Matrix4f.createScale(scale, scale, scale))
            .multiply(Matrix4f.createScale(1f, 1f, -1f))
            .multiply(Quaternionf.rotationXYZ(pitch, yaw, 0f).toMatrix4f());

        // Build via OUR kit's separate camera/projection split:
        // Step 1: entityIsoChain() = S(1,-1,1) * S(1,1,-1) * R_X(pitch) * R_Y(yaw) * S(1,1,-1)
        // Step 2: kit does (transformed.x * modelScale - cx) * scale, FLIP_Y on Y, etc.
        // For a fair comparison, fuse our equivalent here:
        Matrix4f cameraEntity = Matrix4f.createScale(1f, -1f, 1f)
            .multiply(Matrix4f.createScale(1f, 1f, -1f))
            .multiply(Matrix4f.createRotationX(pitch))
            .multiply(Matrix4f.createRotationY(yaw))
            .multiply(Matrix4f.createScale(1f, 1f, -1f));
        // After camera, the kit does linear scale + offset (in screen frame).
        Matrix4f kitScreenScale = Matrix4f.createTranslation(new Vector3f(tx, ty, 0f))
            .multiply(Matrix4f.createScale(scale, scale, scale));
        Matrix4f oursFused = kitScreenScale.multiply(cameraEntity);

        // Apply to a representative bone vertex (witch hat: model y ~ 26)
        Vector3f vModel = new Vector3f(2f, 26f, -2f);
        Vector3f vVanilla = Vector3f.transform(vModel, vanillaIso);
        Vector3f vOurs = Vector3f.transform(vModel, oursFused);

        report("HYP_C(iso compose order)", vOurs, vVanilla);
    }

    // --- Hypothesis D: Vector3f.normalize precision ---

    @Test
    @DisplayName("[HYP_D] normal direction near cardinal axis - normalize drift between input shapes")
    void hypD_normalizeDriftNearCardinal() {
        // Two near-identical normals that differ only by LSB - test if normalize stabilizes them
        // to the same unit vector or amplifies the LSB. If amplified, back-face culling on a
        // triangle whose normal sits near the camera-perpendicular plane can flip front/back
        // classification at random.

        Vector3f n1 = new Vector3f(1f, 0f, 0f);
        // Tiny perturbation that should round to (1,0,0) after normalize
        Vector3f n2 = new Vector3f(Math.nextUp(1f), 0f, 0f);
        Vector3f n3 = new Vector3f(Math.nextDown(1f), 0f, 0f);

        Vector3f u1 = Vector3f.normalize(n1);
        Vector3f u2 = Vector3f.normalize(n2);
        Vector3f u3 = Vector3f.normalize(n3);

        System.out.printf("[HYP_D] n1=%s -> %s%n", fmt(n1), fmt(u1));
        System.out.printf("[HYP_D] n2=%s -> %s  ulps_x_vs_u1=%d%n",
            fmt(n2), fmt(u2), ulpsBetween(u1.x(), u2.x()));
        System.out.printf("[HYP_D] n3=%s -> %s  ulps_x_vs_u1=%d%n",
            fmt(n3), fmt(u3), ulpsBetween(u1.x(), u3.x()));
    }

    @Test
    @DisplayName("[HYP_D'] normal computed from cross product on near-degenerate triangle")
    void hypD_normalFromNearDegenerate() {
        // A typical post-iso-rotation triangle's normal computed via cross product.
        // Use two near-parallel edges to test cross-product LSB amplification.
        Vector3f a = new Vector3f(1f, 0f, 0f);
        Vector3f b1 = new Vector3f(0.5f, 0.866025f, 0f);
        Vector3f b2 = new Vector3f(0.5f, Math.nextUp(0.866025f), 0f);

        Vector3f n1 = Vector3f.normalize(Vector3f.cross(a, b1));
        Vector3f n2 = Vector3f.normalize(Vector3f.cross(a, b2));

        System.out.printf("[HYP_D'] n1=%s n2=%s  ulps=(%d, %d, %d)%n",
            fmt(n1), fmt(n2),
            ulpsBetween(n1.x(), n2.x()),
            ulpsBetween(n1.y(), n2.y()),
            ulpsBetween(n1.z(), n2.z()));
    }

    // --- Hypothesis F: Kit per-axis scale/center vs fused matrix multiply ---

    @Test
    @DisplayName("[HYP_F] kit per-axis (v*modelScale - center)*scale vs fused matrix")
    void hypF_kitPerAxisVsFused() {
        // Kit currently does:  nx = (FLIP ? -1 : 1) * (transformed.x() * modelScale - cx) * scale
        // This is 2 multiplies + 1 subtract per axis = 9 float ops per vertex.
        // A fused matrix would absorb modelScale+center+scale+FLIP into one matrix-vec multiply.
        // Test: do these produce bit-identical results?

        float modelScale = 0.0625f;
        float cx = -2.3f, cy = 14.7f, cz = -5.1f;
        float scale = 280f;
        float vx = 17.3f, vy = 22.1f, vz = -8.7f;

        // Approach A: kit per-axis
        float nxA = (vx * modelScale - cx) * scale;
        float nyA = -(vy * modelScale - cy) * scale; // FLIP_Y
        float nzA = (vz * modelScale - cz) * scale;

        // Approach B: fused matrix S*T (T applied first, then S; or matrix combining both)
        // Fused: nx_fused = (vx * modelScale * scale) + (-cx * scale)
        // = vx * (modelScale * scale) - cx * scale
        float fxA = modelScale * scale;
        float nxB = vx * fxA - cx * scale;
        float nyB = -(vy * fxA - cy * scale);
        float nzB = vz * fxA - cz * scale;

        // Approach C: full matrix multiply
        Matrix4f T = Matrix4f.createTranslation(new Vector3f(-cx, -cy, -cz));
        Matrix4f S = Matrix4f.createScale(modelScale, modelScale, modelScale);
        Matrix4f FlipScale = Matrix4f.createScale(scale, -scale, scale);
        Matrix4f fused = FlipScale.multiply(T).multiply(S);
        Vector3f outC = Vector3f.transform(new Vector3f(vx, vy, vz), fused);

        System.out.printf("[HYP_F] kit-per-axis  : (%.10f, %.10f, %.10f)%n", nxA, nyA, nzA);
        System.out.printf("[HYP_F] fused-precalc : (%.10f, %.10f, %.10f)%n", nxB, nyB, nzB);
        System.out.printf("[HYP_F] full-matrix   : (%.10f, %.10f, %.10f)%n", outC.x(), outC.y(), outC.z());
        System.out.printf("[HYP_F] ulps A-vs-B  : (%d, %d, %d)%n",
            ulpsBetween(nxA, nxB), ulpsBetween(nyA, nyB), ulpsBetween(nzA, nzB));
        System.out.printf("[HYP_F] ulps A-vs-C  : (%d, %d, %d)%n",
            ulpsBetween(nxA, outC.x()), ulpsBetween(nyA, outC.y()), ulpsBetween(nzA, outC.z()));
    }

    // --- Hypothesis E: Camera.entityIsoChain() vs vanilla effectiveRotation float coords ---

    @Test
    @DisplayName("[HYP_E] our entityIsoChain matrix has bit-identical rot block to vanilla's quat")
    void hypE_cameraEntityRotationBlock() {
        float pitch = (float) Math.toRadians(210);
        float yaw = (float) Math.toRadians(45);

        // Vanilla approach: Quaternionf.rotationXYZ(pitch, yaw, 0)
        Matrix4f vanillaR = Quaternionf.rotationXYZ(pitch, yaw, 0f).toMatrix4f();

        // Our approach: createRotationX(pitch) * createRotationY(yaw)
        Matrix4f oursR = Matrix4f.createRotationX(pitch).multiply(Matrix4f.createRotationY(yaw));

        // Compare entries
        System.out.println("[HYP_E] vanilla quat-derived rotation matrix vs our R_X*R_Y:");
        int maxUlps = 0;
        for (int col = 1; col <= 3; col++) {
            for (int row = 1; row <= 3; row++) {
                int u = ulpsBetween(vanillaR.get(col, row), oursR.get(col, row));
                if (u > maxUlps) maxUlps = u;
                System.out.printf("  col=%d row=%d  vanilla=%.10f  ours=%.10f  ulps=%d%n",
                    col, row, vanillaR.get(col, row), oursR.get(col, row), u);
            }
        }
        System.out.printf("[HYP_E] max ULPs across rotation block = %d%n", maxUlps);

        // Then test vertex output
        Vector3f vModel = new Vector3f(2f, 26f, -2f);
        Vector3f vVanilla = Vector3f.transform(vModel, vanillaR);
        Vector3f vOurs = Vector3f.transform(vModel, oursR);
        report("HYP_E(rotation only, R_X*R_Y vs quat-XYZ)", vOurs, vVanilla);
    }

    // --- Helpers ---

    private static void report(String label, Vector3f ours, Vector3f vanilla) {
        int ux = ulpsBetween(ours.x(), vanilla.x());
        int uy = ulpsBetween(ours.y(), vanilla.y());
        int uz = ulpsBetween(ours.z(), vanilla.z());
        float dx = Math.abs(ours.x() - vanilla.x());
        float dy = Math.abs(ours.y() - vanilla.y());
        float dz = Math.abs(ours.z() - vanilla.z());
        System.out.printf("[%s] ours=%s vanilla=%s d=(%g, %g, %g) ulps=(%d, %d, %d)%n",
            label, fmt(ours), fmt(vanilla), dx, dy, dz, ux, uy, uz);
    }

    private static int ulpsBetween(float a, float b) {
        if (a == b) return 0;
        int ia = Float.floatToRawIntBits(a);
        int ib = Float.floatToRawIntBits(b);
        if ((ia ^ ib) < 0) return Integer.MAX_VALUE;
        return Math.abs(ia - ib);
    }

    private static String fmt(Vector3f v) {
        return String.format("(%.10f, %.10f, %.10f)", v.x(), v.y(), v.z());
    }
}
