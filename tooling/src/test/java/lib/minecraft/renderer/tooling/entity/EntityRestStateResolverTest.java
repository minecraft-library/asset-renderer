package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an enum render-state field rests at where the renderer fills it from a static helper handed
 * the entity, against synthetic renderers mirroring the 26.1 bytecode of the parrot's.
 *
 * <p>{@code ParrotRenderer.extractRenderState} fills {@code pose} with {@code ParrotModel.getPose(parrot)},
 * a static helper whose last unguarded return is {@code STANDING} - what a parrot that is not
 * partying, sitting or flying answers. Read on the helper's own owner, since a static does not
 * dispatch, the parrot rests standing; a helper taking anything beside the entity is a question of
 * more than the subject and answers nothing.
 */
@DisplayName("an enum filled by a static helper on the entity rests at the helper's fall-through")
class EntityRestStateResolverTest {

    private static final @NotNull String ENTITY = "fx/Bird";
    private static final @NotNull String STATE = "fx/BirdState";
    private static final @NotNull String RENDERER = "fx/BirdRenderer";
    private static final @NotNull String HELPER = "fx/BirdModel";
    private static final @NotNull String POSE = "fx/BirdModel$Pose";
    private static final @NotNull String HAND = "fx/Hand";
    private static final @NotNull String POSE_REF = "L" + POSE + ";";
    private static final @NotNull String EXTRACT_DESC = "(L" + ENTITY + ";L" + STATE + ";F)V";

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @AfterEach
    void closeFixtureJar() {
        if (this.cache != null) this.cache.close();
    }

    @Test
    @DisplayName("a static helper handed the entity answers its last unguarded constant, read on the helper's owner")
    void aStaticHelperRestsAtItsFallThrough() throws IOException {
        open(entity(), helper(), renderer(oneArgumentHelper()));

        JsonTree rest = resolver().resolve();
        assertEquals(Optional.of("STANDING"), Optional.ofNullable(rest).flatMap(node -> node.findString("pose")),
            "the helper's own fall-through answers, not the entity's same-named method");
    }

    @Test
    @DisplayName("a static helper handed anything beside the entity answers nothing")
    void aTwoArgumentHelperAnswersNothing() throws IOException {
        open(entity(), helper(), renderer(twoArgumentHelper()));

        JsonTree rest = resolver().resolve();
        assertTrue(rest == null || rest.findString("pose").isEmpty(),
            "a helper asked of the entity and a hand is not a question of the subject alone");
    }

    // ------------------------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------------------------

    /**
     * The subject's entity, declaring a static {@code getPose} of the helper's shape that falls
     * through to another constant - the decoy a lookup on the subject's class would read.
     */
    private static @NotNull ClassNode entity() {
        ClassNode node = fixtureClass(ENTITY, "java/lang/Object");
        MethodNode decoy = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getPose",
            "(L" + ENTITY + ";)" + POSE_REF, null, null);
        decoy.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, POSE, "PARTY", POSE_REF));
        decoy.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(decoy);
        return node;
    }

    /**
     * The model holding both helpers: {@code getPose(bird)} answers {@code FLYING} while the bird
     * flies and falls through to {@code STANDING}, and {@code getArmPose(hand, bird)} falls through
     * to {@code SITTING}.
     */
    private static @NotNull ClassNode helper() {
        ClassNode node = fixtureClass(HELPER, "java/lang/Object");
        MethodNode pose = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getPose",
            "(L" + ENTITY + ";)" + POSE_REF, null, null);
        LabelNode grounded = new LabelNode();
        InsnList code = pose.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "isFlying", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, grounded));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, POSE, "FLYING", POSE_REF));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(grounded);
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, POSE, "STANDING", POSE_REF));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(pose);

        MethodNode arm = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getArmPose",
            "(L" + HAND + ";L" + ENTITY + ";)" + POSE_REF, null, null);
        arm.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, POSE, "SITTING", POSE_REF));
        arm.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(arm);
        return node;
    }

    /** {@code state.pose = BirdModel.getPose(bird)}, the parrot's shape. */
    private static @NotNull InsnList oneArgumentHelper() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "getPose", "(L" + ENTITY + ";)" + POSE_REF, false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, STATE, "pose", POSE_REF));
        return code;
    }

    /**
     * {@code state.pose = BirdModel.getArmPose(null, bird)}, a two-argument shape whose last push is
     * still the entity, so only the helper's arity tells it from the parrot's.
     */
    private static @NotNull InsnList twoArgumentHelper() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "getArmPose",
            "(L" + HAND + ";L" + ENTITY + ";)" + POSE_REF, false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, STATE, "pose", POSE_REF));
        return code;
    }

    /** A renderer whose {@code extractRenderState} is one body. */
    private static @NotNull ClassNode renderer(@NotNull InsnList body) {
        ClassNode node = fixtureClass(RENDERER, "java/lang/Object");
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, SourceClasses.Methods.EXTRACT_RENDER_STATE,
            EXTRACT_DESC, null, null);
        method.instructions = body;
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        return node;
    }

    private @NotNull EntityRestStateResolver resolver() {
        Diagnostics diagnostics = Diagnostics.root("entityModels", Diagnostics.Output.NONE, null);
        ToolingRun run = new ToolingRun(ClientOptions.defaults(), this.cache, diagnostics);
        EntityIndexes indexes = new EntityIndexes(null, null, null, null, null, null, null, null);
        EntitySubject subject = new EntitySubject("minecraft:bird", ENTITY, RENDERER, List.of(), List.of(), List.of());
        return new EntityRestStateResolver(new EntityContext(run, indexes, subject, diagnostics));
    }

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

    private static @NotNull ClassNode fixtureClass(@NotNull String name, @NotNull String superName) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = superName;
        return node;
    }

}
