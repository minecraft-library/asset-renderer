package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Units for looked-up walk sources: the cache-fed openers answer {@link Missing#CLASS} or
 * {@link Missing#MEMBER} before any terminal, a resolved empty body walks to {@link Exit#END},
 * collectors on a missing source answer empty while run-shaped terminals answer
 * {@link Exit#MISSING}, and {@code from(null)} is the empty walk rather than a missing one.
 * Runs over a synthesized jar of generated class files - no network, no client jar.
 */
@DisplayName("walk sources - cache-fed openers, MISSING halves, and the empty walk")
class WalkSourceTest {

    private static final @NotNull String WITH_CLINIT = "test/WithClinit";
    private static final @NotNull String NO_CLINIT = "test/NoClinit";
    private static final @NotNull String ABSENT = "test/Absent";

    @TempDir
    static Path tempDir;

    private static ClassNodeCache cache;

    @BeforeAll
    static void openFixtureJar() throws IOException {
        Path jar = tempDir.resolve("fixture.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(zip, WITH_CLINIT, withClinitClass());
            write(zip, NO_CLINIT, noClinitClass());
        }
        cache = ClassNodeCache.open(jar);
    }

    @AfterAll
    static void closeFixtureJar() {
        cache.close();
    }

    @Test
    @DisplayName("over(cache, owner, name) answers CLASS, MEMBER or null via missing() before any terminal")
    void namedOpenerMissingHalves() {
        // missing() is a descriptor query - none of these runs a traversal
        assertEquals(Missing.CLASS, AsmWalker.over(cache, ABSENT, "work").missing());
        assertEquals(Missing.MEMBER, AsmWalker.over(cache, WITH_CLINIT, "absent").missing());
        assertNull(AsmWalker.over(cache, WITH_CLINIT, "work").missing());
        // the resolved opener walks the named method's own body
        assertEquals("w", AsmWalker.over(cache, WITH_CLINIT, "work").mapNotNull(AsmWalker::stringLiteral).first());
        // the half stays readable through a narrowing stage
        assertEquals(Missing.CLASS, AsmWalker.over(cache, ABSENT, "work").names().missing());
    }

    @Test
    @DisplayName("clinit() is the named opener aimed at <clinit>: no initialiser is MEMBER, no class is CLASS")
    void clinitOpener() {
        assertEquals(Missing.MEMBER, AsmWalker.clinit(cache, NO_CLINIT).missing());
        assertEquals(Missing.CLASS, AsmWalker.clinit(cache, ABSENT).missing());
        // resolved: the walk reaches the static initialiser's own instructions
        assertNull(AsmWalker.clinit(cache, WITH_CLINIT).missing());
        assertEquals("boot", AsmWalker.clinit(cache, WITH_CLINIT).mapNotNull(AsmWalker::stringLiteral).first());
    }

    @Test
    @DisplayName("present-but-empty is not missing: an empty body walks to END with missing() null")
    void presentButEmptyBody() {
        AsmWalker walk = AsmWalker.over(cache, WITH_CLINIT, "empty");
        assertNull(walk.missing());
        assertEquals(Exit.END, walk.run());
        assertEquals(List.of(), walk.toList());
        assertNull(walk.first());
    }

    @Test
    @DisplayName("collectors on a missing source answer empty, and the fold chains still name the half")
    void missingSourceCollectorsEmpty() {
        assertEquals(List.of(), AsmWalker.over(cache, ABSENT, "work").toList());
        CommitWalk<FieldInsnNode, String> commits = AsmWalker.over(cache, ABSENT, "work")
            .gather(AsmWalker::stringLiteral)
            .commitAt(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC);
        assertEquals(Missing.CLASS, commits.missing());
        assertEquals(Map.of(), commits.toMap(fi -> fi.name, values -> values.isEmpty() ? null : values.getLast()));
        PairWalk<String, String> pairs = AsmWalker.over(cache, WITH_CLINIT, "absent")
            .latch(node -> node instanceof FieldInsnNode fi ? fi.name : null)
            .commitOn(AsmWalker::stringLiteral);
        assertEquals(Missing.MEMBER, pairs.missing());
        assertEquals(Map.of(), pairs.toMapFirstWins());
    }

    @Test
    @DisplayName("run(), trace() and forEach() on a missing source answer Exit.MISSING")
    void missingSourceRunExits() {
        assertEquals(Exit.MISSING, AsmWalker.over(cache, ABSENT, "work").run());
        assertEquals(Exit.MISSING, AsmWalker.over(cache, ABSENT, "work").trace(AbstractInsnNode::getNext));
        assertEquals(Exit.MISSING, AsmWalker.clinit(cache, NO_CLINIT).forEach(node -> {}));
        assertEquals(Exit.MISSING, AsmWalker.over(cache, ABSENT, "work")
            .latch(node -> node instanceof FieldInsnNode fi ? fi.name : null)
            .commitOn(AsmWalker::stringLiteral)
            .forEach((key, value) -> {}));
    }

    @Test
    @DisplayName("from(null) is the empty walk, not a missing source")
    void fromNullIsEmptyNotMissing() {
        assertNull(AsmWalker.from(null).first());
        assertNull(AsmWalker.from(null).missing());
        assertEquals(Exit.END, AsmWalker.from(null).run());
    }

    // ------------------------------------------------------------------------------------
    // fixture bytecode
    // ------------------------------------------------------------------------------------

    private static void write(@NotNull ZipOutputStream zip, @NotNull String internalName, byte @NotNull [] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(internalName + ".class"));
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * A {@code <clinit>} storing one literal, a literal-bearing {@code work} body, and an abstract {@code empty}.
     */
    private static byte @NotNull [] withClinitClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, WITH_CLINIT, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_STATIC, "NAME", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn("boot");
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, WITH_CLINIT, "NAME", "Ljava/lang/String;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor work = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "work", "()V", null, null);
        work.visitCode();
        work.visitLdcInsn("w");
        work.visitInsn(Opcodes.POP);
        work.visitInsn(Opcodes.RETURN);
        work.visitMaxs(0, 0);
        work.visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "empty", "()V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * A class declaring no static initialiser at all.
     */
    private static byte @NotNull [] noClinitClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, NO_CLINIT, null, "java/lang/Object", null);
        MethodVisitor plain = writer.visitMethod(Opcodes.ACC_PUBLIC, "plain", "()V", null, null);
        plain.visitCode();
        plain.visitInsn(Opcodes.RETURN);
        plain.visitMaxs(0, 0);
        plain.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

}
