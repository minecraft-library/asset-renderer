package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins for {@link TransformWalker} against synthetic classes mirroring the exact 26.1 bytecode of
 * each known block-entity transform site (verified against {@code javap -c -p} of the extracted
 * client classes) - the expected tuples are the checked-in {@code block_models.json} values,
 * byte-for-byte. Covers the eight factory shapes plus the walker's guard rails: poison-on-unknown,
 * {@code Axis.XN} polarity, and the FIELD: stop-at-PUTSTATIC capture.
 */
@DisplayName("TransformWalker decomposes the eight factory shapes to the tuples")
class TransformWalkerTest {

    private static final @NotNull String MATRIX4F = "org/joml/Matrix4f";
    private static final @NotNull String MATRIX4F_RET = "Lorg/joml/Matrix4f;";
    private static final @NotNull String TRANSFORMATION = "com/mojang/math/Transformation";
    private static final @NotNull String VECTOR3F = "org/joml/Vector3f";
    private static final @NotNull String AXIS = "com/mojang/math/Axis";
    private static final @NotNull String AXIS_DESC = "Lcom/mojang/math/Axis;";
    private static final @NotNull String QUAT_RET = "(F)Lorg/joml/Quaternionf;";
    private static final @NotNull String DIRECTION = "net/minecraft/core/Direction";
    private static final @NotNull String ROTATION_SEGMENT = "net/minecraft/world/level/block/state/properties/RotationSegment";
    private static final @NotNull String ATTACHMENT = "net/minecraft/world/level/block/PlainSignBlock$Attachment";
    private static final @NotNull String ATTACHMENT_DESC = "L" + ATTACHMENT + ";";
    private static final @NotNull String VEC3C_DESC = "Lorg/joml/Vector3fc;";
    private static final @NotNull String CTOR_MATRIX = "(Lorg/joml/Matrix4fc;)V";
    private static final @NotNull String CTOR_COMPONENTS =
        "(Lorg/joml/Vector3fc;Lorg/joml/Quaternionfc;Lorg/joml/Vector3fc;Lorg/joml/Quaternionfc;)V";
    private static final @NotNull String ROTATE_DESC = "(Lorg/joml/Quaternionfc;)Lorg/joml/Matrix4f;";
    private static final @NotNull String ROTATE_AROUND_DESC = "(Lorg/joml/Quaternionfc;FFF)Lorg/joml/Matrix4f;";
    private static final @NotNull String VEC3_OPS_DESC = "(FFF)Lorg/joml/Matrix4f;";

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;
    private TransformWalker walker;

    @BeforeEach
    void openFixtureJar() throws IOException {
        Path jar = tempDir.resolve("fixtures.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (ClassNode fixture : new ClassNode[]{
                bedRenderer(), shulkerRenderer(), skullRenderer(), bannerRenderer(), conduitRenderer(),
                standingSignRenderer(), hangingSignRenderer(), potRenderer(), negatedAxisRenderer(), poisonRenderer()}) {
                zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                fixture.accept(writer);
                zip.write(writer.toByteArray());
                zip.closeEntry();
            }
        }
        this.cache = ClassNodeCache.open(jar);
        this.walker = new TransformWalker(this.cache);
    }

    @AfterEach
    void closeFixtureJar() {
        this.cache.close();
    }

    // ------------------------------------------------------------------------------------
    // the eight block-entity transform shapes (expected tuples = the checked-in block_models.json values)
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("bed: translation-overwrite + X90 + dropped block-centred Z180+yaw rotateAround")
    void bedShape() {
        assertArrayEquals(new float[]{0f, 9f, 0f, 90f, 0f, 0f},
            this.walker.decompose("fx/BedRenderer", "createModelTransform", null));
    }

    @Test
    @DisplayName("shulker: 0.9995 fudge snaps, getRotation is reference-identity, scale(1,-1,-1) folds to Rx180")
    void shulkerShape() {
        assertArrayEquals(new float[]{8f, 24f, 8f, 180f, 0f, 0f},
            this.walker.decompose("fx/ShulkerBoxRenderer", "createModelTransform", null));
    }

    @Test
    @DisplayName("skull: int param rides convertToDegrees to the reference yaw; scale(-1,-1,1) folds to Rx180")
    void skullShape() {
        assertArrayEquals(new float[]{8f, 0f, 8f, 180f, 0f, 0f},
            this.walker.decompose("fx/SkullBlockRenderer", "createGroundTransformation", null));
    }

    @Test
    @DisplayName("banner: component ctor with static Vector3f fields + ACONST_NULL right rotation")
    void bannerShape() {
        assertArrayEquals(new float[]{8f, 0f, 8f, 180f, 0f, 0f, 0.6666667f},
            this.walker.decompose("fx/BannerRenderer", "modelTransformation", null));
    }

    @Test
    @DisplayName("conduit: FIELD: entry walks <clinit> to the DEFAULT_TRANSFORMATION store")
    void conduitShape() {
        assertArrayEquals(new float[]{8f, 8f, 8f, 0f, 0f, 0f},
            this.walker.decompose("fx/ConduitRenderer", "FIELD:DEFAULT_TRANSFORMATION", null));
    }

    @Test
    @DisplayName("standing sign: seeded FLOOR skips the wall branch; baseTransformation inlines")
    void standingSignFloor() {
        assertArrayEquals(new float[]{8f, 8f, 8f, 180f, 0f, 0f, 0.6666667f},
            this.walker.decompose("fx/StandingSignRenderer", "bodyTransformation", "FLOOR"));
    }

    @Test
    @DisplayName("wall sign: seeded WALL takes the offset branch through the shared matrix local")
    void standingSignWall() {
        assertArrayEquals(new float[]{8f, 3f, 1f, 180f, 0f, 0f, 0.6666667f},
            this.walker.decompose("fx/StandingSignRenderer", "bodyTransformation", "WALL"));
    }

    @Test
    @DisplayName("hanging sign: translation then rotate-identity then translate folds; scale(1,-1,-1)")
    void hangingSignShape() {
        assertArrayEquals(new float[]{8f, 10f, 8f, 180f, 0f, 0f},
            this.walker.decompose("fx/HangingSignRenderer", "bodyTransformation", null));
    }

    @Test
    @DisplayName("decorated pot: the dropped block-centred Y180-yaw rotateAround leaves identity")
    void potShape() {
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f, 0f, 0f},
            this.walker.decompose("fx/DecoratedPotRenderer", "createModelTransformation", null));
    }

    // ------------------------------------------------------------------------------------
    // guard rails
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("Axis.XN negates the rotation sense")
    void negatedAxis() {
        assertArrayEquals(new float[]{0f, 0f, 0f, -90f, 0f, 0f},
            this.walker.decompose("fx/NegatedAxisRenderer", "negatedTransform", null));
    }

    @Test
    @DisplayName("translate after a non-identity rotation poisons - null, never garbage")
    void poisonOnUnknown() {
        assertNull(this.walker.decompose("fx/PoisonRenderer", "poisonTransform", null));
    }

    @Test
    @DisplayName("FIELD: captures the Transformation at the target PUTSTATIC, not the first ctor")
    void fieldStopsAtTargetStore() {
        // the conduit fixture's <clinit> builds a DECOY Transformation into another field first
        assertArrayEquals(new float[]{8f, 8f, 8f, 0f, 0f, 0f},
            this.walker.decompose("fx/ConduitRenderer", "FIELD:DEFAULT_TRANSFORMATION", null));
        assertNull(this.walker.decompose("fx/ConduitRenderer", "FIELD:NO_SUCH_FIELD", null));
    }

    @Test
    @DisplayName("missing renderer / method decompose to null")
    void missingTargets() {
        assertNull(this.walker.decompose("fx/NoSuchRenderer", "createModelTransform", null));
        assertNull(this.walker.decompose("fx/BedRenderer", "noSuchMethod", null));
    }

    // ------------------------------------------------------------------------------------
    // fixtures - each mirrors the 26.1 javap of its vanilla namesake, instruction for instruction
    // ------------------------------------------------------------------------------------

    /** {@code BedRenderer.createModelTransform(Direction)} - translation, X90 rotate, Z(180+yaw) rotateAround. */
    private static @NotNull ClassNode bedRenderer() {
        ClassNode cn = fixture("fx/BedRenderer");
        InsnList code = method(cn, "createModelTransform", "(L" + DIRECTION + ";)L" + TRANSFORMATION + ";");
        newDup(code, TRANSFORMATION);
        newDup(code, MATRIX4F);
        init(code, MATRIX4F, "()V");
        f(code, 0f); f(code, 0.5625f); f(code, 0f);
        virt(code, "translation", VEC3_OPS_DESC);
        axis(code, "XP"); f(code, 90f); rotationDegrees(code);
        virt(code, "rotate", ROTATE_DESC);
        axis(code, "ZP"); f(code, 180f);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DIRECTION, "toYRot", "()F"));
        code.add(new InsnNode(Opcodes.FADD));
        rotationDegrees(code);
        f(code, 0.5f); f(code, 0.5f); f(code, 0.5f);
        virt(code, "rotateAround", ROTATE_AROUND_DESC);
        init(code, TRANSFORMATION, CTOR_MATRIX);
        code.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /** {@code ShulkerBoxRenderer.createModelTransform(Direction)} - the 0.9995 z-fight fudge chain. */
    private static @NotNull ClassNode shulkerRenderer() {
        ClassNode cn = fixture("fx/ShulkerBoxRenderer");
        InsnList code = method(cn, "createModelTransform", "(L" + DIRECTION + ";)L" + TRANSFORMATION + ";");
        f(code, 0.9995f);
        code.add(new VarInsnNode(Opcodes.FSTORE, 1));
        newDup(code, TRANSFORMATION);
        newDup(code, MATRIX4F);
        init(code, MATRIX4F, "()V");
        f(code, 0.5f); f(code, 0.5f); f(code, 0.5f);
        virt(code, "translation", VEC3_OPS_DESC);
        f(code, 0.9995f); f(code, 0.9995f); f(code, 0.9995f);
        virt(code, "scale", VEC3_OPS_DESC);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DIRECTION, "getRotation", "()Lorg/joml/Quaternionf;"));
        virt(code, "rotate", ROTATE_DESC);
        f(code, 1f); f(code, -1f); f(code, -1f);
        virt(code, "scale", VEC3_OPS_DESC);
        f(code, 0f); f(code, -1f); f(code, 0f);
        virt(code, "translate", VEC3_OPS_DESC);
        init(code, TRANSFORMATION, CTOR_MATRIX);
        code.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /** {@code SkullBlockRenderer.createGroundTransformation(int)} - convertToDegrees + FNEG + scale(-1,-1,1). */
    private static @NotNull ClassNode skullRenderer() {
        ClassNode cn = fixture("fx/SkullBlockRenderer");
        InsnList code = method(cn, "createGroundTransformation", "(I)L" + TRANSFORMATION + ";");
        newDup(code, TRANSFORMATION);
        newDup(code, MATRIX4F);
        init(code, MATRIX4F, "()V");
        f(code, 0.5f); f(code, 0f); f(code, 0.5f);
        virt(code, "translation", VEC3_OPS_DESC);
        axis(code, "YP");
        code.add(new VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ROTATION_SEGMENT, "convertToDegrees", "(I)F"));
        code.add(new InsnNode(Opcodes.FNEG));
        rotationDegrees(code);
        virt(code, "rotate", ROTATE_DESC);
        f(code, -1f); f(code, -1f); f(code, 1f);
        virt(code, "scale", VEC3_OPS_DESC);
        init(code, TRANSFORMATION, CTOR_MATRIX);
        code.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /** {@code BannerRenderer.modelTransformation(float)} - the component ctor over static Vector3f fields. */
    private static @NotNull ClassNode bannerRenderer() {
        ClassNode cn = fixture("fx/BannerRenderer");
        InsnList clinit = method(cn, "<clinit>", "()V");
        staticVector(clinit, cn.name, "MODEL_SCALE", 0.6666667f, -0.6666667f, -0.6666667f);
        staticVector(clinit, cn.name, "MODEL_TRANSLATION", 0.5f, 0f, 0.5f);
        clinit.add(new InsnNode(Opcodes.RETURN));

        InsnList code = method(cn, "modelTransformation", "(F)L" + TRANSFORMATION + ";");
        newDup(code, TRANSFORMATION);
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "MODEL_TRANSLATION", VEC3C_DESC));
        axis(code, "YP");
        code.add(new VarInsnNode(Opcodes.FLOAD, 0));
        code.add(new InsnNode(Opcodes.FNEG));
        rotationDegrees(code);
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "MODEL_SCALE", VEC3C_DESC));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        init(code, TRANSFORMATION, CTOR_COMPONENTS);
        code.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /**
     * {@code ConduitRenderer.<clinit>} - the component ctor stored to {@code DEFAULT_TRANSFORMATION},
     * preceded by a DECOY Transformation stored to another field (the stop-at-PUTSTATIC pin).
     */
    private static @NotNull ClassNode conduitRenderer() {
        ClassNode cn = fixture("fx/ConduitRenderer");
        InsnList code = method(cn, "<clinit>", "()V");
        newDup(code, TRANSFORMATION);
        newDup(code, VECTOR3F);
        f(code, 1f); f(code, 1f); f(code, 1f);
        init(code, VECTOR3F, "(FFF)V");
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        init(code, TRANSFORMATION, CTOR_COMPONENTS);
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "DECOY_TRANSFORMATION", "L" + TRANSFORMATION + ";"));
        newDup(code, TRANSFORMATION);
        newDup(code, VECTOR3F);
        f(code, 0.5f); f(code, 0.5f); f(code, 0.5f);
        init(code, VECTOR3F, "(FFF)V");
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        init(code, TRANSFORMATION, CTOR_COMPONENTS);
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "DEFAULT_TRANSFORMATION", "L" + TRANSFORMATION + ";"));
        code.add(new InsnNode(Opcodes.RETURN));
        return cn;
    }

    /** {@code StandingSignRenderer} - {@code baseTransformation} attachment branch + {@code bodyTransformation}. */
    private static @NotNull ClassNode standingSignRenderer() {
        ClassNode cn = fixture("fx/StandingSignRenderer");
        InsnList base = method(cn, "baseTransformation", "(F" + ATTACHMENT_DESC + ")" + MATRIX4F_RET);
        newDup(base, MATRIX4F);
        init(base, MATRIX4F, "()V");
        f(base, 0.5f); f(base, 0.5f); f(base, 0.5f);
        virt(base, "translate", VEC3_OPS_DESC);
        axis(base, "YP");
        base.add(new VarInsnNode(Opcodes.FLOAD, 0));
        base.add(new InsnNode(Opcodes.FNEG));
        rotationDegrees(base);
        virt(base, "rotate", ROTATE_DESC);
        base.add(new VarInsnNode(Opcodes.ASTORE, 2));
        base.add(new VarInsnNode(Opcodes.ALOAD, 1));
        base.add(new FieldInsnNode(Opcodes.GETSTATIC, ATTACHMENT, "WALL", ATTACHMENT_DESC));
        LabelNode skipWall = new LabelNode();
        base.add(new JumpInsnNode(Opcodes.IF_ACMPNE, skipWall));
        base.add(new VarInsnNode(Opcodes.ALOAD, 2));
        f(base, 0f); f(base, -0.3125f); f(base, -0.4375f);
        virt(base, "translate", VEC3_OPS_DESC);
        base.add(new InsnNode(Opcodes.POP));
        base.add(skipWall);
        base.add(new VarInsnNode(Opcodes.ALOAD, 2));
        base.add(new InsnNode(Opcodes.ARETURN));

        InsnList body = method(cn, "bodyTransformation", "(" + ATTACHMENT_DESC + "F)L" + TRANSFORMATION + ";");
        newDup(body, TRANSFORMATION);
        body.add(new VarInsnNode(Opcodes.FLOAD, 1));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, "baseTransformation",
            "(F" + ATTACHMENT_DESC + ")" + MATRIX4F_RET));
        f(body, 0.6666667f); f(body, -0.6666667f); f(body, -0.6666667f);
        virt(body, "scale", VEC3_OPS_DESC);
        init(body, TRANSFORMATION, CTOR_MATRIX);
        body.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /** {@code HangingSignRenderer} - translation + post-rotate translate fold + {@code scale(1,-1,-1)}. */
    private static @NotNull ClassNode hangingSignRenderer() {
        ClassNode cn = fixture("fx/HangingSignRenderer");
        InsnList base = method(cn, "baseTransformation", "(F)" + MATRIX4F_RET);
        newDup(base, MATRIX4F);
        init(base, MATRIX4F, "()V");
        f(base, 0.5f); f(base, 0.9375f); f(base, 0.5f);
        virt(base, "translation", VEC3_OPS_DESC);
        axis(base, "YP");
        base.add(new VarInsnNode(Opcodes.FLOAD, 0));
        base.add(new InsnNode(Opcodes.FNEG));
        rotationDegrees(base);
        virt(base, "rotate", ROTATE_DESC);
        f(base, 0f); f(base, -0.3125f); f(base, 0f);
        virt(base, "translate", VEC3_OPS_DESC);
        base.add(new InsnNode(Opcodes.ARETURN));

        InsnList body = method(cn, "bodyTransformation", "(F)L" + TRANSFORMATION + ";");
        newDup(body, TRANSFORMATION);
        body.add(new VarInsnNode(Opcodes.FLOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, "baseTransformation", "(F)" + MATRIX4F_RET));
        f(body, 1f); f(body, -1f); f(body, -1f);
        virt(body, "scale", VEC3_OPS_DESC);
        init(body, TRANSFORMATION, CTOR_MATRIX);
        body.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /** {@code DecoratedPotRenderer.createModelTransformation(Direction)} - the lone dropped rotateAround. */
    private static @NotNull ClassNode potRenderer() {
        ClassNode cn = fixture("fx/DecoratedPotRenderer");
        InsnList code = method(cn, "createModelTransformation", "(L" + DIRECTION + ";)L" + TRANSFORMATION + ";");
        newDup(code, TRANSFORMATION);
        newDup(code, MATRIX4F);
        init(code, MATRIX4F, "()V");
        axis(code, "YP"); f(code, 180f);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DIRECTION, "toYRot", "()F"));
        code.add(new InsnNode(Opcodes.FSUB));
        rotationDegrees(code);
        f(code, 0.5f); f(code, 0.5f); f(code, 0.5f);
        virt(code, "rotateAround", ROTATE_AROUND_DESC);
        init(code, TRANSFORMATION, CTOR_MATRIX);
        code.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /** A synthetic {@code Axis.XN} rotation - vanilla has no known transform site using an N axis. */
    private static @NotNull ClassNode negatedAxisRenderer() {
        ClassNode cn = fixture("fx/NegatedAxisRenderer");
        InsnList code = method(cn, "negatedTransform", "()L" + TRANSFORMATION + ";");
        newDup(code, TRANSFORMATION);
        newDup(code, MATRIX4F);
        init(code, MATRIX4F, "()V");
        axis(code, "XN"); f(code, 90f); rotationDegrees(code);
        virt(code, "rotate", ROTATE_DESC);
        init(code, TRANSFORMATION, CTOR_MATRIX);
        code.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /** A translate AFTER a real rotation - unmodellable, must poison to null. */
    private static @NotNull ClassNode poisonRenderer() {
        ClassNode cn = fixture("fx/PoisonRenderer");
        InsnList code = method(cn, "poisonTransform", "()L" + TRANSFORMATION + ";");
        newDup(code, TRANSFORMATION);
        newDup(code, MATRIX4F);
        init(code, MATRIX4F, "()V");
        axis(code, "XP"); f(code, 90f); rotationDegrees(code);
        virt(code, "rotate", ROTATE_DESC);
        f(code, 1f); f(code, 1f); f(code, 1f);
        virt(code, "translate", VEC3_OPS_DESC);
        init(code, TRANSFORMATION, CTOR_MATRIX);
        code.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    // ------------------------------------------------------------------------------------
    // assembly shorthand
    // ------------------------------------------------------------------------------------

    private static @NotNull ClassNode fixture(@NotNull String internalName) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC;
        cn.name = internalName;
        cn.superName = "java/lang/Object";
        return cn;
    }

    /** Adds a private static method to the fixture and returns its instruction list. */
    private static @NotNull InsnList method(@NotNull ClassNode owner, @NotNull String name, @NotNull String desc) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, desc, null, null);
        owner.methods.add(mn);
        return mn.instructions;
    }

    /** {@code NEW type; DUP} - the allocation prelude of every ctor site. */
    private static void newDup(@NotNull InsnList code, @NotNull String type) {
        code.add(new TypeInsnNode(Opcodes.NEW, type));
        code.add(new InsnNode(Opcodes.DUP));
    }

    private static void init(@NotNull InsnList code, @NotNull String owner, @NotNull String desc) {
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "<init>", desc));
    }

    private static void virt(@NotNull InsnList code, @NotNull String name, @NotNull String desc) {
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MATRIX4F, name, desc));
    }

    private static void axis(@NotNull InsnList code, @NotNull String constant) {
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, AXIS, constant, AXIS_DESC));
    }

    private static void rotationDegrees(@NotNull InsnList code) {
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, AXIS, "rotationDegrees", QUAT_RET));
    }

    /** A float constant - {@code FCONST_*} for 0/1/2 (as javac emits), else {@code LDC}. */
    private static void f(@NotNull InsnList code, float value) {
        if (value == 0f) code.add(new InsnNode(Opcodes.FCONST_0));
        else if (value == 1f) code.add(new InsnNode(Opcodes.FCONST_1));
        else if (value == 2f) code.add(new InsnNode(Opcodes.FCONST_2));
        else code.add(new LdcInsnNode(value));
    }

    /** {@code NEW Vector3f; DUP; <xyz>; <init>; PUTSTATIC owner.field} - the static-vector shape. */
    private static void staticVector(@NotNull InsnList code, @NotNull String owner, @NotNull String field,
                                     float x, float y, float z) {
        newDup(code, VECTOR3F);
        f(code, x); f(code, y); f(code, z);
        init(code, VECTOR3F, "(FFF)V");
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field, VEC3C_DESC));
    }

}
