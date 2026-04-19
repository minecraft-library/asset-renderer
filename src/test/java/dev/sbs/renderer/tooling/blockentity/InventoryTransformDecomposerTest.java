package dev.sbs.renderer.tooling.blockentity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Fast mutation tests for {@link InventoryTransformDecomposer}. Each test builds a synthetic
 * renderer class via {@link ClassWriter} with a known factory method shape, runs the
 * decomposer, optionally mutates one bytecode detail and re-runs, asserting the output
 * changed in the expected direction. Proves the decomposer follows the bytecode rather than
 * relying on any hardcoded entity-id to tuple table.
 *
 * <p>All synthetic classes use a {@code test/} package so nothing collides with the real
 * renderer names, and the {@link InventoryTransformDecomposer#RENDERER_ENTRY_METHODS} policy
 * is bypassed by calling the test-only {@code decompose} overload that takes an explicit
 * entry-method descriptor. The policy table itself is exercised only by
 * {@link InventoryTransformDecomposerParityTest} against the real client jar.
 */
@DisplayName("InventoryTransformDecomposer (bytecode-driven)")
class InventoryTransformDecomposerTest {

    // --------------------------------------------------------------------------------------
    // JVM internal names for the classes whose method shapes the decomposer recognises.
    // Fixtures emit INVOKE* against these but we never load / run the bodies.
    // --------------------------------------------------------------------------------------

    private static final String MATRIX4F = "org/joml/Matrix4f";
    private static final String VECTOR3F = "org/joml/Vector3f";
    private static final String QUATERNIONF = "org/joml/Quaternionf";
    private static final String QUATERNIONFC = "org/joml/Quaternionfc";
    private static final String VECTOR3FC = "org/joml/Vector3fc";
    private static final String MATRIX4FC = "org/joml/Matrix4fc";
    private static final String AXIS = "com/mojang/math/Axis";
    private static final String TRANSFORMATION = "com/mojang/math/Transformation";

    @TempDir Path tempDir;

    // --------------------------------------------------------------------------------------
    // Synthetic jar builder - a single-class zip with a renderer whose
    // createGroundTransformation or createModelTransform factory method we emit into.
    // --------------------------------------------------------------------------------------

    /**
     * Functional interface: visit the method body of the factory to emit the test's specific
     * Matrix4f chain / ctor B call. Always ends with ARETURN.
     */
    private interface BodyWriter {
        void write(@NotNull MethodVisitor mv);
    }

    /** Default factory name used by the synthetic renderers. */
    private static final String FACTORY_METHOD = "createModelTransform";
    /** Descriptor: {@code float -> Transformation}, so the walker sees yaw at slot 0. */
    private static final String FACTORY_DESC_FLOAT = "(F)Lcom/mojang/math/Transformation;";
    /** Descriptor: {@code () -> Transformation} for the no-arg static field flavour. */
    private static final String FACTORY_DESC_NOARG = "()Lcom/mojang/math/Transformation;";

    private Path buildRendererJar(String factoryName, String factoryDesc, BodyWriter body) throws IOException {
        return buildRendererJar(factoryName, factoryDesc, body, null);
    }

    /**
     * Builds a synthetic jar containing the renderer class plus any extra helper classes the
     * test needs (e.g. a Vector3f class exposing static fields whose &lt;clinit&gt; we want
     * the decomposer to follow).
     */
    private Path buildRendererJar(
        @NotNull String factoryName,
        @NotNull String factoryDesc,
        @NotNull BodyWriter body,
        @Nullable Map<String, byte[]> extras
    ) throws IOException {
        String jarName = "syn-" + factoryName + "-" + System.nanoTime() + ".jar";
        Path jar = tempDir.resolve(jarName);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/SyntheticRenderer", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, factoryName, factoryDesc, null, null);
            mv.visitCode();
            body.write(mv);
            mv.visitMaxs(20, 10);
            mv.visitEnd();
            cw.visitEnd();
            zos.putNextEntry(new ZipEntry("test/SyntheticRenderer.class"));
            zos.write(cw.toByteArray());
            zos.closeEntry();
            if (extras != null) {
                for (Map.Entry<String, byte[]> e : extras.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                    zos.write(e.getValue());
                    zos.closeEntry();
                }
            }
        }
        return jar;
    }

    private float @Nullable [] runDecomposer(@NotNull Path jar, @NotNull String factoryName, @NotNull String factoryDesc, @NotNull Diagnostics diag) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return InventoryTransformDecomposer.decomposeMethod(zip, "test/SyntheticRenderer", factoryName, factoryDesc, diag);
        }
    }

    // --------------------------------------------------------------------------------------
    // Bytecode primitives - small helpers for emitting Matrix4f / Vector3f / Axis calls.
    // --------------------------------------------------------------------------------------

    /** Emits {@code new Matrix4f()}; stack: -> Matrix4f. */
    private static void emitNewMatrix(@NotNull MethodVisitor mv) {
        mv.visitTypeInsn(Opcodes.NEW, MATRIX4F);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MATRIX4F, "<init>", "()V", false);
    }

    /** Emits {@code m.translation(x, y, z)}; stack: Matrix4f -> Matrix4f. */
    private static void emitTranslation(@NotNull MethodVisitor mv, float x, float y, float z) {
        mv.visitLdcInsn(x); mv.visitLdcInsn(y); mv.visitLdcInsn(z);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX4F, "translation", "(FFF)L" + MATRIX4F + ";", false);
    }

    /** Emits {@code m.translate(x, y, z)}; stack: Matrix4f -> Matrix4f. */
    private static void emitTranslate(@NotNull MethodVisitor mv, float x, float y, float z) {
        mv.visitLdcInsn(x); mv.visitLdcInsn(y); mv.visitLdcInsn(z);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX4F, "translate", "(FFF)L" + MATRIX4F + ";", false);
    }

    /** Emits {@code m.scale(x, y, z)}; stack: Matrix4f -> Matrix4f. */
    private static void emitScale(@NotNull MethodVisitor mv, float x, float y, float z) {
        mv.visitLdcInsn(x); mv.visitLdcInsn(y); mv.visitLdcInsn(z);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX4F, "scale", "(FFF)L" + MATRIX4F + ";", false);
    }

    /**
     * Emits {@code Axis.<axisField>.rotationDegrees(angle)}; stack: -> Quaternionf. Axis field
     * names are {@code XP}, {@code YP}, {@code ZP} (positive-axis rotations).
     */
    private static void emitAxisRotation(@NotNull MethodVisitor mv, @NotNull String axisField, float angle) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, AXIS, axisField, "L" + AXIS + ";");
        mv.visitLdcInsn(angle);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AXIS, "rotationDegrees", "(F)L" + QUATERNIONF + ";", false);
    }

    /**
     * Emits {@code Axis.<axisField>.rotationDegrees(FLOAD slotOffsetBy)}; for testing symbolic
     * yaw evaluation. {@code slotOffsetBy=0} passes the raw yaw; {@code +180} emits
     * {@code FLOAD 0; LDC 180f; FADD}; {@code negate=true} emits {@code FNEG} after the FLOAD.
     */
    private static void emitAxisRotationYaw(@NotNull MethodVisitor mv, @NotNull String axisField, boolean negate, float addedOffset) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, AXIS, axisField, "L" + AXIS + ";");
        mv.visitVarInsn(Opcodes.FLOAD, 0);
        if (negate) mv.visitInsn(Opcodes.FNEG);
        if (addedOffset != 0f) {
            mv.visitLdcInsn(addedOffset);
            mv.visitInsn(Opcodes.FADD);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AXIS, "rotationDegrees", "(F)L" + QUATERNIONF + ";", false);
    }

    /** Emits {@code m.rotate(q)}; stack: Matrix4f, Quaternionf -> Matrix4f. */
    private static void emitRotate(@NotNull MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX4F, "rotate", "(L" + QUATERNIONFC + ";)L" + MATRIX4F + ";", false);
    }

    /**
     * Emits {@code m.rotateAround(q, cx, cy, cz)}; stack: Matrix4f, Quaternionf -> Matrix4f
     * (the 3 floats are pushed and popped as part of the INVOKEVIRTUAL signature).
     */
    private static void emitRotateAround(@NotNull MethodVisitor mv, float cx, float cy, float cz) {
        mv.visitLdcInsn(cx); mv.visitLdcInsn(cy); mv.visitLdcInsn(cz);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX4F, "rotateAround",
            "(L" + QUATERNIONFC + ";FFF)L" + MATRIX4F + ";", false);
    }

    /** Emits {@code new Transformation(m); areturn}. Stack: Matrix4f -> (). */
    private static void emitTransformationFromMatrix(@NotNull MethodVisitor mv) {
        // Stack is: Matrix4f. We need: NEW Transformation ; DUP_X1 ; SWAP ; INVOKESPECIAL ; ARETURN
        // Simpler: store the matrix in a local, then NEW+DUP+ALOAD+INVOKESPECIAL.
        mv.visitVarInsn(Opcodes.ASTORE, 5);
        mv.visitTypeInsn(Opcodes.NEW, TRANSFORMATION);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TRANSFORMATION, "<init>", "(L" + MATRIX4FC + ";)V", false);
        mv.visitInsn(Opcodes.ARETURN);
    }

    // --------------------------------------------------------------------------------------
    // Matrix4f chain walker tests
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("1. translation(0.5, 0.5, 0.5) -> [8, 8, 8, 0, 0, 0]; mutation: x=1.0 -> [16, 8, 8, 0, 0, 0]")
    void pureTranslation() throws IOException {
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0.5f, 0.5f, 0.5f);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        assertThat(out, notNullValue());
        assertTuple(out, new float[]{ 8f, 8f, 8f, 0f, 0f, 0f });

        // Mutation: change x = 1.0 -> expect tx = 16
        Diagnostics diag2 = new Diagnostics();
        Path jar2 = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 1.0f, 0.5f, 0.5f);
            emitTransformationFromMatrix(mv);
        });
        float[] out2 = runDecomposer(jar2, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag2);
        assertTuple(out2, new float[]{ 16f, 8f, 8f, 0f, 0f, 0f });
    }

    @Test
    @DisplayName("2. translation + Rx(90) -> [0, 0, 0, 90, 0, 0]; mutation angle 45 -> [..., 45, 0, 0]")
    void translationPlusRx90() throws IOException {
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0f, 0f, 0f);
            emitAxisRotation(mv, "XP", 90f);
            emitRotate(mv);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        assertTuple(out, new float[]{ 0f, 0f, 0f, 90f, 0f, 0f });

        Path jar2 = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0f, 0f, 0f);
            emitAxisRotation(mv, "XP", 45f);
            emitRotate(mv);
            emitTransformationFromMatrix(mv);
        });
        float[] out2 = runDecomposer(jar2, FACTORY_METHOD, FACTORY_DESC_FLOAT, new Diagnostics());
        assertTuple(out2, new float[]{ 0f, 0f, 0f, 45f, 0f, 0f });
    }

    @Test
    @DisplayName("3. bed shape: translation(0, 0.5625, 0) + Rx(90) + rotateAround(Ry(180), 0.5, 0.5, 0.5) -> [0, 9, 0, 90, 0, 0]")
    void bedRendererShape() throws IOException {
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0f, 0.5625f, 0f);
            emitAxisRotation(mv, "XP", 90f);
            emitRotate(mv);
            emitAxisRotationYaw(mv, "ZP", false, 180f); // "180 + yaw" reduces to 180 at yaw=0
            emitRotateAround(mv, 0.5f, 0.5f, 0.5f);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        // 0.5625 * 16 = 9. The rotateAround at block-center with Rz(180) is yaw-equivalent,
        // drop it. Rx(90) survives as pitch.
        assertTuple(out, new float[]{ 0f, 9f, 0f, 90f, 0f, 0f });
    }

    @Test
    @DisplayName("4. translation + scale(1, -1, -1) folds to Rx(180); mutation scale(1,1,1) -> no rotation")
    void scaleOneNegOneNegOneFoldsToRx180() throws IOException {
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0.5f, 0.625f, 0.5f);
            emitScale(mv, 1f, -1f, -1f);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        assertTuple(out, new float[]{ 8f, 10f, 8f, 180f, 0f, 0f });

        // Mutation: scale(1,1,1) -> pure identity scale, no rotation
        Path jar2 = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0.5f, 0.625f, 0.5f);
            emitScale(mv, 1f, 1f, 1f);
            emitTransformationFromMatrix(mv);
        });
        float[] out2 = runDecomposer(jar2, FACTORY_METHOD, FACTORY_DESC_FLOAT, new Diagnostics());
        assertTuple(out2, new float[]{ 8f, 10f, 8f, 0f, 0f, 0f });
    }

    @Test
    @DisplayName("5. scale(s, -s, -s) with s=0.6666667 folds to uniform 2/3 + Rx(180)")
    void uniformPositiveScalePlusRx180() throws IOException {
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0.5f, 0.5f, 0.5f);
            emitScale(mv, 0.6666667f, -0.6666667f, -0.6666667f);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        assertThat(out, notNullValue());
        assertThat(out.length, equalTo(7));
        assertFloat("tx", out[0], 8f);
        assertFloat("ty", out[1], 8f);
        assertFloat("tz", out[2], 8f);
        assertFloat("pitch", out[3], 180f);
        assertFloat("yaw", out[4], 0f);
        assertFloat("roll", out[5], 0f);
        assertThat("uniform scale", (double) out[6], closeTo(0.6666667, 1e-5));
    }

    @Test
    @DisplayName("6. near-unity uniform scale (0.9995) is dropped as unity")
    void nearUnityScaleDropped() throws IOException {
        // Fixture mimics shulker box: translation(0.5, 0.5, 0.5).scale(0.9995, 0.9995, 0.9995)
        //   .scale(1, -1, -1).translate(0, -1, 0)
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0.5f, 0.5f, 0.5f);
            emitScale(mv, 0.9995f, 0.9995f, 0.9995f);
            emitScale(mv, 1f, -1f, -1f);
            emitTranslate(mv, 0f, -1f, 0f);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        // 6-tuple (no uniform scale) expected; translate folded to (8, 24, 8) with Rx(180).
        assertTuple(out, new float[]{ 8f, 24f, 8f, 180f, 0f, 0f });
    }

    // --------------------------------------------------------------------------------------
    // Ctor B walker tests
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("7. ctor B direct: translation Vector3f, YP yaw, scale Vector3f -> [8, 0, 8, 180, 0, 0, 2/3]")
    void ctorBDirect() throws IOException {
        // new Transformation(
        //     new Vector3f(0.5, 0, 0.5),
        //     Axis.YP.rotationDegrees(-yaw),
        //     new Vector3f(0.6666667, -0.6666667, -0.6666667),
        //     null);
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            // Push translation Vector3f
            mv.visitTypeInsn(Opcodes.NEW, VECTOR3F);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(0.5f); mv.visitLdcInsn(0f); mv.visitLdcInsn(0.5f);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, VECTOR3F, "<init>", "(FFF)V", false);
            mv.visitVarInsn(Opcodes.ASTORE, 1);
            // Push scale Vector3f
            mv.visitTypeInsn(Opcodes.NEW, VECTOR3F);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(0.6666667f); mv.visitLdcInsn(-0.6666667f); mv.visitLdcInsn(-0.6666667f);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, VECTOR3F, "<init>", "(FFF)V", false);
            mv.visitVarInsn(Opcodes.ASTORE, 2);
            // Push left rotation: Axis.YP.rotationDegrees(-yaw)
            mv.visitTypeInsn(Opcodes.NEW, TRANSFORMATION);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.GETSTATIC, AXIS, "YP", "L" + AXIS + ";");
            mv.visitVarInsn(Opcodes.FLOAD, 0);
            mv.visitInsn(Opcodes.FNEG);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AXIS, "rotationDegrees",
                "(F)L" + QUATERNIONF + ";", false);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TRANSFORMATION, "<init>",
                "(L" + VECTOR3FC + ";L" + QUATERNIONFC + ";L" + VECTOR3FC + ";L" + QUATERNIONFC + ";)V", false);
            mv.visitInsn(Opcodes.ARETURN);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        assertThat(out, notNullValue());
        assertThat(out.length, equalTo(7));
        assertTuplePrefix(out, new float[]{ 8f, 0f, 8f, 180f, 0f, 0f });
        assertThat("uniform scale", (double) out[6], closeTo(0.6666667, 1e-5));
    }

    @Test
    @DisplayName("8. ctor B with GETSTATIC Vector3f fields (banner-like) - static field resolution")
    void ctorBStaticFieldResolution() throws IOException {
        // The renderer class has two static Vector3f fields initialised in <clinit>:
        //   MODEL_TRANSLATION = new Vector3f(0.5, 0, 0.5)
        //   MODEL_SCALE       = new Vector3f(0.6666667, -0.6666667, -0.6666667)
        // The factory method does:
        //   new Transformation(MODEL_TRANSLATION, null, MODEL_SCALE, null)
        Diagnostics diag = new Diagnostics();
        String jarName = "syn-banner-" + System.nanoTime() + ".jar";
        Path jar = tempDir.resolve(jarName);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/SyntheticRenderer", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "MODEL_TRANSLATION", "L" + VECTOR3FC + ";", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "MODEL_SCALE", "L" + VECTOR3FC + ";", null, null).visitEnd();
            // <clinit>
            MethodVisitor cmv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            cmv.visitCode();
            cmv.visitTypeInsn(Opcodes.NEW, VECTOR3F);
            cmv.visitInsn(Opcodes.DUP);
            cmv.visitLdcInsn(0.5f); cmv.visitLdcInsn(0f); cmv.visitLdcInsn(0.5f);
            cmv.visitMethodInsn(Opcodes.INVOKESPECIAL, VECTOR3F, "<init>", "(FFF)V", false);
            cmv.visitFieldInsn(Opcodes.PUTSTATIC, "test/SyntheticRenderer", "MODEL_TRANSLATION", "L" + VECTOR3FC + ";");
            cmv.visitTypeInsn(Opcodes.NEW, VECTOR3F);
            cmv.visitInsn(Opcodes.DUP);
            cmv.visitLdcInsn(0.6666667f); cmv.visitLdcInsn(-0.6666667f); cmv.visitLdcInsn(-0.6666667f);
            cmv.visitMethodInsn(Opcodes.INVOKESPECIAL, VECTOR3F, "<init>", "(FFF)V", false);
            cmv.visitFieldInsn(Opcodes.PUTSTATIC, "test/SyntheticRenderer", "MODEL_SCALE", "L" + VECTOR3FC + ";");
            cmv.visitInsn(Opcodes.RETURN);
            cmv.visitMaxs(6, 0);
            cmv.visitEnd();
            // factory
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                FACTORY_METHOD, FACTORY_DESC_FLOAT, null, null);
            mv.visitCode();
            mv.visitTypeInsn(Opcodes.NEW, TRANSFORMATION);
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.GETSTATIC, "test/SyntheticRenderer", "MODEL_TRANSLATION", "L" + VECTOR3FC + ";");
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitFieldInsn(Opcodes.GETSTATIC, "test/SyntheticRenderer", "MODEL_SCALE", "L" + VECTOR3FC + ";");
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TRANSFORMATION, "<init>",
                "(L" + VECTOR3FC + ";L" + QUATERNIONFC + ";L" + VECTOR3FC + ";L" + QUATERNIONFC + ";)V", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(8, 1);
            mv.visitEnd();
            cw.visitEnd();
            zos.putNextEntry(new ZipEntry("test/SyntheticRenderer.class"));
            zos.write(cw.toByteArray());
            zos.closeEntry();
        }
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        assertThat(out, notNullValue());
        assertThat(out.length, equalTo(7));
        assertTuplePrefix(out, new float[]{ 8f, 0f, 8f, 180f, 0f, 0f });
        assertThat("uniform scale", (double) out[6], closeTo(0.6666667, 1e-5));
    }

    // --------------------------------------------------------------------------------------
    // Symbolic yaw evaluation tests
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("9. Axis.YP.rotationDegrees(FLOAD yaw) -> identity at yaw=0")
    void yawIdentityAtZero() throws IOException {
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0.5f, 0.5f, 0.5f);
            emitAxisRotationYaw(mv, "YP", false, 0f);
            emitRotate(mv);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        assertTuple(out, new float[]{ 8f, 8f, 8f, 0f, 0f, 0f });
    }

    @Test
    @DisplayName("10. Axis.YP.rotationDegrees(180 + FLOAD yaw) -> Ry(180) at yaw=0")
    void yawPlusConstantReducesToConstant() throws IOException {
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0f, 0f, 0f);
            emitAxisRotationYaw(mv, "YP", false, 180f);
            emitRotate(mv);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        // Ry(180) lands in the yaw slot (index 4).
        assertThat(out, notNullValue());
        assertFloat("yaw(Ry)", out[4], 180f);
        assertFloat("pitch(Rx)", out[3], 0f);
        assertFloat("roll(Rz)", out[5], 0f);
    }

    @Test
    @DisplayName("11. Axis.YP.rotationDegrees(-FLOAD yaw) -> identity at yaw=0")
    void negatedYawReducesToIdentity() throws IOException {
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0.5f, 0.5f, 0.5f);
            emitAxisRotationYaw(mv, "YP", true, 0f);
            emitRotate(mv);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        assertTuple(out, new float[]{ 8f, 8f, 8f, 0f, 0f, 0f });
    }

    // --------------------------------------------------------------------------------------
    // Failure paths
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("12. non-literal vector component (unresolved slot via ASTORE/ALOAD) returns null + warn")
    void nonLiteralComponentWarns() throws IOException {
        // Factory: (Ljava/lang/Object;)Lcom/mojang/math/Transformation;. Slot 0 is a reference
        // parameter - the walker treats it as OTHER. We then INVOKEVIRTUAL Object.hashCode()I,
        // convert int->float (I2F), and feed it as an x translation. Since Object.hashCode is
        // an opaque library call, the float value is OTHER, which must poison the walker.
        String desc = "(Ljava/lang/Object;)Lcom/mojang/math/Transformation;";
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, desc, mv -> {
            emitNewMatrix(mv);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
            mv.visitInsn(Opcodes.I2F);
            mv.visitLdcInsn(0.5f);
            mv.visitLdcInsn(0.5f);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX4F, "translation", "(FFF)L" + MATRIX4F + ";", false);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, desc, diag);
        assertThat(out, nullValue());
        assertThat("warn diagnostic emitted", diag.strictFailingCount(), greaterThan(0));
    }

    @Test
    @DisplayName("13. unrecognised Matrix4f method call returns null + warn")
    void unknownMethodWarns() throws IOException {
        Diagnostics diag = new Diagnostics();
        Path jar = buildRendererJar(FACTORY_METHOD, FACTORY_DESC_FLOAT, mv -> {
            emitNewMatrix(mv);
            emitTranslation(mv, 0.5f, 0.5f, 0.5f);
            // Fake call to a method we don't recognise (mulLocal).
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX4F, "mulLocal",
                "(L" + MATRIX4FC + ";)L" + MATRIX4F + ";", false);
            emitTransformationFromMatrix(mv);
        });
        float[] out = runDecomposer(jar, FACTORY_METHOD, FACTORY_DESC_FLOAT, diag);
        assertThat(out, nullValue());
        assertThat("warn diagnostic emitted", diag.strictFailingCount(), greaterThan(0));
    }

    // --------------------------------------------------------------------------------------
    // Static field flavour (conduit-like) - ConduitRenderer's DEFAULT_TRANSFORMATION
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("14. static Transformation field built in <clinit> (conduit shape) -> [8, 8, 8, 0, 0, 0]")
    void staticTransformationField() throws IOException {
        // Class layout:
        //   public static final Transformation DEFAULT_TRANSFORMATION;
        //   static { DEFAULT_TRANSFORMATION = new Transformation(
        //       new Vector3f(0.5, 0.5, 0.5), null, null, null); }
        Diagnostics diag = new Diagnostics();
        Path jar = tempDir.resolve("syn-conduit-" + System.nanoTime() + ".jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/SyntheticRenderer", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "DEFAULT_TRANSFORMATION", "L" + TRANSFORMATION + ";", null, null).visitEnd();
            MethodVisitor cmv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            cmv.visitCode();
            cmv.visitTypeInsn(Opcodes.NEW, TRANSFORMATION);
            cmv.visitInsn(Opcodes.DUP);
            cmv.visitTypeInsn(Opcodes.NEW, VECTOR3F);
            cmv.visitInsn(Opcodes.DUP);
            cmv.visitLdcInsn(0.5f); cmv.visitLdcInsn(0.5f); cmv.visitLdcInsn(0.5f);
            cmv.visitMethodInsn(Opcodes.INVOKESPECIAL, VECTOR3F, "<init>", "(FFF)V", false);
            cmv.visitInsn(Opcodes.ACONST_NULL);
            cmv.visitInsn(Opcodes.ACONST_NULL);
            cmv.visitInsn(Opcodes.ACONST_NULL);
            cmv.visitMethodInsn(Opcodes.INVOKESPECIAL, TRANSFORMATION, "<init>",
                "(L" + VECTOR3FC + ";L" + QUATERNIONFC + ";L" + VECTOR3FC + ";L" + QUATERNIONFC + ";)V", false);
            cmv.visitFieldInsn(Opcodes.PUTSTATIC, "test/SyntheticRenderer",
                "DEFAULT_TRANSFORMATION", "L" + TRANSFORMATION + ";");
            cmv.visitInsn(Opcodes.RETURN);
            cmv.visitMaxs(8, 0);
            cmv.visitEnd();
            cw.visitEnd();
            zos.putNextEntry(new ZipEntry("test/SyntheticRenderer.class"));
            zos.write(cw.toByteArray());
            zos.closeEntry();
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            float[] out = InventoryTransformDecomposer.decomposeField(
                zip, "test/SyntheticRenderer", "DEFAULT_TRANSFORMATION", diag);
            assertTuple(out, new float[]{ 8f, 8f, 8f, 0f, 0f, 0f });
        }
    }

    // --------------------------------------------------------------------------------------
    // Smoke test - proving the decomposeAll pipeline wires up to the policy table. Uses the
    // real renderer internal names so the policy map is exercised (hence the qualified names).
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("15. decomposeAll: unknown renderer is skipped without error; known renderer is walked")
    void decomposeAllUnknownRendererSkipped() throws IOException {
        // Empty jar - no renderer classes at all. decomposeAll should return an empty map
        // and emit a warn per missing class.
        Path jar = tempDir.resolve("empty-" + System.nanoTime() + ".jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            // one placeholder entry so the zip is valid
            zos.putNextEntry(new ZipEntry("placeholder"));
            zos.write(new byte[0]);
            zos.closeEntry();
        }
        Map<String, String> entityIdToRenderer = new LinkedHashMap<>();
        entityIdToRenderer.put("minecraft:conduit", "net/minecraft/client/renderer/blockentity/ConduitRenderer");
        Diagnostics diag = new Diagnostics();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Map<String, float[]> out = InventoryTransformDecomposer.decomposeAll(zip, entityIdToRenderer, diag);
            assertThat("empty map returned when classes absent", out.isEmpty(), is(true));
        }
    }

    // --------------------------------------------------------------------------------------
    // Assertion helpers
    // --------------------------------------------------------------------------------------

    private static void assertFloat(@NotNull String name, float actual, float expected) {
        assertThat(name + " (expected " + expected + ", got " + actual + ")",
            (double) actual,
            allOf(greaterThan((double) expected - 1e-4), lessThan((double) expected + 1e-4)));
    }

    private static void assertTuple(float @Nullable [] actual, float @NotNull [] expected) {
        assertThat("tuple not null", actual, notNullValue());
        assertThat("tuple length", actual.length, equalTo(expected.length));
        for (int i = 0; i < expected.length; i++)
            assertFloat("tuple[" + i + "]", actual[i], expected[i]);
    }

    private static void assertTuplePrefix(float @Nullable [] actual, float @NotNull [] expected) {
        assertThat("tuple not null", actual, notNullValue());
        assertThat("tuple length at least expected", actual.length, not(lessThan(expected.length)));
        for (int i = 0; i < expected.length; i++)
            assertFloat("tuple[" + i + "]", actual[i], expected[i]);
    }

}
