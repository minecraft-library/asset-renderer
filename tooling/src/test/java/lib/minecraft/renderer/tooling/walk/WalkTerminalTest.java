package lib.minecraft.renderer.tooling.walk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Units for the walk terminals - the map collectors and their skip arms, the scoped search,
 * the total family, and the null-tolerant decode statics - over synthetic method bodies.
 */
@DisplayName("walk terminals - collectors, the scoped search, and the decode statics")
class WalkTerminalTest {

    private static MethodNode method(AbstractInsnNode... nodes) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        for (AbstractInsnNode node : nodes) method.instructions.add(node);
        return method;
    }

    private static Walk<String> strings(MethodNode method) {
        return AsmWalker.over(method).mapNotNull(AsmWalker::stringLiteral);
    }

    // ------------------------------------------------------------------------------------
    // map collectors
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("toMap is last-wins where toMapFirstWins keeps the first binding")
    void mapCollectorsDuplicateKeys() {
        MethodNode m = method(
            new LdcInsnNode("a1"), new LdcInsnNode("a2"), new LdcInsnNode("b3"),
            new InsnNode(Opcodes.RETURN));
        assertEquals(Map.of("a", "2", "b", "3"),
            strings(m).toMap(s -> s.substring(0, 1), s -> s.substring(1)));
        assertEquals(Map.of("a", "1", "b", "3"),
            strings(m).toMapFirstWins(s -> s.substring(0, 1), s -> s.substring(1)));
    }

    @Test
    @DisplayName("a null key projection skips the entry on both map collectors")
    void mapCollectorsNullKeySkips() {
        MethodNode m = method(
            new LdcInsnNode("-skipped"), new LdcInsnNode("b2"), new LdcInsnNode("a1"),
            new InsnNode(Opcodes.RETURN));
        Map<String, String> lastWins = strings(m)
            .toMap(s -> s.startsWith("-") ? null : s.substring(0, 1), s -> s.substring(1));
        Map<String, String> firstWins = strings(m)
            .toMapFirstWins(s -> s.startsWith("-") ? null : s.substring(0, 1), s -> s.substring(1));
        assertEquals(Map.of("b", "2", "a", "1"), lastWins);
        assertEquals(Map.of("b", "2", "a", "1"), firstWins);
        assertEquals(List.of("b", "a"), List.copyOf(lastWins.keySet()));
        assertEquals(List.of("b", "a"), List.copyOf(firstWins.keySet()));
    }

    @Test
    @DisplayName("a null value projection skips the entry and survivors keep encounter order")
    void mapCollectorsNullValueSkips() {
        MethodNode m = method(
            new LdcInsnNode("aX"), new LdcInsnNode("c3"), new LdcInsnNode("b2"),
            new InsnNode(Opcodes.RETURN));
        Map<String, String> lastWins = strings(m)
            .toMap(s -> s.substring(0, 1), s -> s.endsWith("X") ? null : s.substring(1));
        Map<String, String> firstWins = strings(m)
            .toMapFirstWins(s -> s.substring(0, 1), s -> s.endsWith("X") ? null : s.substring(1));
        assertEquals(Map.of("c", "3", "b", "2"), lastWins);
        assertEquals(Map.of("c", "3", "b", "2"), firstWins);
        assertEquals(List.of("c", "b"), List.copyOf(lastWins.keySet()));
        assertEquals(List.of("c", "b"), List.copyOf(firstWins.keySet()));
    }

    // ------------------------------------------------------------------------------------
    // the scoped search
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("scoped search - the matcher is tested before the stop on a shared node")
    void scopedSearchMatcherFirst() {
        MethodNode m = method(
            new LdcInsnNode("a"), new LdcInsnNode("target"), new LdcInsnNode("late"),
            new InsnNode(Opcodes.RETURN));
        // The passthrough refuses "target" too - the matcher runs first, so it still answers.
        assertEquals("target", strings(m).first("target"::equals, s -> !"target".equals(s)));
    }

    @Test
    @DisplayName("scoped search - a negated stop halts with null, and a match past accepted nodes is found")
    void scopedSearchNegatedStop() {
        Predicate<String> stop = "wall"::equals;
        MethodNode blocked = method(
            new LdcInsnNode("a"), new LdcInsnNode("wall"), new LdcInsnNode("target"),
            new InsnNode(Opcodes.RETURN));
        // "wall" matches neither predicate - the walk aborts there, blind to the match past it.
        assertNull(strings(blocked).first("target"::equals, s -> !stop.test(s)));
        MethodNode open = method(
            new LdcInsnNode("a"), new LdcInsnNode("b"), new LdcInsnNode("target"),
            new InsnNode(Opcodes.RETURN));
        assertEquals("target", strings(open).first("target"::equals, s -> !stop.test(s)));
    }

    // ------------------------------------------------------------------------------------
    // total family
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("last() walks to the boundary and answers the final element")
    void lastWalksToBoundary() {
        MethodNode m = method(
            new LdcInsnNode("a"), new LdcInsnNode("b"), new LdcInsnNode("c"),
            new InsnNode(Opcodes.RETURN));
        assertEquals("c", strings(m).last());
        assertNull(AsmWalker.from(null).last());
    }

    @Test
    @DisplayName("count, toList and toSet answer encounter order; toSet dedupes")
    void countListSetOrder() {
        MethodNode m = method(
            new LdcInsnNode("b"), new LdcInsnNode("a"), new LdcInsnNode("b"),
            new InsnNode(Opcodes.RETURN));
        assertEquals(3L, strings(m).count());
        assertEquals(List.of("b", "a", "b"), strings(m).toList());
        Set<String> distinct = strings(m).toSet();
        assertEquals(List.of("b", "a"), List.copyOf(distinct));
    }

    @Test
    @DisplayName("reduce folds strictly left to right")
    void reduceLeftToRight() {
        MethodNode m = method(
            new LdcInsnNode("a"), new LdcInsnNode("b"), new LdcInsnNode("c"),
            new InsnNode(Opcodes.RETURN));
        assertEquals("^abc", strings(m).reduce("^", (acc, s) -> acc + s));
    }

    @Test
    @DisplayName("forEach answers how the walk ended")
    void forEachAnswersExit() {
        MethodNode m = method(
            new LdcInsnNode("a"), new LdcInsnNode("b"), new InsnNode(Opcodes.RETURN));
        List<String> seen = new ArrayList<>();
        assertEquals(Exit.END, strings(m).forEach(seen::add));
        assertEquals(List.of("a", "b"), seen);
        InsnNode boundary = new InsnNode(Opcodes.RETURN);
        MethodNode bounded = method(new LdcInsnNode("a"), boundary, new LdcInsnNode("late"));
        List<String> partial = new ArrayList<>();
        assertEquals(Exit.SENTINEL, AsmWalker.over(bounded).until(boundary)
            .mapNotNull(AsmWalker::stringLiteral).forEach(partial::add));
        assertEquals(List.of("a"), partial);
    }

    @Test
    @DisplayName("firstNotNull stops probing at the first non-null answer")
    void firstNotNullShortCircuits() {
        MethodNode m = method(
            new LdcInsnNode("a"), new LdcInsnNode("b"), new LdcInsnNode("c"),
            new InsnNode(Opcodes.RETURN));
        int[] probes = {0};
        String answer = strings(m).firstNotNull(s -> {
            probes[0]++;
            return "b".equals(s) ? "hit:" + s : null;
        });
        assertEquals("hit:b", answer);
        assertEquals(2, probes[0]);
    }

    @Test
    @DisplayName("names() projects member names and drops every other node")
    void namesProjection() {
        MethodNode m = method(
            new FieldInsnNode(Opcodes.GETSTATIC, "test/O", "someField", "I"),
            new LdcInsnNode("dropped"),
            new LabelNode(),
            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "test/O", "someMethod", "()V"),
            new InsnNode(Opcodes.RETURN));
        assertEquals(List.of("someField", "someMethod"), AsmWalker.over(m).names().toList());
    }

    @Test
    @DisplayName("any() and any(pred) answer both arms")
    void anyBothArms() {
        MethodNode m = method(
            new LdcInsnNode("a"), new LdcInsnNode("b"), new InsnNode(Opcodes.RETURN));
        assertTrue(strings(m).any());
        assertFalse(AsmWalker.from(null).any());
        assertTrue(strings(m).any("b"::equals));
        assertFalse(strings(m).any("z"::equals));
    }

    // ------------------------------------------------------------------------------------
    // decode statics
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("booleanLiteral answers only the two JVM boolean literals")
    void booleanLiteralDomain() {
        assertEquals(Boolean.FALSE, AsmWalker.booleanLiteral(new InsnNode(Opcodes.ICONST_0)));
        assertEquals(Boolean.TRUE, AsmWalker.booleanLiteral(new InsnNode(Opcodes.ICONST_1)));
        assertNull(AsmWalker.booleanLiteral(new InsnNode(Opcodes.ICONST_M1)));
        for (int op = Opcodes.ICONST_2; op <= Opcodes.ICONST_5; op++)
            assertNull(AsmWalker.booleanLiteral(new InsnNode(op)));
    }

    @Test
    @DisplayName("intLiteral covers every int-push shape and rejects a string constant")
    void intLiteralDomain() {
        for (int op = Opcodes.ICONST_M1; op <= Opcodes.ICONST_5; op++)
            assertEquals(op - Opcodes.ICONST_0, AsmWalker.intLiteral(new InsnNode(op)));
        assertEquals(100, AsmWalker.intLiteral(new IntInsnNode(Opcodes.BIPUSH, 100)));
        assertEquals(30000, AsmWalker.intLiteral(new IntInsnNode(Opcodes.SIPUSH, 30000)));
        assertEquals(123456, AsmWalker.intLiteral(new LdcInsnNode(123456)));
        assertNull(AsmWalker.intLiteral(new LdcInsnNode("not an int")));
    }

    @Test
    @DisplayName("every decode static answers null for a null node")
    void decodeStaticsNullTolerant() {
        assertNull(AsmWalker.stringLiteral(null));
        assertNull(AsmWalker.intLiteral(null));
        assertNull(AsmWalker.floatLiteral(null));
        assertNull(AsmWalker.longLiteral(null));
        assertNull(AsmWalker.doubleLiteral(null));
        assertNull(AsmWalker.booleanLiteral(null));
        assertNull(AsmWalker.typeLiteral(null));
    }

}
