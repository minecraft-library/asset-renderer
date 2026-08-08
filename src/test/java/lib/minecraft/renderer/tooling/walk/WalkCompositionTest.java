package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("walk composition - cross-family rules")
class WalkCompositionTest {

    private static MethodNode method(AbstractInsnNode... nodes) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        for (AbstractInsnNode node : nodes) method.instructions.add(node);
        return method;
    }

    @Test
    @DisplayName("tier-2 cascade under trace() - stages claim in declaration order, the tracer owns the advance")
    void tracedTierTwoCascade() {
        LabelNode target = new LabelNode();
        MethodNode m = method(
            new LdcInsnNode("before"),
            new JumpInsnNode(Opcodes.GOTO, target),
            new LdcInsnNode("skipped"),
            target,
            new LdcInsnNode("after"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/T", "SINK", "Ljava/lang/String;"),
            new InsnNode(Opcodes.RETURN));
        Cells.Window<String> strings = Cells.window(AsmWalker::stringLiteral, 4);
        List<String> ldcHook = new ArrayList<>();
        List<AbstractInsnNode> gotoHook = new ArrayList<>();
        List<String> committed = new ArrayList<>();
        Exit exit = AsmWalker.over(m)
            .feed(strings)
            .on(Insn.ofType(LdcInsnNode.class), ldc -> ldcHook.add(String.valueOf(ldc.cst)))
            .on(Insn.opcode(Opcodes.GOTO), gotoHook::add)
            .commitAt(Insn.putStatic("test/T", "SINK"), fi -> committed.addAll(strings.values()))
            .trace(current -> current instanceof JumpInsnNode jump ? jump.label : current.getNext());
        // The tracer followed the GOTO, so the commit saw the post-jump literal and never "skipped".
        assertEquals(List.of("before", "after"), committed);
        // The feed precedes the ldc hook, so every string literal is claimed before the hook can fire.
        assertTrue(ldcHook.isEmpty());
        assertEquals(1, gotoHook.size());
        assertTrue(strings.values().isEmpty(), "the commit reset cleared the fed window");
        // Past the last instruction the tracer answers null, which is the halt signal by contract.
        assertEquals(Exit.HALTED, exit);
    }

    @Test
    @DisplayName("geometry ahead of drive() throws at the terminal, before any stepping")
    void geometryRefusesDrive() {
        MethodNode m = method(new LdcInsnNode("x"), new InsnNode(Opcodes.RETURN));
        List<AbstractInsnNode> stepped = new ArrayList<>();
        Object unknown = new Object();
        Interp<Object> machine = Interp.of(new Interp.Domain<Object>() {
            @Override public Object decode(AbstractInsnNode node) {
                stepped.add(node);
                return null;
            }
            @Override public Object unknown() { return unknown; }
            @Override public Object underflow() { return unknown; }
            @Override public Object binary(int opcode, Object left, Object right) { return null; }
            @Override public Object unary(int opcode, Object operand) { return null; }
        }, Interp.OnUnknown.SILENT, Interp.Width.FLOAT_AS_FLOAT);
        // Building each chain is inert - the refusal fires at the terminal.
        AsmWalker realChain = AsmWalker.over(m).real().drive(machine);
        AsmWalker untilNodeChain = AsmWalker.over(m).until(m.instructions.getLast()).drive(machine);
        AsmWalker untilMatchChain = AsmWalker.over(m).until(Insn.opcode(Opcodes.RETURN)).drive(machine);
        AsmWalker limitedChain = AsmWalker.over(m).limit(1).drive(machine);
        assertThrows(ToolingException.class, realChain::run);
        assertThrows(ToolingException.class, untilNodeChain::run);
        assertThrows(ToolingException.class, untilMatchChain::run);
        assertThrows(ToolingException.class, limitedChain::run);
        assertTrue(stepped.isEmpty(), "the refusal precedes the loop, so the machine never stepped");
    }

    @Test
    @DisplayName("first() on a commit stream halts at the first event even when its payload is empty")
    void findHaltsAtFirstCommitEvent() {
        MethodNode m = method(
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/T", "A", "I"),
            new LdcInsnNode("v"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/T", "B", "I"),
            new InsnNode(Opcodes.RETURN));
        CommitWalk<FieldInsnNode, String> commits = AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral)
            .commitAt(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC && "test/T".equals(fi.owner));
        CommitWalk.Commit<FieldInsnNode, String> first = commits.first();
        assertNotNull(first);
        assertEquals("A", first.node().name, "the halt is at the event, not at the first non-empty payload");
        assertTrue(first.values().isEmpty());
        assertNull(first.value());
        // A probe over values walks past the empty event to the second commit.
        assertEquals("v", commits.firstNotNull(CommitWalk.Commit::value));
        assertEquals(2, commits.count());
    }

    @Test
    @DisplayName("strict() clears on labels on the raw chain where real() carries the latch to the commit")
    void strictUnderRawPseudoPolicy() {
        MethodNode m = method(
            new LdcInsnNode("key"),
            new LabelNode(),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/T", "SINK", "I"),
            new InsnNode(Opcodes.RETURN));
        Match<FieldInsnNode> sink = Insn.putStatic("test/T", "SINK");
        CommitWalk.Commit<FieldInsnNode, String> raw = AsmWalker.over(m)
            .latch(AsmWalker::stringLiteral).strict().commitAt(sink).first();
        CommitWalk.Commit<FieldInsnNode, String> real = AsmWalker.over(m)
            .real().latch(AsmWalker::stringLiteral).strict().commitAt(sink).first();
        assertNotNull(raw);
        assertNotNull(real);
        assertNull(raw.value(), "the label is a fall-through yield on the raw chain, so the strict latch cleared");
        assertEquals("key", real.value(), "real() never offers the label, so nothing fell through");
    }

    @Test
    @DisplayName("keep(2) is label-blind either way while limit(3) counts labels only raw")
    void rawRealDivergenceIsPerDiscipline() {
        MethodNode m = method(
            new InsnNode(Opcodes.ICONST_1),
            new LabelNode(),
            new InsnNode(Opcodes.ICONST_2),
            new LabelNode(),
            new InsnNode(Opcodes.ICONST_3),
            new InsnNode(Opcodes.RETURN));
        // Eviction counts pushes and a label decodes to nothing, so raw and real() agree here.
        CommitWalk.Commit<AbstractInsnNode, Integer> rawKeep = AsmWalker.over(m)
            .gather(AsmWalker::intLiteral).keep(2).commitAt(Insn.opcode(Opcodes.RETURN)).first();
        CommitWalk.Commit<AbstractInsnNode, Integer> realKeep = AsmWalker.over(m)
            .real().gather(AsmWalker::intLiteral).keep(2).commitAt(Insn.opcode(Opcodes.RETURN)).first();
        assertNotNull(rawKeep);
        assertNotNull(realKeep);
        assertEquals(List.of(2, 3), rawKeep.values());
        assertEquals(rawKeep.values(), realKeep.values());
        // The yield budget is under the pseudo policy - raw spends two of its three yields on labels.
        assertEquals(List.of(1, 2), AsmWalker.over(m).limit(3).mapNotNull(AsmWalker::intLiteral).toList());
        assertEquals(List.of(1, 2, 3), AsmWalker.over(m).real().limit(3).mapNotNull(AsmWalker::intLiteral).toList());
    }

    @Test
    @DisplayName("retain() with strict() on one cell - the buffer survives commits and clears on fall-through")
    void retainAndStrictCompose() {
        MethodNode m = method(
            new LdcInsnNode("a"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/T", "A", "I"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/T", "B", "I"),
            new InsnNode(Opcodes.NOP),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/T", "C", "I"),
            new InsnNode(Opcodes.RETURN));
        List<List<String>> payloads = new ArrayList<>();
        Exit exit = AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral)
            .strict()
            .retain()
            .commitAt(Insn.putStatic("test/T"))
            .forEach(event -> payloads.add(event.values()));
        assertEquals(Exit.END, exit);
        // Retain carries the buffer through both commits; the unconsumed NOP is the strict clear.
        assertEquals(List.of(List.of("a"), List.of("a"), List.of()), payloads);
    }

}
