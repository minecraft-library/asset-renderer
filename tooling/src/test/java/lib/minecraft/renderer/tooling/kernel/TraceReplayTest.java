package lib.minecraft.renderer.tooling.kernel;

import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.Trace;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link TraceReplay} against synthetic classes carrying each shape the closed step
 * vocabulary addresses - a static array table indexed by an ordinal the entry member loads, a
 * captured constructor parameter recovered through a getter's field read, and a literal reached by
 * following an invoke into another class. The engine is subject-blind, so the fixtures are too:
 * they carry the bytecode shapes and none of the vanilla names.
 *
 * <p>The failure half is the point of the arm as much as the success half - a trace declares that
 * its recipe still runs, so every way a step can fail to complete raises rather than answering a
 * fact nothing derived.
 */
@DisplayName("TraceReplay executes each step of the closed vocabulary, and fails loudly on any it cannot")
class TraceReplayTest {

    private static final @NotNull String STRING = "java/lang/String";
    private static final @NotNull String STRING_DESC = "Ljava/lang/String;";
    private static final @NotNull String STRING_ARRAY_DESC = "[Ljava/lang/String;";
    private static final @NotNull String STRING_FACTORY = "()Ljava/lang/String;";
    private static final @NotNull String CLASS_FACTORY = "()Ljava/lang/Class;";
    private static final @NotNull String INT_FACTORY = "()I";

    private static final @NotNull String SKIN_TABLE = "fx/SkinTable";
    private static final @NotNull String SKINS_FIELD = "DEFAULT_SKINS";
    private static final @NotNull String TINT_SOURCE = "fx/TintSource$1";
    private static final @NotNull String IN_HAND = "val$colorInHand";
    private static final @NotNull String IN_WORLD = "val$colorInWorld";
    private static final @NotNull String FACADE = "fx/Facade";
    private static final @NotNull String TARGET = "fx/Target";

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;
    private TraceReplay replay;

    @BeforeEach
    void openFixtureJar() throws IOException {
        Path jar = this.tempDir.resolve("fixtures.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (ClassNode fixture : new ClassNode[]{skinTable(), tintSource(), facade(), target()}) {
                zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                fixture.accept(writer);
                zip.write(writer.toByteArray());
                zip.closeEntry();
            }
        }
        this.cache = ClassNodeCache.open(jar);
        this.replay = new TraceReplay(this.cache);
    }

    @AfterEach
    void closeFixtureJar() {
        this.cache.close();
    }

    // ------------------------------------------------------------------------------------
    // the eight steps
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("int literal, static-field binding, array ordinal and string literal chain to the element")
    void arrayTableChain() {
        assertEquals("second", this.replay.replay(new Navigation.Dataflow(SKIN_TABLE, "defaultSkin", Trace.of(
            new Trace.Step.ReadIntLiteral(),
            new Trace.Step.FollowPutStatic(SKINS_FIELD),
            new Trace.Step.IndexArrayInit(),
            new Trace.Step.ReadStringLiteral()))));
    }

    @Test
    @DisplayName("the ordinal is re-read per subject, so a different loader answers a different element")
    void arrayOrdinalIsRederived() {
        assertEquals("third", this.replay.replay(new Navigation.Dataflow(SKIN_TABLE, "lastSkin", Trace.of(
            new Trace.Step.ReadIntLiteral(),
            new Trace.Step.FollowPutStatic(SKINS_FIELD),
            new Trace.Step.IndexArrayInit(),
            new Trace.Step.ReadStringLiteral()))));
    }

    @Test
    @DisplayName("the array walk anchors on the allocation, so literals bound elsewhere in the initialiser never bind an ordinal")
    void arrayWalkIgnoresLiteralsOutsideTheAllocation() {
        // the fixture's <clinit> binds a decoy int and a decoy string BEFORE the array; an unanchored
        // scan would latch the decoy int as ordinal 0 and pair it with the decoy string
        assertEquals("first", this.replay.replay(new Navigation.Dataflow(SKIN_TABLE, "firstSkin", Trace.of(
            new Trace.Step.ReadIntLiteral(),
            new Trace.Step.FollowPutStatic(SKINS_FIELD),
            new Trace.Step.IndexArrayInit(),
            new Trace.Step.ReadStringLiteral()))));
    }

    @Test
    @DisplayName("a getter's sole field read maps back to the constructor parameter it captures")
    void soleFieldReadMapsToItsParameter() {
        assertEquals(0, this.replay.replay(new Navigation.Dataflow(TINT_SOURCE, "color", Trace.of(
            new Trace.Step.FollowGetField(null),
            new Trace.Step.MapParamSlot()))));
    }

    @Test
    @DisplayName("the parameter index is read off the assignment, so the second field answers the second slot")
    void parameterIndexFollowsTheAssignment() {
        assertEquals(1, this.replay.replay(new Navigation.Dataflow(TINT_SOURCE, "worldColor", Trace.of(
            new Trace.Step.FollowGetField(null),
            new Trace.Step.MapParamSlot()))));
    }

    @Test
    @DisplayName("a named field read selects among several")
    void namedFieldReadSelectsAmongSeveral() {
        assertEquals(1, this.replay.replay(new Navigation.Dataflow(TINT_SOURCE, "bothColors", Trace.of(
            new Trace.Step.FollowGetField(IN_WORLD),
            new Trace.Step.MapParamSlot()))));
    }

    @Test
    @DisplayName("following an invoke crosses into the callee's own class before the read")
    void followInvokeCrossesClasses() {
        assertEquals("resolved/stem", this.replay.replay(new Navigation.Dataflow(FACADE, "entry", Trace.of(
            new Trace.Step.FollowInvoke("stem", STRING_FACTORY),
            new Trace.Step.ReadStringLiteral()))));
    }

    @Test
    @DisplayName("a descriptorless invoke step matches on name alone")
    void descriptorlessInvokeMatchesOnName() {
        assertEquals("resolved/stem", this.replay.replay(new Navigation.Dataflow(FACADE, "entry", Trace.of(
            new Trace.Step.FollowInvoke("stem", null),
            new Trace.Step.ReadStringLiteral()))));
    }

    @Test
    @DisplayName("a class literal reads as its JVM internal name")
    void classLiteralReadsAsInternalName() {
        assertEquals(TARGET, this.replay.replay(new Navigation.Dataflow(TARGET, "marker", Trace.of(
            new Trace.Step.ReadTypeLiteral()))));
    }

    // ------------------------------------------------------------------------------------
    // the loud failures - a trace that cannot complete never answers
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a trace declaring no steps fails loudly")
    void emptyTraceFailsLoudly() {
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(FACADE, "entry", new Trace(List.of()))));
    }

    @Test
    @DisplayName("a coordinate the jar does not hold fails loudly")
    void missingCoordinateFailsLoudly() {
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow("fx/NoSuchClass", "entry", Trace.of(new Trace.Step.ReadStringLiteral()))));
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(FACADE, "noSuchMember", Trace.of(new Trace.Step.ReadStringLiteral()))));
    }

    @Test
    @DisplayName("a trace whose last step recovers nothing fails loudly rather than answering null")
    void valuelessTraceFailsLoudly() {
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(TINT_SOURCE, "color", Trace.of(new Trace.Step.FollowGetField(null)))));
    }

    @Test
    @DisplayName("a literal the entered member does not push fails loudly")
    void missingLiteralFailsLoudly() {
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(FACADE, "entry", Trace.of(new Trace.Step.ReadStringLiteral()))));
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(FACADE, "entry", Trace.of(new Trace.Step.ReadTypeLiteral()))));
    }

    @Test
    @DisplayName("a static field the entered member never reads fails loudly")
    void unreadStaticFieldFailsLoudly() {
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(FACADE, "entry", Trace.of(
                new Trace.Step.FollowPutStatic(SKINS_FIELD),
                new Trace.Step.ReadStringLiteral()))));
    }

    @Test
    @DisplayName("an ordinal no array element binds fails loudly")
    void unboundOrdinalFailsLoudly() {
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(SKIN_TABLE, "outOfRange", Trace.of(
                new Trace.Step.ReadIntLiteral(),
                new Trace.Step.FollowPutStatic(SKINS_FIELD),
                new Trace.Step.IndexArrayInit(),
                new Trace.Step.ReadStringLiteral()))));
    }

    @Test
    @DisplayName("indexing an array with no running ordinal fails loudly")
    void ordinallessIndexFailsLoudly() {
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(SKIN_TABLE, "defaultSkin", Trace.of(
                new Trace.Step.FollowPutStatic(SKINS_FIELD),
                new Trace.Step.IndexArrayInit(),
                new Trace.Step.ReadStringLiteral()))));
    }

    @Test
    @DisplayName("an unnamed field read fails loudly where the member reads several")
    void ambiguousFieldReadFailsLoudly() {
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(TINT_SOURCE, "bothColors", Trace.of(
                new Trace.Step.FollowGetField(null),
                new Trace.Step.MapParamSlot()))));
    }

    @Test
    @DisplayName("mapping a parameter slot off a field access fails loudly")
    void parameterSlotOffAFieldFailsLoudly() {
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(SKIN_TABLE, "defaultSkin", Trace.of(
                new Trace.Step.ReadIntLiteral(),
                new Trace.Step.MapParamSlot()))));
    }

    @Test
    @DisplayName("an invoke the entered member does not make fails loudly")
    void missingInvokeFailsLoudly() {
        assertThrows(ToolingException.class, () ->
            this.replay.replay(new Navigation.Dataflow(FACADE, "entry", Trace.of(
                new Trace.Step.FollowInvoke("noSuchCallee", null),
                new Trace.Step.ReadStringLiteral()))));
    }

    // ------------------------------------------------------------------------------------
    // fixtures - the bytecode shapes, carrying no vanilla names
    // ------------------------------------------------------------------------------------

    /** A static string array built element by element, plus three loaders naming three ordinals. */
    private static @NotNull ClassNode skinTable() {
        ClassNode cn = fixture(SKIN_TABLE);
        staticField(cn, "DECOY_TEXT", STRING_DESC);
        staticField(cn, "DECOY_COUNT", "I");
        staticField(cn, SKINS_FIELD, STRING_ARRAY_DESC);

        InsnList clinit = method(cn, ClassKit.CLINIT, "()V");
        // decoys ahead of the allocation: an unanchored literal scan would take these for the table
        clinit.add(new LdcInsnNode("decoy"));
        clinit.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "DECOY_TEXT", STRING_DESC));
        clinit.add(new IntInsnNode(Opcodes.BIPUSH, 99));
        clinit.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "DECOY_COUNT", "I"));
        clinit.add(new InsnNode(Opcodes.ICONST_3));
        clinit.add(new TypeInsnNode(Opcodes.ANEWARRAY, STRING));
        element(clinit, Opcodes.ICONST_0, "first");
        element(clinit, Opcodes.ICONST_1, "second");
        element(clinit, Opcodes.ICONST_2, "third");
        clinit.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, SKINS_FIELD, STRING_ARRAY_DESC));
        clinit.add(new InsnNode(Opcodes.RETURN));

        loader(cn, "firstSkin", Opcodes.ICONST_0);
        loader(cn, "defaultSkin", Opcodes.ICONST_1);
        loader(cn, "lastSkin", Opcodes.ICONST_2);
        loader(cn, "outOfRange", Opcodes.ICONST_5);
        return cn;
    }

    /** A capturing inner class: two constructor parameters stored to two fields, read back by getters. */
    private static @NotNull ClassNode tintSource() {
        ClassNode cn = fixture(TINT_SOURCE);
        cn.fields.add(new FieldNode(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, IN_HAND, "I", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, IN_WORLD, "I", null, null));

        MethodNode constructor = new MethodNode(0, ClassKit.INIT, "(II)V", null, null);
        cn.methods.add(constructor);
        InsnList init = constructor.instructions;
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", ClassKit.INIT, "()V"));
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new VarInsnNode(Opcodes.ILOAD, 1));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, IN_HAND, "I"));
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new VarInsnNode(Opcodes.ILOAD, 2));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, IN_WORLD, "I"));
        init.add(new InsnNode(Opcodes.RETURN));

        getter(cn, "color", IN_HAND);
        getter(cn, "worldColor", IN_WORLD);

        InsnList both = instanceMethod(cn, "bothColors", INT_FACTORY);
        both.add(new VarInsnNode(Opcodes.ALOAD, 0));
        both.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, IN_HAND, "I"));
        both.add(new VarInsnNode(Opcodes.ALOAD, 0));
        both.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, IN_WORLD, "I"));
        both.add(new InsnNode(Opcodes.IADD));
        both.add(new InsnNode(Opcodes.IRETURN));
        return cn;
    }

    /** A forwarder holding no literal of its own - the value is a hop away. */
    private static @NotNull ClassNode facade() {
        ClassNode cn = fixture(FACADE);
        InsnList entry = method(cn, "entry", STRING_FACTORY);
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "stem", STRING_FACTORY));
        entry.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /** The forwarder's callee - the string it answers, and a class literal beside it. */
    private static @NotNull ClassNode target() {
        ClassNode cn = fixture(TARGET);
        InsnList stem = method(cn, "stem", STRING_FACTORY);
        stem.add(new LdcInsnNode("resolved/stem"));
        stem.add(new InsnNode(Opcodes.ARETURN));

        InsnList marker = method(cn, "marker", CLASS_FACTORY);
        marker.add(new LdcInsnNode(Type.getObjectType(TARGET)));
        marker.add(new InsnNode(Opcodes.ARETURN));
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

    private static void staticField(@NotNull ClassNode owner, @NotNull String name, @NotNull String desc) {
        owner.fields.add(new FieldNode(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, name, desc, null, null));
    }

    /** Adds a private static method to the fixture and returns its instruction list. */
    private static @NotNull InsnList method(@NotNull ClassNode owner, @NotNull String name, @NotNull String desc) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, desc, null, null);
        owner.methods.add(mn);
        return mn.instructions;
    }

    /** Adds a public instance method to the fixture and returns its instruction list. */
    private static @NotNull InsnList instanceMethod(@NotNull ClassNode owner, @NotNull String name, @NotNull String desc) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        owner.methods.add(mn);
        return mn.instructions;
    }

    /** {@code DUP; <ordinal>; LDC value; AASTORE} - one array element initialiser. */
    private static void element(@NotNull InsnList code, int ordinalOpcode, @NotNull String value) {
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new InsnNode(ordinalOpcode));
        code.add(new LdcInsnNode(value));
        code.add(new InsnNode(Opcodes.AASTORE));
    }

    /** {@code GETSTATIC table; <ordinal>; AALOAD; ARETURN} - one element loader. */
    private static void loader(@NotNull ClassNode owner, @NotNull String name, int ordinalOpcode) {
        InsnList code = method(owner, name, STRING_FACTORY);
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner.name, SKINS_FIELD, STRING_ARRAY_DESC));
        code.add(new InsnNode(ordinalOpcode));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new InsnNode(Opcodes.ARETURN));
    }

    /** {@code ALOAD 0; GETFIELD field; IRETURN} - one captured-field getter. */
    private static void getter(@NotNull ClassNode owner, @NotNull String name, @NotNull String field) {
        InsnList code = instanceMethod(owner, name, INT_FACTORY);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, field, "I"));
        code.add(new InsnNode(Opcodes.IRETURN));
    }

}
