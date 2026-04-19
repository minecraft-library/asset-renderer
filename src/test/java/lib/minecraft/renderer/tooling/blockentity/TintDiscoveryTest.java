package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

/**
 * Fast mutation tests for {@link TintDiscovery}. Each test builds a synthetic client jar via
 * {@link ClassWriter}, runs discovery against it, then flips the tint-accessor presence or
 * the {@code Flag} naming convention and re-runs to prove the output follows the bytecode.
 */
@DisplayName("TintDiscovery (bytecode-driven)")
class TintDiscoveryTest {

    @TempDir Path tempDir;

    private static void writeClass(ZipOutputStream zos, String internalName, byte[] bytes) throws IOException {
        zos.putNextEntry(new ZipEntry(internalName + ".class"));
        zos.write(bytes);
        zos.closeEntry();
    }

    /**
     * Builds a synthetic renderer class. When {@code callDyeColor} is true the renderer's
     * {@code render} method calls {@code DyeColor.getTextureDiffuseColor} so discovery sees a
     * tint-accessor. When false the method body is a pure {@code RETURN}.
     */
    private static byte[] rendererClass(String internalName, boolean callDyeColor) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
        mv.visitCode();
        if (callDyeColor) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/item/DyeColor", "getTextureDiffuseColor", "()I", false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("renderer with DyeColor.getTextureDiffuseColor is tint-bearing")
    void tintFromDyeColor() throws IOException {
        Path jar = tempDir.resolve("tint.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeClass(zos, "test/R", rendererClass("test/R", true));
        }
        ConcurrentList<Source> sources = Concurrent.newList();
        sources.add(new Source("test/FlagModel.class", "createFlagLayer", "minecraft:flag", YAxis.DOWN, 0f));
        Map<String, String> entityIdToRenderer = new LinkedHashMap<>();
        entityIdToRenderer.put("minecraft:flag", "test/R");
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Set<String> tinted = TintDiscovery.discover(zf, sources, entityIdToRenderer, new Diagnostics());
            assertThat("tint-bearing renderer + Flag-suffixed model -> entityId tinted", tinted, hasSize(1));
            assertThat(tinted, contains("minecraft:flag"));
        }
    }

    @Test
    @DisplayName("remove tint-accessor call -> entityId no longer tinted (mutation proof)")
    void removeTintAccessor() throws IOException {
        Path jar = tempDir.resolve("no-tint.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeClass(zos, "test/R", rendererClass("test/R", false));
        }
        ConcurrentList<Source> sources = Concurrent.newList();
        sources.add(new Source("test/FlagModel.class", "createFlagLayer", "minecraft:flag", YAxis.DOWN, 0f));
        Map<String, String> entityIdToRenderer = new LinkedHashMap<>();
        entityIdToRenderer.put("minecraft:flag", "test/R");
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Set<String> tinted = TintDiscovery.discover(zf, sources, entityIdToRenderer, new Diagnostics());
            assertThat("no tint-accessor call -> empty tint set", tinted, empty());
        }
    }

    @Test
    @DisplayName("renderer owning both body and Flag sources tints only the Flag")
    void onlyFlagClassesTinted() throws IOException {
        Path jar = tempDir.resolve("both.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeClass(zos, "test/R", rendererClass("test/R", true));
        }
        ConcurrentList<Source> sources = Concurrent.newList();
        sources.add(new Source("test/BodyModel.class", "createBodyLayer", "minecraft:banner", YAxis.DOWN, 0f));
        sources.add(new Source("test/FlagModel.class", "createFlagLayer", "minecraft:banner_flag", YAxis.DOWN, 0f));
        Map<String, String> entityIdToRenderer = new LinkedHashMap<>();
        entityIdToRenderer.put("minecraft:banner", "test/R");
        entityIdToRenderer.put("minecraft:banner_flag", "test/R");
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Set<String> tinted = TintDiscovery.discover(zf, sources, entityIdToRenderer, new Diagnostics());
            assertThat("only the Flag-suffixed source is tinted", tinted, contains("minecraft:banner_flag"));
        }
    }

    @Test
    @DisplayName("renderer class missing from jar surfaces diag.warn + skips")
    void missingRendererClass() throws IOException {
        Path jar = tempDir.resolve("no-renderer.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            // Empty jar: no test/R present.
        }
        ConcurrentList<Source> sources = Concurrent.newList();
        sources.add(new Source("test/FlagModel.class", "createFlagLayer", "minecraft:flag", YAxis.DOWN, 0f));
        Map<String, String> entityIdToRenderer = new LinkedHashMap<>();
        entityIdToRenderer.put("minecraft:flag", "test/R");
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Diagnostics diag = new Diagnostics();
            Set<String> tinted = TintDiscovery.discover(zf, sources, entityIdToRenderer, diag);
            assertThat("missing renderer -> no contribution", tinted, empty());
            assertThat("a warn was emitted", diag.strictFailingCount(), org.hamcrest.Matchers.is(1));
        }
    }

}
