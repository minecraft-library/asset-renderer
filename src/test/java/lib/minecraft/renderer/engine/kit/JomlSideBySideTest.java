package lib.minecraft.renderer.engine.kit;

import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bit-comparison between our tensor math and {@code org.joml} (vanilla's actual matrix
 * backend). Vanilla's {@code PoseStack.Pose.pose} is an {@code org.joml.Matrix4f}; every
 * vertex transform vanilla performs is JOML arithmetic. If our equivalent operations
 * differ from JOML at the bit level, the difference accumulates through the chain and
 * shows up as snap-rescued vertex drift.
 *
 * <p><b>Test method:</b> build the same matrix chain via JOML and our tensor classes side
 * by side, transform the same vertex, ULP-compare the results. Reports as {@code [JOML_*]}
 * lines so we can see each step's divergence.
 *
 * <p>Targets investigated:
 * <ul>
 *   <li>{@code Matrix4f.createRotationX(angle)} vs {@code new org.joml.Matrix4f().rotationX(angle)}
 *   <li>{@code Matrix4f.createRotationY/Z}
 *   <li>{@code Matrix4f.createTranslation} vs {@code new Matrix4f().translation(x, y, z)}
 *   <li>{@code Matrix4f.createScale} vs {@code new Matrix4f().scaling(x, y, z)}
 *   <li>Quaternion-to-matrix: {@code Quaternionf.rotationXYZ(...).toMatrix4f()} vs
 *       {@code new Matrix4f().rotation(new Quaternionf().rotationXYZ(...))}
 *   <li>Full iso chain composition (matching vanilla harness's PoseStack ops)
 *   <li>Matrix-vector multiply
 * </ul>
 *
 * <p>Vanilla's exact iso chain per {@code EntityFrameRenderer}:
 * <pre>
 *   poseStack.translate(translateX, translateY, 0)
 *   poseStack.scale(scale, scale, scale)
 *   poseStack.scale(1, 1, -1)
 *   poseStack.mulPose(new Quaternionf().rotationXYZ(210deg, 45deg, 0))
 *   ... then per-bone translateAndRotate (translate + mulPose) ...
 * </pre>
 */
class JomlSideBySideTest {

    @Test
    @DisplayName("[JOML_R_X] Matrix4f.createRotationX vs org.joml.Matrix4f.rotationX")
    void rotationXBitCompare() {
        float angle = (float) Math.toRadians(210);
        org.joml.Matrix4f vanillaR = new org.joml.Matrix4f().rotationX(angle);
        Matrix4f oursR = Matrix4f.createRotationX(angle);
        compareMatrices("JOML_R_X(210)", vanillaR, oursR);
    }

    @Test
    @DisplayName("[JOML_R_Y] Matrix4f.createRotationY vs org.joml.Matrix4f.rotationY")
    void rotationYBitCompare() {
        float angle = (float) Math.toRadians(45);
        org.joml.Matrix4f vanillaR = new org.joml.Matrix4f().rotationY(angle);
        Matrix4f oursR = Matrix4f.createRotationY(angle);
        compareMatrices("JOML_R_Y(45)", vanillaR, oursR);
    }

    @Test
    @DisplayName("[JOML_QUAT_XYZ] Quaternionf.rotationXYZ.toMatrix4f vs JOML Matrix4f.rotation(Quaternionf)")
    void quatXyzToMatrixBitCompare() {
        float pitch = (float) Math.toRadians(210);
        float yaw = (float) Math.toRadians(45);

        org.joml.Matrix4f vanillaR = new org.joml.Matrix4f()
            .rotation(new org.joml.Quaternionf().rotationXYZ(pitch, yaw, 0f));

        Matrix4f oursR = Quaternionf.rotationXYZ(pitch, yaw, 0f).toMatrix4f();

        compareMatrices("JOML_QUAT_XYZ(210,45,0)", vanillaR, oursR);
    }

    @Test
    @DisplayName("[JOML_ISO_CHAIN] full vanilla iso pose chain bit-compare")
    void fullIsoChainBitCompare() {
        // Vanilla's exact chain (from EntityFrameRenderer):
        //   pose.translate(translateX, translateY, 0)
        //   pose.scale(scale, scale, scale)
        //   pose.scale(1, 1, -1)
        //   pose.mulPose(rotationXYZ(210, 45, 0))
        float tx = 113.0f, ty = 153.0f;
        float scale = 17.5f;
        float pitch = (float) Math.toRadians(210);
        float yaw = (float) Math.toRadians(45);

        // Vanilla path: JOML PoseStack-equivalent operations
        org.joml.Matrix4f vanilla = new org.joml.Matrix4f();
        vanilla.translate(tx, ty, 0f);
        vanilla.scale(scale, scale, scale);
        vanilla.scale(1f, 1f, -1f);
        vanilla.rotate(new org.joml.Quaternionf().rotationXYZ(pitch, yaw, 0f));

        // Ours: chain createTranslation, createScale, createScale, Quaternionf-toMatrix
        // (post-multiply via .multiply): T * S * S(1,1,-1) * R
        Matrix4f ours = Matrix4f.createTranslation(new Vector3f(tx, ty, 0f))
            .multiply(Matrix4f.createScale(scale, scale, scale))
            .multiply(Matrix4f.createScale(1f, 1f, -1f))
            .multiply(Quaternionf.rotationXYZ(pitch, yaw, 0f).toMatrix4f());

        compareMatrices("JOML_ISO_CHAIN", vanilla, ours);

        // Now transform a test vertex (witch head main top-front corner)
        float vx = -4f, vy = -10f, vz = -4f;
        org.joml.Vector3f vanillaOut = new org.joml.Vector3f();
        vanilla.transformPosition(vx, vy, vz, vanillaOut);
        Vector3f oursOut = Vector3f.transform(new Vector3f(vx, vy, vz), ours);

        System.out.printf("[JOML_ISO_CHAIN_VERT] vanilla=(%.10f, %.10f, %.10f)  ours=(%.10f, %.10f, %.10f)  ulps=(%d, %d, %d)%n",
            vanillaOut.x, vanillaOut.y, vanillaOut.z,
            oursOut.x(), oursOut.y(), oursOut.z(),
            ulpsBetween(vanillaOut.x, oursOut.x()),
            ulpsBetween(vanillaOut.y, oursOut.y()),
            ulpsBetween(vanillaOut.z, oursOut.z()));
    }

    @Test
    @DisplayName("[JOML_VEC_MUL] Vector3f.transform vs JOML transformPosition")
    void vectorMultiplyBitCompare() {
        // Build a complex matrix and transform a vertex through both.
        org.joml.Matrix4f jM = new org.joml.Matrix4f();
        jM.translate(10f, 20f, 30f);
        jM.scale(2.5f);
        jM.rotateY((float) Math.toRadians(37));
        jM.rotateX((float) Math.toRadians(53));

        // Build equivalent ours matrix entry-by-entry from JOML's result
        float[] m = new float[16];
        jM.get(m); // column-major
        Matrix4f oursM = new Matrix4f(
            m[0], m[1], m[2], m[3],
            m[4], m[5], m[6], m[7],
            m[8], m[9], m[10], m[11],
            m[12], m[13], m[14], m[15]
        );

        // Confirm matrices are bit-identical at this point
        compareMatrices("JOML_MAT_TRANSFER", jM, oursM);

        // Transform same vertex through both
        float vx = 1.7f, vy = 22.3f, vz = -5.9f;
        org.joml.Vector3f vanillaOut = new org.joml.Vector3f();
        jM.transformPosition(vx, vy, vz, vanillaOut);
        Vector3f oursOut = Vector3f.transform(new Vector3f(vx, vy, vz), oursM);

        System.out.printf("[JOML_VEC_MUL] vanilla=(%.10f, %.10f, %.10f)  ours=(%.10f, %.10f, %.10f)  ulps=(%d, %d, %d)%n",
            vanillaOut.x, vanillaOut.y, vanillaOut.z,
            oursOut.x(), oursOut.y(), oursOut.z(),
            ulpsBetween(vanillaOut.x, oursOut.x()),
            ulpsBetween(vanillaOut.y, oursOut.y()),
            ulpsBetween(vanillaOut.z, oursOut.z()));
    }

    @Test
    @DisplayName("[JOML_BONE_CHAIN] vanilla per-bone translateAndRotate chain reproduction")
    void boneChainBitCompare() {
        // Simulate a bone chain: root -> body -> head with translate+rotate at each.
        // Vanilla: poseStack.translate(p.x/16, p.y/16, p.z/16); poseStack.mulPose(quat)

        // Realistic witch values
        float bodyX = 0f, bodyY = 0f, bodyZ = 0f;
        // head is at PartPose.ZERO in witch model
        float headX = 0f, headY = 0f, headZ = 0f;

        // Vanilla: pose stack chain (matches ModelPart.translateAndRotate)
        org.joml.Matrix4f vanilla = new org.joml.Matrix4f();
        vanilla.translate(bodyX, bodyY, bodyZ);
        // body has no rotation
        vanilla.translate(headX, headY, headZ);
        // head has no rotation in idle

        // After bone chain, transform a head cube corner (-4, -10, -4)
        float vx = -4f, vy = -10f, vz = -4f;
        org.joml.Vector3f vanillaOut = new org.joml.Vector3f();
        vanilla.transformPosition(vx, vy, vz, vanillaOut);

        // Ours: equivalent chain
        Matrix4f ours = Matrix4f.IDENTITY
            .multiply(Matrix4f.createTranslation(new Vector3f(bodyX, bodyY, bodyZ)))
            .multiply(Matrix4f.createTranslation(new Vector3f(headX, headY, headZ)));
        Vector3f oursOut = Vector3f.transform(new Vector3f(vx, vy, vz), ours);

        System.out.printf("[JOML_BONE_CHAIN] vanilla=(%.10f, %.10f, %.10f)  ours=(%.10f, %.10f, %.10f)  ulps=(%d, %d, %d)%n",
            vanillaOut.x, vanillaOut.y, vanillaOut.z,
            oursOut.x(), oursOut.y(), oursOut.z(),
            ulpsBetween(vanillaOut.x, oursOut.x()),
            ulpsBetween(vanillaOut.y, oursOut.y()),
            ulpsBetween(vanillaOut.z, oursOut.z()));
    }

    @Test
    @DisplayName("[JOML_FLUENT] Matrix4f fluent translate/scale/rotate matches JOML PoseStack bit-for-bit")
    void fluentOpsMatchJomlBitIdentical() {
        // Build identical pose chain via JOML PoseStack-equivalent and our new fluent ops.
        // Each step should produce 0-ULP-matching matrices since the algorithms are ported.
        float tx = 113.0f, ty = 153.0f;
        float scale = 17.5f;
        float pitch = (float) Math.toRadians(210);
        float yaw = (float) Math.toRadians(45);

        org.joml.Matrix4f jPose = new org.joml.Matrix4f();
        jPose.translate(tx, ty, 0f);
        jPose.scale(scale, scale, scale);
        jPose.scale(1f, 1f, -1f);
        jPose.rotate(new org.joml.Quaternionf().rotationXYZ(pitch, yaw, 0f));
        jPose.scale(1f, 1f, 1f);
        jPose.rotate(new org.joml.Quaternionf().rotationY((float) Math.PI));
        jPose.scale(-1f, -1f, 1f);
        jPose.translate(0f, -1.501f, 0f);

        Matrix4f ours = Matrix4f.IDENTITY
            .translate(tx, ty, 0f)
            .scale(scale, scale, scale)
            .scale(1f, 1f, -1f)
            .rotate(Quaternionf.rotationXYZ(pitch, yaw, 0f))
            .scale(1f, 1f, 1f)
            .rotate(Quaternionf.rotationXYZ(0f, (float) Math.PI, 0f))
            .scale(-1f, -1f, 1f)
            .translate(0f, -1.501f, 0f);

        compareMatrices("JOML_FLUENT_FULL", jPose, ours);

        // Vertex transform should match too
        float vx = -4f, vy = -10f, vz = -4f;
        org.joml.Vector3f vanillaOut = new org.joml.Vector3f();
        jPose.transformPosition(vx, vy, vz, vanillaOut);
        Vector3f oursOut = Vector3f.transform(new Vector3f(vx, vy, vz), ours);

        System.out.printf("[JOML_FLUENT_VERT] vanilla=(%.10f, %.10f, %.10f)  ours=(%.10f, %.10f, %.10f)  ulps=(%d, %d, %d)%n",
            vanillaOut.x, vanillaOut.y, vanillaOut.z,
            oursOut.x(), oursOut.y(), oursOut.z(),
            ulpsBetween(vanillaOut.x, oursOut.x()),
            ulpsBetween(vanillaOut.y, oursOut.y()),
            ulpsBetween(vanillaOut.z, oursOut.z()));

        // Manual FMA-by-FMA reproduction to find the source of the residual ULPs.
        // JOML mulPositionGeneric does:
        //   y = fma(m01, x, fma(m11, y, fma(m21, z, m31)))
        // Let's compute step by step matching our matrix entries.
        float m01 = ours.get(1, 2);
        float m11 = ours.get(2, 2);
        float m21 = ours.get(3, 2);
        float m31 = ours.get(4, 2);
        float step1 = (float) Math.fma(m21, vz, m31);
        float step2 = (float) Math.fma(m11, vy, step1);
        float step3 = (float) Math.fma(m01, vx, step2);
        System.out.printf("[JOML_FLUENT_VERT_Y_TRACE] step1(m21*vz+m31)=%.10f, step2(m11*vy+step1)=%.10f, step3=%.10f%n",
            step1, step2, step3);

        // Also: confirm the matrix entries we're reading match JOML's at the bit level.
        System.out.printf("[JOML_FLUENT_VERT_Y_MAT] m01=%.10f, m11=%.10f, m21=%.10f, m31=%.10f%n", m01, m11, m21, m31);
        System.out.printf("[JOML_FLUENT_VERT_Y_MAT_JOML] m01=%.10f, m11=%.10f, m21=%.10f, m31=%.10f%n",
            jPose.m01(), jPose.m11(), jPose.m21(), jPose.m31());
    }

    @Test
    @DisplayName("[JOML_FULL_PIPELINE] vanilla full canvas-fit + iso + bone chain reproduction")
    void fullPipelineBitCompare() {
        // Reproduce the exact vanilla harness chain for a witch head cube corner.
        // Per EntityFrameRenderer.render:
        //   pose.translate(canvasW/2 - anchorX*scale, canvasH/2 - anchorY*scale, 0)
        //   pose.scale(scale, scale, scale)
        //   pose.scale(1, 1, -1)
        //   pose.mulPose(rotationXYZ(210, 45, 0))
        //   pose.scale(1, 1, -1)   (initial chirality from bounds-walker; not in render? double-check)
        //   pose.scale(living.scale)
        //   setupRotations: Y180
        //   pose.scale(-1, -1, 1)
        //   pose.translate(0, -1.501, 0)
        //   per bone: pose.translate + mulPose
        // Then vertex is transformed.

        float canvasW = 261f, canvasH = 614f;
        float anchorX = 0.5f, anchorY = -0.5f;
        float scale = 17.5f;
        float tx = canvasW / 2f - anchorX * scale;
        float ty = canvasH / 2f - anchorY * scale;
        float pitch = (float) Math.toRadians(210);
        float yaw = (float) Math.toRadians(45);

        // Vanilla chain via JOML
        org.joml.Matrix4f jPose = new org.joml.Matrix4f();
        jPose.translate(tx, ty, 0f);
        jPose.scale(scale, scale, scale);
        jPose.scale(1f, 1f, -1f);
        jPose.rotate(new org.joml.Quaternionf().rotationXYZ(pitch, yaw, 0f));
        // The PIP submit then calls into vanilla's LER chain. The LER chain (from
        // LivingEntityRenderer.submit -> setupRotations + scale + translate) adds:
        jPose.scale(1f, 1f, 1f); // living.scale = 1.0 typically
        // setupRotations base: poseStack.mulPose(Axis.YP.rotationDegrees(180 - bodyRot))
        // With bodyRot=0, that's rotateY(180deg)
        jPose.rotate(new org.joml.Quaternionf().rotationY((float) Math.PI));
        jPose.scale(-1f, -1f, 1f);
        jPose.translate(0f, -1.501f, 0f);
        // No bone translate/rotate for root; witch body and head are at PartPose.ZERO.
        // Add witch head cube vertex
        float vx = -4f, vy = -10f, vz = -4f;
        org.joml.Vector3f vanillaOut = new org.joml.Vector3f();
        jPose.transformPosition(vx, vy, vz, vanillaOut);

        // Ours (matrix-equivalent chain)
        Matrix4f ours = Matrix4f.createTranslation(new Vector3f(tx, ty, 0f))
            .multiply(Matrix4f.createScale(scale, scale, scale))
            .multiply(Matrix4f.createScale(1f, 1f, -1f))
            .multiply(Quaternionf.rotationXYZ(pitch, yaw, 0f).toMatrix4f())
            .multiply(Matrix4f.createScale(1f, 1f, 1f))
            .multiply(Matrix4f.createRotationY((float) Math.PI))
            .multiply(Matrix4f.createScale(-1f, -1f, 1f))
            .multiply(Matrix4f.createTranslation(new Vector3f(0f, -1.501f, 0f)));
        Vector3f oursOut = Vector3f.transform(new Vector3f(vx, vy, vz), ours);

        System.out.printf("[JOML_FULL_PIPELINE] vertex (-4,-10,-4) thru full witch chain%n");
        System.out.printf("  vanilla=(%.10f, %.10f, %.10f)%n", vanillaOut.x, vanillaOut.y, vanillaOut.z);
        System.out.printf("  ours   =(%.10f, %.10f, %.10f)%n", oursOut.x(), oursOut.y(), oursOut.z());
        System.out.printf("  d=(%g, %g, %g)  ulps=(%d, %d, %d)%n",
            Math.abs(vanillaOut.x - oursOut.x()),
            Math.abs(vanillaOut.y - oursOut.y()),
            Math.abs(vanillaOut.z - oursOut.z()),
            ulpsBetween(vanillaOut.x, oursOut.x()),
            ulpsBetween(vanillaOut.y, oursOut.y()),
            ulpsBetween(vanillaOut.z, oursOut.z()));

        // Also dump the matrices for comparison
        compareMatrices("JOML_FULL_PIPELINE_MAT", jPose, ours);
    }

    // --- helpers ---

    private static void compareMatrices(String label, org.joml.Matrix4fc vanilla, Matrix4f ours) {
        int maxUlps = 0;
        int worstCol = 0, worstRow = 0;
        StringBuilder details = new StringBuilder();
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                // JOML accessor is m(col, row) - matches our get(col+1, row+1)
                float vVal = vanilla.get(col, row);
                float oVal = ours.get(col + 1, row + 1);
                int u = ulpsBetween(vVal, oVal);
                if (u > maxUlps) { maxUlps = u; worstCol = col; worstRow = row; }
                if (u > 0) {
                    details.append(String.format("  m(%d,%d): vanilla=%.10f ours=%.10f ulps=%d%n", col, row, vVal, oVal, u));
                }
            }
        }
        System.out.printf("[%s] max=%d ULPs (worst at col=%d row=%d)%n", label, maxUlps, worstCol, worstRow);
        if (details.length() > 0) System.out.print(details);
    }

    private static int ulpsBetween(float a, float b) {
        if (a == b) return 0;
        int ia = Float.floatToRawIntBits(a);
        int ib = Float.floatToRawIntBits(b);
        if ((ia ^ ib) < 0) return Integer.MAX_VALUE;
        return Math.abs(ia - ib);
    }
}
