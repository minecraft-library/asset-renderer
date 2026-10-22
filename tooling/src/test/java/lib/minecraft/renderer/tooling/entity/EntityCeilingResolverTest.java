package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
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
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The fastest speed a subject's own behaviours hand its navigation, against synthetic goal walks and
 * brain owners in the shapes the 26.1 jar writes them.
 *
 * <p>The goal census starts at the subject's constructors, so it reaches what {@code registerGoals}
 * builds and what a constructor installs, and leaves a parent's {@code registerGoals} the subject's own
 * never calls unread. A constructor {@code double} counts as a speed only where it reaches a field a
 * navigation call reads as its speed - through a super call, and down both arms of a select, the
 * literal one arm hands a super counting too. The brain census reads its row's owner for the panic,
 * the chase, the retreat, the ram and the stroll entry points, following a panic subclass to the super
 * call it makes, and never a charge's velocity. A goal a policy row excludes at an age drops at that
 * age alone.
 */
@DisplayName("the ceiling: the goal census, the flow rule and the brain census")
class EntityCeilingResolverTest {

    private static final @NotNull String GOAL = "net/minecraft/world/entity/ai/goal/Goal";
    private static final @NotNull String NAVIGATION = "net/minecraft/world/entity/ai/navigation/PathNavigation";
    private static final @NotNull String RUN_AROUND = "net/minecraft/world/entity/ai/goal/RunAroundLikeCrazyGoal";
    private static final @NotNull String ANIMAL_PANIC = "net/minecraft/world/entity/ai/behavior/AnimalPanic";
    private static final @NotNull String CHARGE = "net/minecraft/world/entity/ai/behavior/ChargeAttack";
    private static final @NotNull String RANDOM_STROLL = "net/minecraft/world/entity/ai/behavior/RandomStroll";
    private static final @NotNull String CAMEL_AI = "net/minecraft/world/entity/animal/camel/CamelAi";
    private static final @NotNull String CAMEL_PANIC = CAMEL_AI + "$CamelPanic";

    private static final @NotNull String MOB = "fx/Mob";
    private static final @NotNull String BASE = "fx/Base";
    private static final @NotNull String HUNTER = "fx/Hunter";
    private static final @NotNull String CHASE = "fx/Chase";
    private static final @NotNull String SUB_CHASE = "fx/SubChase";
    private static final @NotNull String SELECTING = "fx/Selecting";
    private static final @NotNull String WADING = "fx/Wading";
    private static final @NotNull String MOB_REF = "L" + MOB + ";";

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @AfterEach
    void closeFixtureJar() {
        if (this.cache != null) this.cache.close();
    }

    @Test
    @DisplayName("a double is a speed where it reaches, through a super call, a field a navigation call reads")
    void theFlowRuleFollowsASuperCall() throws IOException {
        // new SubChase(this, 9.0, 1.7): the first double is a range the goal stores, the second the
        // speed it hands its super, which stores it where its tick sets the navigation's speed.
        open(subject(registerGoals(construct(SUB_CHASE, "(" + MOB_REF + "DD)V", 9.0, 1.7))));
        assertEquals(Optional.of(new EntityCeilingResolver.Fastest(1.7, "goal SubChase")), fastest(HUNTER, "adult"));
    }

    @Test
    @DisplayName("both arms of a select count - at the navigation call, and in the literal one arm hands a super")
    void bothArmsOfASelectCount() throws IOException {
        open(subject(registerGoals(construct(SELECTING, "(" + MOB_REF + "DD)V", 1.6, 1.8))));
        assertEquals(1.8, fastest(HUNTER, "adult").orElseThrow().modifier(), 0d, "the field the taken arm reads");
        this.cache.close();
        open(subject(registerGoals(construct(SELECTING, "(" + MOB_REF + "DD)V", 1.9, 1.6))));
        assertEquals(1.9, fastest(HUNTER, "adult").orElseThrow().modifier(), 0d, "the field the other arm reads");
        this.cache.close();
        open(subject(registerGoals(construct(WADING, "(" + MOB_REF + "D)V", 1.0))));
        assertEquals(2.0, fastest(HUNTER, "adult").orElseThrow().modifier(), 0d, "the literal one arm hands the super");
    }

    @Test
    @DisplayName("a goal a constructor installs counts, and a parent registerGoals the subject's own never calls does not")
    void theCensusReadsWhatTheConstructorChainReaches() throws IOException {
        open(subject(registerGoals(construct(CHASE, "(" + MOB_REF + "D)V", 1.0))));
        // The hunter's constructor installs a chase at 1.5, its own registerGoals adds one at 1.0, and
        // its parent's registerGoals - which the hunter's override never calls - one at 3.0.
        assertEquals(Optional.of(new EntityCeilingResolver.Fastest(1.5, "goal Chase")), fastest(HUNTER, "adult"));
    }

    @Test
    @DisplayName("a brain's panic subclass is followed to its super call, and a charge's velocity never counts")
    void theBrainCensusFollowsAPanicSubclassAndSkipsACharge() throws IOException {
        open(subject(registerGoals()));
        assertEquals(Optional.of(new EntityCeilingResolver.Fastest(4.0f, "brain CamelAi$CamelPanic")),
            fastest("minecraft:camel", MOB, "adult"));
    }

    @Test
    @DisplayName("a goal the exclusions name at an age drops at that age alone")
    void anExcludedGoalDropsAtItsAgeAlone() throws IOException {
        // The run-around goes faster than anything else the hunter does; a baby never runs around, so
        // its fastest is the chase its constructor installs.
        open(subject(registerGoals(construct(RUN_AROUND, "(" + MOB_REF + "D)V", 1.6), construct(CHASE, "(" + MOB_REF + "D)V", 1.0))));
        assertEquals(Optional.of(new EntityCeilingResolver.Fastest(1.6, "goal RunAroundLikeCrazyGoal")), fastest(HUNTER, "adult"));
        assertEquals(Optional.of(new EntityCeilingResolver.Fastest(1.5, "goal Chase")), fastest(HUNTER, "baby"));
    }

    // ------------------------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------------------------

    private @NotNull Optional<EntityCeilingResolver.Fastest> fastest(@NotNull String entityClass, @NotNull String age) {
        return fastest("minecraft:hunter", entityClass, age);
    }

    private @NotNull Optional<EntityCeilingResolver.Fastest> fastest(
        @NotNull String id, @NotNull String entityClass, @NotNull String age) {

        Diagnostics diagnostics = Diagnostics.root("entityModels", Diagnostics.Output.NONE, null);
        ToolingRun run = new ToolingRun(ClientOptions.defaults(), this.cache, diagnostics);
        EntitySubject subject = new EntitySubject(id, entityClass, "fx/Renderer", List.of(), List.of(), List.of());
        return EntityCeilingResolver.fastest(run, subject, age, diagnostics);
    }

    /**
     * The hunter and everything its census reaches: the mob whose constructor calls registerGoals on
     * itself, a parent registering a chase at 3.0 that the hunter's override never calls, the hunter's
     * constructor installing a chase at 1.5, and the goal family and the camel's brain owner.
     */
    private static @NotNull List<ClassNode> subject(@NotNull InsnList registerGoals) {
        List<ClassNode> out = new ArrayList<>();

        ClassNode mob = fixtureClass(MOB, "java/lang/Object");
        InsnList mobInit = new InsnList();
        mobInit.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mobInit.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        mobInit.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mobInit.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOB, "registerGoals", "()V", false));
        mobInit.add(new InsnNode(Opcodes.RETURN));
        mob.methods.add(method(0, "<init>", "()V", mobInit));
        mob.methods.add(method(0, "registerGoals", "()V", returning()));
        out.add(mob);

        ClassNode base = fixtureClass(BASE, MOB);
        base.methods.add(method(0, "<init>", "()V", superCall(MOB)));
        base.methods.add(method(0, "registerGoals", "()V", registerGoals(construct(CHASE, "(" + MOB_REF + "D)V", 3.0))));
        out.add(base);

        ClassNode hunter = fixtureClass(HUNTER, BASE);
        InsnList hunterInit = superCall(BASE);
        hunterInit.remove(hunterInit.getLast());
        hunterInit.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hunterInit.add(construct(CHASE, "(" + MOB_REF + "D)V", 1.5));
        hunterInit.add(new FieldInsnNode(Opcodes.PUTFIELD, HUNTER, "melee", "L" + CHASE + ";"));
        hunterInit.add(new InsnNode(Opcodes.RETURN));
        hunter.methods.add(method(0, "<init>", "()V", hunterInit));
        hunter.methods.add(method(0, "registerGoals", "()V", registerGoals));
        out.add(hunter);

        out.addAll(goals());
        out.addAll(camelBrain());
        return out;
    }

    /** The goal family: a chase whose tick sets the navigation's speed off the field its double is stored in. */
    private static @NotNull List<ClassNode> goals() {
        List<ClassNode> out = new ArrayList<>();
        out.add(fixtureClass(GOAL, "java/lang/Object"));
        out.add(speedGoal(CHASE));
        out.add(speedGoal(RUN_AROUND));

        // SubChase(mob, range, speed): super(mob, speed); this.range = range
        ClassNode sub = fixtureClass(SUB_CHASE, CHASE);
        InsnList subInit = new InsnList();
        subInit.add(new VarInsnNode(Opcodes.ALOAD, 0));
        subInit.add(new VarInsnNode(Opcodes.ALOAD, 1));
        subInit.add(new VarInsnNode(Opcodes.DLOAD, 4));
        subInit.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CHASE, "<init>", "(" + MOB_REF + "D)V", false));
        subInit.add(new VarInsnNode(Opcodes.ALOAD, 0));
        subInit.add(new VarInsnNode(Opcodes.DLOAD, 2));
        subInit.add(new FieldInsnNode(Opcodes.PUTFIELD, SUB_CHASE, "range", "D"));
        subInit.add(new InsnNode(Opcodes.RETURN));
        sub.methods.add(method(0, "<init>", "(" + MOB_REF + "DD)V", subInit));
        out.add(sub);

        // Selecting(mob, slow, fast): tick moves at leader ? fast : slow
        ClassNode selecting = fixtureClass(SELECTING, GOAL);
        InsnList selectingInit = superCall(GOAL);
        selectingInit.remove(selectingInit.getLast());
        selectingInit.add(store(SELECTING, "slow", 2));
        selectingInit.add(store(SELECTING, "fast", 4));
        selectingInit.add(new InsnNode(Opcodes.RETURN));
        selecting.methods.add(method(0, "<init>", "(" + MOB_REF + "DD)V", selectingInit));
        LabelNode slow = new LabelNode();
        LabelNode move = new LabelNode();
        InsnList tick = new InsnList();
        tick.add(new InsnNode(Opcodes.ACONST_NULL));
        tick.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tick.add(new FieldInsnNode(Opcodes.GETFIELD, SELECTING, "leader", "Z"));
        tick.add(new JumpInsnNode(Opcodes.IFEQ, slow));
        tick.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tick.add(new FieldInsnNode(Opcodes.GETFIELD, SELECTING, "fast", "D"));
        tick.add(new JumpInsnNode(Opcodes.GOTO, move));
        tick.add(slow);
        tick.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tick.add(new FieldInsnNode(Opcodes.GETFIELD, SELECTING, "slow", "D"));
        tick.add(move);
        tick.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NAVIGATION, "setSpeedModifier", "(D)V", false));
        tick.add(new InsnNode(Opcodes.RETURN));
        selecting.methods.add(method(0, "tick", "()V", tick));
        out.add(selecting);

        // Wading(mob, speed): super(mob, mob.isBaby() ? 2.0 : speed)
        ClassNode wading = fixtureClass(WADING, CHASE);
        LabelNode adult = new LabelNode();
        LabelNode call = new LabelNode();
        InsnList wadingInit = new InsnList();
        wadingInit.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wadingInit.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wadingInit.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wadingInit.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOB, "isBaby", "()Z", false));
        wadingInit.add(new JumpInsnNode(Opcodes.IFEQ, adult));
        wadingInit.add(new LdcInsnNode(2.0));
        wadingInit.add(new JumpInsnNode(Opcodes.GOTO, call));
        wadingInit.add(adult);
        wadingInit.add(new VarInsnNode(Opcodes.DLOAD, 2));
        wadingInit.add(call);
        wadingInit.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CHASE, "<init>", "(" + MOB_REF + "D)V", false));
        wadingInit.add(new InsnNode(Opcodes.RETURN));
        wading.methods.add(method(0, "<init>", "(" + MOB_REF + "D)V", wadingInit));
        out.add(wading);
        return out;
    }

    /** A goal storing its one double where its tick hands it to the navigation as the speed. */
    private static @NotNull ClassNode speedGoal(@NotNull String name) {
        ClassNode goal = fixtureClass(name, GOAL);
        InsnList init = superCall(GOAL);
        init.remove(init.getLast());
        init.add(store(name, "speedModifier", 2));
        init.add(new InsnNode(Opcodes.RETURN));
        goal.methods.add(method(0, "<init>", "(" + MOB_REF + "D)V", init));
        InsnList tick = new InsnList();
        tick.add(new InsnNode(Opcodes.ACONST_NULL));
        tick.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tick.add(new FieldInsnNode(Opcodes.GETFIELD, name, "speedModifier", "D"));
        tick.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NAVIGATION, "setSpeedModifier", "(D)V", false));
        tick.add(new InsnNode(Opcodes.RETURN));
        goal.methods.add(method(0, "tick", "()V", tick));
        return goal;
    }

    /**
     * The camel's brain owner: an idle stroll at 2.0, a core panic through its own subclass at 4.0,
     * and a fight charge at a velocity of 9.0 that is no walk.
     */
    private static @NotNull List<ClassNode> camelBrain() {
        List<ClassNode> out = new ArrayList<>();
        ClassNode ai = fixtureClass(CAMEL_AI, "java/lang/Object");
        ai.innerClasses.add(new InnerClassNode(CAMEL_PANIC, CAMEL_AI, "CamelPanic", Opcodes.ACC_STATIC));

        InsnList idle = new InsnList();
        idle.add(new LdcInsnNode(2.0f));
        idle.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RANDOM_STROLL, "stroll",
            "(F)Lnet/minecraft/world/entity/ai/behavior/OneShot;", false));
        idle.add(new InsnNode(Opcodes.ARETURN));
        ai.methods.add(method(Opcodes.ACC_STATIC, "initIdleActivity", "()Ljava/lang/Object;", idle));

        InsnList core = new InsnList();
        core.add(new TypeInsnNode(Opcodes.NEW, CAMEL_PANIC));
        core.add(new InsnNode(Opcodes.DUP));
        core.add(new LdcInsnNode(4.0f));
        core.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CAMEL_PANIC, "<init>", "(F)V", false));
        core.add(new InsnNode(Opcodes.ARETURN));
        ai.methods.add(method(Opcodes.ACC_STATIC, "initCoreActivity", "()Ljava/lang/Object;", core));

        InsnList fight = new InsnList();
        fight.add(new TypeInsnNode(Opcodes.NEW, CHARGE));
        fight.add(new InsnNode(Opcodes.DUP));
        fight.add(new IntInsnNode(Opcodes.BIPUSH, 80));
        fight.add(new InsnNode(Opcodes.ACONST_NULL));
        fight.add(new LdcInsnNode(9.0f));
        fight.add(new InsnNode(Opcodes.FCONST_2));
        fight.add(new LdcInsnNode(12.0));
        fight.add(new LdcInsnNode(11.0));
        fight.add(new InsnNode(Opcodes.ACONST_NULL));
        fight.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CHARGE, "<init>",
            "(ILnet/minecraft/world/entity/ai/targeting/TargetingConditions;FFDDLnet/minecraft/sounds/SoundEvent;)V", false));
        fight.add(new InsnNode(Opcodes.ARETURN));
        ai.methods.add(method(Opcodes.ACC_STATIC, "initFightActivity", "()Ljava/lang/Object;", fight));
        out.add(ai);

        out.add(fixtureClass(ANIMAL_PANIC, "java/lang/Object"));
        out.add(fixtureClass(CHARGE, "java/lang/Object"));
        ClassNode panic = fixtureClass(CAMEL_PANIC, ANIMAL_PANIC);
        InsnList panicInit = new InsnList();
        panicInit.add(new VarInsnNode(Opcodes.ALOAD, 0));
        panicInit.add(new VarInsnNode(Opcodes.FLOAD, 1));
        panicInit.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ANIMAL_PANIC, "<init>", "(F)V", false));
        panicInit.add(new InsnNode(Opcodes.RETURN));
        panic.methods.add(method(0, "<init>", "(F)V", panicInit));
        out.add(panic);
        return out;
    }

    /** A registerGoals body adding each goal in turn. */
    private static @NotNull InsnList registerGoals(@NotNull InsnList @NotNull ... goals) {
        InsnList code = new InsnList();
        for (InsnList goal : goals) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, MOB, "goalSelector", "Lfx/GoalSelector;"));
            code.add(new InsnNode(Opcodes.ICONST_3));
            code.add(goal);
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "fx/GoalSelector", "addGoal", "(IL" + GOAL + ";)V", false));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    /** {@code new <type>(this, <doubles>...)}. */
    private static @NotNull InsnList construct(@NotNull String type, @NotNull String desc, double @NotNull ... doubles) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, type));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (double value : doubles) code.add(new LdcInsnNode(value));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type, "<init>", desc, false));
        return code;
    }

    /** {@code super()} on the given class, then a return. */
    private static @NotNull InsnList superCall(@NotNull String superName) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    /** {@code this.<field> = <the double in slot>}. */
    private static @NotNull InsnList store(@NotNull String owner, @NotNull String field, int slot) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.DLOAD, slot));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, "D"));
        return code;
    }

    private static @NotNull InsnList returning() {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
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
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "goalSelector", "Lfx/GoalSelector;", null, null));
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
