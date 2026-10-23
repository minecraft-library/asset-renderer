package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.entity.OperandWalk.Operand;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The speed a subject's idle stroll hands its navigation - the modifier {@code MoveControl.tick}
 * multiplies the movement speed by.
 *
 * <p>A goal mob is read first. The walk starts at the nearest {@code registerGoals} up the subject's
 * class, re-enters a super call to {@code registerGoals} at the declaration it names and a call the
 * subject makes on itself at the subject's own nearest declaration - the call is virtual, so an
 * override is what runs. Every stroll goal constructed on the way answers the literal its constructor
 * is handed for its first {@code double}; a stroll taking none is read at the call it makes to its
 * own super. Strolls that agree answer their speed, strolls that disagree answer nothing.
 *
 * <p>A subject the walk finds no stroll for runs a brain, and is answered by the stroll row of
 * {@link EntityPacePolicies} naming it: a member holding the one stroll entry point its idle activity
 * calls, whose float is read there, or a declared float. A member holding no stroll, two, or one at a
 * speed that is no literal is a jar that moved under the row, and refuses.
 *
 * <p>The reads ride the operand machine {@link OperandWalk}, which the fastest-walk census and the
 * move-control read share.
 */
@UtilityClass
final class EntityWanderResolver {

    /** The descriptor of a no-argument void member - {@code registerGoals} and a call a subject makes on itself. */
    private static final @NotNull String NO_ARG_VOID = "()V";

    /**
     * One idle stroll, with what it was read off.
     *
     * @param speed the speed modifier the stroll hands the navigation
     * @param source the goal it was read off ({@code goal WaterAvoidingRandomStrollGoal}) or the policy row's name
     */
    record Stroll(double speed, @NotNull String source) { }

    /**
     * A subject's idle stroll and where it was read - the goal walk, then the stroll rows.
     *
     * @param session the live run
     * @param subject the subject read
     * @param diagnostics the consulting scope
     * @return the stroll, or empty where neither answers one
     * @throws ToolingException if a stroll row names a member that does not hold exactly one literal stroll
     */
    static @NotNull Optional<Stroll> stroll(@NotNull ToolingRun session, @NotNull EntitySubject subject, @NotNull Diagnostics diagnostics) {
        ClassNodeCache cache = session.cache();
        List<Stroll> found = new ArrayList<>();
        int strolls = 0;
        List<Body> starts = nearest(cache, subject.entityClass(), SourceClasses.Methods.REGISTER_GOALS, NO_ARG_VOID).stream().toList();
        for (Body body : reach(cache, subject.entityClass(), starts, false)) {
            List<Optional<Stroll>> read = new ArrayList<>();
            OperandWalk.run(body.method(), new OperandWalk.Listener() {
                @Override
                public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                    if (!(receiver instanceof Operand.Fresh) || !ClassKit.INIT.equals(call.name)
                        || !ClassKit.extendsClass(cache, call.owner, SourceClasses.Types.RANDOM_STROLL_GOAL)) return;
                    OptionalDouble speed = goalSpeed(cache, call.owner, call.desc, arguments);
                    read.add(speed.isPresent()
                        ? Optional.of(new Stroll(speed.getAsDouble(), "goal " + ClassKit.simpleName(call.owner)))
                        : Optional.empty());
                }
            });
            strolls += read.size();
            read.forEach(stroll -> stroll.ifPresent(found::add));
        }

        if (strolls == 0) return rowStroll(session, subject, diagnostics);
        Set<Double> speeds = found.stream().map(Stroll::speed).collect(Collectors.toCollection(LinkedHashSet::new));
        if (speeds.size() == 1) return Optional.of(found.getFirst());
        if (speeds.size() > 1) {
            diagnostics.info("%s strolls by %d goals at [%s] and takes no pace", subject.entityId(), strolls,
                speeds.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        return Optional.empty();
    }

    /**
     * The speed a stroll goal is handed - the literal for its constructor's first {@code double}, or,
     * where it takes none, the one it hands its own super.
     */
    private static @NotNull OptionalDouble goalSpeed(
        @NotNull ClassNodeCache cache, @NotNull String goal, @NotNull String desc, @NotNull List<Operand> arguments) {

        int speed = ClassKit.firstArgOfSort(desc, Type.DOUBLE);
        if (speed >= 0) {
            return arguments.get(speed) instanceof Operand.Literal literal
                ? OptionalDouble.of(literal.value().doubleValue())
                : OptionalDouble.empty();
        }
        ClassNode owner = cache.load(goal);
        MethodNode constructor = owner == null ? null : ClassKit.findMethod(owner, ClassKit.INIT, desc);
        if (constructor == null) return OptionalDouble.empty();
        OptionalDouble[] handed = {OptionalDouble.empty()};
        OperandWalk.run(constructor, new OperandWalk.Listener() {
            @Override
            public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                if (receiver == Operand.Self.THIS && ClassKit.INIT.equals(call.name) && handed[0].isEmpty())
                    handed[0] = goalSpeed(cache, call.owner, call.desc, arguments);
            }
        });
        return handed[0];
    }

    /**
     * The stroll a subject's policy row answers - read at the member a coordinate row names, or the
     * float a value row declares.
     */
    private static @NotNull Optional<Stroll> rowStroll(
        @NotNull ToolingRun session, @NotNull EntitySubject subject, @NotNull Diagnostics diagnostics) {

        List<EntityPacePolicies> rows = new ArrayList<>();
        for (EntityPacePolicies row : EntityPacePolicies.values())
            if (row.strollsFor(subject.entityId())) rows.add(row);
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() > 1) {
            throw new ToolingException("Pace policy rows '%s' and '%s' both stroll for '%s'",
                rows.getFirst(), rows.get(1), subject.entityId());
        }

        EntityPacePolicies row = rows.getFirst();
        Navigation answer = row.navigate(new AsmContext(session, subject.entityId(), null, diagnostics));
        if (answer instanceof Navigation.At member) return Optional.of(new Stroll(memberStroll(session.cache(), row, member), row.name()));
        return Optional.of(new Stroll(row.strollSpeed(), row.name()));
    }

    /** The float the one stroll entry point a row's member calls is handed, widened. */
    private static double memberStroll(@NotNull ClassNodeCache cache, @NotNull EntityPacePolicies row, @NotNull Navigation.At member) {
        ClassNode owner = cache.load(member.owner());
        MethodNode method = owner == null ? null
            : member.desc() == null ? ClassKit.findMethod(owner, member.member()) : ClassKit.findMethod(owner, member.member(), member.desc());
        if (method == null)
            throw new ToolingException("Pace policy row '%s' names '%s.%s', which the jar does not declare", row, member.owner(), member.member());

        List<Operand> speeds = new ArrayList<>();
        OperandWalk.run(method, new OperandWalk.Listener() {
            @Override
            public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                if (isStrollEntry(call)) speeds.add(arguments.get(ClassKit.firstArgOfSort(call.desc, Type.FLOAT)));
            }
        });
        if (speeds.size() != 1) {
            throw new ToolingException("Pace policy row '%s' names '%s.%s', which strolls '%s' times where one stroll is read",
                row, member.owner(), member.member(), speeds.size());
        }
        if (!(speeds.getFirst() instanceof Operand.Literal literal)) {
            throw new ToolingException("Pace policy row '%s' names '%s.%s', which strolls at a speed that is no literal",
                row, member.owner(), member.member());
        }
        return literal.value().doubleValue();
    }

    /**
     * Whether a call is one of the brain stroll entry points - a stroll on foot, in the air or in
     * water, or a villager's village-bound stroll.
     *
     * @param call the call read
     * @return whether it strolls
     */
    static boolean isStrollEntry(@NotNull MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC || ClassKit.firstArgOfSort(call.desc, Type.FLOAT) < 0) return false;
        if (SourceClasses.Types.RANDOM_STROLL.equals(call.owner)) {
            return SourceClasses.Methods.STROLL.equals(call.name) || SourceClasses.Methods.FLY.equals(call.name)
                || SourceClasses.Methods.SWIM.equals(call.name);
        }
        return SourceClasses.Types.VILLAGE_BOUND_RANDOM_STROLL.equals(call.owner) && SourceClasses.Methods.CREATE.equals(call.name);
    }

    // ------------------------------------------------------------------------------------
    // the goal walk
    // ------------------------------------------------------------------------------------

    /**
     * One method body a goal walk reaches, with the class declaring it.
     *
     * @param owner the declaring class's internal name
     * @param method the body
     */
    record Body(@NotNull String owner, @NotNull MethodNode method) { }

    /**
     * Every body a goal walk reaches from its starting bodies. A super call to {@code registerGoals}
     * re-enters at the declaration from the named owner upward; a no-argument void call the subject makes
     * on itself, to a member of its own chain, re-enters at the subject's nearest declaration, the call
     * being virtual; and where {@code constructors} is set, a {@code super(...)} or {@code this(...)} call
     * re-enters at the constructor of that descriptor. Each (class, method) is walked once.
     *
     * @param cache the session's jar cache
     * @param subjectClass the subject's entity class
     * @param starts the bodies the walk opens at
     * @param constructors whether a constructor's call up its chain is followed
     * @return the bodies reached, in the order they are first walked
     */
    static @NotNull List<Body> reach(
        @NotNull ClassNodeCache cache, @NotNull String subjectClass, @NotNull List<Body> starts, boolean constructors) {

        Set<String> chain = new HashSet<>();
        ClassKit.walkSuperChain(cache, subjectClass, node -> chain.add(node.name));
        List<Body> reached = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Body> pending = new ArrayDeque<>(starts);
        while (!pending.isEmpty()) {
            Body body = pending.poll();
            if (!visited.add(body.owner() + "." + body.method().name + body.method().desc)) continue;
            reached.add(body);
            List<Body> next = new ArrayList<>();
            OperandWalk.run(body.method(), new OperandWalk.Listener() {
                @Override
                public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                    if (receiver != Operand.Self.THIS) return;
                    if (call.getOpcode() == Opcodes.INVOKESPECIAL && ClassKit.INIT.equals(call.name)) {
                        if (!constructors) return;
                        ClassNode owner = cache.load(call.owner);
                        MethodNode constructor = owner == null ? null : ClassKit.findMethod(owner, ClassKit.INIT, call.desc);
                        if (constructor != null) next.add(new Body(call.owner, constructor));
                    } else if (call.getOpcode() == Opcodes.INVOKESPECIAL && SourceClasses.Methods.REGISTER_GOALS.equals(call.name)
                        && NO_ARG_VOID.equals(call.desc))
                        nearest(cache, call.owner, call.name, call.desc).ifPresent(next::add);
                    else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && NO_ARG_VOID.equals(call.desc) && chain.contains(call.owner))
                        nearest(cache, subjectClass, call.name, call.desc).ifPresent(next::add);
                }
            });
            pending.addAll(next);
        }
        return reached;
    }

    /**
     * The nearest declaration of a member up a class's chain.
     *
     * @param cache the session's jar cache
     * @param start the class the chain is walked from
     * @param name the member's name
     * @param desc the member's descriptor
     * @return the declaration, or empty where the chain declares none
     */
    static @NotNull Optional<Body> nearest(@NotNull ClassNodeCache cache, @NotNull String start, @NotNull String name, @NotNull String desc) {
        ClassNode declaring = ClassKit.walkSuperChainUntil(cache, start, node -> ClassKit.findMethod(node, name, desc) != null);
        if (declaring == null) return Optional.empty();
        return Optional.of(new Body(declaring.name, ClassKit.findMethod(declaring, name, desc)));
    }

}
