package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.geometry.FixedRootPose;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the input-free {@code setupAnim} read against a fixture shaped like the 26.1 copper golem
 * statue: a renderer baking a {@code ModelLayers} field into a model through
 * {@code bakeLayer} and the model's {@code (ModelPart)} constructor, and the model's
 * {@code setupAnim(Unit)} writing constants to {@code Model.root}. Its siblings are the cases the rule
 * has to leave alone or refuse - a model whose {@code setupAnim} takes a render state, one whose
 * input-free body is empty, and input-free bodies doing something other than a constant write.
 */
@DisplayName("a block entity model's setupAnim taking no input is read as a fixed root pose")
class NoInputSetupAnimTest {

    private static final @NotNull String MODEL_PART = SourceClasses.Types.MODEL_PART;
    private static final @NotNull String MODEL_PART_DESC = "L" + MODEL_PART + ";";
    private static final @NotNull String UNIT_SETUP = "(L" + SourceClasses.Types.UNIT + ";)V";
    private static final @NotNull String LAYER = "STATUE";
    private static final @NotNull String RENDERER = "fixture/StatueRenderer";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the statue's body reads as the root's y at 0 and its zRot at pi")
    void theStatueBodyReadsAsAFixedRootPose() throws IOException {
        FixedRootPose pose = read("fixture/StatueModel", UNIT_SETUP, constantWrites(
            new Object[] { 0f, "y" }, new Object[] { (float) Math.PI, "zRot" }));

        assertEquals("fixture/StatueModel", pose.modelClass());
        assertEquals(Map.of(PoseChannel.Y, 0f, PoseChannel.Z_ROT, (float) Math.PI), pose.writes());
    }

    @Test
    @DisplayName("a setupAnim taking a render state is an animation and is not read")
    void aSetupAnimTakingAnInputIsNotRead() throws IOException {
        assertNull(read("fixture/AnimatedModel", "(Ljava/lang/Object;)V", constantWrites(new Object[] { 0f, "y" })));
    }

    @Test
    @DisplayName("an input-free body that writes nothing carries no pose")
    void anEmptyBodyCarriesNoPose() throws IOException {
        assertNull(read("fixture/SimpleModel", UNIT_SETUP, constantWrites()));
    }

    @Test
    @DisplayName("an input-free body doing more than writing constants to the root stops the flow")
    void anUnreadableBodyStopsTheFlow() throws IOException {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "fixture/BusyModel", "resetPose", "()V"));
        body.add(new InsnNode(Opcodes.RETURN));

        assertThrows(ToolingException.class, () -> read("fixture/BusyModel", UNIT_SETUP, body));
    }

    @Test
    @DisplayName("an input-free body writing the root's scale stops the flow, a fixed pose carrying no scale")
    void aScaleWriteStopsTheFlow() {
        assertThrows(ToolingException.class,
            () -> read("fixture/ScaledModel", UNIT_SETUP, constantWrites(new Object[] { 2f, "xScale" })));
    }

    /**
     * Builds the fixture jar - {@code Model} declaring {@code root}, the model over it carrying one
     * {@code setupAnim}, and a renderer baking {@link #LAYER} into the model - and reads it.
     *
     * @param model the model's internal name
     * @param setupDesc the descriptor its {@code setupAnim} is declared with
     * @param body the {@code setupAnim} body
     * @return what {@link NoInputSetupAnim#read} answers
     * @throws IOException if the fixture jar cannot be written
     */
    private FixedRootPose read(@NotNull String model, @NotNull String setupDesc, @NotNull InsnList body) throws IOException {
        ClassNode base = new ClassNode();
        base.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, SourceClasses.Types.MODEL, null, "java/lang/Object", null);
        base.fields.add(new FieldNode(Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL, SourceClasses.Fields.MODEL_ROOT, MODEL_PART_DESC, null, null));

        ClassNode subject = new ClassNode();
        subject.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, model, null, SourceClasses.Types.MODEL, null);
        MethodNode setupAnim = new MethodNode(Opcodes.ACC_PUBLIC, SourceClasses.Methods.SETUP_ANIM, setupDesc, null, null);
        rehome(body, model);
        setupAnim.instructions = body;
        subject.methods.add(setupAnim);

        ClassNode renderer = new ClassNode();
        renderer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, RENDERER, null, "java/lang/Object", null);
        MethodNode build = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(L" + SourceClasses.Types.ENTITY_MODEL_SET + ";)V", null, null);
        build.instructions.add(new TypeInsnNode(Opcodes.NEW, model));
        build.instructions.add(new InsnNode(Opcodes.DUP));
        build.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        build.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, SourceClasses.Types.MODEL_LAYERS, LAYER,
            "Lnet/minecraft/client/model/geom/ModelLayerLocation;"));
        build.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SourceClasses.Types.ENTITY_MODEL_SET,
            SourceClasses.Methods.BAKE_LAYER, "(Lnet/minecraft/client/model/geom/ModelLayerLocation;)" + MODEL_PART_DESC));
        build.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, model, "<init>", "(" + MODEL_PART_DESC + ")V"));
        build.instructions.add(new InsnNode(Opcodes.POP));
        build.instructions.add(new InsnNode(Opcodes.RETURN));
        renderer.methods.add(build);

        Path jar = this.tmp.resolve(model.replace('/', '_') + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (ClassNode node : List.of(base, subject, renderer)) {
                ClassWriter writer = new ClassWriter(0);
                node.accept(writer);
                zip.putNextEntry(new ZipEntry(node.name + ".class"));
                zip.write(writer.toByteArray());
                zip.closeEntry();
            }
        }
        try (ClassNodeCache cache = ClassNodeCache.open(jar)) {
            return NoInputSetupAnim.read(cache, RENDERER, LAYER);
        }
    }

    /**
     * Builds a body of constant writes to the root, each {@code this.root; <value>; putfield}, then
     * {@code return}. The field reads name a placeholder owner {@link #rehome} points at the model.
     *
     * @param writes each write's value and {@code ModelPart} member
     * @return the body
     */
    private static @NotNull InsnList constantWrites(Object @NotNull []... writes) {
        InsnList body = new InsnList();
        for (Object[] write : writes) {
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            body.add(new FieldInsnNode(Opcodes.GETFIELD, "?", SourceClasses.Fields.MODEL_ROOT, MODEL_PART_DESC));
            body.add(new LdcInsnNode(write[0]));
            body.add(new FieldInsnNode(Opcodes.PUTFIELD, MODEL_PART, (String) write[1], "F"));
        }
        body.add(new InsnNode(Opcodes.RETURN));
        return body;
    }

    /**
     * Points a body's placeholder field reads at the model, as javac names the subclass a read goes
     * through.
     *
     * @param body the body
     * @param model the model's internal name
     */
    private static void rehome(@NotNull InsnList body, @NotNull String model) {
        for (AbstractInsnNode insn : body)
            if (insn instanceof FieldInsnNode field && field.owner.equals("?"))
                field.owner = model;
    }

}
