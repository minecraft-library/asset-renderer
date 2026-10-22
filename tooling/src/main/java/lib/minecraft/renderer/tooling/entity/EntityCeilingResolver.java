package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.entity.EntityWanderResolver.Body;
import lib.minecraft.renderer.tooling.entity.EntityWanderResolver.Operand;
import lib.minecraft.renderer.tooling.entity.EntityWanderResolver.Operands;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;

/**
 * The fastest speed setting a subject's own behaviours hand its navigation - the panic, chase,
 * tempt, avoid, follow, ram or retreat it walks fastest at.
 *
 * <p>A speed setting is a value reaching the subject's own navigation as its speed modifier:
 * {@code PathNavigation.moveTo(..., D)}, {@code PathNavigation.setSpeedModifier(D)},
 * {@code MoveControl.setWantedPosition(..., D)} or a brain walk target. Each becomes
 * {@code (float) (modifier * MOVEMENT_SPEED)} exactly as the stroll's does. Only the subject's own
 * classes are read, so a rider driving it and a player steering it set nothing here.
 *
 * <p><b>The goal census</b> walks from every constructor the subject's class declares, following the
 * stroll walk's re-entries and the calls up the constructor chain. That reaches what
 * {@code registerGoals} builds - {@code Mob}'s constructor calls it - and the goals a constructor
 * installs, and leaves a parent's {@code registerGoals} the subject's own never calls unread. A goal
 * offers the literals handed the constructor parameters that are speeds, both arms of a select, and
 * the literals it hands its own super at a speed.
 *
 * <p><b>A speed is told apart with no parameter names</b>, which the jar is read without: a
 * constructor {@code double} is a speed when it reaches - directly or through {@code super(...)} or
 * {@code this(...)} - a field one of the goal chain's methods hands a navigation call as its speed.
 *
 * <p><b>The brain census</b> reads the class owning the subject's stroll row and its nested classes for
 * the panic, the chase, the retreat, the ram and the stroll entry points, each handed its speed as a
 * float; a panic subclass is followed to the speed it hands its super. A charge's float is a
 * velocity rather than a walk target, and is no entry point.
 *
 * <p>The answer is the largest setting the census finds, less any goal the policy drops at the age.
 */
@UtilityClass
final class EntityCeilingResolver {

    /** How many constructor hops a parameter is followed up before the flow rule stops. */
    private static final int MAX_HOPS = 12;

    /** The census memos of each open session, dropped with the session's cache. */
    private static final @NotNull Map<ClassNodeCache, Memo> MEMOS = new WeakHashMap<>();

    /**
     * The fastest speed setting a census finds at one age.
     *
     * @param modifier the speed modifier handed the navigation
     * @param source the behaviour it was read off, {@code goal <Goal>} or {@code brain <Behaviour>}
     */
    record Fastest(double modifier, @NotNull String source) { }

    /**
     * One speed setting a census found.
     *
     * @param modifier the speed modifier
     * @param source the behaviour it was read off
     * @param goal the internal name of the goal it was read off, empty for a brain behaviour
     */
    private record Candidate(double modifier, @NotNull String source, @NotNull Optional<String> goal) { }

    /**
     * What one constructor does with its parameters.
     *
     * @param stored each parameter's index, to the fields it is stored in on the object built
     * @param handed each call the constructor makes up its own chain, with what it is handed
     */
    record ConstructorFlow(@NotNull Map<Integer, Set<String>> stored, @NotNull List<Handed> handed) {

        /** The flow of a constructor the jar does not hold. */
        static final @NotNull ConstructorFlow NONE = new ConstructorFlow(Map.of(), List.of());

    }

    /**
     * One {@code super(...)} or {@code this(...)} call.
     *
     * @param owner the class whose constructor is called
     * @param desc the constructor's descriptor
     * @param arguments the values it is handed
     */
    record Handed(@NotNull String owner, @NotNull String desc, @NotNull List<Operand> arguments) { }

    /** What a session's census has already read. */
    private static final class Memo {

        private final @NotNull Map<String, ConstructorFlow> flows = new HashMap<>();
        private final @NotNull Map<String, Set<String>> navigationFields = new HashMap<>();
        private final @NotNull Map<String, List<Candidate>> censuses = new HashMap<>();

    }

    /**
     * The fastest speed setting a subject's own behaviours hand its navigation at one age.
     *
     * @param session the live run
     * @param subject the subject read
     * @param age the age token the census is taken at
     * @param diagnostics the consulting scope
     * @return the fastest setting, or empty where the census finds none
     */
    static @NotNull Optional<Fastest> fastest(
        @NotNull ToolingRun session, @NotNull EntitySubject subject, @NotNull String age, @NotNull Diagnostics diagnostics) {
        return fastest(session, subject, age, EntityPacePolicies::excludesGoal, diagnostics);
    }

    /**
     * The fastest speed setting at one age, with the goals a predicate names dropped.
     *
     * @param session the live run
     * @param subject the subject read
     * @param age the age token the census is taken at
     * @param excluded whether a goal, by internal name, is dropped at an age
     * @param diagnostics the consulting scope
     * @return the fastest setting, or empty where the census finds none
     */
    static @NotNull Optional<Fastest> fastest(
        @NotNull ToolingRun session, @NotNull EntitySubject subject, @NotNull String age,
        @NotNull BiPredicate<String, String> excluded, @NotNull Diagnostics diagnostics) {

        Candidate best = null;
        for (Candidate candidate : census(session, subject, diagnostics)) {
            if (candidate.goal().filter(goal -> excluded.test(goal, age)).isPresent()) continue;
            if (best == null || candidate.modifier() > best.modifier()) best = candidate;
        }
        return Optional.ofNullable(best).map(candidate -> new Fastest(candidate.modifier(), candidate.source()));
    }

    /** Every speed setting the subject's goals and brain hand its navigation, in the order they are met. */
    private static @NotNull List<Candidate> census(
        @NotNull ToolingRun session, @NotNull EntitySubject subject, @NotNull Diagnostics diagnostics) {

        ClassNodeCache cache = session.cache();
        Memo memo = MEMOS.computeIfAbsent(cache, key -> new Memo());
        String key = subject.entityId() + "|" + subject.entityClass();
        List<Candidate> known = memo.censuses.get(key);
        if (known != null) return known;

        List<Candidate> out = new ArrayList<>();
        ClassNode entity = cache.load(subject.entityClass());
        if (entity != null) {
            List<Body> starts = entity.methods.stream()
                .filter(method -> ClassKit.INIT.equals(method.name))
                .map(method -> new Body(entity.name, method))
                .toList();
            for (Body body : EntityWanderResolver.reach(cache, subject.entityClass(), starts, true)) {
                Operands.run(body.method(), new Operands.Listener() {
                    @Override
                    public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                        if (!(receiver instanceof Operand.Fresh) || !ClassKit.INIT.equals(call.name)
                            || !ClassKit.extendsClass(cache, call.owner, SourceClasses.Types.GOAL)) return;
                        for (double speed : goalSpeeds(cache, memo, call.owner, call.desc, arguments))
                            out.add(new Candidate(speed, "goal " + ClassKit.simpleName(call.owner), Optional.of(call.owner)));
                    }
                });
            }
        }

        AsmContext frame = new AsmContext(session, subject.entityId(), null, diagnostics);
        for (EntityPacePolicies row : EntityPacePolicies.values()) {
            if (row.strollsFor(subject.entityId()) && row.navigate(frame) instanceof Navigation.At owner)
                brainCensus(cache, memo, owner.owner(), out);
        }

        List<Candidate> settled = List.copyOf(out);
        memo.censuses.put(key, settled);
        return settled;
    }

    // ------------------------------------------------------------------------------------
    // the goal census
    // ------------------------------------------------------------------------------------

    /**
     * The speeds one goal construction offers: the literals handed each parameter that is a speed, and
     * the literals the goal's constructors hand their supers at a speed.
     */
    private static @NotNull List<Double> goalSpeeds(
        @NotNull ClassNodeCache cache, @NotNull Memo memo, @NotNull String goal, @NotNull String desc,
        @NotNull List<Operand> arguments) {

        Set<String> speedFields = speedFields(cache, memo, goal);
        List<Double> out = new ArrayList<>();
        Type[] parameters = ClassKit.argTypes(desc);
        for (int index = 0; index < parameters.length && index < arguments.size(); index++) {
            if (parameters[index].getSort() == Type.DOUBLE
                && !Collections.disjoint(fieldsReached(cache, memo, goal, desc, index, 0), speedFields))
                out.addAll(Operand.literalsOf(arguments.get(index)));
        }
        out.addAll(handedLiterals(cache, memo, goal, desc, speedFields, 0));
        return out;
    }

    /**
     * The fields one constructor parameter reaches on the object built - stored directly, or handed up
     * the chain to a constructor that stores it.
     *
     * @param cache the session's jar cache
     * @param owner the class declaring the constructor
     * @param desc the constructor's descriptor
     * @param parameter the parameter's index
     * @return the names of the fields it reaches
     */
    static @NotNull Set<String> fieldsReached(@NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String desc, int parameter) {
        return fieldsReached(cache, MEMOS.computeIfAbsent(cache, key -> new Memo()), owner, desc, parameter, 0);
    }

    /** The fields a constructor parameter reaches, stored directly or handed up the chain. */
    private static @NotNull Set<String> fieldsReached(
        @NotNull ClassNodeCache cache, @NotNull Memo memo, @NotNull String owner, @NotNull String desc, int parameter, int hops) {

        if (hops > MAX_HOPS) return Set.of();
        ConstructorFlow flow = flow(cache, memo, owner, desc);
        Set<String> out = new LinkedHashSet<>(flow.stored().getOrDefault(parameter, Set.of()));
        for (Handed up : flow.handed()) {
            for (int index = 0; index < up.arguments().size(); index++) {
                if (Operand.parametersOf(up.arguments().get(index)).contains(parameter))
                    out.addAll(fieldsReached(cache, memo, up.owner(), up.desc(), index, hops + 1));
            }
        }
        return out;
    }

    /** The literals a constructor and the ones above it hand their supers for a parameter that is a speed. */
    private static @NotNull List<Double> handedLiterals(
        @NotNull ClassNodeCache cache, @NotNull Memo memo, @NotNull String owner, @NotNull String desc,
        @NotNull Set<String> speedFields, int hops) {

        if (hops > MAX_HOPS) return List.of();
        List<Double> out = new ArrayList<>();
        for (Handed up : flow(cache, memo, owner, desc).handed()) {
            Type[] parameters = ClassKit.argTypes(up.desc());
            for (int index = 0; index < parameters.length && index < up.arguments().size(); index++) {
                if (parameters[index].getSort() == Type.DOUBLE
                    && !Collections.disjoint(fieldsReached(cache, memo, up.owner(), up.desc(), index, hops + 1), speedFields))
                    out.addAll(Operand.literalsOf(up.arguments().get(index)));
            }
            out.addAll(handedLiterals(cache, memo, up.owner(), up.desc(), speedFields, hops + 1));
        }
        return out;
    }

    /** Every field a goal chain's methods hand a navigation call as its speed. */
    private static @NotNull Set<String> speedFields(@NotNull ClassNodeCache cache, @NotNull Memo memo, @NotNull String goal) {
        Set<String> out = new LinkedHashSet<>();
        ClassKit.walkSuperChain(cache, goal, node -> out.addAll(navigationFields(cache, memo, node)));
        return out;
    }

    /** The fields one class's own methods hand a navigation call as its speed. */
    private static @NotNull Set<String> navigationFields(@NotNull ClassNodeCache cache, @NotNull Memo memo, @NotNull ClassNode owner) {
        Set<String> known = memo.navigationFields.get(owner.name);
        if (known != null) return known;
        Set<String> out = new LinkedHashSet<>();
        for (MethodNode method : owner.methods) {
            Operands.run(method, new Operands.Listener() {
                @Override
                public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                    if (isNavigation(cache, call) && !arguments.isEmpty()) out.addAll(Operand.fieldsOf(arguments.getLast()));
                }
            });
        }
        Set<String> settled = Set.copyOf(out);
        memo.navigationFields.put(owner.name, settled);
        return settled;
    }

    /** Whether a call hands a navigation or a move control a speed, as its last {@code double}. */
    private static boolean isNavigation(@NotNull ClassNodeCache cache, @NotNull MethodInsnNode call) {
        boolean named = SourceClasses.Methods.MOVE_TO.equals(call.name) || SourceClasses.Methods.SET_SPEED_MODIFIER.equals(call.name)
            || SourceClasses.Methods.SET_WANTED_POSITION.equals(call.name);
        if (!named) return false;
        Type[] parameters = ClassKit.argTypes(call.desc);
        if (parameters.length == 0 || parameters[parameters.length - 1].getSort() != Type.DOUBLE) return false;
        return ClassKit.extendsClass(cache, call.owner, SourceClasses.Types.PATH_NAVIGATION)
            || ClassKit.extendsClass(cache, call.owner, SourceClasses.Types.MOVE_CONTROL);
    }

    /**
     * What one constructor does with its parameters - the fields it stores each in, and the calls up
     * its own chain with what each is handed - or {@link ConstructorFlow#NONE} where the jar does not
     * hold the constructor.
     */
    private static @NotNull ConstructorFlow flow(
        @NotNull ClassNodeCache cache, @NotNull Memo memo, @NotNull String owner, @NotNull String desc) {

        String key = owner + "." + desc;
        ConstructorFlow known = memo.flows.get(key);
        if (known != null) return known;
        ClassNode node = cache.load(owner);
        MethodNode constructor = node == null ? null : ClassKit.findMethod(node, ClassKit.INIT, desc);
        if (constructor == null) {
            memo.flows.put(key, ConstructorFlow.NONE);
            return ConstructorFlow.NONE;
        }
        Map<Integer, Set<String>> stored = new LinkedHashMap<>();
        List<Handed> handed = new ArrayList<>();
        Operands.run(constructor, new Operands.Listener() {
            @Override
            public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                if (receiver == Operand.Self.THIS && call.getOpcode() == Opcodes.INVOKESPECIAL && ClassKit.INIT.equals(call.name))
                    handed.add(new Handed(call.owner, call.desc, List.copyOf(arguments)));
            }

            @Override
            public void put(@NotNull FieldInsnNode put, @NotNull Operand receiver, @NotNull Operand value) {
                if (receiver != Operand.Self.THIS) return;
                for (int parameter : Operand.parametersOf(value))
                    stored.computeIfAbsent(parameter, index -> new LinkedHashSet<>()).add(put.name);
            }
        });
        ConstructorFlow flow = new ConstructorFlow(Map.copyOf(stored), List.copyOf(handed));
        memo.flows.put(key, flow);
        return flow;
    }

    // ------------------------------------------------------------------------------------
    // the brain census
    // ------------------------------------------------------------------------------------

    /** Every speed the brain behaviours a stroll row's owner and its nested classes build are handed. */
    private static void brainCensus(
        @NotNull ClassNodeCache cache, @NotNull Memo memo, @NotNull String owner, @NotNull List<Candidate> out) {

        ClassNode outer = cache.load(owner);
        if (outer == null) return;
        List<ClassNode> classes = new ArrayList<>();
        classes.add(outer);
        for (InnerClassNode inner : outer.innerClasses) {
            if (!inner.name.startsWith(owner + "$")) continue;
            ClassNode nested = cache.load(inner.name);
            if (nested != null && !classes.contains(nested)) classes.add(nested);
        }
        for (ClassNode node : classes) {
            for (MethodNode method : node.methods) {
                Operands.run(method, new Operands.Listener() {
                    @Override
                    public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                        if (isBrainFactory(call)) {
                            for (double speed : Operand.literalsOf(arguments.get(EntityWanderResolver.firstOf(call.desc, Type.FLOAT))))
                                out.add(new Candidate(speed, "brain " + ClassKit.simpleName(call.owner), Optional.empty()));
                        } else if (receiver instanceof Operand.Fresh && ClassKit.INIT.equals(call.name) && isBrainBehaviour(cache, call.owner)) {
                            for (double speed : brainSpeeds(cache, memo, call.owner, call.desc, arguments))
                                out.add(new Candidate(speed, "brain " + ClassKit.simpleName(call.owner), Optional.empty()));
                        }
                    }
                });
            }
        }
    }

    /** Whether a call is a brain factory handed its speed as a float - the chase, the retreat or a stroll. */
    private static boolean isBrainFactory(@NotNull MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC || EntityWanderResolver.firstOf(call.desc, Type.FLOAT) < 0) return false;
        if (SourceClasses.Types.SET_WALK_TARGET_FROM_ATTACK_TARGET.equals(call.owner)) return SourceClasses.Methods.CREATE.equals(call.name);
        if (SourceClasses.Types.SET_WALK_TARGET_AWAY_FROM.equals(call.owner))
            return SourceClasses.Methods.ENTITY.equals(call.name) || SourceClasses.Methods.POS.equals(call.name);
        return EntityWanderResolver.isStrollEntry(call);
    }

    /** Whether a constructed class is a brain behaviour handed its speed - a panic or a ram, or one extending them. */
    private static boolean isBrainBehaviour(@NotNull ClassNodeCache cache, @NotNull String type) {
        return ClassKit.extendsClass(cache, type, SourceClasses.Types.ANIMAL_PANIC)
            || ClassKit.extendsClass(cache, type, SourceClasses.Types.RAM_TARGET);
    }

    /** Whether a class is a brain behaviour itself, whose own float is its speed. */
    private static boolean isBrainEntry(@NotNull String type) {
        return SourceClasses.Types.ANIMAL_PANIC.equals(type) || SourceClasses.Types.RAM_TARGET.equals(type);
    }

    /** The speeds one brain behaviour's construction offers, followed up its subclasses' super calls. */
    private static @NotNull List<Double> brainSpeeds(
        @NotNull ClassNodeCache cache, @NotNull Memo memo, @NotNull String type, @NotNull String desc, @NotNull List<Operand> arguments) {

        List<Double> out = new ArrayList<>();
        for (int position : brainPositions(cache, memo, type, desc, 0))
            if (position < arguments.size()) out.addAll(Operand.literalsOf(arguments.get(position)));
        out.addAll(brainHanded(cache, memo, type, desc, 0));
        return out;
    }

    /** The parameters of a brain behaviour's constructor that reach the behaviour's own speed float. */
    private static @NotNull Set<Integer> brainPositions(
        @NotNull ClassNodeCache cache, @NotNull Memo memo, @NotNull String type, @NotNull String desc, int hops) {

        Set<Integer> out = new LinkedHashSet<>();
        if (isBrainEntry(type)) {
            int speed = EntityWanderResolver.firstOf(desc, Type.FLOAT);
            if (speed >= 0) out.add(speed);
            return out;
        }
        if (hops > MAX_HOPS) return out;
        for (Handed up : flow(cache, memo, type, desc).handed()) {
            for (int position : brainPositions(cache, memo, up.owner(), up.desc(), hops + 1))
                if (position < up.arguments().size()) out.addAll(Operand.parametersOf(up.arguments().get(position)));
        }
        return out;
    }

    /** The literal speeds a brain behaviour's subclass constructors hand their supers. */
    private static @NotNull List<Double> brainHanded(
        @NotNull ClassNodeCache cache, @NotNull Memo memo, @NotNull String type, @NotNull String desc, int hops) {

        if (isBrainEntry(type) || hops > MAX_HOPS) return List.of();
        List<Double> out = new ArrayList<>();
        for (Handed up : flow(cache, memo, type, desc).handed()) {
            for (int position : brainPositions(cache, memo, up.owner(), up.desc(), hops + 1))
                if (position < up.arguments().size()) out.addAll(Operand.literalsOf(up.arguments().get(position)));
            out.addAll(brainHanded(cache, memo, up.owner(), up.desc(), hops + 1));
        }
        return out;
    }

}
