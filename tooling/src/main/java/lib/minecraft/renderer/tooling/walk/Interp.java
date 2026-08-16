package lib.minecraft.renderer.tooling.walk;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one interpreter chassis: an operand stack, a slot table and the arithmetic mechanism,
 * parameterised by a {@link Domain} that owns the values. The chassis owns pop order - the
 * first pop is the right operand, and argument lists fill in reverse - the divide-by-zero
 * guard, width crossing, capacity eviction with its one-shot overflow warning - automatic at
 * first eviction, or site-owned through {@link #willOverflow} and {@link #overflowWarnOnce}
 * when the warning discipline counts only some pushes - and the typed pop family's exact
 * consume-or-leave rules. Depth reads ({@link #size}, {@link #isEmpty}) are non-destructive,
 * a slot unbinds by {@link #removeSlot removal} rather than a stored placeholder, and a slot
 * frame gives an inlined callee fresh locals over the same stack. The domain owns what values
 * mean; the walk site owns which calls, fields and returns matter to its flow.
 *
 * @param <V> the interpreted value type
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Interp<V> {

    /**
     * A flow's value model - the literal reader, the placeholder for what cannot be modelled,
     * the pop-on-empty answer, and the arithmetic.
     *
     * @param <V> the interpreted value type
     */
    public interface Domain<V> {

        /**
         * Decodes this flow's literal from an instruction.
         *
         * @param node the instruction to decode
         * @return the decoded value, or {@code null} when the node pushes no modelled literal
         */
        @Nullable V decode(@NotNull AbstractInsnNode node);

        /**
         * The canonical placeholder for an unmodellable value. Answered identically on every
         * call - the chassis recognises it by identity.
         *
         * @return the placeholder value
         */
        @NotNull V unknown();

        /**
         * The pop-on-empty answer. A domain that treats underflow as a hard fault throws here.
         *
         * @return the value an empty pop answers
         */
        @NotNull V underflow();

        /**
         * Applies a binary opcode. The chassis has already popped right-then-left and guarded
         * division by zero, so both operands arrive live.
         *
         * @param opcode the JVM opcode
         * @param left the left operand
         * @param right the right operand
         * @return the result, or {@code null} when the domain does not model the operation
         */
        @Nullable V binary(int opcode, @NotNull V left, @NotNull V right);

        /**
         * Applies a unary opcode - a negation or a width conversion.
         *
         * @param opcode the JVM opcode
         * @param operand the operand
         * @return the result, or {@code null} when the domain does not model the operation
         */
        @Nullable V unary(int opcode, @NotNull V operand);

    }

    /** What the machine does when it meets a value it cannot model. */
    public enum OnUnknown { SILENT, ZERO, POISON, THROW }

    /** How float arithmetic is carried - as floats, widened to doubles, or decided per operand. */
    public enum Width { FLOAT_AS_FLOAT, FLOAT_AS_DOUBLE, BY_OPERANDS }

    private final @NotNull Domain<V> domain;
    private final @NotNull OnUnknown onUnknown;

    /** The float-arithmetic carriage this machine runs under. */
    @Getter(style = NamingStyle.FLUENT)
    private final @NotNull Width width;

    private final @NotNull Deque<V> stack = new ArrayDeque<>();
    private final @NotNull Map<Integer, V> slots = new LinkedHashMap<>();
    private final @NotNull Deque<Map<Integer, V>> slotFrames = new ArrayDeque<>();
    private int capacity = -1;
    private @Nullable Runnable overflowWarning;
    private boolean @NotNull [] overflowWarned = {false};

    /** The remaining inline-frame budget. */
    @Getter(style = NamingStyle.FLUENT)
    private int depth = Integer.MAX_VALUE;

    /** Whether the machine has met something it refuses to reason past. */
    @Getter(style = NamingStyle.FLUENT)
    private boolean poisoned;

    /**
     * Creates a machine over a domain.
     *
     * @param domain the value model
     * @param onUnknown the unmodellable-value policy
     * @param width the float-arithmetic carriage
     * @return the machine
     */
    public static <V> @NotNull Interp<V> of(@NotNull Domain<V> domain, @NotNull OnUnknown onUnknown, @NotNull Width width) {
        return new Interp<>(domain, onUnknown, width);
    }

    /**
     * Bounds the operand stack: a push past the bound evicts the oldest entry, and the first
     * eviction fires the overflow warning once for the machine and all its children.
     *
     * @param bound the retention capacity
     * @return this machine
     */
    public @NotNull Interp<V> capacity(int bound) {
        this.capacity = bound;
        return this;
    }

    /**
     * Installs the one-shot overflow warning - the site supplies the text and the sink, the
     * chassis owns the once-only firing.
     *
     * @param warning the warning emission
     * @return this machine
     */
    public @NotNull Interp<V> overflowWarning(@NotNull Runnable warning) {
        this.overflowWarning = warning;
        return this;
    }

    /**
     * A fresh frame for an inlined callee: it inherits the domain, the unknown policy and the
     * width - nothing else. Stack, slots and poison start clean; a negative budget answers a
     * frame born poisoned, so an over-deep inline chain resolves nothing instead of recursing.
     *
     * @param depthBudget the child's remaining inline budget
     * @return the child frame
     */
    public @NotNull Interp<V> child(int depthBudget) {
        Interp<V> sub = new Interp<>(this.domain, this.onUnknown, this.width);
        sub.capacity = this.capacity;
        sub.overflowWarning = this.overflowWarning;
        sub.overflowWarned = this.overflowWarned;
        sub.depth = depthBudget;
        if (depthBudget < 0) sub.poisoned = true;
        return sub;
    }

    // ------------------------------------------------------------------------------------
    // the machine view - what step functions and commits read
    // ------------------------------------------------------------------------------------

    /**
     * Pushes a value, evicting the oldest entry past the capacity bound.
     *
     * @param value the value to push
     */
    public void push(@NotNull V value) {
        this.stack.addLast(value);
        if (this.capacity >= 0 && this.stack.size() > this.capacity) {
            this.stack.removeFirst();
            if (this.overflowWarning != null && !this.overflowWarned[0]) {
                this.overflowWarned[0] = true;
                this.overflowWarning.run();
            }
        }
    }

    /**
     * Whether the next push would evict - the overflow event read before the push, so a site
     * whose warning discipline counts only some pushes can own the event while the chassis
     * keeps the once-only firing through {@link #overflowWarnOnce}.
     *
     * @return {@code true} when the stack stands at a set capacity bound
     */
    public boolean willOverflow() {
        return this.capacity >= 0 && this.stack.size() >= this.capacity;
    }

    /**
     * Fires {@code warning} once for the machine and all its children - the site-owned arm of
     * the one-shot overflow warning, for a discipline that warns on a narrower event than
     * any-push eviction. The once-latch is shared with the automatic arm; a machine with no
     * installed warning evicts silently, leaving the event wholly site-owned.
     *
     * @param warning the warning emission
     */
    public void overflowWarnOnce(@NotNull Runnable warning) {
        if (this.overflowWarned[0]) return;
        this.overflowWarned[0] = true;
        warning.run();
    }

    /**
     * Pops the top of the stack; an empty stack answers the domain's underflow, which may
     * throw.
     *
     * @return the popped value
     */
    public @NotNull V pop() {
        if (this.stack.isEmpty()) return this.domain.underflow();
        return this.stack.removeLast();
    }

    /**
     * The top of the stack without consuming it; an empty stack answers the domain's
     * underflow.
     *
     * @return the top value
     */
    public @NotNull V peek() {
        if (this.stack.isEmpty()) return this.domain.underflow();
        return this.stack.getLast();
    }

    /** The current operand-stack depth - a non-destructive read for guards that skip without consuming. */
    public int size() {
        return this.stack.size();
    }

    /** Whether the operand stack is empty. */
    public boolean isEmpty() {
        return this.stack.isEmpty();
    }

    /**
     * Pops the top only when it is of the given type; a wrong-type top is left in place and
     * an empty stack answers {@code null}, so a caller hunting one type never silently
     * consumes another.
     *
     * @param type the required value type
     * @return the popped value, or {@code null} on an empty stack or a wrong-type top
     */
    public @Nullable V popTyped(@NotNull Class<?> type) {
        if (this.stack.isEmpty()) return null;
        V top = this.stack.getLast();
        if (!type.isInstance(top)) return null;
        return this.stack.removeLast();
    }

    /**
     * Pops the top unconditionally - the operand is consumed even when the machine cannot
     * evaluate it - answering {@code null} for the unknown placeholder so a computed value
     * never satisfies a literal test.
     *
     * @return the popped value, or {@code null} on an empty stack or an unknown top
     */
    public @Nullable V popLiteral() {
        if (this.stack.isEmpty()) return null;
        V top = this.stack.removeLast();
        if (top == this.domain.unknown()) return null;
        return top;
    }

    /**
     * Pops the last {@code arity} values into argument order - the first pop is the last
     * argument.
     *
     * @param arity the argument count
     * @return the arguments in declaration order
     */
    public @NotNull List<V> popArguments(int arity) {
        List<V> filled = new ArrayList<>(arity);
        for (int i = 0; i < arity; i++) filled.add(null);
        for (int i = arity - 1; i >= 0; i--) filled.set(i, pop());
        return filled;
    }

    /**
     * The value bound to a local-variable slot, or {@code null} when unbound.
     *
     * @param var the slot index
     * @return the bound value, or {@code null}
     */
    public @Nullable V slot(int var) {
        return this.slots.get(var);
    }

    /**
     * Binds a value to a local-variable slot. The unknown placeholder binds like any value,
     * so a load of a computed store answers unknown rather than unbound.
     *
     * @param var the slot index
     * @param value the value to bind
     */
    public void store(int var, @NotNull V value) {
        this.slots.put(var, value);
    }

    /**
     * Unbinds a local-variable slot - removal, not a stored placeholder: an unbound slot
     * answers {@code null} where a stored unknown answers the placeholder, and the two route
     * a load differently at every site that falls back past the slot table.
     *
     * @param var the slot index
     */
    public void removeSlot(int var) {
        this.slots.remove(var);
    }

    /**
     * Opens a fresh slot frame over the same stack - an inlined callee's locals start unbound
     * while the operand stack, the capacity bound, the shared overflow latch and the poison
     * state stay this machine's own. Frames nest; {@link #closeSlotFrame} restores the
     * caller's bindings untouched.
     */
    public void openSlotFrame() {
        this.slotFrames.addLast(new LinkedHashMap<>(this.slots));
        this.slots.clear();
    }

    /**
     * Closes the innermost slot frame, restoring the caller's bindings exactly as
     * {@link #openSlotFrame} saved them.
     *
     * @throws ToolingException when no frame is open
     */
    public void closeSlotFrame() {
        Map<Integer, V> saved = this.slotFrames.pollLast();
        if (saved == null) throw new ToolingException("closeSlotFrame without an open frame");
        this.slots.clear();
        this.slots.putAll(saved);
    }

    /** Marks the machine unusable - every later step is a no-op. */
    public void poison() {
        this.poisoned = true;
    }

    // ------------------------------------------------------------------------------------
    // the zero-defaulting pops - the builder-dispatch reads
    // ------------------------------------------------------------------------------------

    /**
     * Pops an {@code int} silently: {@code 0} on an empty stack or a non-{@code Integer} top,
     * the wrong-type top left in place - for callers that treat an absent operand as a benign
     * zero.
     *
     * @return the popped int, or {@code 0}
     */
    public int popIntOrZero() {
        Object value = popTyped(Integer.class);
        return value != null ? (Integer) value : 0;
    }

    /**
     * Pops an {@code int} with attribution: an empty stack is a silent zero; the unknown
     * placeholder and a wrong-type top are consumed, resolved to zero and recorded so an
     * accounting gap never masquerades as a coordinate of {@code 0}.
     *
     * @param diagnostics the diagnostic sink
     * @param contextPrefix a short label prepended to the warnings, typically the subject id
     * @param popSite a short label identifying the caller's pop site
     * @return the popped int, or {@code 0} on empty, unknown or wrong-type
     */
    public int popIntOrZero(@NotNull Diagnostics diagnostics, @NotNull String contextPrefix, @NotNull String popSite) {
        if (this.stack.isEmpty()) return 0;
        V top = this.stack.removeLast();
        if (top == this.domain.unknown()) {
            diagnostics.warn(
                "%s at %s: non-literal argument consumed - a local variable populated from a computation, resolved to 0",
                contextPrefix, popSite
            );
            return 0;
        }
        if (top instanceof Integer value) return value;
        if (top instanceof Number number) return number.intValue();
        diagnostics.warn(
            "%s at %s: type mismatch popping int (found %s), resolved to 0",
            contextPrefix, popSite, top.getClass().getSimpleName()
        );
        return 0;
    }

    /**
     * The {@code float} counterpart of {@link #popIntOrZero(Diagnostics, String, String)}.
     *
     * @param diagnostics the diagnostic sink
     * @param contextPrefix a short label prepended to the warnings
     * @param popSite a short label identifying the caller's pop site
     * @return the popped float, or {@code 0f} on empty, unknown or wrong-type
     */
    public float popFloatOrZero(@NotNull Diagnostics diagnostics, @NotNull String contextPrefix, @NotNull String popSite) {
        if (this.stack.isEmpty()) return 0f;
        V top = this.stack.removeLast();
        if (top == this.domain.unknown()) {
            diagnostics.warn(
                "%s at %s: non-literal argument consumed - a local variable populated from a computation, resolved to 0",
                contextPrefix, popSite
            );
            return 0f;
        }
        if (top instanceof Float value) return value;
        if (top instanceof Number number) return number.floatValue();
        diagnostics.warn(
            "%s at %s: type mismatch popping float (found %s), resolved to 0",
            contextPrefix, popSite, top.getClass().getSimpleName()
        );
        return 0f;
    }

    // ------------------------------------------------------------------------------------
    // stepping - the linear stack and slot core
    // ------------------------------------------------------------------------------------

    /**
     * Applies one instruction's stack and slot effect and answers the jump target when a
     * branch evaluates taken. Literal pushes come off the domain's reader; binary operations
     * pop right-then-left behind the divide-by-zero guard; stores park the popped value -
     * unknown included - in the slot table. Opcodes the chassis does not model have no effect
     * here: recognition stays with the walk site.
     *
     * @param in the instruction to step
     * @return the jump target when control diverts, else {@code null}
     */
    public @Nullable AbstractInsnNode step(@NotNull AbstractInsnNode in) {
        if (this.poisoned) return null;
        int op = in.getOpcode();
        if (op < 0) return null;
        V literal = this.domain.decode(in);
        if (literal != null) {
            push(literal);
            return null;
        }
        if (op == Opcodes.DUP) push(peek());
        else if (op == Opcodes.POP) pop();
        else if (op == Opcodes.SWAP) {
            V top = pop();
            V under = pop();
            push(top);
            push(under);
        } else if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE) this.slots.put(((VarInsnNode) in).var, pop());
        else if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
            V held = this.slots.get(((VarInsnNode) in).var);
            push(held != null ? held : this.domain.unknown());
        } else if (op == Opcodes.GOTO) return ((JumpInsnNode) in).label;
        else if (op >= Opcodes.IADD && op <= Opcodes.LXOR && !(op >= Opcodes.INEG && op <= Opcodes.DNEG)) applyBinary(op);
        else if (op >= Opcodes.INEG && op <= Opcodes.DNEG || op >= Opcodes.I2L && op <= Opcodes.I2S) applyUnary(op);
        else if (op >= Opcodes.LCMP && op <= Opcodes.DCMPG) applyBinary(op);
        else if (op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL)
            return branch((JumpInsnNode) in, op);
        return null;
    }

    /** The first pop is the right operand; division and remainder by zero - a negative zero included - answer the result-typed zero. */
    private void applyBinary(int op) {
        V right = pop();
        V left = pop();
        if (isDivRem(op) && right instanceof Number divisor && right != this.domain.unknown() && divisor.doubleValue() == 0.0) {
            push(zeroFor(op));
            return;
        }
        V result = this.domain.binary(op, left, right);
        if (result != null) push(result);
        else unmodelled();
    }

    private void applyUnary(int op) {
        V operand = pop();
        V result = this.domain.unary(op, operand);
        if (result != null) push(result);
        else unmodelled();
    }

    private static boolean isDivRem(int op) {
        return op >= Opcodes.IDIV && op <= Opcodes.DDIV || op >= Opcodes.IREM && op <= Opcodes.DREM;
    }

    /** The result-typed zero of a guarded division, widened per the machine's float carriage. */
    @SuppressWarnings("unchecked")
    private @NotNull V zeroFor(int op) {
        Number zero = switch (op) {
            case Opcodes.IDIV, Opcodes.IREM -> 0;
            case Opcodes.LDIV, Opcodes.LREM -> 0L;
            case Opcodes.FDIV, Opcodes.FREM -> this.width == Width.FLOAT_AS_DOUBLE ? (Number) 0.0d : (Number) 0f;
            default -> 0.0d;
        };
        return (V) zero;
    }

    private void unmodelled() {
        switch (this.onUnknown) {
            case THROW -> throw new ToolingException("Interpreter met an unmodelled operation under a throwing policy");
            case POISON -> poison();
            case SILENT, ZERO -> push(this.domain.unknown());
        }
    }

    private @Nullable AbstractInsnNode branch(@NotNull JumpInsnNode jump, int op) {
        if (op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ICMPLE) {
            Integer right = literalInt(pop());
            Integer left = literalInt(pop());
            return left != null && right != null && evaluateIntComparison(op, left, right) ? jump.label : null;
        }
        if (op >= Opcodes.IFEQ && op <= Opcodes.IFLE) {
            Integer value = literalInt(pop());
            return value != null && evaluateIntComparison(op, value, 0) ? jump.label : null;
        }
        if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) {
            pop();
            pop();
            return null;
        }
        pop();
        return null;
    }

    private @Nullable Integer literalInt(@NotNull V value) {
        if (value == this.domain.unknown()) return null;
        return value instanceof Number number ? number.intValue() : null;
    }

    /**
     * Evaluates a JVM integer-comparison jump opcode on concrete operands - the
     * branch-decision half of the numeric domain. Unary opcodes compare {@code lhs} against
     * zero and ignore {@code rhs}.
     *
     * @param opcode the JVM jump opcode
     * @param lhs the left operand
     * @param rhs the right operand, ignored for unary opcodes
     * @return {@code true} when the comparison takes the branch
     */
    public static boolean evaluateIntComparison(int opcode, int lhs, int rhs) {
        return switch (opcode) {
            case Opcodes.IFEQ -> lhs == 0;
            case Opcodes.IFNE -> lhs != 0;
            case Opcodes.IFLT -> lhs < 0;
            case Opcodes.IFGE -> lhs >= 0;
            case Opcodes.IFGT -> lhs > 0;
            case Opcodes.IFLE -> lhs <= 0;
            case Opcodes.IF_ICMPEQ -> lhs == rhs;
            case Opcodes.IF_ICMPNE -> lhs != rhs;
            case Opcodes.IF_ICMPLT -> lhs < rhs;
            case Opcodes.IF_ICMPGE -> lhs >= rhs;
            case Opcodes.IF_ICMPGT -> lhs > rhs;
            case Opcodes.IF_ICMPLE -> lhs <= rhs;
            default -> false;
        };
    }

}
