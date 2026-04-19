package lib.minecraft.renderer.tooling.asm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Fast unit tests for the {@link AsmKit} bytecode primitives. Every fixture is an in-memory
 * {@link ClassNode} / {@link MethodNode} - no real jar load happens.
 */
@DisplayName("AsmKit bytecode primitives")
class AsmKitTest {

    @Nested
    @DisplayName("readIntLiteral")
    class ReadIntLiteral {

        @Test
        @DisplayName("ICONST_M1 through ICONST_5 decode to -1 .. 5")
        void iconstRange() {
            for (int i = -1; i <= 5; i++) {
                InsnNode node = new InsnNode(Opcodes.ICONST_0 + i);
                assertThat("ICONST_" + i, AsmKit.readIntLiteral(node), equalTo(i));
            }
        }

        @Test
        @DisplayName("BIPUSH returns operand")
        void bipush() {
            IntInsnNode node = new IntInsnNode(Opcodes.BIPUSH, 42);
            assertThat(AsmKit.readIntLiteral(node), equalTo(42));
        }

        @Test
        @DisplayName("SIPUSH returns operand")
        void sipush() {
            IntInsnNode node = new IntInsnNode(Opcodes.SIPUSH, 12345);
            assertThat(AsmKit.readIntLiteral(node), equalTo(12345));
        }

        @Test
        @DisplayName("LDC Integer returns value")
        void ldcInt() {
            LdcInsnNode node = new LdcInsnNode(0xDEADBEEF);
            assertThat(AsmKit.readIntLiteral(node), equalTo(0xDEADBEEF));
        }

        @Test
        @DisplayName("LDC Long returns null")
        void ldcLongIsNotInt() {
            LdcInsnNode node = new LdcInsnNode(123L);
            assertThat(AsmKit.readIntLiteral(node), is(nullValue()));
        }

        @Test
        @DisplayName("non-literal instruction returns null")
        void nonLiteral() {
            InsnNode node = new InsnNode(Opcodes.NOP);
            assertThat(AsmKit.readIntLiteral(node), is(nullValue()));
        }

    }

    @Nested
    @DisplayName("readFloatLiteral")
    class ReadFloatLiteral {

        @Test
        @DisplayName("FCONST_0 through FCONST_2 decode to 0f, 1f, 2f")
        void fconstRange() {
            for (int i = 0; i <= 2; i++) {
                InsnNode node = new InsnNode(Opcodes.FCONST_0 + i);
                assertThat("FCONST_" + i, AsmKit.readFloatLiteral(node), equalTo((float) i));
            }
        }

        @Test
        @DisplayName("LDC Float returns value")
        void ldcFloat() {
            LdcInsnNode node = new LdcInsnNode(3.14f);
            assertThat(AsmKit.readFloatLiteral(node), equalTo(3.14f));
        }

        @Test
        @DisplayName("LDC Integer returns null (not a float)")
        void ldcIntIsNotFloat() {
            LdcInsnNode node = new LdcInsnNode(1);
            assertThat(AsmKit.readFloatLiteral(node), is(nullValue()));
        }

    }

    @Nested
    @DisplayName("readLongLiteral")
    class ReadLongLiteral {

        @Test
        @DisplayName("LCONST_0 returns 0L")
        void lconst0() {
            assertThat(AsmKit.readLongLiteral(new InsnNode(Opcodes.LCONST_0)), equalTo(0L));
        }

        @Test
        @DisplayName("LCONST_1 returns 1L")
        void lconst1() {
            assertThat(AsmKit.readLongLiteral(new InsnNode(Opcodes.LCONST_1)), equalTo(1L));
        }

        @Test
        @DisplayName("LDC Long returns value")
        void ldcLong() {
            assertThat(AsmKit.readLongLiteral(new LdcInsnNode(9999999999L)), equalTo(9999999999L));
        }

    }

    @Nested
    @DisplayName("readDoubleLiteral")
    class ReadDoubleLiteral {

        @Test
        @DisplayName("DCONST_0 returns 0.0")
        void dconst0() {
            assertThat(AsmKit.readDoubleLiteral(new InsnNode(Opcodes.DCONST_0)), equalTo(0.0));
        }

        @Test
        @DisplayName("DCONST_1 returns 1.0")
        void dconst1() {
            assertThat(AsmKit.readDoubleLiteral(new InsnNode(Opcodes.DCONST_1)), equalTo(1.0));
        }

        @Test
        @DisplayName("LDC Double returns value")
        void ldcDouble() {
            assertThat(AsmKit.readDoubleLiteral(new LdcInsnNode(2.71828)), equalTo(2.71828));
        }

    }

    @Nested
    @DisplayName("readStringLiteral / readTypeLiteral / readAnyLiteral")
    class ReadOther {

        @Test
        @DisplayName("LDC String returns value")
        void ldcString() {
            assertThat(AsmKit.readStringLiteral(new LdcInsnNode("hello")), equalTo("hello"));
        }

        @Test
        @DisplayName("LDC non-String returns null for readStringLiteral")
        void ldcNonStringIsNull() {
            assertThat(AsmKit.readStringLiteral(new LdcInsnNode(42)), is(nullValue()));
        }

        @Test
        @DisplayName("LDC Type returns value")
        void ldcType() {
            Type expected = Type.getObjectType("java/lang/String");
            LdcInsnNode node = new LdcInsnNode(expected);
            assertThat(AsmKit.readTypeLiteral(node), equalTo(expected));
        }

        @Test
        @DisplayName("readAnyLiteral picks the matching typed decoder")
        void readAnyLiteralDispatch() {
            assertThat(AsmKit.readAnyLiteral(new LdcInsnNode("s")), equalTo("s"));
            assertThat(AsmKit.readAnyLiteral(new LdcInsnNode(7)), equalTo(7));
            assertThat(AsmKit.readAnyLiteral(new LdcInsnNode(7L)), equalTo(7L));
            assertThat(AsmKit.readAnyLiteral(new LdcInsnNode(7.0f)), equalTo(7.0f));
            assertThat(AsmKit.readAnyLiteral(new LdcInsnNode(7.0)), equalTo(7.0));
            assertThat(AsmKit.readAnyLiteral(new InsnNode(Opcodes.NOP)), is(nullValue()));
        }

    }

    @Nested
    @DisplayName("LiteralStack")
    class LiteralStackTests {

        @Test
        @DisplayName("push then pop returns LIFO order")
        void lifoOrder() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push(1);
            stack.push(2);
            stack.push(3);
            assertThat(stack.pop(), equalTo(3));
            assertThat(stack.pop(), equalTo(2));
            assertThat(stack.pop(), equalTo(1));
            assertThat(stack.pop(), is(nullValue()));
        }

        @Test
        @DisplayName("peek returns top without removing")
        void peekLeavesTop() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push("only");
            assertThat(stack.peek(), equalTo("only"));
            assertThat(stack.peek(), equalTo("only"));
            assertThat(stack.size(), equalTo(1));
        }

        @Test
        @DisplayName("overflow evicts the oldest entry (ConcurrentList.removeFirst parity)")
        void overflowEvictsOldest() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(3);
            stack.push(1);
            stack.push(2);
            stack.push(3);
            stack.push(4);
            assertThat(stack.size(), equalTo(3));
            assertThat(stack.pop(), equalTo(4));
            assertThat(stack.pop(), equalTo(3));
            assertThat(stack.pop(), equalTo(2));
            assertThat(stack.pop(), is(nullValue()));
        }

        @Test
        @DisplayName("popInt on non-int top returns null without popping")
        void popIntWrongTypeReturnsNull() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push("not-an-int");
            assertThat(stack.popInt(), is(nullValue()));
            assertThat("top preserved", stack.size(), equalTo(1));
            assertThat(stack.peek(), equalTo("not-an-int"));
        }

        @Test
        @DisplayName("popFloat on non-float top returns null")
        void popFloatWrongTypeReturnsNull() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push(5);
            assertThat(stack.popFloat(), is(nullValue()));
            assertThat(stack.size(), equalTo(1));
        }

        @Test
        @DisplayName("popString on non-string top returns null")
        void popStringWrongTypeReturnsNull() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push(42);
            assertThat(stack.popString(), is(nullValue()));
            assertThat(stack.size(), equalTo(1));
        }

        @Test
        @DisplayName("typed pops return values when top matches")
        void typedPopsWork() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push(1);
            stack.push(2.5f);
            stack.push("hello");
            assertThat(stack.popString(), equalTo("hello"));
            assertThat(stack.popFloat(), equalTo(2.5f));
            assertThat(stack.popInt(), equalTo(1));
            assertThat(stack.isEmpty(), is(true));
        }

        @Test
        @DisplayName("reset clears all entries")
        void resetClears() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push(1);
            stack.push(2);
            stack.reset();
            assertThat(stack.size(), equalTo(0));
            assertThat(stack.isEmpty(), is(true));
            assertThat(stack.pop(), is(nullValue()));
        }

        @Test
        @DisplayName("isEmpty reflects live size")
        void isEmptyFollowsSize() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            assertThat(stack.isEmpty(), is(true));
            stack.push("x");
            assertThat(stack.isEmpty(), is(false));
            stack.pop();
            assertThat(stack.isEmpty(), is(true));
        }

    }

    @Nested
    @DisplayName("findMethod / findMethodInHierarchy")
    class FindMethodTests {

        @Test
        @DisplayName("findMethod by name picks the first match")
        void findByName() {
            ClassNode cn = new ClassNode();
            cn.name = "Foo";
            MethodNode a = new MethodNode(Opcodes.ACC_STATIC, "foo", "()V", null, null);
            MethodNode b = new MethodNode(Opcodes.ACC_STATIC, "bar", "()I", null, null);
            cn.methods.add(a);
            cn.methods.add(b);

            MethodNode found = AsmKit.findMethod(cn, "bar");
            assertThat(found, sameInstance(b));
            assertThat(AsmKit.findMethod(cn, "missing"), is(nullValue()));
        }

        @Test
        @DisplayName("findMethod (name, descriptor) disambiguates overloads")
        void findByNameAndDescriptor() {
            ClassNode cn = new ClassNode();
            cn.name = "Foo";
            MethodNode a = new MethodNode(Opcodes.ACC_STATIC, "op", "(I)V", null, null);
            MethodNode b = new MethodNode(Opcodes.ACC_STATIC, "op", "(J)V", null, null);
            cn.methods.add(a);
            cn.methods.add(b);

            assertThat(AsmKit.findMethod(cn, "op", "(J)V"), sameInstance(b));
            assertThat(AsmKit.findMethod(cn, "op", "(S)V"), is(nullValue()));
        }

    }

    @Nested
    @DisplayName("invocation predicates")
    class InvocationPredicates {

        @Test
        @DisplayName("isInvoke (name-only) matches opcode + owner + name")
        void isInvokeNameOnly() {
            MethodInsnNode node = new MethodInsnNode(Opcodes.INVOKESTATIC, "A", "f", "()V");
            assertThat(AsmKit.isInvoke(node, Opcodes.INVOKESTATIC, "A", "f"), is(true));
            assertThat(AsmKit.isInvoke(node, Opcodes.INVOKEVIRTUAL, "A", "f"), is(false));
            assertThat(AsmKit.isInvoke(node, Opcodes.INVOKESTATIC, "B", "f"), is(false));
            assertThat(AsmKit.isInvoke(node, Opcodes.INVOKESTATIC, "A", "g"), is(false));
        }

        @Test
        @DisplayName("isInvoke (descriptor) also matches the descriptor")
        void isInvokeWithDescriptor() {
            MethodInsnNode node = new MethodInsnNode(Opcodes.INVOKESTATIC, "A", "f", "(I)V");
            assertThat(AsmKit.isInvoke(node, Opcodes.INVOKESTATIC, "A", "f", "(I)V"), is(true));
            assertThat(AsmKit.isInvoke(node, Opcodes.INVOKESTATIC, "A", "f", "(J)V"), is(false));
        }

        @Test
        @DisplayName("non-method nodes fail isInvoke")
        void nonMethodNodeFails() {
            AbstractInsnNode node = new InsnNode(Opcodes.NOP);
            assertThat(AsmKit.isInvoke(node, Opcodes.INVOKESTATIC, "A", "f"), is(false));
        }

    }

    @Test
    @DisplayName("ClassNode created by loadClass-style walk carries populated method list")
    void loadClassSanity() {
        // Sanity: the helpers consume ClassNode populated to the same shape ASM's ClassReader
        // builds. Construct one manually and verify the method lookup works (exercises the
        // same path used by loadClass once a zip read populates the node).
        ClassNode cn = new ClassNode();
        cn.name = "Sample";
        cn.superName = "java/lang/Object";
        MethodNode m = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        cn.methods.add(m);
        assertThat(AsmKit.findMethod(cn, "<clinit>"), notNullValue());
    }

}
