package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.entity.EntityWanderResolver.Operand;
import lib.minecraft.renderer.tooling.entity.EntityWanderResolver.Operands;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The speed a subject's idle stroll hands its navigation, against synthetic goal walks and brain
 * members in the shapes the 26.1 jar writes them.
 *
 * <p>The goal walk starts at the subject's nearest {@code registerGoals}, re-enters a super call and
 * a call on itself, and reads a stroll goal's speed off the constructor's first {@code double} - or,
 * for a stroll taking none, off the call it makes to its own super. Strolls that disagree answer
 * nothing, and say so. A subject the walk finds no stroll for is answered by its policy row: a member
 * holding exactly one stroll entry point at a literal speed, or a declared value. A member holding no
 * stroll, or two, is a row the jar moved under, and refuses.
 *
 * <p>The operand machine those reads ride is held to the JVM's own stack shuffles, each form of
 * {@code dup2_x2} handing a call the values the specification leaves.
 */
@DisplayName("the wander: a goal stroll's speed, and a brain's stroll row")
class EntityWanderResolverTest {

    private static final @NotNull String GOAL = "net/minecraft/world/entity/ai/goal/Goal";
    private static final @NotNull String STROLL = "net/minecraft/world/entity/ai/goal/RandomStrollGoal";
    private static final @NotNull String RANDOM_STROLL = "net/minecraft/world/entity/ai/behavior/RandomStroll";
    private static final @NotNull String ALLAY_AI = "net/minecraft/world/entity/animal/allay/AllayAi";
    private static final @NotNull String BEHAVIOR = "Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;";

    private static final @NotNull String MOB = "fx/Mob";
    private static final @NotNull String PARENT = "fx/Parent";
    private static final @NotNull String WALKER = "fx/Walker";
    private static final @NotNull String AVOIDING = "fx/AvoidingStroll";
    private static final @NotNull String MERGING = "fx/MergingStroll";
    private static final @NotNull String CHASE = "fx/Chase";
    private static final @NotNull String STILL = "fx/Still";

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @AfterEach
    void closeFixtureJar() {
        if (this.cache != null) this.cache.close();
    }

    @Test
    @DisplayName("a stroll's speed is the literal handed its constructor's first double")
    void aStrollReadsItsFirstDouble() throws IOException {
        open(goals(registerGoals(stroll(AVOIDING, 0.8), goal(CHASE, 1.4))));
        assertEquals(OptionalDouble.of(0.8), strollSpeed(WALKER), "the stroll, and not the chase beside it");
    }

    @Test
    @DisplayName("a stroll taking no double is read at the call it makes to its own super")
    void aStrollTakingNoDoubleIsReadAtItsSuperCall() throws IOException {
        open(goals(registerGoals(merging())));
        assertEquals(OptionalDouble.of(1.0), strollSpeed(WALKER), "the speed the merge goal hands its super");
    }

    @Test
    @DisplayName("the walk follows a super call and a call the subject makes on itself")
    void theWalkFollowsSuperAndSelfCalls() throws IOException {
        // The subject's registerGoals calls up to its parent's, and calls addBehaviourGoals on itself:
        // the parent declares one at 0.6 and the subject overrides it, the override being the one run.
        InsnList own = new InsnList();
        own.add(new VarInsnNode(Opcodes.ALOAD, 0));
        own.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, PARENT, "registerGoals", "()V", false));
        own.add(new InsnNode(Opcodes.RETURN));
        ClassNode walker = fixtureClass(WALKER, PARENT);
        walker.methods.add(method(0, "registerGoals", "()V", own));
        walker.methods.add(method(0, "addBehaviourGoals", "()V", body(stroll(AVOIDING, 0.9))));
        InsnList parentGoals = new InsnList();
        parentGoals.add(new VarInsnNode(Opcodes.ALOAD, 0));
        parentGoals.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, PARENT, "addBehaviourGoals", "()V", false));
        parentGoals.add(new InsnNode(Opcodes.RETURN));
        ClassNode parent = fixtureClass(PARENT, MOB);
        parent.methods.add(method(0, "registerGoals", "()V", parentGoals));
        parent.methods.add(method(0, "addBehaviourGoals", "()V", body(stroll(AVOIDING, 0.6))));
        open(withGoalClasses(List.of(walker, parent)));
        assertEquals(OptionalDouble.of(0.9), strollSpeed(WALKER), "the override the subject runs");
    }

    @Test
    @DisplayName("two strolls at two speeds answer nothing, and say so")
    void disagreeingStrollsAnswerNothing() throws IOException {
        open(goals(registerGoals(stroll(AVOIDING, 0.8), stroll(AVOIDING, 1.0))));
        Diagnostics diagnostics = diagnostics();
        assertEquals(OptionalDouble.empty(), EntityWanderResolver.strollSpeed(run(diagnostics), subject("minecraft:walker", WALKER), diagnostics));
        assertTrue(diagnostics.entries().stream().anyMatch(entry -> entry.message().startsWith("minecraft:walker strolls by 2 goals")),
            "the disagreement is named: " + diagnostics.entries());
    }

    @Test
    @DisplayName("a brain the goal walk finds no stroll for is read at its row's member")
    void aBrainStrollIsReadAtItsRowsMember() throws IOException {
        open(brain(1));
        assertEquals(OptionalDouble.of(1.0), strollSpeed("minecraft:allay", STILL), "the allay's idle flight");
    }

    @Test
    @DisplayName("a row's member holding no stroll, or two, refuses")
    void aRowMemberWithoutOneStrollRefuses() throws IOException {
        open(brain(0));
        assertThrows(ToolingException.class, () -> strollSpeed("minecraft:allay", STILL), "a member holding no stroll");
        this.cache.close();
        open(brain(2));
        assertThrows(ToolingException.class, () -> strollSpeed("minecraft:allay", STILL), "a member holding two");
    }

    @Test
    @DisplayName("a declared row answers its value")
    void aValueRowAnswersItsFloat() throws IOException {
        open(brain(1));
        assertEquals(OptionalDouble.of(0.5f), strollSpeed("minecraft:villager", STILL), "the villager's declared stroll");
    }

    @Test
    @DisplayName("dup2_x2 copies the top two slots under the two beneath them, in each of its four forms")
    void dup2X2CopiesThePairUnderThePairBeneath() {
        assertAll(
            () -> assertEquals(literals(3, 4, 1, 2, 3, 4), handedAfterDup2X2("(IIIIII)V", 1, 2, 3, 4), "four narrow values"),
            () -> assertEquals(literals(3.0, 1, 2, 3.0), handedAfterDup2X2("(DIID)V", 1, 2, 3.0), "a wide value over two narrow ones"),
            () -> assertEquals(literals(2, 3, 1.0, 2, 3), handedAfterDup2X2("(IIDII)V", 1.0, 2, 3), "two narrow values over a wide one"),
            () -> assertEquals(literals(2.0, 1.0, 2.0), handedAfterDup2X2("(DDD)V", 1.0, 2.0), "a wide value over a wide one"));
    }

    // ------------------------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------------------------

    /** What a static call of the given descriptor is handed after the literals are pushed and {@code dup2_x2} runs. */
    private static @NotNull List<Operand> handedAfterDup2X2(@NotNull String desc, @NotNull Number @NotNull ... pushed) {
        InsnList code = new InsnList();
        for (Number value : pushed) code.add(new LdcInsnNode(value));
        code.add(new InsnNode(Opcodes.DUP2_X2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "fx/Sink", "take", desc, false));
        code.add(new InsnNode(Opcodes.RETURN));
        List<Operand> handed = new ArrayList<>();
        Operands.run(method(Opcodes.ACC_STATIC, "shuffle", "()V", code), new Operands.Listener() {
            @Override
            public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                handed.addAll(arguments);
            }
        });
        return handed;
    }

    private static @NotNull List<Operand> literals(@NotNull Number @NotNull ... values) {
        List<Operand> out = new ArrayList<>();
        for (Number value : values) out.add(new Operand.Literal(value));
        return out;
    }

    private @NotNull OptionalDouble strollSpeed(@NotNull String entityClass) {
        return strollSpeed("minecraft:walker", entityClass);
    }

    private @NotNull OptionalDouble strollSpeed(@NotNull String id, @NotNull String entityClass) {
        Diagnostics diagnostics = diagnostics();
        return EntityWanderResolver.strollSpeed(run(diagnostics), subject(id, entityClass), diagnostics);
    }

    private @NotNull ToolingRun run(@NotNull Diagnostics diagnostics) {
        return new ToolingRun(ClientOptions.defaults(), this.cache, diagnostics);
    }

    private static @NotNull Diagnostics diagnostics() {
        return Diagnostics.root("entityModels", Diagnostics.Output.NONE, null);
    }

    private static @NotNull EntitySubject subject(@NotNull String id, @NotNull String entityClass) {
        return new EntitySubject(id, entityClass, "fx/Renderer", List.of(), List.of(), List.of());
    }

    /** The goal family and a subject whose own registerGoals is the given body. */
    private static @NotNull List<ClassNode> goals(@NotNull InsnList registerGoals) {
        ClassNode walker = fixtureClass(WALKER, MOB);
        walker.methods.add(method(0, "registerGoals", "()V", registerGoals));
        return withGoalClasses(List.of(walker));
    }

    private static @NotNull List<ClassNode> withGoalClasses(@NotNull List<ClassNode> subjects) {
        List<ClassNode> out = new ArrayList<>(subjects);
        out.add(fixtureClass(MOB, "java/lang/Object"));
        out.add(fixtureClass(GOAL, "java/lang/Object"));
        out.add(fixtureClass(STROLL, GOAL));
        out.add(fixtureClass(AVOIDING, STROLL));
        out.add(fixtureClass(CHASE, GOAL));
        ClassNode merging = fixtureClass(MERGING, STROLL);
        // super(mob, 1.0, 10)
        InsnList init = new InsnList();
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new VarInsnNode(Opcodes.ALOAD, 1));
        init.add(new InsnNode(Opcodes.DCONST_1));
        init.add(new IntInsnNode(Opcodes.BIPUSH, 10));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, STROLL, "<init>", "(L" + MOB + ";DI)V", false));
        init.add(new InsnNode(Opcodes.RETURN));
        merging.methods.add(method(0, "<init>", "(L" + MOB + ";)V", init));
        out.add(merging);
        return out;
    }

    /** A subject with no goal walk at all, and the allay's brain member holding the given number of strolls. */
    private static @NotNull List<ClassNode> brain(int strolls) {
        List<ClassNode> out = withGoalClasses(List.of(fixtureClass(STILL, MOB)));
        ClassNode ai = fixtureClass(ALLAY_AI, "java/lang/Object");
        InsnList idle = new InsnList();
        for (int index = 0; index < strolls; index++) {
            idle.add(new LdcInsnNode(1.0f));
            idle.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RANDOM_STROLL, "fly", "(F)" + BEHAVIOR, false));
            idle.add(new InsnNode(Opcodes.POP));
        }
        idle.add(new InsnNode(Opcodes.ACONST_NULL));
        idle.add(new InsnNode(Opcodes.ARETURN));
        ai.methods.add(method(Opcodes.ACC_STATIC, "initIdleActivity", "()Ljava/lang/Object;", idle));
        out.add(ai);
        return out;
    }

    /** A registerGoals body adding each goal in turn. */
    private static @NotNull InsnList registerGoals(@NotNull InsnList @NotNull ... goals) {
        return body(goals);
    }

    private static @NotNull InsnList body(@NotNull InsnList @NotNull ... goals) {
        InsnList code = new InsnList();
        for (InsnList goal : goals) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, MOB, "goalSelector", "Lfx/GoalSelector;"));
            code.add(new InsnNode(Opcodes.ICONST_5));
            code.add(goal);
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "fx/GoalSelector", "addGoal", "(IL" + GOAL + ";)V", false));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    /** {@code new <type>(this, <speed>, 1.0E-5f)}, a stroll taking a double first. */
    private static @NotNull InsnList stroll(@NotNull String type, double speed) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, type));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new LdcInsnNode(speed));
        code.add(new LdcInsnNode(1.0E-5f));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type, "<init>", "(L" + MOB + ";DF)V", false));
        return code;
    }

    /** {@code new <type>(this, <speed>)}, a goal that is no stroll. */
    private static @NotNull InsnList goal(@NotNull String type, double speed) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, type));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new LdcInsnNode(speed));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type, "<init>", "(L" + MOB + ";D)V", false));
        return code;
    }

    /** {@code new MergingStroll(this)}, a stroll naming its speed only to its super. */
    private static @NotNull InsnList merging() {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, MERGING));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MERGING, "<init>", "(L" + MOB + ";)V", false));
        return code;
    }

    private static @NotNull MethodNode method(int access, @NotNull String name, @NotNull String desc, @NotNull InsnList code) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | access, name, desc, null, null);
        method.instructions = code;
        return method;
    }

    private static @NotNull ClassNode fixtureClass(@NotNull String name, @NotNull String superName) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = superName;
        return node;
    }

    private void open(@NotNull List<ClassNode> fixtures) throws IOException {
        Path jar = this.tempDir.resolve("fixtures-" + System.nanoTime() + ".jar");
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

}
