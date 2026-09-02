package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a question asked of a reference the render state holds rests answering, against synthetic
 * states mirroring the 26.1 shape - a field assigned a static of its own type, and that static built
 * from literals in the owner's initialiser.
 *
 * <p>The accepted case is pinned per component rather than as a set, because the whole point is that
 * the question names a POSITION: an armour stand's legs splay opposite ways, and a resolver that lost
 * the index would answer both the same and splay them together. Getting the sign wrong there is worth
 * seven and a half of delta against the four and a half of answering nothing.
 *
 * <p>The refusals are what keep the answer honest. Every one of them is a shape the walk would
 * otherwise have to guess at, and a guessed resting value is a subject standing somewhere vanilla
 * never puts it - which is the same failure as answering zero, wearing a number that looks derived.
 */
@DisplayName("a question rests at the component its receiver was built holding")
class QuestionDefaultResolverTest {

    private static final @NotNull String STATE =
        VanillaSourceClasses.Types.ENTITY_RENDER_STATE_PACKAGE + "TestRenderState";
    private static final @NotNull String ROTATIONS = "fx/Rotations";
    private static final @NotNull String ROTATIONS_DESC = "Lfx/Rotations;";
    private static final @NotNull String HOLDER = "fx/Holder";
    private static final @NotNull String DEFAULT_POSE = "DEFAULT_LEG_POSE";

    /** The three questions the corpus's own reader asks of one receiver. */
    private static final @NotNull Set<String> ASKED = Set.of("legPose.x", "legPose.y", "legPose.z");

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @AfterEach
    void closeFixtureJar() {
        if (this.cache != null) this.cache.close();
    }

    @Test
    @DisplayName("each component answers by its own position, and a zero one is left to say nothing")
    void componentsAnswerByPosition() throws IOException {
        open(state(fromStatic()), rotations("x", "y", "z"), holder(-1f, 0f, 2.5f));
        Map<String, Float> resting =
            InputDefaultResolver.resolveQuestions(this.cache, STATE, ASKED);

        assertEquals(-1f, resting.get("legPose.x"), "the first component");
        assertEquals(2.5f, resting.get("legPose.z"), "the third, which a lost index would confuse for the first");
        assertTrue(resting.containsKey("legPose.y") == false,
            "and a component built at nothing says what its own absence says");
    }

    @Test
    @DisplayName("a receiver the constructor builds itself answers nothing, having no initialiser to read")
    void aValueBuiltInPlaceAnswersNothing() throws IOException {
        open(state(builtInPlace()), rotations("x", "y", "z"), holder(-1f, 0f, 2.5f));
        assertEquals(Map.of(), InputDefaultResolver.resolveQuestions(this.cache, STATE, ASKED));
    }

    @Test
    @DisplayName("a question naming no component of the value answers nothing")
    void anUnknownComponentAnswersNothing() throws IOException {
        open(state(fromStatic()), rotations("pitch", "yaw", "roll"), holder(-1f, 0f, 2.5f));
        assertEquals(Map.of(), InputDefaultResolver.resolveQuestions(this.cache, STATE, ASKED));
    }

    @Test
    @DisplayName("an argument that is not a literal answers nothing rather than the literal beside it")
    void aComputedArgumentAnswersNothing() throws IOException {
        open(state(fromStatic()), rotations("x", "y", "z"), computedHolder());
        assertEquals(Map.of(), InputDefaultResolver.resolveQuestions(this.cache, STATE, ASKED));
    }

    // ------------------------------------------------------------------------------------

    private void open(@NotNull ClassNode @NotNull ... fixtures) throws IOException {
        Path jar = this.tempDir.resolve("fixtures.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (ClassNode fixture : fixtures) {
                zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                fixture.accept(writer);
                zip.write(writer.toByteArray());
                zip.closeEntry();
            }
        }
        this.cache = ClassNodeCache.open(jar);
    }

    /** A render state whose constructor fills its one reference the way the given body says. */
    private static @NotNull ClassNode state(@NotNull InsnList body) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = STATE;
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "legPose", ROTATIONS_DESC, null, null));

        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions = body;
        node.methods.add(constructor);
        return node;
    }

    /** {@code this.legPose = Holder.DEFAULT_LEG_POSE}, which is the shape the corpus carries. */
    private static @NotNull InsnList fromStatic() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, HOLDER, DEFAULT_POSE, ROTATIONS_DESC));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, STATE, "legPose", ROTATIONS_DESC));
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    /** {@code this.legPose = new Rotations(-1, 0, 2.5)}, which names no static to read. */
    private static @NotNull InsnList builtInPlace() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        allocate(code, -1f, 0f, 2.5f);
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, STATE, "legPose", ROTATIONS_DESC));
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    /** A record of three floats under the given component names. */
    private static @NotNull ClassNode rotations(@NotNull String @NotNull ... components) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD;
        node.name = ROTATIONS;
        node.superName = "java/lang/Record";
        node.recordComponents = new java.util.ArrayList<>();
        for (String component : components) {
            node.recordComponents.add(new RecordComponentNode(component, "F", null));
            node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, component, "F", null, null));
        }
        return node;
    }

    /** The class holding the static, built from three literals. */
    private static @NotNull ClassNode holder(float x, float y, float z) {
        InsnList code = new InsnList();
        allocate(code, x, y, z);
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, HOLDER, DEFAULT_POSE, ROTATIONS_DESC));
        code.add(new InsnNode(Opcodes.RETURN));
        return owner(code);
    }

    /** The same holder with its middle argument computed rather than pushed. */
    private static @NotNull ClassNode computedHolder() {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, ROTATIONS));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode(-1f));
        code.add(new LdcInsnNode(0.5f));
        code.add(new LdcInsnNode(0.5f));
        code.add(new InsnNode(Opcodes.FADD));
        code.add(new LdcInsnNode(2.5f));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ROTATIONS, "<init>", "(FFF)V", false));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, HOLDER, DEFAULT_POSE, ROTATIONS_DESC));
        code.add(new InsnNode(Opcodes.RETURN));
        return owner(code);
    }

    private static @NotNull ClassNode owner(@NotNull InsnList clinit) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = HOLDER;
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            DEFAULT_POSE, ROTATIONS_DESC, null, null));

        MethodNode initialiser = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initialiser.instructions = clinit;
        node.methods.add(initialiser);
        return node;
    }

    /** {@code new Rotations(x, y, z)}, left on the stack. */
    private static void allocate(@NotNull InsnList code, float x, float y, float z) {
        code.add(new TypeInsnNode(Opcodes.NEW, ROTATIONS));
        code.add(new InsnNode(Opcodes.DUP));
        for (float component : List.of(x, y, z)) code.add(new LdcInsnNode(component));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ROTATIONS, "<init>", "(FFF)V", false));
    }

}
