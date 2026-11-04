package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.asm.Insn;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.interp.Cells;
import lib.minecraft.renderer.tooling.interp.Exit;
import lib.minecraft.renderer.tooling.interp.Interpreter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Governance pins for the walk package - the identities the package guarantees: one-hop
 * agreement with the statics, per-run cell minting on a reused descriptor, the bare-source
 * refusal, the comparison rows, the state-dump seam and the sibling-write memo shape.
 */
@DisplayName("walk governance - the package identities")
class WalkGovernanceTest {

    private static MethodNode body(String name, AbstractInsnNode... nodes) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, name, "()V", null, null);
        for (AbstractInsnNode node : nodes) method.instructions.add(node);
        return method;
    }

    private static MethodNode method(AbstractInsnNode... nodes) {
        return body("m", nodes);
    }

    @Test
    @DisplayName("after/before with real() agree with nextReal/previousReal, edges included")
    void oneHopIdentities() {
        LabelNode head = new LabelNode();
        LdcInsnNode lit = new LdcInsnNode("a");
        LabelNode mid = new LabelNode();
        InsnNode ret = new InsnNode(Opcodes.RETURN);
        MethodNode m = method(head, lit, mid, ret);
        for (AbstractInsnNode node : List.of(head, lit, mid, ret)) {
            assertSame(AsmWalker.nextReal(node), AsmWalker.after(node).real().first());
            assertSame(AsmWalker.previousReal(node), AsmWalker.before(node).real().first());
        }
        assertSame(ret, AsmWalker.after(lit).real().first(), "the label between is skipped");
        assertNull(AsmWalker.nextReal(ret), "no real instruction follows the tail");
        assertNull(AsmWalker.after(ret).real().first());
        assertNull(AsmWalker.previousReal(lit), "only a label precedes the first real instruction");
        assertNull(AsmWalker.before(lit).real().first());
        assertEquals(4, m.instructions.size());
    }

    @Test
    @DisplayName("one held descriptor runs its terminal twice identically")
    void descriptorRerunIdentity() {
        MethodNode m = method(
            new LdcInsnNode("id"), new LdcInsnNode("serialized"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/E", "A", "Ltest/E;"),
            new LdcInsnNode("id2"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/E", "B", "Ltest/E;"),
            new InsnNode(Opcodes.RETURN));
        CommitWalk<FieldInsnNode, String> chain = AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral)
            .commitAt(Insn.putStatic("test/E"));
        Map<String, String> firstRun = chain.toMap(fi -> fi.name, values -> String.join("+", values));
        Map<String, String> secondRun = chain.toMap(fi -> fi.name, values -> String.join("+", values));
        assertEquals(firstRun, secondRun, "cells are minted per run, so nothing leaks into the second");
        assertEquals(Map.of("A", "id+serialized", "B", "id2"), secondRun);

        Walk<String> narrowed = AsmWalker.over(m).mapNotNull(AsmWalker::stringLiteral);
        assertEquals(narrowed.toList(), narrowed.toList());
        assertEquals(List.of("id", "serialized", "id2"), narrowed.toList());
    }

    @Test
    @DisplayName("a keep window downstream of a narrowing stage is refused at run")
    void rootAttachGuard() {
        MethodNode m = method(new LdcInsnNode("a"), new InsnNode(Opcodes.RETURN));
        Descriptor foldAfterNarrow = new Descriptor(Descriptor.Source.over(m))
            .with(new Descriptor.Narrow(v -> v))
            .with(Descriptor.Fold.of(true, AsmWalker::stringLiteral).withKeep(2));
        ToolingException foldRefusal = assertThrows(ToolingException.class,
            () -> Drive.run(foldAfterNarrow, null, new Drive.Sink() {}));
        assertTrue(foldRefusal.getMessage().contains("bare source"), foldRefusal.getMessage());

        Descriptor windowAfterNarrow = new Descriptor(Descriptor.Source.over(m))
            .with(new Descriptor.Narrow(v -> v))
            .with(new Descriptor.Feed(Cells.window(AsmWalker::stringLiteral, 2)));
        ToolingException feedRefusal = assertThrows(ToolingException.class,
            () -> Drive.run(windowAfterNarrow, null, new Drive.Sink() {}));
        assertTrue(feedRefusal.getMessage().contains("bare source"), feedRefusal.getMessage());
    }

    @Test
    @DisplayName("evaluateIntComparison answers every modelled row and false off the grid")
    void intComparisonRows() {
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IFEQ, 0, 99));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IFEQ, 1, 99));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IFNE, 1, 99));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IFNE, 0, 99));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IFLT, -1, 99));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IFLT, 0, 99));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IFGE, 0, 99));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IFGE, -1, 99));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IFGT, 1, 99));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IFGT, 0, 99));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IFLE, 0, 99));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IFLE, 1, 99));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPEQ, 5, 5));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPEQ, 5, 6));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPNE, 5, 6));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPNE, 5, 5));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPLT, 4, 5));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPLT, 5, 5));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPGE, 5, 5));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPGE, 4, 5));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPGT, 6, 5));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPGT, 5, 5));
        assertTrue(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPLE, 5, 5));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.IF_ICMPLE, 6, 5));
        assertFalse(Interpreter.evaluateIntComparison(Opcodes.GOTO, 0, 0), "an unmodelled opcode answers false");
    }

    @Test
    @DisplayName("unarmed state dump answers null - no sink, no property")
    void stateDumpUnarmed() {
        String saved = System.getProperty(WalkDump.PROPERTY);
        System.clearProperty(WalkDump.PROPERTY);
        WalkDump.sink = null;
        try {
            assertNull(WalkDump.armed(), "the engine carries a single null check per event");
        } finally {
            if (saved != null) System.setProperty(WalkDump.PROPERTY, saved);
        }
    }

    @Test
    @DisplayName("armed state dump emits one line per cascade event with kind, op, stage and cells")
    void stateDumpArmed() {
        List<String> lines = new ArrayList<>();
        WalkDump.sink = lines::add;
        try {
            MethodNode m = method(
                new LdcInsnNode(1.5f),
                new FieldInsnNode(Opcodes.PUTSTATIC, "test/C", "A", "F"),
                new LdcInsnNode("x"),
                new InsnNode(Opcodes.RETURN));
            assertSame(Exit.END, AsmWalker.over(m)
                .latch(AsmWalker::floatLiteral)
                .commitAt(Insn.putStatic("test/C"))
                .forEach(event -> {}));
        } finally {
            WalkDump.sink = null;
        }
        assertEquals(5, lines.size(), "claim, commit, reset, then two fall-throughs");
        assertTrue(lines.getFirst().startsWith("claim "), lines.getFirst());
        assertTrue(lines.get(1).startsWith("commit "), lines.get(1));
        assertTrue(lines.get(2).startsWith("reset "), lines.get(2));
        assertTrue(lines.get(3).startsWith("fall-through "), lines.get(3));
        assertTrue(lines.getLast().startsWith("fall-through "), lines.getLast());
        for (String line : lines) {
            assertTrue(line.contains(" op="), line);
            assertTrue(line.contains(" stage="), line);
            assertTrue(line.contains("cells{"), line);
        }
        assertTrue(lines.getFirst().contains("latch=1.5"), "the claim line carries the cell contents");
        assertTrue(lines.get(1).contains("stage=commitAt"), lines.get(1));
        assertTrue(lines.get(2).contains("stage=commit-reset"), lines.get(2));
    }

    @Test
    @DisplayName("sibling writes in one static initialiser - canonical maps its float, broken maps a stored null")
    void siblingClinitWrites() {
        MethodNode clinit = body("<clinit>",
            new LdcInsnNode(1.5f),
            new LabelNode(),
            new MethodInsnNode(Opcodes.INVOKESTATIC, "test/Factory", "of", "(F)Ltest/Factory;", false),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/C", "A", "Ltest/Factory;"),
            new LdcInsnNode(2.5f),
            new MethodInsnNode(Opcodes.INVOKESTATIC, "test/Factory", "of", "(F)Ltest/Factory;", false),
            new LdcInsnNode(3.5f),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/C", "B", "Ltest/Factory;"),
            new InsnNode(Opcodes.RETURN));
        Map<String, Float> memo = new HashMap<>();
        Exit exit = AsmWalker.over(clinit).real()
            .latch(AsmWalker::floatLiteral)
            .strict()
            .takeAt(Insn.invokeStatic("test/Factory", "of"))
            .commitAt(Insn.putStatic("test/C"))
            .forEach(event -> {
                if (!memo.containsKey(event.node().name)) memo.put(event.node().name, event.value());
            });
        assertSame(Exit.END, exit);
        assertEquals(2, memo.size(), "both fields commit, whatever their rows held");
        assertEquals(Float.valueOf(1.5f), memo.get("A"));
        assertTrue(memo.containsKey("B"), "the broken row still writes its key");
        assertNull(memo.get("B"), "the intervening literal cleared the promoted register");
    }

}
