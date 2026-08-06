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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for the fold-token disciplines over synthetic bodies - the gather buffer's keep window,
 * the feed window's destructive take, the latch's sticky / strict / first-wins split, the
 * open-seal segment gate, promotion via {@code takeAt}, and the retain deviation from the
 * commit reset.
 */
@DisplayName("fold-token discipline - keep window, window take, latch stickiness, segment gate, promotion, retain")
class WalkFoldTokenTest {

    private static final Match<FieldInsnNode> COMMIT = Insn.putStatic("test/T");
    private static final Match<FieldInsnNode> OPENER = Insn.getStatic("test/Seg");
    private static final Match<AbstractInsnNode> SEAL = Insn.opcode(Opcodes.NOP);
    private static final Match<AbstractInsnNode> TAKE = Insn.opcode(Opcodes.NOP);

    private static MethodNode method(AbstractInsnNode... nodes) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        for (AbstractInsnNode node : nodes) method.instructions.add(node);
        return method;
    }

    private static FieldInsnNode commit() {
        return new FieldInsnNode(Opcodes.PUTSTATIC, "test/T", "SLOT", "Ltest/T;");
    }

    private static FieldInsnNode open() {
        return new FieldInsnNode(Opcodes.GETSTATIC, "test/Seg", "MARK", "Ltest/Seg;");
    }

    private static InsnNode nop() {
        return new InsnNode(Opcodes.NOP);
    }

    private static <G> List<List<G>> events(CommitWalk<FieldInsnNode, G> chain) {
        List<List<G>> seen = new ArrayList<>();
        chain.forEach(event -> seen.add(event.values()));
        return seen;
    }

    private static CommitWalk.Commit<FieldInsnNode, String> promoted(MethodNode m) {
        CommitWalk.Commit<FieldInsnNode, String> event = AsmWalker.over(m)
            .latch(AsmWalker::stringLiteral)
            .takeAt(TAKE)
            .commitAt(COMMIT)
            .first();
        assertNotNull(event);
        return event;
    }

    @Test
    @DisplayName("keep(3) under retain() - push always, evict the oldest past three, byte-exact per commit")
    void keepEvictionHistory() {
        MethodNode m = method(
            new LdcInsnNode(1), new LdcInsnNode(2), commit(),
            new LdcInsnNode(3), new LdcInsnNode(4), commit(),
            new LdcInsnNode(5), new LdcInsnNode(6), commit());
        List<List<Integer>> seen = events(AsmWalker.over(m)
            .gather(AsmWalker::intLiteral).keep(3).retain()
            .commitAt(COMMIT));
        assertEquals(List.of(List.of(1, 2), List.of(2, 3, 4), List.of(4, 5, 6)), seen);
    }

    @Test
    @DisplayName("takeLast() pops the newest for good - successive takes step down the stack, an empty take answers null consuming nothing")
    void windowTakeLastConsumes() {
        MethodNode m = method(new LdcInsnNode(1), new LdcInsnNode(2), nop(), nop(), nop(), commit());
        Cells.Window<Integer> ints = Cells.window(AsmWalker::intLiteral, 4);
        List<Integer> takes = new ArrayList<>();
        List<List<Integer>> atCommit = new ArrayList<>();
        AsmWalker.over(m)
            .feed(ints)
            .on(TAKE, n -> takes.add(ints.takeLast()))
            .commitAt(COMMIT, c -> atCommit.add(ints.values()))
            .run();
        assertEquals(Arrays.asList(2, 1, null), takes);
        assertEquals(List.of(List.of()), atCommit);
    }

    @Test
    @DisplayName("push count is the eviction history under takes - a take frees a slot, an evicted entry is gone for good")
    void windowTakeLastNeverUnEvicts() {
        MethodNode m = method(
            new LdcInsnNode(1), new LdcInsnNode(2), new LdcInsnNode(3), nop(),
            new LdcInsnNode(4), nop(), nop(), nop(), commit());
        Cells.Window<Integer> ints = Cells.window(AsmWalker::intLiteral, 2);
        List<Integer> takes = new ArrayList<>();
        List<List<Integer>> atCommit = new ArrayList<>();
        AsmWalker.over(m)
            .feed(ints)
            .on(TAKE, n -> takes.add(ints.takeLast()))
            .commitAt(COMMIT, c -> atCommit.add(ints.values()))
            .run();
        assertEquals(Arrays.asList(3, 4, 2, null), takes);
        assertEquals(List.of(List.of()), atCommit);
    }

    @Test
    @DisplayName("list takeLast() pops the newest for good - takes step down the appends and an empty take answers null consuming nothing")
    void listTakeLastConsumes() {
        MethodNode m = method(
            new LdcInsnNode(1), new LdcInsnNode(2), nop(), nop(), nop(),
            new LdcInsnNode(3), nop(), commit());
        Cells.ListCell<Integer> values = Cells.list();
        List<Integer> takes = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        AsmWalker.over(m)
            .feed(values)
            .on(Insn.of(AbstractInsnNode.class, node -> AsmWalker.intLiteral(node) != null),
                node -> values.add(AsmWalker.intLiteral(node)))
            .on(TAKE, n -> {
                sizes.add(values.size());
                takes.add(values.takeLast());
            })
            .commitAt(COMMIT, c -> { })
            .run();
        assertEquals(Arrays.asList(2, 1, null, 3), takes);
        assertEquals(List.of(2, 1, 0, 1), sizes);
    }

    @Test
    @DisplayName("sticky default holds across an unconsumed node; strict() clears; firstWins() keeps the first decode")
    void latchDisciplines() {
        MethodNode m = method(new LdcInsnNode("one"), new LdcInsnNode("two"), nop(), commit());
        CommitWalk.Commit<FieldInsnNode, String> sticky = AsmWalker.over(m)
            .latch(AsmWalker::stringLiteral).commitAt(COMMIT).first();
        assertNotNull(sticky);
        assertEquals("two", sticky.value(), "the default keeps the last decode across the unconsumed node");
        CommitWalk.Commit<FieldInsnNode, String> strict = AsmWalker.over(m)
            .latch(AsmWalker::stringLiteral).strict().commitAt(COMMIT).first();
        assertNotNull(strict);
        assertNull(strict.value(), "the unconsumed node clears a strict latch before the commit");
        CommitWalk.Commit<FieldInsnNode, String> firstWins = AsmWalker.over(m)
            .latch(AsmWalker::stringLiteral).firstWins().commitAt(COMMIT).first();
        assertNotNull(firstWins);
        assertEquals("one", firstWins.value(), "first-wins keeps the first decode where the default keeps the last");
    }

    @Test
    @DisplayName("firstWins() does not consult the decoder while a value is held")
    void firstWinsSkipsDecoder() {
        MethodNode m = method(new LdcInsnNode("a"), new LdcInsnNode("b"), commit());
        int[] calls = {0};
        Function<AbstractInsnNode, String> counting = node -> {
            calls[0]++;
            return AsmWalker.stringLiteral(node);
        };
        CommitWalk.Commit<FieldInsnNode, String> event = AsmWalker.over(m)
            .latch(counting).firstWins().commitAt(COMMIT).first();
        assertNotNull(event);
        assertEquals("a", event.value());
        assertEquals(1, calls[0], "a held latch skips the decoder on every later yield");
        calls[0] = 0;
        AsmWalker.over(m).latch(counting).commitAt(COMMIT).first();
        assertEquals(3, calls[0], "the sticky default consults the decoder on every yield");
    }

    @Test
    @DisplayName("a second opener before any seal clears the unsealed buffer")
    void reopenClearsUnsealedBuffer() {
        MethodNode m = method(open(), new LdcInsnNode("stale"), open(), new LdcInsnNode("fresh"), nop(), commit());
        List<List<String>> seen = events(AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral).openAt(OPENER).sealAt(SEAL)
            .commitAt(COMMIT));
        assertEquals(List.of(List.of("fresh")), seen, "the sealed payload carries only what followed the second opener");
    }

    @Test
    @DisplayName("sealAt parks an empty buffer - the commit still emits its row")
    void sealParksEmptyBuffer() {
        MethodNode m = method(open(), nop(), commit());
        CommitWalk<FieldInsnNode, String> chain = AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral).openAt(OPENER).sealAt(SEAL)
            .commitAt(COMMIT);
        CommitWalk.Commit<FieldInsnNode, String> event = chain.first();
        assertNotNull(event, "an empty parked buffer still commits");
        assertTrue(event.values().isEmpty());
        Map<String, String> rows = chain.toMap(fi -> fi.name,
            (fi, values) -> values.isEmpty() ? "empty:" + fi.name : String.join("+", values));
        assertEquals(Map.of("SLOT", "empty:SLOT"), rows, "the node-and-payload projection reads the commit node as fallback");
    }

    @Test
    @DisplayName("a commit with nothing sealed emits nothing and still resets")
    void unsealedCommitEmitsNothing() {
        MethodNode m = method(open(), new LdcInsnNode("x"), commit(), open(), new LdcInsnNode("y"), nop(), commit());
        List<List<String>> seen = events(AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral).openAt(OPENER).sealAt(SEAL)
            .commitAt(COMMIT));
        assertEquals(List.of(List.of("y")), seen, "the first commit emits no row and leaks nothing into the second round");
    }

    @Test
    @DisplayName("a seal while the segment is closed does not fire")
    void closedSealDoesNotFire() {
        MethodNode m = method(new LdcInsnNode("a"), nop(), commit(), open(), new LdcInsnNode("b"), nop(), commit());
        List<List<String>> seen = events(AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral).openAt(OPENER).sealAt(SEAL)
            .commitAt(COMMIT));
        assertEquals(List.of(List.of("b")), seen, "literals before any opener never park, so the first commit stays silent");
    }

    @Test
    @DisplayName("a commit closes the segment gate - nothing gathers until a new opener")
    void commitClosesGate() {
        MethodNode m = method(
            open(), new LdcInsnNode("a"), nop(), commit(),
            new LdcInsnNode("b"), nop(), commit(),
            open(), nop(), commit());
        List<List<String>> seen = events(AsmWalker.over(m)
            .gather(AsmWalker::stringLiteral).openAt(OPENER).sealAt(SEAL)
            .commitAt(COMMIT));
        assertEquals(List.of(List.of("a"), List.of()), seen,
            "the closed middle round neither gathers nor seals; the reopened round parks empty");
    }

    @Test
    @DisplayName("takeAt promotes read-and-clear; a later latch write clears the register; an empty-latch take preserves it")
    void takeAtRegisterDiscipline() {
        assertEquals("a", promoted(method(new LdcInsnNode("a"), nop(), commit())).value(),
            "an armed latch is taken into the register the commit answers");
        assertNull(promoted(method(new LdcInsnNode("a"), nop(), new LdcInsnNode("b"), commit())).value(),
            "a latch write after the take clears the stale promotion");
        assertEquals("b", promoted(method(new LdcInsnNode("a"), nop(), new LdcInsnNode("b"), nop(), commit())).value(),
            "the commit answers the new promotion only after its own take");
        assertEquals("a", promoted(method(new LdcInsnNode("a"), nop(), nop(), commit())).value(),
            "a take with an empty latch leaves the promoted register untouched");
    }

    @Test
    @DisplayName("retain() and strict() combine - the buffer survives a commit yet clears on a fall-through")
    void retainWithStrict() {
        MethodNode m = method(
            new LdcInsnNode(1), commit(),
            new LdcInsnNode(2), commit(),
            nop(),
            new LdcInsnNode(3), commit());
        List<List<Integer>> seen = events(AsmWalker.over(m)
            .gather(AsmWalker::intLiteral).retain().strict()
            .commitAt(COMMIT));
        assertEquals(List.of(List.of(1), List.of(1, 2), List.of(3)), seen);
    }

    @Test
    @DisplayName("keep(2) read positionally is the prior=latest sliding window")
    void keepTwoPositionalWindow() {
        MethodNode m = method(new LdcInsnNode(5), new LdcInsnNode(6), new LdcInsnNode(7), commit());
        CommitWalk.Commit<FieldInsnNode, Integer> event = AsmWalker.over(m)
            .gather(AsmWalker::intLiteral).keep(2).commitAt(COMMIT).first();
        assertNotNull(event);
        Integer prior = null;
        Integer latest = null;
        for (AbstractInsnNode in : m.instructions) {
            Integer value = AsmWalker.intLiteral(in);
            if (value == null) continue;
            prior = latest;
            latest = value;
        }
        assertEquals(prior, event.values().getFirst(), "getFirst() is the older entry");
        assertEquals(latest, event.values().getLast(), "getLast() is the newer entry");
    }

}
