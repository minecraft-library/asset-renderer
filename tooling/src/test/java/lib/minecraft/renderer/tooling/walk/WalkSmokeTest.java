package lib.minecraft.renderer.tooling.walk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("walk smoke - drive sequence, owner-example shapes")
class WalkSmokeTest {

    private static MethodNode method(AbstractInsnNode... nodes) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        for (AbstractInsnNode node : nodes) method.instructions.add(node);
        return method;
    }

    @Test
    @DisplayName("over() yields the hand loop's sequence")
    void driveSequence() {
        MethodNode m = method(new LdcInsnNode("a"), new LabelNode(), new LdcInsnNode("b"), new InsnNode(Opcodes.RETURN));
        List<AbstractInsnNode> hand = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) hand.add(in);
        assertEquals(hand, AsmWalker.over(m).toList());
        assertEquals(List.of("a", "b"), AsmWalker.over(m).mapNotNull(AsmWalker::stringLiteral).toList());
    }

    @Test
    @DisplayName("gather + commitAt + toMap runs the enum-serialized-ids shape")
    void gatherCommit() {
        MethodNode m = method(
            new LdcInsnNode("id"), new LdcInsnNode("serialized"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/E", "A", "Ltest/E;"),
            new LdcInsnNode("id2"), new LdcInsnNode("serialized2"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/E", "B", "Ltest/E;"),
            new InsnNode(Opcodes.RETURN));
        Map<String, String> ids = AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral)
            .commitAt(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC && "test/E".equals(fi.owner))
            .toMap(fi -> fi.name, strings -> strings.size() >= 2 ? strings.get(1) : null);
        assertEquals(Map.of("A", "serialized", "B", "serialized2"), ids);
    }

    @Test
    @DisplayName("latch + commitOn keeps an armed latch across a null decode")
    void latchCommitOn() {
        MethodNode m = method(
            new FieldInsnNode(Opcodes.GETSTATIC, "test/K", "WHITE", "Ltest/K;"),
            new InsnNode(Opcodes.POP),
            new LdcInsnNode("horse_white"),
            new InsnNode(Opcodes.RETURN));
        Map<String, String> map = AsmWalker.over(m)
            .latch(node -> node instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC
                && fi.desc.equals("L" + fi.owner + ";") ? fi.name : null)
            .commitOn(AsmWalker::stringLiteral)
            .toMapFirstWins();
        assertEquals(Map.of("WHITE", "horse_white"), map);
        assertNull(AsmWalker.from(null).first());
    }

}
