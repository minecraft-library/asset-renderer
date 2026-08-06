package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.kernel.ToolingException;
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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Governance pins for the walk package - the frozen public surface and the identities the
 * package guarantees: one-hop agreement with the statics, per-run cell minting on a reused
 * descriptor, the bare-source refusal, the comparison rows, the state-dump seam and the
 * sibling-write memo shape.
 */
@DisplayName("walk governance - the frozen public surface and the package identities")
class WalkGovernanceTest {

    private static MethodNode body(String name, AbstractInsnNode... nodes) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, name, "()V", null, null);
        for (AbstractInsnNode node : nodes) method.instructions.add(node);
        return method;
    }

    private static MethodNode method(AbstractInsnNode... nodes) {
        return body("m", nodes);
    }

    // ------------------------------------------------------------------------------------
    // the vocabulary roster
    // ------------------------------------------------------------------------------------

    private static final List<Class<?>> PUBLIC_TYPES = List.of(
        AsmWalker.class, Walk.class, GatherWalk.class, LatchWalk.class,
        CommitWalk.class, CommitWalk.Commit.class, PairWalk.class,
        Insn.class, Match.class,
        Cells.class, Cells.Cell.class, Cells.Window.class, Cells.ListCell.class,
        Cells.Latch.class, Cells.Flag.class, Cells.Slots.class,
        Interp.class, Interp.Domain.class, Interp.OnUnknown.class, Interp.Width.class,
        Exit.class, Missing.class, Tracer.class);

    private static final List<String> FROZEN_ROSTER = List.of(
        "AsmWalker.after(1)", "AsmWalker.before(1)", "AsmWalker.booleanLiteral(1)", "AsmWalker.clearing(1)",
        "AsmWalker.clinit(2)", "AsmWalker.commitAt(2)", "AsmWalker.detectIntForLoop(1)", "AsmWalker.doubleLiteral(1)",
        "AsmWalker.drive(1)", "AsmWalker.extractLambdaHandle(1)", "AsmWalker.feed(1)", "AsmWalker.findBsmHandleByName(2)",
        "AsmWalker.findEnumDefaultName(3)", "AsmWalker.findStringConcatRecipeIn(1)", "AsmWalker.first(1)",
        "AsmWalker.floatLiteral(1)", "AsmWalker.from(1)",
        "AsmWalker.gather(1)", "AsmWalker.getField(2)", "AsmWalker.getStatic(1)", "AsmWalker.getStatic(2)",
        "AsmWalker.intLiteral(1)", "AsmWalker.invokeSpecial(2)", "AsmWalker.invokeStatic(2)", "AsmWalker.invokeStatic(3)",
        "AsmWalker.invokeVirtual(2)",
        "AsmWalker.isBranchInsn(1)", "AsmWalker.isGetStatic(2)", "AsmWalker.isGetStatic(3)",
        "AsmWalker.isInvoke(4)", "AsmWalker.isInvoke(5)", "AsmWalker.isInvokeStatic(3)",
        "AsmWalker.isInvokeStatic(4)", "AsmWalker.isInvokeVirtual(3)", "AsmWalker.isLambdaInvokeDynamic(1)",
        "AsmWalker.isNewInstance(2)", "AsmWalker.isParameterOfType(3)", "AsmWalker.isPseudoNode(1)",
        "AsmWalker.isPutField(3)", "AsmWalker.isPutStatic(2)", "AsmWalker.isPutStatic(3)",
        "AsmWalker.latch(1)", "AsmWalker.limit(1)",
        "AsmWalker.longLiteral(1)", "AsmWalker.new_(1)", "AsmWalker.nextReal(1)", "AsmWalker.on(2)",
        "AsmWalker.opcode(1)", "AsmWalker.over(1)", "AsmWalker.over(3)", "AsmWalker.previousReal(1)",
        "AsmWalker.putField(2)", "AsmWalker.putStatic(1)", "AsmWalker.putStatic(2)", "AsmWalker.readStaticEnumMap(4)",
        "AsmWalker.real(0)", "AsmWalker.resolveLambdaTargetClass(2)", "AsmWalker.resolveStaticScalingFactor(6)",
        "AsmWalker.resolveStringConcatRecipe(1)",
        "AsmWalker.run(0)", "AsmWalker.stringLiteral(1)", "AsmWalker.trace(1)", "AsmWalker.traceFirst(2)",
        "AsmWalker.typeLiteral(1)", "AsmWalker.until(1)", "AsmWalker.until(1)", "AsmWalker.walkLambdaBody(3)",
        "Cells.Cell.clear(0)",
        "Cells.Flag.clear(0)", "Cells.Flag.get(0)", "Cells.Flag.set(0)",
        "Cells.Latch.clear(0)", "Cells.Latch.get(0)", "Cells.Latch.set(1)",
        "Cells.ListCell.add(1)", "Cells.ListCell.clear(0)", "Cells.ListCell.values(0)",
        "Cells.Slots.clear(0)", "Cells.Slots.clear(1)", "Cells.Slots.load(1)", "Cells.Slots.store(2)",
        "Cells.Window.clear(0)", "Cells.Window.size(0)", "Cells.Window.takeLast(0)", "Cells.Window.values(0)",
        "Cells.flag(0)", "Cells.latch(0)", "Cells.list(0)", "Cells.slots(0)", "Cells.window(2)",
        "CommitWalk.Commit.node(0)", "CommitWalk.Commit.value(0)", "CommitWalk.Commit.values(0)",
        "CommitWalk.any(0)", "CommitWalk.count(0)", "CommitWalk.first(0)", "CommitWalk.firstNotNull(1)",
        "CommitWalk.forEach(1)", "CommitWalk.map(1)", "CommitWalk.mapNotNull(1)", "CommitWalk.missing(0)",
        "CommitWalk.toMap(2)", "CommitWalk.toMap(2)",
        "Exit.valueOf(1)", "Exit.values(0)",
        "GatherWalk.commitAt(1)", "GatherWalk.commitAt(2)", "GatherWalk.keep(1)", "GatherWalk.openAt(1)",
        "GatherWalk.resetAt(1)", "GatherWalk.retain(0)", "GatherWalk.sealAt(1)", "GatherWalk.strict(0)",
        "Insn.branch(0)", "Insn.getField(2)", "Insn.getStatic(1)", "Insn.getStatic(2)",
        "Insn.invokeSpecial(2)", "Insn.invokeStatic(2)", "Insn.invokeStatic(3)", "Insn.invokeVirtual(2)",
        "Insn.lambdaIndy(0)", "Insn.new_(1)", "Insn.of(2)", "Insn.ofType(1)",
        "Insn.opcode(1)", "Insn.putField(2)", "Insn.putStatic(1)", "Insn.putStatic(2)",
        "Interp.Domain.binary(3)", "Interp.Domain.decode(1)", "Interp.Domain.unary(2)",
        "Interp.Domain.underflow(0)", "Interp.Domain.unknown(0)",
        "Interp.OnUnknown.valueOf(1)", "Interp.OnUnknown.values(0)",
        "Interp.Width.valueOf(1)", "Interp.Width.values(0)",
        "Interp.capacity(1)", "Interp.child(1)", "Interp.closeSlotFrame(0)", "Interp.depth(0)",
        "Interp.evaluateIntComparison(3)", "Interp.isEmpty(0)",
        "Interp.of(3)", "Interp.openSlotFrame(0)", "Interp.overflowWarnOnce(1)", "Interp.overflowWarning(1)",
        "Interp.peek(0)", "Interp.poison(0)",
        "Interp.poisoned(0)", "Interp.pop(0)", "Interp.popArguments(1)", "Interp.popFloatOrZero(3)",
        "Interp.popIntOrZero(0)", "Interp.popIntOrZero(3)", "Interp.popLiteral(0)", "Interp.popTyped(1)",
        "Interp.push(1)", "Interp.removeSlot(1)", "Interp.size(0)", "Interp.slot(1)", "Interp.step(1)",
        "Interp.store(2)", "Interp.width(0)", "Interp.willOverflow(0)",
        "LatchWalk.commitAt(1)", "LatchWalk.commitAt(2)", "LatchWalk.commitOn(1)", "LatchWalk.firstWins(0)",
        "LatchWalk.resetAt(1)", "LatchWalk.retain(0)", "LatchWalk.strict(0)", "LatchWalk.takeAt(1)",
        "Match.and(1)", "Match.matches(1)", "Match.test(1)", "Match.type(0)",
        "Missing.valueOf(1)", "Missing.values(0)",
        "PairWalk.forEach(1)", "PairWalk.missing(0)", "PairWalk.toMap(0)", "PairWalk.toMapFirstWins(0)",
        "Tracer.advance(1)",
        "Walk.any(0)", "Walk.any(1)", "Walk.count(0)", "Walk.first(0)", "Walk.first(1)", "Walk.first(2)",
        "Walk.firstNotNull(1)", "Walk.forEach(1)", "Walk.last(0)", "Walk.map(1)", "Walk.mapNotNull(1)",
        "Walk.missing(0)", "Walk.names(0)", "Walk.ofType(1)", "Walk.reduce(2)", "Walk.toList(0)",
        "Walk.toMap(2)", "Walk.toMapFirstWins(2)", "Walk.toSet(0)", "Walk.where(1)");

    @Test
    @DisplayName("public vocabulary roster matches the frozen list exactly")
    void vocabularyRoster() {
        List<String> roster = new ArrayList<>();
        for (Class<?> type : PUBLIC_TYPES) {
            String label = type.getName().substring(type.getPackageName().length() + 1).replace('$', '.');
            for (Method member : type.getDeclaredMethods())
                if (!member.isSynthetic() && Modifier.isPublic(member.getModifiers()))
                    roster.add(label + "." + member.getName() + "(" + member.getParameterCount() + ")");
        }
        roster.sort(Comparator.naturalOrder());
        assertEquals(FROZEN_ROSTER, roster);
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
        assertTrue(Interp.evaluateIntComparison(Opcodes.IFEQ, 0, 99));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IFEQ, 1, 99));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IFNE, 1, 99));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IFNE, 0, 99));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IFLT, -1, 99));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IFLT, 0, 99));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IFGE, 0, 99));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IFGE, -1, 99));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IFGT, 1, 99));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IFGT, 0, 99));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IFLE, 0, 99));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IFLE, 1, 99));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IF_ICMPEQ, 5, 5));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IF_ICMPEQ, 5, 6));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IF_ICMPNE, 5, 6));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IF_ICMPNE, 5, 5));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IF_ICMPLT, 4, 5));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IF_ICMPLT, 5, 5));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IF_ICMPGE, 5, 5));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IF_ICMPGE, 4, 5));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IF_ICMPGT, 6, 5));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IF_ICMPGT, 5, 5));
        assertTrue(Interp.evaluateIntComparison(Opcodes.IF_ICMPLE, 5, 5));
        assertFalse(Interp.evaluateIntComparison(Opcodes.IF_ICMPLE, 6, 5));
        assertFalse(Interp.evaluateIntComparison(Opcodes.GOTO, 0, 0), "an unmodelled opcode answers false");
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
