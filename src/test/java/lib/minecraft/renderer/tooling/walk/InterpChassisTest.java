package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Units for the {@link Interp} chassis quirks, driven directly - hand-built nodes stepped with
 * no walk attached. A recording {@link Interp.Domain} over {@code Number} does real arithmetic
 * and counts its invocations, so a chassis answer is distinguishable from a domain answer.
 */
@DisplayName("Interp chassis - pop order, zero guards, width, capacity, children, branches")
class InterpChassisTest {

    /**
     * The canonical unknown sentinel - recognised by identity, zero on every primitive face.
     */
    private static final @NotNull Number UNKNOWN = new Number() {
        @Override public int intValue() { return 0; }
        @Override public long longValue() { return 0L; }
        @Override public float floatValue() { return 0f; }
        @Override public double doubleValue() { return 0d; }
    };

    /**
     * The unknown sentinel of the untyped domain the typed-pop tests run on.
     */
    private static final @NotNull Object RAW_UNKNOWN = new Object();

    /**
     * A Number domain doing real arithmetic, recording every binary invocation.
     */
    private static final class NumberDomain implements Interp.Domain<Number> {

        int binaryCalls;
        @Nullable Number lastLeft;
        @Nullable Number lastRight;

        @Override
        public @Nullable Number decode(@NotNull AbstractInsnNode node) {
            Integer intValue = AsmWalker.intLiteral(node);
            if (intValue != null) return intValue;
            Float floatValue = AsmWalker.floatLiteral(node);
            if (floatValue != null) return floatValue;
            return AsmWalker.doubleLiteral(node);
        }

        @Override
        public @NotNull Number unknown() {
            return UNKNOWN;
        }

        @Override
        public @NotNull Number underflow() {
            return UNKNOWN;
        }

        @Override
        public @Nullable Number binary(int opcode, @NotNull Number left, @NotNull Number right) {
            this.binaryCalls++;
            this.lastLeft = left;
            this.lastRight = right;
            return switch (opcode) {
                case Opcodes.IADD -> left.intValue() + right.intValue();
                case Opcodes.ISUB -> left.intValue() - right.intValue();
                case Opcodes.IDIV -> left.intValue() / right.intValue();
                case Opcodes.FDIV -> left.floatValue() / right.floatValue();
                case Opcodes.FREM -> left.floatValue() % right.floatValue();
                case Opcodes.DDIV -> left.doubleValue() / right.doubleValue();
                default -> null;
            };
        }

        @Override
        public @Nullable Number unary(int opcode, @NotNull Number operand) {
            return opcode == Opcodes.INEG ? -operand.intValue() : null;
        }

    }

    /**
     * A decode-less domain over raw objects, so a non-Number top is pushable.
     */
    private static final class ObjectDomain implements Interp.Domain<Object> {

        @Override
        public @Nullable Object decode(@NotNull AbstractInsnNode node) {
            return null;
        }

        @Override
        public @NotNull Object unknown() {
            return RAW_UNKNOWN;
        }

        @Override
        public @NotNull Object underflow() {
            return RAW_UNKNOWN;
        }

        @Override
        public @Nullable Object binary(int opcode, @NotNull Object left, @NotNull Object right) {
            return null;
        }

        @Override
        public @Nullable Object unary(int opcode, @NotNull Object operand) {
            return null;
        }

    }

    private static Interp<Number> machine(NumberDomain domain) {
        return Interp.of(domain, Interp.OnUnknown.SILENT, Interp.Width.FLOAT_AS_FLOAT);
    }

    private static Interp<Object> rawMachine() {
        return Interp.of(new ObjectDomain(), Interp.OnUnknown.SILENT, Interp.Width.FLOAT_AS_FLOAT);
    }

    @Test
    @DisplayName("division and remainder by a literal zero push the result-typed zero without consulting the domain")
    void divRemByLiteralZeroSkipsDomain() {
        NumberDomain domain = new NumberDomain();
        Interp<Number> machine = machine(domain);
        machine.push(8);
        machine.push(0);
        assertNull(machine.step(new InsnNode(Opcodes.IDIV)));
        assertEquals(Integer.valueOf(0), machine.pop());
        machine.push(3f);
        machine.push(0f);
        machine.step(new InsnNode(Opcodes.FDIV));
        assertEquals(Float.valueOf(0f), machine.pop());
        machine.push(3f);
        machine.push(0f);
        machine.step(new InsnNode(Opcodes.FREM));
        assertEquals(Float.valueOf(0f), machine.pop());
        machine.push(3.0d);
        machine.push(0.0d);
        machine.step(new InsnNode(Opcodes.DDIV));
        assertEquals(Double.valueOf(0.0d), machine.pop());
        assertEquals(0, domain.binaryCalls, "the guard answered every zero division");
        machine.push(8);
        machine.push(2);
        machine.step(new InsnNode(Opcodes.IDIV));
        assertEquals(Integer.valueOf(4), machine.pop());
        assertEquals(1, domain.binaryCalls, "a live divisor still reaches the domain");
    }

    @Test
    @DisplayName("a negative-zero divisor decoded off an LDC is guarded like zero")
    void negativeZeroDivisorGuarded() {
        NumberDomain domain = new NumberDomain();
        Interp<Number> machine = machine(domain);
        machine.push(3f);
        machine.step(new LdcInsnNode(-0.0f));
        machine.step(new InsnNode(Opcodes.FDIV));
        assertEquals(Float.valueOf(0f), machine.pop(), "the guarded zero is the positive one");
        machine.push(3.0d);
        machine.step(new LdcInsnNode(-0.0d));
        machine.step(new InsnNode(Opcodes.DDIV));
        assertEquals(Double.valueOf(0.0d), machine.pop());
        assertEquals(0, domain.binaryCalls, "a negative-zero divisor never reaches the domain");
    }

    @Test
    @DisplayName("the first pop is the right operand")
    void popOrderRightFirst() {
        NumberDomain domain = new NumberDomain();
        Interp<Number> machine = machine(domain);
        machine.push(7);
        machine.push(3);
        assertNull(machine.step(new InsnNode(Opcodes.ISUB)));
        assertEquals(1, domain.binaryCalls);
        assertEquals(Integer.valueOf(7), domain.lastLeft);
        assertEquals(Integer.valueOf(3), domain.lastRight);
        assertEquals(Integer.valueOf(4), machine.pop());
    }

    @Test
    @DisplayName("popArguments fills in reverse, answering declaration order")
    void popArgumentsFillsReverse() {
        Interp<Number> machine = machine(new NumberDomain());
        machine.push(1);
        machine.push(2);
        machine.push(3);
        assertEquals(List.of(1, 2, 3), machine.popArguments(3));
    }

    @Test
    @DisplayName("the guarded float zero widens per the machine's float carriage")
    void guardedZeroWidth() {
        Interp<Number> widened = Interp.of(new NumberDomain(), Interp.OnUnknown.SILENT, Interp.Width.FLOAT_AS_DOUBLE);
        widened.push(1f);
        widened.push(0f);
        widened.step(new InsnNode(Opcodes.FDIV));
        assertEquals(Double.valueOf(0.0d), widened.pop(), "FLOAT_AS_DOUBLE widens the guarded zero");
        Interp<Number> narrow = Interp.of(new NumberDomain(), Interp.OnUnknown.SILENT, Interp.Width.FLOAT_AS_FLOAT);
        narrow.push(1f);
        narrow.push(0f);
        narrow.step(new InsnNode(Opcodes.FDIV));
        assertEquals(Float.valueOf(0f), narrow.pop(), "FLOAT_AS_FLOAT keeps it a Float");
    }

    @Test
    @DisplayName("popTyped leaves a wrong-type top in place and answers null on empty")
    void popTypedLeavesWrongTypeTop() {
        Interp<Number> machine = machine(new NumberDomain());
        machine.push(1.5f);
        assertNull(machine.popTyped(Integer.class), "a Float top refuses an Integer hunt");
        assertEquals(Float.valueOf(1.5f), machine.popTyped(Float.class), "and is still there for its own type");
        assertNull(machine.popTyped(Float.class), "an empty stack answers null");
    }

    @Test
    @DisplayName("popLiteral always consumes - an unknown top answers null and is gone")
    void popLiteralAlwaysConsumes() {
        Interp<Number> machine = machine(new NumberDomain());
        machine.push(5);
        machine.push(UNKNOWN);
        assertNull(machine.popLiteral(), "an unknown top answers null");
        assertEquals(Integer.valueOf(5), machine.pop(), "and was consumed all the same");
        machine.push(7);
        assertEquals(Integer.valueOf(7), machine.popLiteral());
    }

    @Test
    @DisplayName("popIntOrZero with diagnostics warns with the exact texts and coerces Numbers silently")
    void popIntOrZeroWarnTexts() {
        Diagnostics diagnostics = Diagnostics.root("test", Diagnostics.Output.NONE, null);
        Interp<Object> machine = rawMachine();
        assertEquals(0, machine.popIntOrZero(diagnostics, "ctx", "site"));
        assertTrue(diagnostics.entries().isEmpty(), "an empty stack is a silent zero");
        machine.push(RAW_UNKNOWN);
        assertEquals(0, machine.popIntOrZero(diagnostics, "ctx", "site"));
        assertEquals(1, diagnostics.entries().size());
        assertEquals(Diagnostics.Severity.WARN, diagnostics.entries().getFirst().severity());
        assertEquals(
            "ctx at site: non-literal argument consumed - a local variable populated from a computation, resolved to 0",
            diagnostics.entries().getFirst().message());
        machine.push(41);
        assertEquals(41, machine.popIntOrZero(diagnostics, "ctx", "site"));
        machine.push(3.9f);
        assertEquals(3, machine.popIntOrZero(diagnostics, "ctx", "site"), "a non-Integer Number coerces via intValue");
        assertEquals(1, diagnostics.entries().size(), "the Integer answer and the coercion stay silent");
        machine.push("nope");
        assertEquals(0, machine.popIntOrZero(diagnostics, "ctx", "site"));
        assertEquals(2, diagnostics.entries().size());
        assertEquals(Diagnostics.Severity.WARN, diagnostics.entries().getLast().severity());
        assertEquals(
            "ctx at site: type mismatch popping int (found String), resolved to 0",
            diagnostics.entries().getLast().message());
    }

    @Test
    @DisplayName("the silent popIntOrZero leaves a wrong-type top in place")
    void silentPopIntOrZeroLeavesWrongTypeTop() {
        Interp<Object> machine = rawMachine();
        machine.push("still here");
        assertEquals(0, machine.popIntOrZero());
        assertEquals("still here", machine.pop(), "the wrong-type top survived the silent zero");
        assertEquals(0, machine.popIntOrZero(), "and empty answers zero too");
    }

    @Test
    @DisplayName("a stored unknown reads back by identity and an unbound load pushes unknown")
    void unknownEntersSlotTable() {
        Interp<Number> machine = machine(new NumberDomain());
        machine.push(UNKNOWN);
        assertNull(machine.step(new VarInsnNode(Opcodes.ISTORE, 1)));
        assertSame(UNKNOWN, machine.slot(1), "the store parked the sentinel itself");
        machine.step(new VarInsnNode(Opcodes.ILOAD, 1));
        assertSame(UNKNOWN, machine.pop(), "the load answers the same sentinel");
        machine.step(new VarInsnNode(Opcodes.ILOAD, 5));
        assertSame(UNKNOWN, machine.pop(), "an unbound load pushes unknown");
        assertNull(machine.slot(5), "without binding the slot");
    }

    @Test
    @DisplayName("capacity eviction fires the overflow warning once across parent and child")
    void capacityEvictionWarnsOnce() {
        int[] fired = {0};
        Interp<Number> machine = machine(new NumberDomain()).capacity(2).overflowWarning(() -> fired[0]++);
        machine.push(1);
        machine.push(2);
        machine.push(3);
        machine.push(4);
        assertEquals(1, fired[0], "two evictions, one warning");
        assertEquals(Integer.valueOf(4), machine.pop());
        assertEquals(Integer.valueOf(3), machine.pop());
        assertNull(machine.popTyped(Integer.class), "the oldest entries were evicted");
        Interp<Number> child = machine.child(3);
        child.push(5);
        child.push(6);
        child.push(7);
        assertEquals(1, fired[0], "a child's eviction keeps the once-only");
        assertEquals(Integer.valueOf(7), child.pop());
        assertEquals(Integer.valueOf(6), child.pop());
        assertNull(child.popTyped(Integer.class), "the child inherited the bound");
    }

    @Test
    @DisplayName("child starts with fresh stack, slots and poison, inheriting domain, policy and width")
    void childFreshAndInheriting() {
        NumberDomain domain = new NumberDomain();
        Interp<Number> parent = Interp.of(domain, Interp.OnUnknown.SILENT, Interp.Width.FLOAT_AS_DOUBLE);
        parent.push(9);
        parent.store(2, 9);
        Interp<Number> child = parent.child(1);
        assertFalse(child.poisoned());
        assertEquals(1, child.depth());
        assertSame(UNKNOWN, child.pop(), "a fresh stack pops the underflow answer");
        assertNull(child.slot(2), "slots do not inherit");
        child.step(new LdcInsnNode(2.5f));
        assertEquals(Float.valueOf(2.5f), child.peek(), "the child decodes through the inherited domain");
        child.push(0f);
        child.step(new InsnNode(Opcodes.FDIV));
        assertEquals(Double.valueOf(0.0d), child.pop(), "the guarded zero widens under the inherited width");
        assertEquals(Integer.valueOf(9), parent.pop(), "the parent's stack is untouched");
    }

    @Test
    @DisplayName("a negative-budget child is born poisoned and step is a no-op on it")
    void childNegativeBudgetBornPoisoned() {
        Interp<Number> parent = machine(new NumberDomain());
        Interp<Number> poisoned = parent.child(-1);
        assertTrue(poisoned.poisoned());
        assertNull(poisoned.step(new LdcInsnNode(5)));
        assertNull(poisoned.popTyped(Integer.class), "the poisoned step pushed nothing");
        assertFalse(parent.poisoned(), "the parent stays live");
    }

    @Test
    @DisplayName("branches through step - a taken comparison answers the label, an unknown operand falls through")
    void branchesThroughStep() {
        Interp<Number> machine = machine(new NumberDomain());
        LabelNode target = new LabelNode();
        machine.push(5);
        machine.push(3);
        assertSame(target, machine.step(new JumpInsnNode(Opcodes.IF_ICMPGE, target)), "5 >= 3 takes the branch");
        machine.push(3);
        machine.push(5);
        assertNull(machine.step(new JumpInsnNode(Opcodes.IF_ICMPGE, target)), "3 >= 5 falls through");
        assertSame(target, machine.step(new JumpInsnNode(Opcodes.GOTO, target)));
        machine.push(UNKNOWN);
        machine.push(3);
        assertNull(machine.step(new JumpInsnNode(Opcodes.IF_ICMPGE, target)), "an unknown operand falls through");
    }

}
