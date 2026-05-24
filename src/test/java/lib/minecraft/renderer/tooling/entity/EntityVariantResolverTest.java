package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.util.ClassNodeCache;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Unit coverage for {@link EntityVariantResolver#resolveEnumDefault}, the enum-DEFAULT
 * variant walker folded into {@link EntityVariantResolver} from the deleted
 * {@code EntityVariantDefaultResolver}. Every fixture is a synthetic in-memory jar.
 */
@DisplayName("EntityVariantResolver.resolveEnumDefault")
class EntityVariantResolverTest {

    private static final String VARIANT_ENUM = "AxolotlVariant";
    private static final String STATE_CLASS = "AxolotlRenderState";
    private static final String RENDERER_CLASS = "AxolotlRenderer";

    @Test
    @DisplayName("happy path: renderer reads state.variant, enum has DEFAULT -> returns lowercase name")
    void happyPath(@TempDir Path tmp) throws IOException {
        byte[] enumBytes = writeVariantEnum(VARIANT_ENUM, "LUCY");
        byte[] stateBytes = writeStateClass(STATE_CLASS, VARIANT_ENUM);
        byte[] rendererBytes = writeRendererWithVariantRead(RENDERER_CLASS, STATE_CLASS, VARIANT_ENUM);
        try (ZipFile zip = makeJar(tmp, Map.of(
            VARIANT_ENUM, enumBytes,
            STATE_CLASS, stateBytes,
            RENDERER_CLASS, rendererBytes
        ))) {
            ClassNodeCache cache = new ClassNodeCache(zip);
            EntityVariantResolver.EnumDefault result = EntityVariantResolver.resolveEnumDefault(
                cache, RENDERER_CLASS, new Diagnostics());

            assertThat(result, notNullValue());
            assertThat(result.variantClass(), equalTo(VARIANT_ENUM));
            assertThat("defaultName must be lowercased", result.defaultName(), equalTo("lucy"));
        }
    }

    @Test
    @DisplayName("missing renderer class returns null")
    void missingRenderer(@TempDir Path tmp) throws IOException {
        try (ZipFile zip = makeJar(tmp, Map.of())) {
            ClassNodeCache cache = new ClassNodeCache(zip);
            assertThat(EntityVariantResolver.resolveEnumDefault(cache, "NoSuch", new Diagnostics()), is(nullValue()));
        }
    }

    @Test
    @DisplayName("renderer without getTextureLocation method returns null")
    void noGetTextureLocation(@TempDir Path tmp) throws IOException {
        byte[] rendererBytes = writeBareRenderer(RENDERER_CLASS);
        try (ZipFile zip = makeJar(tmp, Map.of(RENDERER_CLASS, rendererBytes))) {
            ClassNodeCache cache = new ClassNodeCache(zip);
            assertThat(EntityVariantResolver.resolveEnumDefault(cache, RENDERER_CLASS, new Diagnostics()),
                is(nullValue()));
        }
    }

    @Test
    @DisplayName("getTextureLocation with no GETFIELD variant returns null")
    void getTextureLocationWithoutVariantRead(@TempDir Path tmp) throws IOException {
        byte[] rendererBytes = writeRendererWithoutVariantRead(RENDERER_CLASS, STATE_CLASS);
        try (ZipFile zip = makeJar(tmp, Map.of(RENDERER_CLASS, rendererBytes))) {
            ClassNodeCache cache = new ClassNodeCache(zip);
            assertThat(EntityVariantResolver.resolveEnumDefault(cache, RENDERER_CLASS, new Diagnostics()),
                is(nullValue()));
        }
    }

    @Test
    @DisplayName("variant enum without DEFAULT field returns null")
    void variantEnumLacksDefault(@TempDir Path tmp) throws IOException {
        byte[] enumBytes = writeVariantEnumWithoutDefault(VARIANT_ENUM);
        byte[] stateBytes = writeStateClass(STATE_CLASS, VARIANT_ENUM);
        byte[] rendererBytes = writeRendererWithVariantRead(RENDERER_CLASS, STATE_CLASS, VARIANT_ENUM);
        try (ZipFile zip = makeJar(tmp, Map.of(
            VARIANT_ENUM, enumBytes,
            STATE_CLASS, stateBytes,
            RENDERER_CLASS, rendererBytes
        ))) {
            ClassNodeCache cache = new ClassNodeCache(zip);
            assertThat(EntityVariantResolver.resolveEnumDefault(cache, RENDERER_CLASS, new Diagnostics()),
                is(nullValue()));
        }
    }

    // ============================================================================================
    // Fixture helpers
    // ============================================================================================

    /** Variant enum class with the canonical GETSTATIC + PUTSTATIC DEFAULT pattern in <clinit>. */
    private static byte[] writeVariantEnum(String enumName, String constName) {
        ClassWriter cw = new ClassWriter(0);
        String fieldDesc = "L" + enumName + ";";
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, enumName, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, constName, fieldDesc, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "DEFAULT", fieldDesc, null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, enumName, constName, fieldDesc);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, enumName, "DEFAULT", fieldDesc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Variant enum class with no DEFAULT field at all. */
    private static byte[] writeVariantEnumWithoutDefault(String enumName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, enumName, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "RED", "L" + enumName + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** RenderState class declaring a public {@code variant} field of the given enum type. */
    private static byte[] writeStateClass(String stateName, String variantEnum) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, stateName, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "variant", "L" + variantEnum + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Renderer class with a {@code getTextureLocation(StateClass)Identifier} method whose body
     * reads {@code state.variant : LVariantEnum;} - the GETFIELD that
     * {@link EntityVariantResolver#resolveEnumDefault} keys on.
     */
    private static byte[] writeRendererWithVariantRead(String rendererName, String stateClass, String variantEnum) {
        ClassWriter cw = new ClassWriter(0);
        String identifierDesc = "L" + VanillaSourceClasses.IDENTIFIER + ";";
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, rendererName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
            "getTextureLocation", "(L" + stateClass + ";)" + identifierDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETFIELD, stateClass, "variant", "L" + variantEnum + ";");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Renderer with a {@code getTextureLocation} method whose body never touches a
     * {@code variant} field - tests the {@code findVariantFieldClass} bail-out path.
     */
    private static byte[] writeRendererWithoutVariantRead(String rendererName, String stateClass) {
        ClassWriter cw = new ClassWriter(0);
        String identifierDesc = "L" + VanillaSourceClasses.IDENTIFIER + ";";
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, rendererName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
            "getTextureLocation", "(L" + stateClass + ";)" + identifierDesc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Renderer with no {@code getTextureLocation} method at all. */
    private static byte[] writeBareRenderer(String rendererName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, rendererName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ZipFile makeJar(Path dir, Map<String, byte[]> classes) throws IOException {
        Path jar = dir.resolve("fixture.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            Map<String, byte[]> ordered = new LinkedHashMap<>(classes);
            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return new ZipFile(jar.toFile());
    }
}
