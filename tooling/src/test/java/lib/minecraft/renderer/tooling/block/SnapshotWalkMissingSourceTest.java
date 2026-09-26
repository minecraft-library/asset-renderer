package lib.minecraft.renderer.tooling.block;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.index.BlockRegistryIndex;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import lib.minecraft.renderer.tooling.item.GlintItemsWalk;
import lib.minecraft.renderer.tooling.item.PotionColorWalk;

/**
 * Pins for the three snapshot walks' missing-source reports: each records exactly one ERROR
 * with its own subject - the class-absent arm naming the class, the member-absent arm naming
 * class and member - and returns before emitting anything else. The failure is invisible on a
 * shipped jar, where every walked class resolves, so the texts are pinned here over
 * synthesized jars - no network, no client jar.
 */
@DisplayName("snapshot walks - class-absent and member-absent ERROR texts, verbatim")
class SnapshotWalkMissingSourceTest {

    @TempDir
    static Path tempDir;

    /** A jar holding none of the walked classes. */
    private static ClassNodeCache classAbsent;

    /** A jar holding each walked class without its walked member. */
    private static ClassNodeCache memberAbsent;

    /** Never consulted - both arms return before the walk reads it; built over the synthetic jar. */
    private static BlockRegistryIndex index;

    @BeforeAll
    static void openFixtureJars() throws IOException {
        Path absentJar = tempDir.resolve("class-absent.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(absentJar))) {
            write(zip, "test/Unrelated");
        }
        classAbsent = ClassNodeCache.open(absentJar);

        Path memberlessJar = tempDir.resolve("member-absent.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(memberlessJar))) {
            write(zip, SourceClasses.Types.BLOCK_COLORS);
            write(zip, SourceClasses.Types.ITEMS);
            write(zip, SourceClasses.Types.MOB_EFFECTS);
        }
        memberAbsent = ClassNodeCache.open(memberlessJar);

        index = BlockRegistryIndex.build(run(memberAbsent, Diagnostics.root("fixture", Diagnostics.Output.NONE, null)));
    }

    @AfterAll
    static void closeFixtureJars() {
        classAbsent.close();
        memberAbsent.close();
    }

    @Test
    @DisplayName("tint walk - both arms name the tint table")
    void tintWalkArms() {
        Diagnostics classArm = Diagnostics.root("snapshot", Diagnostics.Output.NONE, null);
        TintWalk.run(run(classAbsent, classArm), index, JsonTree.object());
        assertSoleError(classArm,
            "'" + SourceClasses.Types.BLOCK_COLORS + "' class missing - tint table unresolved");

        Diagnostics memberArm = Diagnostics.root("snapshot", Diagnostics.Output.NONE, null);
        TintWalk.run(run(memberAbsent, memberArm), index, JsonTree.object());
        assertSoleError(memberArm,
            "'" + SourceClasses.Types.BLOCK_COLORS + "." + SourceClasses.Methods.CREATE_DEFAULT
                + "' missing - tint table unresolved");
    }

    @Test
    @DisplayName("glint walk - both arms name the glint set")
    void glintItemsWalkArms() {
        Diagnostics classArm = Diagnostics.root("snapshot", Diagnostics.Output.NONE, null);
        GlintItemsWalk.run(run(classAbsent, classArm), JsonTree.object());
        assertSoleError(classArm,
            "'" + SourceClasses.Types.ITEMS + "' class missing - glint set unresolved");

        Diagnostics memberArm = Diagnostics.root("snapshot", Diagnostics.Output.NONE, null);
        GlintItemsWalk.run(run(memberAbsent, memberArm), JsonTree.object());
        assertSoleError(memberArm,
            "'" + SourceClasses.Types.ITEMS + "." + ClassKit.CLINIT + "' missing - glint set unresolved");
    }

    @Test
    @DisplayName("potion walk - both arms name the effect colour table")
    void potionColorWalkArms() {
        Diagnostics classArm = Diagnostics.root("snapshot", Diagnostics.Output.NONE, null);
        PotionColorWalk.run(run(classAbsent, classArm), JsonTree.object());
        assertSoleError(classArm,
            "'" + SourceClasses.Types.MOB_EFFECTS + "' class missing - effect colour table unresolved");

        Diagnostics memberArm = Diagnostics.root("snapshot", Diagnostics.Output.NONE, null);
        PotionColorWalk.run(run(memberAbsent, memberArm), JsonTree.object());
        assertSoleError(memberArm,
            "'" + SourceClasses.Types.MOB_EFFECTS + "." + ClassKit.CLINIT + "' missing - effect colour table unresolved");
    }

    // ------------------------------------------------------------------------------------
    // fixture plumbing
    // ------------------------------------------------------------------------------------

    private static @NotNull ToolingRun run(@NotNull ClassNodeCache cache, @NotNull Diagnostics diagnostics) {
        return new ToolingRun(ClientOptions.defaults(), cache, diagnostics);
    }

    private static void assertSoleError(@NotNull Diagnostics root, @NotNull String expected) {
        List<Diagnostics.Entry> entries = root.entries();
        assertEquals(1, entries.size(), "the walk returns after its one report");
        Diagnostics.Entry entry = entries.getFirst();
        assertEquals(Diagnostics.Severity.ERROR, entry.severity());
        assertEquals(expected, entry.message());
    }

    /**
     * Writes a class of the given name holding one empty static method and no static
     * initialiser - present in the jar, absent every walked member.
     *
     * @param zip the open fixture jar being written
     * @param internalName the class's internal name
     * @throws IOException if the entry cannot be written
     */
    private static void write(@NotNull ZipOutputStream zip, @NotNull String internalName) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor other = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "other", "()V", null, null);
        other.visitCode();
        other.visitInsn(Opcodes.RETURN);
        other.visitMaxs(0, 0);
        other.visitEnd();
        writer.visitEnd();
        zip.putNextEntry(new ZipEntry(internalName + ".class"));
        zip.write(writer.toByteArray());
        zip.closeEntry();
    }

}
