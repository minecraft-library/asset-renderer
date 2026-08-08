package lib.minecraft.renderer.tooling.walk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Units for the traced advance - the tracer-driven cursor, the identity visited set, and
 * geometry composed under a tracer.
 */
@DisplayName("traced advance - jump following, the cycle guard, geometry under a tracer")
class WalkTraceTest {

    /**
     * Takes a jump's label; steps linearly otherwise.
     */
    private static final Tracer FOLLOW_JUMPS = current ->
        current instanceof JumpInsnNode jump ? jump.label : current.getNext();

    private static MethodNode method(AbstractInsnNode... nodes) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        for (AbstractInsnNode node : nodes) method.instructions.add(node);
        return method;
    }

    @Test
    @DisplayName("a tracer following a GOTO's label visits the jump target next")
    void gotoVisitsTarget() {
        LabelNode target = new LabelNode();
        LdcInsnNode a = new LdcInsnNode("a");
        JumpInsnNode jump = new JumpInsnNode(Opcodes.GOTO, target);
        LdcInsnNode skipped = new LdcInsnNode("skipped");
        LdcInsnNode b = new LdcInsnNode("b");
        InsnNode ret = new InsnNode(Opcodes.RETURN);
        MethodNode m = method(a, jump, skipped, target, b, ret);
        List<AbstractInsnNode> visits = new ArrayList<>();
        AsmWalker.over(m).on(Insn.ofType(AbstractInsnNode.class), visits::add).trace(FOLLOW_JUMPS);
        assertEquals(List.of(a, jump, target, b, ret), visits, "the jump target follows the GOTO; the fall-through node is never offered");
    }

    @Test
    @DisplayName("a tracer answering an already-visited node ends the walk with CYCLE")
    void revisitEndsCycle() {
        LabelNode top = new LabelNode();
        LdcInsnNode a = new LdcInsnNode("a");
        JumpInsnNode loop = new JumpInsnNode(Opcodes.GOTO, top);
        MethodNode m = method(top, a, loop);
        List<AbstractInsnNode> visits = new ArrayList<>();
        Exit exit = AsmWalker.over(m).on(Insn.ofType(AbstractInsnNode.class), visits::add).trace(FOLLOW_JUMPS);
        assertEquals(Exit.CYCLE, exit);
        assertEquals(List.of(top, a, loop), visits, "each node offered once - the guard stopped the walk before a second lap");
    }

    @Test
    @DisplayName("a tracer that jumps past an identity sentinel keeps the walk going")
    void skippedSentinelKeepsWalking() {
        LabelNode target = new LabelNode();
        LdcInsnNode a = new LdcInsnNode("a");
        JumpInsnNode jump = new JumpInsnNode(Opcodes.GOTO, target);
        LdcInsnNode sentinel = new LdcInsnNode("sentinel");
        LdcInsnNode b = new LdcInsnNode("b");
        InsnNode ret = new InsnNode(Opcodes.RETURN);
        MethodNode m = method(a, jump, sentinel, target, b, ret);
        List<AbstractInsnNode> visits = new ArrayList<>();
        Exit exit = AsmWalker.over(m).until(sentinel).on(Insn.ofType(AbstractInsnNode.class), visits::add).trace(FOLLOW_JUMPS);
        assertEquals(List.of(a, jump, target, b, ret), visits, "the sentinel binds at the cursor - the skipped node never fires the bound");
        // a traced walk that runs off the list ends by its tracer's null answer - HALTED, where
        // the linear walk's run-off-the-end would answer END
        assertEquals(Exit.HALTED, exit);
    }

    @Test
    @DisplayName("the identity sentinel stops the traced walk when the cursor lands on it")
    void cursorOnSentinelStops() {
        LabelNode target = new LabelNode();
        LdcInsnNode a = new LdcInsnNode("a");
        JumpInsnNode jump = new JumpInsnNode(Opcodes.GOTO, target);
        LdcInsnNode sentinel = new LdcInsnNode("sentinel");
        LdcInsnNode b = new LdcInsnNode("b");
        InsnNode ret = new InsnNode(Opcodes.RETURN);
        MethodNode m = method(a, jump, sentinel, target, b, ret);
        List<AbstractInsnNode> visits = new ArrayList<>();
        Exit exit = AsmWalker.over(m).until(sentinel).on(Insn.ofType(AbstractInsnNode.class), visits::add)
            .trace(AbstractInsnNode::getNext);
        assertEquals(Exit.SENTINEL, exit);
        assertEquals(List.of(a, jump), visits, "the stop is exclusive - the sentinel itself is never offered");
    }

    @Test
    @DisplayName("traceFirst short-circuits at the first non-null probe answer")
    void traceFirstShortCircuits() {
        MethodNode m = method(new LdcInsnNode("a"), new LdcInsnNode("b"), new LdcInsnNode("c"), new InsnNode(Opcodes.RETURN));
        int[] probes = {0};
        String answer = AsmWalker.over(m).traceFirst(node -> {
            probes[0]++;
            String literal = AsmWalker.stringLiteral(node);
            return "b".equals(literal) ? literal : null;
        }, AbstractInsnNode::getNext);
        assertEquals("b", answer);
        assertEquals(2, probes[0], "the probe never sees the yields past its first non-null answer");
    }

    @Test
    @DisplayName("traceFirst answers null when every probe declines")
    void traceFirstAllDecline() {
        MethodNode m = method(new LdcInsnNode("a"), new LdcInsnNode("b"), new LdcInsnNode("c"), new InsnNode(Opcodes.RETURN));
        int[] probes = {0};
        assertNull(AsmWalker.over(m).traceFirst(node -> {
            probes[0]++;
            return null;
        }, AbstractInsnNode::getNext));
        assertEquals(4, probes[0], "every yield was probed before the miss");
    }

    @Test
    @DisplayName("limit under a tracer answers BUDGET at the yield past the cap")
    void limitAnswersBudget() {
        LdcInsnNode a = new LdcInsnNode("a");
        LdcInsnNode b = new LdcInsnNode("b");
        LdcInsnNode c = new LdcInsnNode("c");
        MethodNode m = method(a, b, c, new InsnNode(Opcodes.RETURN));
        List<AbstractInsnNode> visits = new ArrayList<>();
        Exit exit = AsmWalker.over(m).limit(2).on(Insn.ofType(AbstractInsnNode.class), visits::add)
            .trace(AbstractInsnNode::getNext);
        assertEquals(Exit.BUDGET, exit, "the third yield arrived and the budget refused it");
        assertEquals(List.of(a, b), visits);
    }

    @Test
    @DisplayName("until(Match) under a tracer answers SENTINEL at the matching yield")
    void untilMatchAnswersSentinel() {
        LdcInsnNode a = new LdcInsnNode("a");
        LdcInsnNode b = new LdcInsnNode("b");
        MethodNode m = method(a, b, new LdcInsnNode("stop"), new InsnNode(Opcodes.RETURN));
        List<AbstractInsnNode> visits = new ArrayList<>();
        Exit exit = AsmWalker.over(m).until(Insn.of(LdcInsnNode.class, ldc -> "stop".equals(ldc.cst)))
            .on(Insn.ofType(AbstractInsnNode.class), visits::add).trace(AbstractInsnNode::getNext);
        assertEquals(Exit.SENTINEL, exit);
        assertEquals(List.of(a, b), visits, "the stop is exclusive - the matching yield is never offered");
    }

}
