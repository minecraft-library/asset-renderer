package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Units for the walk drive loop - each source shape against its hand-written loop, the
 * sentinel and budget bounds under both pseudo policies, and every {@link Exit} value, over
 * synthetic method bodies and a synthesized jar behind a {@link ClassNodeCache}.
 */
@DisplayName("walk engine - the drive loop and every Exit value")
class WalkEngineTest {

    private static final String FIXTURE = "test/Fixture";
    private static final String ABSENT = "test/Absent";

    @TempDir
    static Path tempDir;

    private static ClassNodeCache cache;

    @BeforeAll
    static void openFixtureJar() throws IOException {
        Path jar = tempDir.resolve("fixture.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(FIXTURE + ".class"));
            zip.write(fixtureClass());
            zip.closeEntry();
        }
        cache = ClassNodeCache.open(jar);
    }

    @AfterAll
    static void closeFixtureJar() {
        cache.close();
    }

    /**
     * One static method {@code m()} whose body is {@code LDC "a"; POP; RETURN}.
     */
    private static byte[] fixtureClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, FIXTURE, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_STATIC, "m", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("a");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode method(AbstractInsnNode... nodes) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        for (AbstractInsnNode node : nodes) method.instructions.add(node);
        return method;
    }

    // ------------------------------------------------------------------------------------
    // sources - each shape against its hand-written loop
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("over() yields the whole-body forward hand loop's sequence")
    void overMatchesHandLoop() {
        MethodNode m = method(new LdcInsnNode("a"), new LabelNode(), new LdcInsnNode("b"), new InsnNode(Opcodes.RETURN));
        List<AbstractInsnNode> hand = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) hand.add(in);
        assertEquals(4, hand.size());
        assertEquals(hand, AsmWalker.over(m).toList());
    }

    @Test
    @DisplayName("before(x) yields the getPrevious() hand loop's sequence")
    void beforeMatchesHandLoop() {
        LdcInsnNode a = new LdcInsnNode("a");
        LabelNode label = new LabelNode();
        LdcInsnNode b = new LdcInsnNode("b");
        method(a, label, b, new InsnNode(Opcodes.RETURN));
        List<AbstractInsnNode> hand = new ArrayList<>();
        for (AbstractInsnNode prev = b.getPrevious(); prev != null; prev = prev.getPrevious()) hand.add(prev);
        assertEquals(List.of(label, a), hand);
        assertEquals(hand, AsmWalker.before(b).toList());
    }

    @Test
    @DisplayName("from(x) yields the anchor-inclusive forward hand loop's sequence")
    void fromMatchesHandLoop() {
        LabelNode label = new LabelNode();
        LdcInsnNode b = new LdcInsnNode("b");
        InsnNode ret = new InsnNode(Opcodes.RETURN);
        method(new LdcInsnNode("a"), label, b, ret);
        List<AbstractInsnNode> hand = new ArrayList<>();
        for (AbstractInsnNode in = label; in != null; in = in.getNext()) hand.add(in);
        assertEquals(List.of(label, b, ret), hand);
        assertEquals(hand, AsmWalker.from(label).toList());
    }

    @Test
    @DisplayName("after(x) yields the anchor-exclusive forward hand loop's sequence")
    void afterMatchesHandLoop() {
        LabelNode label = new LabelNode();
        LdcInsnNode b = new LdcInsnNode("b");
        InsnNode ret = new InsnNode(Opcodes.RETURN);
        method(new LdcInsnNode("a"), label, b, ret);
        List<AbstractInsnNode> hand = new ArrayList<>();
        for (AbstractInsnNode in = label.getNext(); in != null; in = in.getNext()) hand.add(in);
        assertEquals(List.of(b, ret), hand);
        assertEquals(hand, AsmWalker.after(label).toList());
    }

    @Test
    @DisplayName("a body with no instructions and from(null) both yield nothing")
    void emptySourcesYieldNothing() {
        MethodNode empty = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        assertEquals(List.of(), AsmWalker.over(empty).toList());
        assertEquals(Exit.END, AsmWalker.over(empty).forEach(node -> {}));
        assertEquals(List.of(), AsmWalker.from(null).toList());
        assertNull(AsmWalker.from(null).first());
        assertEquals(Exit.END, AsmWalker.from(null).forEach(node -> {}));
    }

    // ------------------------------------------------------------------------------------
    // geometry - sentinels and the budget under both pseudo policies
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("until(node) binds by identity on the raw chain - a label sentinel stops a real() walk")
    void untilNodeIdentityOnRawChain() {
        LdcInsnNode a = new LdcInsnNode("a");
        LabelNode sentinel = new LabelNode();
        LdcInsnNode b = new LdcInsnNode("b");
        MethodNode m = method(a, sentinel, b, new InsnNode(Opcodes.RETURN));
        // the sentinel stops the walk even though real() would never yield it
        assertEquals(List.of(a), AsmWalker.over(m).real().until(sentinel).toList());
        assertEquals(Exit.SENTINEL, AsmWalker.over(m).real().until(sentinel).forEach(node -> {}));
        // exclusive: the sentinel itself is withheld
        assertEquals(List.of(a, sentinel), AsmWalker.over(m).until(b).toList());
        // identity, not equality: a structurally identical node outside the list stops nothing
        assertEquals(4, AsmWalker.over(m).until(new LdcInsnNode("a")).count());
        assertEquals(Exit.END, AsmWalker.over(m).until(new LdcInsnNode("a")).forEach(node -> {}));
    }

    @Test
    @DisplayName("until(Match) is exclusive and tested on yields - real() walks past a matching pseudo node")
    void untilMatchTestedOnYieldsOnly() {
        LdcInsnNode a = new LdcInsnNode("a");
        LabelNode label = new LabelNode();
        LdcInsnNode b = new LdcInsnNode("b");
        InsnNode ret = new InsnNode(Opcodes.RETURN);
        MethodNode m = method(a, label, b, ret);
        // raw: the label is a yield, so the stop fires there and the stopping yield is withheld
        assertEquals(List.of(a), AsmWalker.over(m).until(Insn.ofType(LabelNode.class)).toList());
        assertEquals(Exit.SENTINEL, AsmWalker.over(m).until(Insn.ofType(LabelNode.class)).forEach(node -> {}));
        // real(): the label is never a yield, so the same stop never fires
        assertEquals(List.of(a, b, ret), AsmWalker.over(m).real().until(Insn.ofType(LabelNode.class)).toList());
        assertEquals(Exit.END, AsmWalker.over(m).real().until(Insn.ofType(LabelNode.class)).forEach(node -> {}));
        // exclusive at a real stop too
        assertEquals(List.of(a, label, b), AsmWalker.over(m).until(Insn.opcode(Opcodes.RETURN)).toList());
    }

    @Test
    @DisplayName("limit(n) spends budget on raw yields where real().limit(n) spends it on real ones")
    void limitSpendsBudgetUnderPseudoPolicy() {
        LdcInsnNode a = new LdcInsnNode("a");
        LabelNode firstLabel = new LabelNode();
        LdcInsnNode b = new LdcInsnNode("b");
        LabelNode secondLabel = new LabelNode();
        LdcInsnNode c = new LdcInsnNode("c");
        MethodNode m = method(a, firstLabel, b, secondLabel, c, new InsnNode(Opcodes.RETURN));
        assertEquals(List.of(a, firstLabel, b), AsmWalker.over(m).limit(3).toList());
        assertEquals(List.of(a, b, c), AsmWalker.over(m).real().limit(3).toList());
    }

    // ------------------------------------------------------------------------------------
    // the Exit table - one test per row
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("exhaustion answers END")
    void exitEnd() {
        MethodNode m = method(new LdcInsnNode("a"), new InsnNode(Opcodes.RETURN));
        assertEquals(Exit.END, AsmWalker.over(m).forEach(node -> {}));
        assertEquals(Exit.END, AsmWalker.over(m).run());
    }

    @Test
    @DisplayName("a fired bound answers SENTINEL in both the identity and recognizer forms")
    void exitSentinel() {
        LdcInsnNode a = new LdcInsnNode("a");
        InsnNode ret = new InsnNode(Opcodes.RETURN);
        MethodNode m = method(a, ret);
        assertEquals(Exit.SENTINEL, AsmWalker.over(m).until(ret).run());
        assertEquals(Exit.SENTINEL, AsmWalker.over(m).until(Insn.opcode(Opcodes.RETURN)).forEach(node -> {}));
    }

    @Test
    @DisplayName("a refused arriving yield answers BUDGET")
    void exitBudget() {
        MethodNode m = method(new LdcInsnNode("a"), new LdcInsnNode("b"), new InsnNode(Opcodes.RETURN));
        assertEquals(Exit.BUDGET, AsmWalker.over(m).limit(1).forEach(node -> {}));
        assertEquals(Exit.BUDGET, AsmWalker.over(m).limit(1).run());
    }

    @Test
    @DisplayName("a tracer answering null answers HALTED")
    void exitHalted() {
        MethodNode m = method(new LdcInsnNode("a"), new InsnNode(Opcodes.RETURN));
        assertEquals(Exit.HALTED, AsmWalker.over(m).trace(current -> null));
    }

    @Test
    @DisplayName("a tracer revisiting a node answers CYCLE")
    void exitCycle() {
        MethodNode m = method(new LdcInsnNode("a"), new InsnNode(Opcodes.RETURN));
        assertEquals(Exit.CYCLE, AsmWalker.over(m).trace(current -> current));
    }

    @Test
    @DisplayName("an absent class or member answers MISSING from run-shaped terminals")
    void exitMissing() {
        assertEquals(Exit.MISSING, AsmWalker.over(cache, ABSENT, "m").run());
        assertEquals(Exit.MISSING, AsmWalker.over(cache, FIXTURE, "absent").run());
        assertEquals(Exit.MISSING, AsmWalker.over(cache, ABSENT, "m").forEach(node -> {}));
        // the resolved control: the same opener with both halves present walks to END
        assertEquals(Exit.END, AsmWalker.over(cache, FIXTURE, "m").run());
    }

    // ------------------------------------------------------------------------------------
    // boundaries - where two exits contend
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("exactly n yields that then exhaust answer END; only an (n+1)th arriving yield answers BUDGET")
    void budgetBoundary() {
        MethodNode m = method(new LdcInsnNode("a"), new LdcInsnNode("b"), new InsnNode(Opcodes.RETURN));
        assertEquals(Exit.END, AsmWalker.over(m).limit(3).forEach(node -> {}));
        assertEquals(Exit.BUDGET, AsmWalker.over(m).limit(2).forEach(node -> {}));
    }

    @Test
    @DisplayName("where a stop and an exhausted budget coincide on one node, SENTINEL wins")
    void sentinelWinsOverExhaustedBudget() {
        LdcInsnNode a = new LdcInsnNode("a");
        LdcInsnNode b = new LdcInsnNode("b");
        InsnNode ret = new InsnNode(Opcodes.RETURN);
        MethodNode m = method(a, b, ret);
        // two yields spend the budget, then the recognizer and the empty budget meet at RETURN
        assertEquals(Exit.SENTINEL, AsmWalker.over(m).until(Insn.opcode(Opcodes.RETURN)).limit(2).forEach(node -> {}));
        assertEquals(Exit.SENTINEL, AsmWalker.over(m).until(ret).limit(2).run());
    }

    // ------------------------------------------------------------------------------------
    // missing sources - the silent miss and the named arm
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the find family misses silently on a missing source while missing() names the arm")
    void missingSourceFindsSilently() {
        assertNull(AsmWalker.over(cache, ABSENT, "m").first());
        assertFalse(AsmWalker.over(cache, ABSENT, "m").any());
        assertEquals(List.of(), AsmWalker.over(cache, ABSENT, "m").toList());
        assertEquals(Missing.CLASS, AsmWalker.over(cache, ABSENT, "m").missing());
        assertEquals(Missing.MEMBER, AsmWalker.over(cache, FIXTURE, "absent").missing());
        // the resolved source names no arm and walks
        assertNull(AsmWalker.over(cache, FIXTURE, "m").missing());
        assertEquals("a", AsmWalker.over(cache, FIXTURE, "m").mapNotNull(AsmWalker::stringLiteral).first());
    }

}
