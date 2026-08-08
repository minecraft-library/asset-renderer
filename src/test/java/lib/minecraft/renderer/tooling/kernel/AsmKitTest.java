package lib.minecraft.renderer.tooling.kernel;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Units for the tooling {@link AsmKit} dissolvers and cache-fed hierarchy walks against the
 * {@link ClassNodeCache} surface, over a synthesized jar of generated class files - no network,
 * no client jar.
 */
@DisplayName("tooling AsmKit dissolvers + hierarchy walks against the cache surface")
class AsmKitTest {

    private static final @NotNull String BASE = "test/Base";
    private static final @NotNull String MID = "test/Mid";
    private static final @NotNull String LEAF = "test/Leaf";
    private static final @NotNull String COAT = "test/Coat";
    private static final @NotNull String COAT_MAP = "test/CoatMap";
    private static final @NotNull String TRANSFORMER = "test/MeshTransformer";
    private static final @NotNull String SCALES = "test/Scales";
    private static final @NotNull String VARIANT = "test/Variant";
    private static final @NotNull String TRANSFORMER_DESC = "L" + TRANSFORMER + ";";

    @TempDir
    static Path tempDir;

    private static ClassNodeCache cache;

    @BeforeAll
    static void openFixtureJar() throws IOException {
        Path jar = tempDir.resolve("fixture.jar");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            write(zip, BASE, baseClass());
            write(zip, MID, midClass());
            write(zip, LEAF, leafClass());
            write(zip, COAT_MAP, coatMapClass());
            write(zip, SCALES, scalesClass());
            write(zip, VARIANT, variantClass());
            zip.putNextEntry(new ZipEntry("assets/test/some.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        cache = ClassNodeCache.open(jar);
    }

    @AfterAll
    static void closeFixtureJar() {
        cache.close();
    }

    // ------------------------------------------------------------------------------------
    // cache surface
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("cache: load caches misses, require throws, resource API reads entries")
    void cacheSurface() {
        assertNotNull(cache.load(BASE));
        assertNull(cache.load("test/Absent"));
        assertNull(cache.load("test/Absent"), "absence is cached, second lookup identical");
        assertEquals("Missing class 'test/Absent' (unit)",
            org.junit.jupiter.api.Assertions.assertThrows(ToolingException.class,
                () -> cache.require("test/Absent", "unit")).getMessage());
        assertTrue(cache.hasEntry("assets/test/some.png"));
        assertFalse(cache.hasEntry("assets/test/other.png"));
        assertEquals(List.of("assets/test/some.png"), cache.list("assets/", ".png"));
        byte[] bytes = cache.readBytes("assets/test/some.png");
        assertNotNull(bytes);
        assertEquals(3, bytes.length);
        assertNull(cache.readBytes("assets/test/other.png"));
    }

    // ------------------------------------------------------------------------------------
    // hierarchy walks
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("findMethodInHierarchy resolves through supers; null descriptor matches by name")
    void methodHierarchy() {
        MethodNode shared = AsmKit.findMethodInHierarchy(cache, LEAF, "shared", "()V");
        assertNotNull(shared);
        assertNull(AsmKit.findMethodInHierarchy(cache, LEAF, "shared", "(I)V"), "descriptor mismatch");
        assertNotNull(AsmKit.findMethodInHierarchy(cache, LEAF, "shared", null), "name-only match");
        assertNull(AsmKit.findMethodInHierarchy(cache, LEAF, "absent", null));
    }

    @Test
    @DisplayName("findFieldInHierarchy resolves through supers")
    void fieldHierarchy() {
        FieldNode flag = AsmKit.findFieldInHierarchy(cache, LEAF, "flag");
        assertNotNull(flag);
        assertEquals("Z", flag.desc);
        assertNull(AsmKit.findFieldInHierarchy(cache, LEAF, "absent"));
    }

    @Test
    @DisplayName("walkSuperChainUntil returns the first accepted class and stops before Object")
    void superChainUntil() {
        ClassNode hit = AsmKit.walkSuperChainUntil(cache, LEAF, node -> AsmKit.findMethod(node, "mid") != null);
        assertNotNull(hit);
        assertEquals(MID, hit.name);
        assertNull(AsmKit.walkSuperChainUntil(cache, LEAF, node -> false));
        assertTrue(AsmKit.extendsClass(cache, LEAF, BASE));
        assertFalse(AsmKit.extendsClass(cache, BASE, LEAF));
    }

    // ------------------------------------------------------------------------------------
    // dissolvers
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("readStaticEnumMap pairs enum keys with first decoded values, committing at the map store")
    void enumMap() {
        Map<String, String> map = AsmKit.readStaticEnumMap(
            cache, COAT_MAP, "MAP", AsmKit::readStringLiteral);
        assertEquals(2, map.size());
        assertEquals(List.of("WHITE", "BLACK"), List.copyOf(map.keySet()), "clinit encounter order");
        assertEquals("horse_white", map.get("WHITE"));
        assertEquals("horse_black", map.get("BLACK"));
        // GRAY rides after the PUTSTATIC MAP commit boundary and must not appear.
        assertFalse(map.containsKey("GRAY"));
    }

    @Test
    @DisplayName("resolveStaticScalingFactor binds canonical triplets; intervening literal clears; memo hits")
    void scalingFactor() {
        Float small = AsmKit.resolveStaticScalingFactor(cache, SCALES, "SMALL", TRANSFORMER, "scaling", TRANSFORMER_DESC);
        assertNotNull(small);
        assertEquals(1.5f, small);
        // LARGE has an intervening LDC between scaling() and its PUTSTATIC, so the stricter
        // reset resolves it null.
        assertNull(AsmKit.resolveStaticScalingFactor(cache, SCALES, "LARGE", TRANSFORMER, "scaling", TRANSFORMER_DESC));
        // memo: the sibling field bound during the first walk resolves without a re-walk
        assertEquals(1.5f, AsmKit.resolveStaticScalingFactor(cache, SCALES, "SMALL", TRANSFORMER, "scaling", TRANSFORMER_DESC));
    }

    @Test
    @DisplayName("findEnumDefaultName reads the policy-supplied default field")
    void enumDefault() {
        assertEquals("RED", AsmKit.findEnumDefaultName(cache, VARIANT, "DEFAULT"));
        assertNull(AsmKit.findEnumDefaultName(cache, VARIANT, "OTHER_DEFAULT"));
    }

    // ------------------------------------------------------------------------------------
    // fixture bytecode
    // ------------------------------------------------------------------------------------

    private static void write(@NotNull ZipOutputStream zip, @NotNull String internalName, byte @NotNull [] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(internalName + ".class"));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte @NotNull [] baseClass() {
        ClassWriter writer = classHeader(BASE, "java/lang/Object");
        writer.visitField(Opcodes.ACC_PUBLIC, "flag", "Z", null, null).visitEnd();
        emptyMethod(writer, "shared", "()V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte @NotNull [] midClass() {
        ClassWriter writer = classHeader(MID, BASE);
        emptyMethod(writer, "mid", "()V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte @NotNull [] leafClass() {
        ClassWriter writer = classHeader(LEAF, MID);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Enum-map construction: two keyed literals, a commit store, and a post-commit stray. */
    private static byte @NotNull [] coatMapClass() {
        ClassWriter writer = classHeader(COAT_MAP, "java/lang/Object");
        writer.visitField(Opcodes.ACC_STATIC, "MAP", "Ljava/util/Map;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_STATIC, "STRAY", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, COAT, "WHITE", "L" + COAT + ";");
        clinit.visitInsn(Opcodes.POP);
        clinit.visitLdcInsn("horse_white");
        clinit.visitInsn(Opcodes.POP);
        clinit.visitFieldInsn(Opcodes.GETSTATIC, COAT, "BLACK", "L" + COAT + ";");
        clinit.visitInsn(Opcodes.POP);
        clinit.visitLdcInsn("horse_black");
        clinit.visitInsn(Opcodes.POP);
        clinit.visitInsn(Opcodes.ACONST_NULL);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, COAT_MAP, "MAP", "Ljava/util/Map;");
        clinit.visitFieldInsn(Opcodes.GETSTATIC, COAT, "GRAY", "L" + COAT + ";");
        clinit.visitInsn(Opcodes.POP);
        clinit.visitLdcInsn("ignored");
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, COAT_MAP, "STRAY", "Ljava/lang/String;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code SMALL = scaling(1.5f)}; {@code LARGE} has an intervening literal (clears). */
    private static byte @NotNull [] scalesClass() {
        ClassWriter writer = classHeader(SCALES, "java/lang/Object");
        writer.visitField(Opcodes.ACC_STATIC, "SMALL", TRANSFORMER_DESC, null, null).visitEnd();
        writer.visitField(Opcodes.ACC_STATIC, "LARGE", TRANSFORMER_DESC, null, null).visitEnd();
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn(1.5f);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, TRANSFORMER, "scaling", "(F)" + TRANSFORMER_DESC, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, SCALES, "SMALL", TRANSFORMER_DESC);
        clinit.visitLdcInsn(4.5f);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, TRANSFORMER, "scaling", "(F)" + TRANSFORMER_DESC, false);
        clinit.visitLdcInsn(9.9f);                       // intervening literal - clears pendingScaled
        clinit.visitInsn(Opcodes.POP);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, SCALES, "LARGE", TRANSFORMER_DESC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code DEFAULT = RED} via the canonical GETSTATIC-then-PUTSTATIC pair. */
    private static byte @NotNull [] variantClass() {
        ClassWriter writer = classHeader(VARIANT, "java/lang/Object");
        writer.visitField(Opcodes.ACC_STATIC, "RED", "L" + VARIANT + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_STATIC, "DEFAULT", "L" + VARIANT + ";", null, null).visitEnd();
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, VARIANT, "RED", "L" + VARIANT + ";");
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, VARIANT, "DEFAULT", "L" + VARIANT + ";");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static @NotNull ClassWriter classHeader(@NotNull String internalName, @NotNull String superName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        return writer;
    }

    private static void emptyMethod(@NotNull ClassWriter writer, @NotNull String name, @NotNull String descriptor) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

}
