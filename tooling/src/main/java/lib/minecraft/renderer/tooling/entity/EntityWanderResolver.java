package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.interp.Interpreter;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p>The reads ride one operand machine over the {@link Interpreter} chassis, which the fastest-walk
 * census and the move-control read share: it carries a literal, a parameter, a field read and an
 * allocation as values of their own, and at the label a select's two arms meet it carries both.
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
     * The speed modifier a subject's idle stroll hands its navigation - the goal walk, then the stroll
     * rows.
     *
     * @param session the live run
     * @param subject the subject read
     * @param diagnostics the consulting scope
     * @return the modifier, or empty where neither answers one
     * @throws ToolingException if a stroll row names a member that does not hold exactly one literal stroll
     */
    static @NotNull OptionalDouble strollSpeed(@NotNull ToolingRun session, @NotNull EntitySubject subject, @NotNull Diagnostics diagnostics) {
        return stroll(session, subject, diagnostics).map(stroll -> OptionalDouble.of(stroll.speed())).orElse(OptionalDouble.empty());
    }

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
            Operands.run(body.method(), new Operands.Listener() {
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

        int speed = firstOf(desc, Type.DOUBLE);
        if (speed >= 0) {
            return arguments.get(speed) instanceof Operand.Literal literal
                ? OptionalDouble.of(literal.value().doubleValue())
                : OptionalDouble.empty();
        }
        ClassNode owner = cache.load(goal);
        MethodNode constructor = owner == null ? null : ClassKit.findMethod(owner, ClassKit.INIT, desc);
        if (constructor == null) return OptionalDouble.empty();
        OptionalDouble[] handed = {OptionalDouble.empty()};
        Operands.run(constructor, new Operands.Listener() {
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
        Operands.run(method, new Operands.Listener() {
            @Override
            public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                if (isStrollEntry(call)) speeds.add(arguments.get(firstOf(call.desc, Type.FLOAT)));
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
        if (call.getOpcode() != Opcodes.INVOKESTATIC || firstOf(call.desc, Type.FLOAT) < 0) return false;
        if (SourceClasses.Types.RANDOM_STROLL.equals(call.owner)) {
            return SourceClasses.Methods.STROLL.equals(call.name) || SourceClasses.Methods.FLY.equals(call.name)
                || SourceClasses.Methods.SWIM.equals(call.name);
        }
        return SourceClasses.Types.VILLAGE_BOUND_RANDOM_STROLL.equals(call.owner) && SourceClasses.Methods.CREATE.equals(call.name);
    }

    /**
     * The index of a descriptor's first parameter of one sort.
     *
     * @param desc the method descriptor
     * @param sort the {@link Type} sort looked for
     * @return the parameter index, or -1 where none is of that sort
     */
    static int firstOf(@NotNull String desc, int sort) {
        Type[] parameters = ClassKit.argTypes(desc);
        for (int index = 0; index < parameters.length; index++)
            if (parameters[index].getSort() == sort) return index;
        return -1;
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
            Operands.run(body.method(), new Operands.Listener() {
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

    // ------------------------------------------------------------------------------------
    // the operand machine
    // ------------------------------------------------------------------------------------

    /**
     * A value the operand machine carries.
     */
    sealed interface Operand {

        /**
         * Whether the value fills two operand-stack slots - a {@code long} or a {@code double} - which is
         * what a two-slot pop or duplicate reads.
         *
         * @return whether the value is wide
         */
        boolean wide();

        /**
         * A numeric literal push.
         *
         * @param value the number, boxed as the type it is pushed as
         */
        record Literal(@NotNull Number value) implements Operand {
            @Override
            public boolean wide() {
                return this.value instanceof Double || this.value instanceof Long;
            }
        }

        /**
         * A parameter of the method walked, as its entry frame holds it.
         *
         * @param index the parameter's position in the descriptor
         * @param wide whether it is a {@code long} or a {@code double}
         */
        record Parameter(int index, boolean wide) implements Operand { }

        /**
         * A value read off one or more fields, through any arithmetic.
         *
         * @param names the names of the fields read
         * @param wide whether the value is wide
         */
        record Fields(@NotNull Set<String> names, boolean wide) implements Operand { }

        /**
         * An object a {@code new} allocates.
         *
         * @param site the allocation
         */
        record Fresh(@NotNull TypeInsnNode site) implements Operand {
            @Override
            public boolean wide() {
                return false;
            }
        }

        /**
         * The value one of a select's arms leaves, both arms at once.
         *
         * @param arms the arms, the fall-through first
         */
        record Choice(@NotNull List<Operand> arms) implements Operand {
            @Override
            public boolean wide() {
                return this.arms.getFirst().wide();
            }
        }

        /**
         * Anything the machine does not model.
         *
         * @param wide whether the value is wide
         */
        record Opaque(boolean wide) implements Operand { }

        /** The receiver of an instance method - slot zero of its entry frame. */
        enum Self implements Operand {

            /** The object the walked method runs on. */
            THIS;

            @Override
            public boolean wide() {
                return false;
            }
        }

        /** The placeholder the chassis answers for what it cannot model, recognised by identity. */
        @NotNull Opaque UNKNOWN = new Opaque(false);

        /** The value a two-slot push the machine does not model carries. */
        @NotNull Opaque WIDE = new Opaque(true);

        /**
         * Every literal a value carries, down each arm of a select.
         *
         * @param value the value read
         * @return the literals, widened
         */
        static @NotNull List<Double> literalsOf(@NotNull Operand value) {
            List<Double> out = new ArrayList<>();
            if (value instanceof Literal literal) out.add(literal.value().doubleValue());
            else if (value instanceof Choice choice) choice.arms().forEach(arm -> out.addAll(literalsOf(arm)));
            return out;
        }

        /**
         * Every parameter a value carries, down each arm of a select.
         *
         * @param value the value read
         * @return the parameter indices
         */
        static @NotNull Set<Integer> parametersOf(@NotNull Operand value) {
            Set<Integer> out = new LinkedHashSet<>();
            if (value instanceof Parameter parameter) out.add(parameter.index());
            else if (value instanceof Choice choice) choice.arms().forEach(arm -> out.addAll(parametersOf(arm)));
            return out;
        }

        /**
         * Every field a value was read off, down each arm of a select.
         *
         * @param value the value read
         * @return the field names
         */
        static @NotNull Set<String> fieldsOf(@NotNull Operand value) {
            Set<String> out = new LinkedHashSet<>();
            if (value instanceof Fields fields) out.addAll(fields.names());
            else if (value instanceof Choice choice) choice.arms().forEach(arm -> out.addAll(fieldsOf(arm)));
            return out;
        }

    }

    /**
     * One linear walk of a method body, telling a listener every call made and every field stored with
     * the values each was handed.
     *
     * <p>The chassis models the literals, the locals, the arithmetic, {@code dup}, {@code pop} and
     * {@code swap}; this models the rest of the stack - fields, calls, allocations, arrays, and
     * {@code dup_x1}, {@code dup_x2}, {@code dup2}, {@code dup2_x1}, {@code dup2_x2} and {@code pop2} in
     * every form the JVM specification gives each, the form told by which of the values are wide - so the
     * operands at a call are the values pushed for them however each was built. Control flow is walked
     * straight through: a forward {@code goto} remembers the stack depth it leaves, and where the label it
     * jumps to finds the stack deeper the two arms' values are taken as one {@link Operand.Choice}.
     */
    static final class Operands {

        /** What a walk tells its caller. */
        interface Listener {

            /**
             * A call, with what it was handed.
             *
             * @param call the call
             * @param receiver the object it is made on, or {@code null} for a static call
             * @param arguments the values pushed for its parameters, in declaration order
             */
            default void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) { }

            /**
             * A field stored on an object.
             *
             * @param put the store
             * @param receiver the object stored on
             * @param value the value stored
             */
            default void put(@NotNull FieldInsnNode put, @NotNull Operand receiver, @NotNull Operand value) { }

        }

        private static final @NotNull Interpreter.Domain<Operand> DOMAIN = new OperandDomain();

        private final @NotNull Interpreter<Operand> machine;
        private final @NotNull Listener listener;
        private final @NotNull Map<LabelNode, Integer> forward = new HashMap<>();
        private final @NotNull Set<LabelNode> passed = new HashSet<>();

        private Operands(@NotNull Interpreter<Operand> machine, @NotNull Listener listener) {
            this.machine = machine;
            this.listener = listener;
        }

        /**
         * Walks one method body, its entry frame holding the receiver and each parameter.
         *
         * @param method the body walked
         * @param listener what is told each call and store
         */
        static void run(@NotNull MethodNode method, @NotNull Listener listener) {
            Interpreter<Operand> machine = Interpreter.of(DOMAIN, Interpreter.OnUnknown.SILENT, Interpreter.Width.BY_OPERANDS);
            int slot = 0;
            if ((method.access & Opcodes.ACC_STATIC) == 0) machine.store(slot++, Operand.Self.THIS);
            Type[] parameters = Type.getArgumentTypes(method.desc);
            for (int index = 0; index < parameters.length; index++) {
                machine.store(slot, new Operand.Parameter(index, parameters[index].getSize() == 2));
                slot += parameters[index].getSize();
            }
            Operands operands = new Operands(machine, listener);
            AsmWalker.over(method).drive(machine).forEach(operands::after);
        }

        /** Applies what the chassis left undone for one instruction it has already stepped. */
        private void after(@NotNull AbstractInsnNode node) {
            if (node instanceof LabelNode label) {
                meet(label);
                return;
            }
            int opcode = node.getOpcode();
            switch (opcode) {
                case Opcodes.GOTO -> {
                    LabelNode target = ((JumpInsnNode) node).label;
                    if (!this.passed.contains(target)) this.forward.merge(target, this.machine.size(), Math::min);
                }
                case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.RETURN,
                    Opcodes.ATHROW -> clear();
                case Opcodes.POP2 -> {
                    if (!this.machine.pop().wide()) this.machine.pop();
                }
                case Opcodes.DUP_X1 -> {
                    Operand top = this.machine.pop();
                    Operand under = this.machine.pop();
                    push(top, under, top);
                }
                case Opcodes.DUP_X2 -> duplicateDown();
                case Opcodes.DUP2 -> {
                    Operand top = this.machine.pop();
                    if (top.wide()) push(top, top);
                    else {
                        Operand under = this.machine.pop();
                        push(under, top, under, top);
                    }
                }
                case Opcodes.DUP2_X1 -> {
                    Operand top = this.machine.pop();
                    if (top.wide()) {
                        Operand under = this.machine.pop();
                        push(top, under, top);
                    } else {
                        Operand second = this.machine.pop();
                        Operand third = this.machine.pop();
                        push(second, top, third, second, top);
                    }
                }
                case Opcodes.DUP2_X2 -> duplicatePairDown();
                case Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> replace(2, Operand.UNKNOWN);
                case Opcodes.LALOAD, Opcodes.DALOAD -> replace(2, Operand.WIDE);
                case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE,
                    Opcodes.CASTORE, Opcodes.SASTORE -> drop(3);
                case Opcodes.GETSTATIC -> this.machine.push(opaque(((FieldInsnNode) node).desc));
                case Opcodes.PUTSTATIC, Opcodes.MONITORENTER, Opcodes.MONITOREXIT, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> drop(1);
                case Opcodes.GETFIELD -> {
                    FieldInsnNode read = (FieldInsnNode) node;
                    this.machine.pop();
                    this.machine.push(new Operand.Fields(Set.of(read.name), Type.getType(read.desc).getSize() == 2));
                }
                case Opcodes.PUTFIELD -> {
                    Operand value = this.machine.pop();
                    Operand receiver = this.machine.pop();
                    this.listener.put((FieldInsnNode) node, receiver, value);
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> invoke((MethodInsnNode) node);
                case Opcodes.INVOKEDYNAMIC -> {
                    InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) node;
                    drop(Type.getArgumentTypes(indy.desc).length);
                    this.machine.push(opaqueReturn(indy.desc));
                }
                case Opcodes.NEW -> this.machine.push(new Operand.Fresh((TypeInsnNode) node));
                case Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.ARRAYLENGTH, Opcodes.INSTANCEOF -> replace(1, Operand.UNKNOWN);
                case Opcodes.MULTIANEWARRAY -> replace(((MultiANewArrayInsnNode) node).dims, Operand.UNKNOWN);
                default -> { }
            }
        }

        /** A call: its arguments and receiver off the stack, the listener told, the result pushed. */
        private void invoke(@NotNull MethodInsnNode call) {
            List<Operand> arguments = this.machine.popArguments(Type.getArgumentTypes(call.desc).length);
            Operand receiver = call.getOpcode() == Opcodes.INVOKESTATIC ? null : this.machine.pop();
            this.listener.call(call, receiver, arguments);
            if (Type.getReturnType(call.desc).getSort() != Type.VOID) this.machine.push(opaqueReturn(call.desc));
        }

        /**
         * Where a forward {@code goto} lands: the values its arm left meet the ones the fall-through arm
         * left, and are taken as one choice between them.
         */
        private void meet(@NotNull LabelNode label) {
            this.passed.add(label);
            Integer left = this.forward.remove(label);
            if (left == null || left < 1 || this.machine.size() <= left) return;
            int arms = this.machine.size() - left + 1;
            Deque<Operand> taken = new ArrayDeque<>();
            for (int index = 0; index < arms; index++) taken.push(this.machine.pop());
            List<Operand> flat = new ArrayList<>();
            for (Operand arm : taken) {
                if (arm instanceof Operand.Choice choice) flat.addAll(choice.arms());
                else flat.add(arm);
            }
            this.machine.push(new Operand.Choice(List.copyOf(flat)));
        }

        /** {@code dup_x2}: the top copied under the one or two slots beneath it. */
        private void duplicateDown() {
            Operand top = this.machine.pop();
            Operand under = this.machine.pop();
            if (under.wide()) push(top, under, top);
            else {
                Operand third = this.machine.pop();
                push(top, third, under, top);
            }
        }

        /** {@code dup2_x2}: the top two slots copied under the two slots beneath them, each pair one wide value or two narrow ones. */
        private void duplicatePairDown() {
            List<Operand> pair = popTwoSlots();
            List<Operand> under = popTwoSlots();
            for (List<Operand> values : List.of(pair, under, pair)) values.forEach(this.machine::push);
        }

        /** The values filling the top two slots, popped and answered bottom first. */
        private @NotNull List<Operand> popTwoSlots() {
            Operand top = this.machine.pop();
            return top.wide() ? List.of(top) : List.of(this.machine.pop(), top);
        }

        private void push(@NotNull Operand @NotNull ... values) {
            for (Operand value : values) this.machine.push(value);
        }

        private void drop(int count) {
            for (int index = 0; index < count; index++) this.machine.pop();
        }

        private void replace(int count, @NotNull Operand value) {
            drop(count);
            this.machine.push(value);
        }

        private void clear() {
            while (!this.machine.isEmpty()) this.machine.pop();
        }

        /** What an unmodelled read of a field of this descriptor pushes. */
        private static @NotNull Operand opaque(@NotNull String desc) {
            return Type.getType(desc).getSize() == 2 ? Operand.WIDE : Operand.UNKNOWN;
        }

        /** What an unmodelled call returning this descriptor's type pushes. */
        private static @NotNull Operand opaqueReturn(@NotNull String desc) {
            return Type.getReturnType(desc).getSize() == 2 ? Operand.WIDE : Operand.UNKNOWN;
        }

    }

    /**
     * The value model the chassis steps: a numeric push is a literal, any other constant is opaque, and
     * arithmetic carries the fields its operands were read off and nothing else.
     */
    private static final class OperandDomain implements Interpreter.Domain<Operand> {

        @Override
        public @Nullable Operand decode(@NotNull AbstractInsnNode node) {
            Integer whole = AsmWalker.intLiteral(node);
            if (whole != null) return new Operand.Literal(whole);
            Float single = AsmWalker.floatLiteral(node);
            if (single != null) return new Operand.Literal(single);
            Double wide = AsmWalker.doubleLiteral(node);
            if (wide != null) return new Operand.Literal(wide);
            Long large = AsmWalker.longLiteral(node);
            if (large != null) return new Operand.Literal(large);
            if (node.getOpcode() == Opcodes.ACONST_NULL || node instanceof LdcInsnNode) return Operand.UNKNOWN;
            return null;
        }

        @Override
        public @NotNull Operand unknown() {
            return Operand.UNKNOWN;
        }

        @Override
        public @NotNull Operand underflow() {
            return Operand.UNKNOWN;
        }

        @Override
        public @NotNull Operand binary(int opcode, @NotNull Operand left, @NotNull Operand right) {
            Set<String> fields = new LinkedHashSet<>(Operand.fieldsOf(left));
            fields.addAll(Operand.fieldsOf(right));
            boolean wide = wideResult(opcode);
            return fields.isEmpty() ? new Operand.Opaque(wide) : new Operand.Fields(Set.copyOf(fields), wide);
        }

        @Override
        public @NotNull Operand unary(int opcode, @NotNull Operand operand) {
            boolean wide = wideResult(opcode);
            if (operand instanceof Operand.Literal literal) {
                Number value = literal.value();
                Number converted = switch (opcode) {
                    case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> value.longValue();
                    case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> value.floatValue();
                    case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> value.doubleValue();
                    case Opcodes.L2I, Opcodes.F2I, Opcodes.D2I -> value.intValue();
                    default -> null;
                };
                if (converted != null) return new Operand.Literal(converted);
            }
            Set<String> fields = Operand.fieldsOf(operand);
            return fields.isEmpty() ? new Operand.Opaque(wide) : new Operand.Fields(Set.copyOf(fields), wide);
        }

        /** Whether an arithmetic or conversion opcode leaves a {@code long} or a {@code double}. */
        private static boolean wideResult(int opcode) {
            if (opcode >= Opcodes.IADD && opcode <= Opcodes.DNEG) return (opcode - Opcodes.IADD) % 2 == 1;
            if (opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR) return (opcode - Opcodes.ISHL) % 2 == 1;
            return switch (opcode) {
                case Opcodes.I2L, Opcodes.I2D, Opcodes.L2D, Opcodes.F2L, Opcodes.F2D, Opcodes.D2L -> true;
                default -> false;
            };
        }

    }

}
