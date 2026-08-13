package lib.minecraft.renderer.tooling.snapshot;

import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.Trace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one policy row that goes through the SPI end to end: the {@code constant} tint's in-hand
 * argument, declared as a coordinate plus a trace and recovered by replaying it over the tint
 * source's own bytecode. The fixtures mirror the 26.1 shape - a factory forwarding two captures into
 * an anonymous source whose no-context accessor reads one of them back.
 *
 * <p>The re-derivation is what the arm buys over a frozen index, so the pin that matters most is the
 * pair: one fixture whose accessor reads the first capture answers {@code 0} and one whose accessor
 * reads the second answers {@code 1}, off traces that are identical. The rest pin the hop the step
 * vocabulary does not cross - the factory forwarding its own parameters in order - which is checked
 * rather than assumed, and fails the run before a row is written.
 */
@DisplayName("the constant tint's in-hand argument is replayed off the source, not declared")
class TintInHandArgTest {

    private static final @NotNull String SOURCES = VanillaSourceClasses.Types.BLOCK_TINT_SOURCES;
    private static final @NotNull String SOURCE = SOURCES + "$1";
    private static final @NotNull String OTHER_SOURCE = SOURCES + "$7";
    private static final @NotNull String IN_HAND = "val$colorInHand";
    private static final @NotNull String IN_WORLD = "val$colorInWorld";
    private static final @NotNull String CTOR_DESC = "(II)V";
    private static final @NotNull String ACCESSOR_DESC = "()I";

    /** The one-colour overload vanilla declares FIRST - it builds its source through a lambda and constructs nothing. */
    private static final @NotNull String DECOY_FACTORY_DESC =
        VanillaSourceClasses.Descs.of(VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.BLOCK_TINT_SOURCE), "I");

    /** The slots vanilla's factory forwards - its own two parameters, in declaration order. */
    private static final int @NotNull [] IN_ORDER = {0, 1};

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------------------------
    // the row, and what replaying it recovers
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the row answers a trace rather than a value, so no index is frozen in the policy")
    void rowDeclaresATrace() throws IOException {
        try (ClassNodeCache cache = open("shape", IN_HAND, IN_ORDER, SOURCE)) {
            Navigation navigation = SnapshotShapePolicies.TINT_CONSTANT_IN_HAND.navigate(
                AsmContext.keyless(session(cache, diagnostics()), diagnostics()));
            Navigation.Dataflow coordinate = assertInstanceOf(Navigation.Dataflow.class, navigation);
            assertEquals(SOURCE, coordinate.owner());
            assertEquals(List.of(new Trace.Step.FollowGetField(null), new Trace.Step.MapParamSlot()),
                coordinate.trace().steps());
        }
    }

    @Test
    @DisplayName("the overload that constructs nothing is skipped - the factory is reached by descriptor, never by name")
    void decoyOverloadIsSkipped() throws IOException {
        try (ClassNodeCache cache = open("decoy", IN_HAND, IN_ORDER, SOURCE)) {
            ClassNode sources = cache.require(SOURCES, "the fixture");
            assertEquals(2, sources.methods.stream()
                    .filter(method -> method.name.equals(VanillaSourceClasses.Methods.CONSTANT)).count(),
                "the fixture carries both overloads, as vanilla does");
            assertEquals(DECOY_FACTORY_DESC,
                ClassKit.requireMethod(sources, VanillaSourceClasses.Methods.CONSTANT, "the fixture").desc,
                "a name-only lookup answers the decoy, which is the defect this pins");

            Diagnostics diagnostics = diagnostics();
            assertEquals(0, TintWalk.resolveInHandArg(session(cache, diagnostics), diagnostics));
        }
    }

    @Test
    @DisplayName("an accessor reading the first capture recovers the first argument")
    void firstCaptureRecoversFirstArgument() throws IOException {
        try (ClassNodeCache cache = open("first", IN_HAND, IN_ORDER, SOURCE)) {
            Diagnostics diagnostics = diagnostics();
            assertEquals(0, TintWalk.resolveInHandArg(session(cache, diagnostics), diagnostics));
            assertTrue(diagnostics.entries().isEmpty(), "the resolution is silent when it resolves");
        }
    }

    @Test
    @DisplayName("an accessor reading the second capture recovers the second argument - the index is derived, not declared")
    void secondCaptureRecoversSecondArgument() throws IOException {
        try (ClassNodeCache cache = open("second", IN_WORLD, IN_ORDER, SOURCE)) {
            assertEquals(1, TintWalk.resolveInHandArg(session(cache, diagnostics()), diagnostics()));
        }
    }

    // ------------------------------------------------------------------------------------
    // the hop the step vocabulary does not cross
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a factory forwarding its parameters out of order fails the run")
    void swappedForwardingFailsLoudly() throws IOException {
        try (ClassNodeCache cache = open("swapped", IN_HAND, new int[]{1, 0}, SOURCE)) {
            Diagnostics diagnostics = diagnostics();
            assertThrows(ToolingException.class, () -> TintWalk.resolveInHandArg(session(cache, diagnostics), diagnostics));
        }
    }

    @Test
    @DisplayName("a factory computing an argument rather than forwarding it fails the run")
    void computedArgumentFailsLoudly() throws IOException {
        try (ClassNodeCache cache = open("computed", IN_HAND, new int[]{0}, SOURCE)) {
            Diagnostics diagnostics = diagnostics();
            assertThrows(ToolingException.class, () -> TintWalk.resolveInHandArg(session(cache, diagnostics), diagnostics));
        }
    }

    @Test
    @DisplayName("a factory building another class - a drifted anonymous ordinal - fails the run")
    void driftedSourceClassFailsLoudly() throws IOException {
        try (ClassNodeCache cache = open("drifted", IN_HAND, IN_ORDER, OTHER_SOURCE)) {
            Diagnostics diagnostics = diagnostics();
            assertThrows(ToolingException.class, () -> TintWalk.resolveInHandArg(session(cache, diagnostics), diagnostics));
        }
    }

    @Test
    @DisplayName("an accessor reading no capture fails the replay rather than answering a default")
    void captureLessAccessorFailsLoudly() throws IOException {
        try (ClassNodeCache cache = open("captureless", null, IN_ORDER, SOURCE)) {
            Diagnostics diagnostics = diagnostics();
            assertThrows(ToolingException.class, () -> TintWalk.resolveInHandArg(session(cache, diagnostics), diagnostics));
        }
    }

    @Test
    @DisplayName("a jar holding neither class fails the run before a row is written")
    void absentSourceFailsLoudly() throws IOException {
        Path jar = this.tempDir.resolve("absent.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(zip, fixture("test/Unrelated"));
        }
        try (ClassNodeCache cache = ClassNodeCache.open(jar)) {
            Diagnostics diagnostics = diagnostics();
            assertThrows(ToolingException.class, () -> TintWalk.resolveInHandArg(session(cache, diagnostics), diagnostics));
        }
    }

    // ------------------------------------------------------------------------------------
    // fixture plumbing
    // ------------------------------------------------------------------------------------

    private static @NotNull Diagnostics diagnostics() {
        return Diagnostics.root("snapshot", Diagnostics.Output.NONE, null);
    }

    private static @NotNull ToolingSession session(@NotNull ClassNodeCache cache, @NotNull Diagnostics diagnostics) {
        return new ToolingSession(ClientOptions.defaults(), cache, diagnostics);
    }

    /**
     * Opens a cache over a fresh two-class fixture jar.
     *
     * @param name the jar's file stem, so each case owns its own file
     * @param read the capture the accessor reads back, or {@code null} for an accessor reading none
     * @param forwarded the slots the factory loads before the construction
     * @param constructed the class the factory constructs
     * @return the open cache
     * @throws IOException if the fixture jar cannot be written
     */
    private @NotNull ClassNodeCache open(
        @NotNull String name,
        @Nullable String read,
        int @NotNull [] forwarded,
        @NotNull String constructed
    ) throws IOException {
        Path jar = this.tempDir.resolve(name + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(zip, tintSources(forwarded, constructed));
            write(zip, tintSource(read));
        }
        return ClassNodeCache.open(jar);
    }

    /**
     * The factory class, carrying BOTH {@code constant} overloads in vanilla's declaration order:
     * the one-colour decoy first, constructing nothing, then the two-colour form that allocates,
     * forwards the named slots and constructs. A name-only lookup answers the decoy.
     */
    private static @NotNull ClassNode tintSources(int @NotNull [] forwarded, @NotNull String constructed) {
        ClassNode cn = fixture(SOURCES);

        MethodNode decoy = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            VanillaSourceClasses.Methods.CONSTANT, DECOY_FACTORY_DESC, null, null);
        cn.methods.add(decoy);
        decoy.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        decoy.instructions.add(new InsnNode(Opcodes.ARETURN));

        MethodNode factory = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            VanillaSourceClasses.Methods.CONSTANT, VanillaSourceClasses.Descs.CONSTANT_TINT_DESC, null, null);
        cn.methods.add(factory);
        InsnList code = factory.instructions;
        code.add(new TypeInsnNode(Opcodes.NEW, constructed));
        code.add(new InsnNode(Opcodes.DUP));
        for (int slot : forwarded) code.add(new VarInsnNode(Opcodes.ILOAD, slot));
        // a factory forwarding fewer slots than it declares computes the rest - the shape the check refuses
        for (int missing = 2 - forwarded.length; missing > 0; missing--) code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, constructed, ClassKit.INIT, CTOR_DESC));
        code.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /** The anonymous source: two captured colours, and the no-context accessor reading one back. */
    private static @NotNull ClassNode tintSource(@Nullable String read) {
        ClassNode cn = fixture(SOURCE);
        cn.fields.add(new FieldNode(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, IN_HAND, "I", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, IN_WORLD, "I", null, null));

        MethodNode constructor = new MethodNode(0, ClassKit.INIT, CTOR_DESC, null, null);
        cn.methods.add(constructor);
        InsnList init = constructor.instructions;
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", ClassKit.INIT, "()V"));
        capture(init, cn.name, 1, IN_HAND);
        capture(init, cn.name, 2, IN_WORLD);
        init.add(new InsnNode(Opcodes.RETURN));

        MethodNode accessor = new MethodNode(Opcodes.ACC_PUBLIC, "color", ACCESSOR_DESC, null, null);
        cn.methods.add(accessor);
        InsnList body = accessor.instructions;
        if (read == null) {
            body.add(new InsnNode(Opcodes.ICONST_0));
        } else {
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            body.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, read, "I"));
        }
        body.add(new InsnNode(Opcodes.IRETURN));
        return cn;
    }

    /** {@code ALOAD 0; ILOAD slot; PUTFIELD field} - one captured constructor parameter. */
    private static void capture(@NotNull InsnList code, @NotNull String owner, int slot, @NotNull String field) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, slot));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, "I"));
    }

    private static @NotNull ClassNode fixture(@NotNull String internalName) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V21;
        cn.access = Opcodes.ACC_PUBLIC;
        cn.name = internalName;
        cn.superName = "java/lang/Object";
        return cn;
    }

    private static void write(@NotNull ZipOutputStream zip, @NotNull ClassNode fixture) throws IOException {
        zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        fixture.accept(writer);
        zip.write(writer.toByteArray());
        zip.closeEntry();
    }

}
