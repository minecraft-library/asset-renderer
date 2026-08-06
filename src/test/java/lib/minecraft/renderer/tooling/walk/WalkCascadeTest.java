package lib.minecraft.renderer.tooling.walk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The cascade contract - claiming, commit, reset and fall-through. Stages run in declaration
 * order and the first that fires claims the element; a commit reads its payload before any
 * reset; the engine-owned commit-reset is unconditional while {@code retain()} and
 * {@code clearing(subset)} are the declared deviations; an element no stage consumed is the
 * fall-through event strict cells clear on; an armed commit recognizer that fails its own
 * cell-reading match raises no event and therefore no reset.
 */
@DisplayName("walk cascade - claiming, commit, reset, fall-through")
class WalkCascadeTest {

    private static MethodNode method(AbstractInsnNode... nodes) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        for (AbstractInsnNode node : nodes) method.instructions.add(node);
        return method;
    }

    private static String getstaticName(AbstractInsnNode node) {
        return node instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC ? fi.name : null;
    }

    @Test
    @DisplayName("claiming is declaration order - the first-declared of feed and on consumes the shared hit")
    void claimingDeclarationOrder() {
        MethodNode m = method(new LdcInsnNode("x"), new InsnNode(Opcodes.RETURN));

        // feed declared first: the cell decodes the LDC and claims it, so the hook never fires
        int[] hookHits = {0};
        Cells.Window<String> feedFirst = Cells.window(AsmWalker::stringLiteral, 2);
        AsmWalker.over(m)
            .feed(feedFirst)
            .on(Insn.ofType(LdcInsnNode.class), ldc -> hookHits[0]++)
            .run();
        assertEquals(List.of("x"), feedFirst.values());
        assertEquals(0, hookHits[0]);

        // on declared first: the hook claims the LDC, so the cell's decoder never decodes it
        int[] swappedHits = {0};
        int[] decoded = {0};
        Cells.Window<String> feedSecond = Cells.window(node -> {
            String value = AsmWalker.stringLiteral(node);
            if (value != null) decoded[0]++;
            return value;
        }, 2);
        AsmWalker.over(m)
            .on(Insn.ofType(LdcInsnNode.class), ldc -> swappedHits[0]++)
            .feed(feedSecond)
            .run();
        assertEquals(1, swappedHits[0]);
        assertEquals(0, decoded[0]);
        assertEquals(List.of(), feedSecond.values());
    }

    @Test
    @DisplayName("a latch claims its key node - the commitOn value decoder is never offered it")
    void latchClaimsKeyNode() {
        FieldInsnNode key = new FieldInsnNode(Opcodes.GETSTATIC, "test/K", "WHITE", "Ltest/K;");
        LdcInsnNode value = new LdcInsnNode("v");
        MethodNode m = method(key, value, new InsnNode(Opcodes.RETURN));
        List<AbstractInsnNode> offered = new ArrayList<>();
        Map<String, String> pairs = AsmWalker.over(m)
            .latch(WalkCascadeTest::getstaticName)
            .commitOn(node -> {
                offered.add(node);
                return AsmWalker.stringLiteral(node);
            })
            .toMap();
        assertEquals(Map.of("WHITE", "v"), pairs);
        assertEquals(List.of(value), offered, "the key node was claimed by the latch, never decoded as a value");
    }

    @Test
    @DisplayName("resetAt fires after the commit on a shared node - the event carries the pre-reset payload")
    void resetAfterCommit() {
        MethodNode m = method(
            new LdcInsnNode("a"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/O", "R", "I"),
            new LdcInsnNode("b"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/O", "K", "I"),
            new InsnNode(Opcodes.RETURN));
        List<List<String>> payloads = new ArrayList<>();
        AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral)
            .retain()
            .resetAt(Insn.putStatic("test/O", "R"))
            .commitAt(Insn.putStatic("test/O"))
            .forEach(event -> payloads.add(event.values()));
        // the first commit shares its node with the resetAt target: the event still carries "a",
        // so the commit read before the reset; the second commit lacks "a" despite retain(),
        // so the reset did fire
        assertEquals(List.of(List.of("a"), List.of("b")), payloads);
    }

    @Test
    @DisplayName("commit-reset is unconditional - a null-skipped row still clears the buffer")
    void commitResetUnconditional() {
        MethodNode m = method(
            new LdcInsnNode("a"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/O", "A", "I"),
            new LdcInsnNode("b"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/O", "B", "I"),
            new InsnNode(Opcodes.RETURN));
        Map<String, String> rows = AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral)
            .commitAt(Insn.putStatic("test/O"))
            .toMap(fi -> fi.name, values -> values.contains("a") ? null : String.join("+", values));
        // the A row is skipped by the collector, yet the reset ran: B's payload starts fresh
        // at "b" rather than carrying "a" forward
        assertEquals(Map.of("B", "b"), rows);
    }

    @Test
    @DisplayName("retain() keeps the buffer across commits")
    void retainAcrossCommits() {
        MethodNode m = method(
            new LdcInsnNode("a"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/O", "A", "I"),
            new LdcInsnNode("b"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/O", "B", "I"),
            new InsnNode(Opcodes.RETURN));
        List<List<String>> payloads = new ArrayList<>();
        AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral)
            .retain()
            .commitAt(Insn.putStatic("test/O"))
            .forEach(event -> payloads.add(event.values()));
        assertEquals(List.of(List.of("a"), List.of("a", "b")), payloads);
    }

    @Test
    @DisplayName("clearing(subset) clears only the named cell - the other attached cell stays live")
    void clearingSubset() {
        MethodNode m = method(
            new LdcInsnNode("s1"), new InsnNode(Opcodes.ICONST_1),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/O", "C1", "I"),
            new LdcInsnNode("s2"), new InsnNode(Opcodes.ICONST_2),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/O", "C2", "I"),
            new InsnNode(Opcodes.RETURN));
        Cells.Window<String> strings = Cells.window(AsmWalker::stringLiteral, 4);
        Cells.Window<Integer> ints = Cells.window(AsmWalker::intLiteral, 4);
        List<List<String>> stringSnaps = new ArrayList<>();
        List<List<Integer>> intSnaps = new ArrayList<>();
        AsmWalker.over(m)
            .feed(strings)
            .feed(ints)
            .commitAt(Insn.putStatic("test/O"), fi -> {
                stringSnaps.add(strings.values());
                intSnaps.add(ints.values());
            })
            .clearing(ints)
            .run();
        assertEquals(List.of(List.of("s1"), List.of("s1", "s2")), stringSnaps, "the unnamed cell survives the commit");
        assertEquals(List.of(List.of(1), List.of(2)), intSnaps, "the named cell is cleared at each commit");
    }

    @Test
    @DisplayName("commitOn is armed-guarded - never consulted unarmed, a null decode keeps the latch armed")
    void commitOnArmedGuard() {
        LdcInsnNode early = new LdcInsnNode("early");
        FieldInsnNode key = new FieldInsnNode(Opcodes.GETSTATIC, "test/K", "K", "Ltest/K;");
        InsnNode gap1 = new InsnNode(Opcodes.POP);
        InsnNode gap2 = new InsnNode(Opcodes.NOP);
        LdcInsnNode value = new LdcInsnNode("v");
        MethodNode m = method(early, key, gap1, gap2, value, new InsnNode(Opcodes.RETURN));
        List<AbstractInsnNode> offered = new ArrayList<>();
        Map<String, String> pairs = AsmWalker.over(m)
            .latch(WalkCascadeTest::getstaticName)
            .commitOn(node -> {
                offered.add(node);
                return AsmWalker.stringLiteral(node);
            })
            .toMap();
        // decodable "early" precedes any key and RETURN follows the pair, so neither reaches
        // the decoder; the two null decodes between key and value left the latch armed
        assertEquals(List.of(gap1, gap2, value), offered);
        assertEquals(Map.of("K", "v"), pairs);
    }

    @Test
    @DisplayName("strict() clears on the fall-through event - the sticky default keeps the held value")
    void strictFallThrough() {
        MethodNode m = method(
            new FieldInsnNode(Opcodes.GETSTATIC, "test/K", "HELD", "Ltest/K;"),
            new InsnNode(Opcodes.NOP),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/O", "C", "I"),
            new InsnNode(Opcodes.RETURN));

        CommitWalk.Commit<FieldInsnNode, String> sticky = AsmWalker.over(m)
            .latch(WalkCascadeTest::getstaticName)
            .commitAt(Insn.putStatic("test/O"))
            .first();
        assertNotNull(sticky);
        assertEquals("HELD", sticky.value(), "the unconsumed NOP does not disturb a sticky latch");

        CommitWalk.Commit<FieldInsnNode, String> strict = AsmWalker.over(m)
            .latch(WalkCascadeTest::getstaticName)
            .strict()
            .commitAt(Insn.putStatic("test/O"))
            .first();
        assertNotNull(strict, "the commit still fires; only the payload was lost");
        assertNull(strict.value());
        assertEquals(List.of(), strict.values());
    }

    @Test
    @DisplayName("armed commit recognizer - a failed recognizer raises no event and no reset; the armed one commits and resets")
    void armedCommitRecognizer() {
        MethodNode m = method(
            new LdcInsnNode(7),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/T", "SLOT", "Ltest/T;"),
            new LdcInsnNode("id"),
            new FieldInsnNode(Opcodes.PUTSTATIC, "test/T", "SLOT", "Ltest/T;"));
        Cells.Latch<String> id = Cells.latch();
        Cells.ListCell<Integer> blocks = Cells.list();
        List<String> committed = new ArrayList<>();
        AsmWalker.over(m)
            .feed(id)
            .feed(blocks)
            .on(Insn.of(AbstractInsnNode.class, node -> AsmWalker.intLiteral(node) != null),
                node -> blocks.add(AsmWalker.intLiteral(node)))
            .on(Insn.of(AbstractInsnNode.class, node -> AsmWalker.stringLiteral(node) != null),
                node -> id.set(AsmWalker.stringLiteral(node)))
            .commitAt(Insn.putStatic("test/T").and(put -> id.get() != null),
                put -> committed.add(id.get() + blocks.values()))
            .run();
        assertEquals(List.of("id[7]"), committed,
            "the unarmed PUTSTATIC raised no event and reset nothing - the block list survived it");
        assertNull(id.get(), "the armed commit's default reset cleared the latch");
        assertEquals(List.of(), blocks.values(), "and the list");
    }

}
