package lib.minecraft.renderer.tooling.util;

import lib.minecraft.renderer.exception.ToolingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    // ============================================================================================
    // Primitive helpers (constants, type / instruction predicates, literal readers)
    // ============================================================================================

    @Nested
    @DisplayName("constants")
    class Constants {

        @Test
        @DisplayName("CLINIT and INIT match JVM convention")
        void clinitAndInit() {
            assertThat(AsmKit.CLINIT, equalTo("<clinit>"));
            assertThat(AsmKit.INIT, equalTo("<init>"));
        }

        @Test
        @DisplayName("OBJECT_INTERNAL is java/lang/Object")
        void objectInternal() {
            assertThat(AsmKit.OBJECT_INTERNAL, equalTo("java/lang/Object"));
        }

        @Test
        @DisplayName("VanillaSourceClasses internal names compose from package roots correctly")
        void vanillaSourceClassesCompose() {
            assertThat(VanillaSourceClasses.BLOCK_ENTITY_RENDERERS,
                equalTo("net/minecraft/client/renderer/blockentity/BlockEntityRenderers"));
            assertThat(VanillaSourceClasses.IDENTIFIER, equalTo("net/minecraft/resources/Identifier"));
            assertThat(VanillaSourceClasses.MODEL_PART, equalTo("net/minecraft/client/model/geom/ModelPart"));
            assertThat(VanillaSourceClasses.LAYER_DEFINITION,
                equalTo("net/minecraft/client/model/geom/builders/LayerDefinition"));
            assertThat(VanillaSourceClasses.MODEL_LAYERS, equalTo("net/minecraft/client/model/geom/ModelLayers"));
            assertThat(VanillaSourceClasses.ENTITY_TYPE, equalTo("net/minecraft/world/entity/EntityType"));
            assertThat(VanillaSourceClasses.LIVING_ENTITY, equalTo("net/minecraft/world/entity/LivingEntity"));
            // Nested-class composition still produces the correct dollar-suffixed name.
            assertThat(VanillaSourceClasses.ENTITY_TYPE_BUILDER,
                equalTo("net/minecraft/world/entity/EntityType$Builder"));
            // Package prefix keeps its trailing slash for startsWith use.
            assertThat(VanillaSourceClasses.EFFECT_PACKAGE_PREFIX, endsWith("/"));
        }

    }

    @Nested
    @DisplayName("require* throwing variants")
    class RequireFamily {

        @Test
        @DisplayName("requireMethod returns matching node")
        void requireMethodHit() {
            ClassNode cn = newClass("Foo");
            MethodNode m = newMethod("doIt", "()V");
            cn.methods.add(m);
            assertThat(AsmKit.requireMethod(cn, "doIt", "test"), sameInstance(m));
        }

        @Test
        @DisplayName("requireMethod throws ToolingException for missing name")
        void requireMethodThrows() {
            ClassNode cn = newClass("Foo");
            ToolingException ex = assertThrows(ToolingException.class,
                () -> AsmKit.requireMethod(cn, "missing", "test"));
            assertThat(ex.getMessage(), containsString("Foo"));
            assertThat(ex.getMessage(), containsString("missing"));
            assertThat(ex.getMessage(), containsString("obfuscated"));
        }

        @Test
        @DisplayName("requireMethod (with descriptor) throws when descriptor mismatches")
        void requireMethodDescThrows() {
            ClassNode cn = newClass("Foo");
            cn.methods.add(newMethod("op", "(I)V"));
            assertThrows(ToolingException.class, () -> AsmKit.requireMethod(cn, "op", "(J)V", "test"));
        }

        @Test
        @DisplayName("requireClinit finds <clinit>")
        void requireClinitHit() {
            ClassNode cn = newClass("Foo");
            MethodNode clinit = newMethod("<clinit>", "()V");
            cn.methods.add(clinit);
            assertThat(AsmKit.requireClinit(cn, "test"), sameInstance(clinit));
        }

        @Test
        @DisplayName("requireField returns matching field")
        void requireFieldHit() {
            ClassNode cn = newClass("Foo");
            FieldNode f = new FieldNode(Opcodes.ACC_STATIC, "BAR", "I", null, null);
            cn.fields.add(f);
            assertThat(AsmKit.requireField(cn, "BAR", "test"), sameInstance(f));
        }

        @Test
        @DisplayName("requireField throws ToolingException for missing field")
        void requireFieldThrows() {
            ClassNode cn = newClass("Foo");
            ToolingException ex = assertThrows(ToolingException.class,
                () -> AsmKit.requireField(cn, "MISSING", "test"));
            assertThat(ex.getMessage(), containsString("MISSING"));
        }

    }

    @Nested
    @DisplayName("findField / findFieldInHierarchy")
    class FindFieldTests {

        @Test
        @DisplayName("findField by name picks the first match")
        void findByName() {
            ClassNode cn = newClass("Foo");
            FieldNode f1 = new FieldNode(0, "a", "I", null, null);
            FieldNode f2 = new FieldNode(0, "b", "J", null, null);
            cn.fields.add(f1);
            cn.fields.add(f2);
            assertThat(AsmKit.findField(cn, "b"), sameInstance(f2));
            assertThat(AsmKit.findField(cn, "missing"), is(nullValue()));
        }

        @Test
        @DisplayName("findField by name + descriptor disambiguates")
        void findByNameAndDesc() {
            ClassNode cn = newClass("Foo");
            FieldNode f = new FieldNode(0, "x", "I", null, null);
            cn.fields.add(f);
            assertThat(AsmKit.findField(cn, "x", "I"), sameInstance(f));
            assertThat(AsmKit.findField(cn, "x", "J"), is(nullValue()));
        }

        @Test
        @DisplayName("findFieldInHierarchy walks the super chain")
        void findInHierarchy(@TempDir Path tmp) throws IOException {
            byte[] childBytes = writeClass("Child", "Parent", List.of(), List.of());
            byte[] parentBytes = writeClass("Parent", "java/lang/Object",
                List.of(new FieldDef("INHERITED", "I")), List.of());
            try (ZipFile zip = makeJar(tmp, Map.of("Child", childBytes, "Parent", parentBytes))) {
                FieldNode found = AsmKit.findFieldInHierarchy(zip, "Child", "INHERITED");
                assertThat(found, notNullValue());
                assertThat(found.name, equalTo("INHERITED"));
                assertThat(AsmKit.findFieldInHierarchy(zip, "Child", "missing"), is(nullValue()));
            }
        }

    }

    @Nested
    @DisplayName("class-hierarchy walks")
    class HierarchyWalks {

        @Test
        @DisplayName("walkSuperChain visits start class then ancestors, stops before Object")
        void walkSuperChainOrder(@TempDir Path tmp) throws IOException {
            byte[] grandchildBytes = writeClass("Grandchild", "Child", List.of(), List.of());
            byte[] childBytes = writeClass("Child", "Parent", List.of(), List.of());
            byte[] parentBytes = writeClass("Parent", "java/lang/Object", List.of(), List.of());
            try (ZipFile zip = makeJar(tmp, Map.of(
                "Grandchild", grandchildBytes,
                "Child", childBytes,
                "Parent", parentBytes
            ))) {
                List<String> visited = new ArrayList<>();
                AsmKit.walkSuperChain(zip, "Grandchild", cn -> visited.add(cn.name));
                assertThat(visited, contains("Grandchild", "Child", "Parent"));
            }
        }

        @Test
        @DisplayName("walkConstructorChain visits every <init> on every chain link")
        void walkConstructorChainCollectsAllInits(@TempDir Path tmp) throws IOException {
            byte[] childBytes = writeClass("Child", "Parent",
                List.of(), List.of(new MethodDef("<init>", "()V"), new MethodDef("foo", "()V")));
            byte[] parentBytes = writeClass("Parent", "java/lang/Object",
                List.of(), List.of(new MethodDef("<init>", "()V")));
            try (ZipFile zip = makeJar(tmp, Map.of("Child", childBytes, "Parent", parentBytes))) {
                List<String> ownerByCall = new ArrayList<>();
                AsmKit.walkConstructorChain(zip, "Child", m -> ownerByCall.add(m.name));
                // Both constructors are named <init>; we should see exactly 2 visits.
                assertThat(ownerByCall, hasSize(2));
                assertThat(ownerByCall, everyItem(equalTo("<init>")));
            }
        }

        @Test
        @DisplayName("extendsClass returns true for direct subclass")
        void extendsDirect(@TempDir Path tmp) throws IOException {
            byte[] childBytes = writeClass("Child", "Parent", List.of(), List.of());
            byte[] parentBytes = writeClass("Parent", "java/lang/Object", List.of(), List.of());
            try (ZipFile zip = makeJar(tmp, Map.of("Child", childBytes, "Parent", parentBytes))) {
                assertThat(AsmKit.extendsClass(zip, "Child", "Parent"), is(true));
                assertThat(AsmKit.extendsClass(zip, "Child", "Child"), is(true));
                assertThat(AsmKit.extendsClass(zip, "Parent", "Child"), is(false));
            }
        }

        @Test
        @DisplayName("extendsClass returns false when an ancestor can't be loaded")
        void extendsMissing(@TempDir Path tmp) throws IOException {
            byte[] childBytes = writeClass("Child", "ForeignParent", List.of(), List.of());
            try (ZipFile zip = makeJar(tmp, Map.of("Child", childBytes))) {
                assertThat(AsmKit.extendsClass(zip, "Child", "Anything"), is(false));
            }
        }

    }

    @Nested
    @DisplayName("descriptor utilities")
    class DescriptorUtilities {

        @Test
        @DisplayName("descriptorReturns matches reference returns")
        void descriptorReturnsRef() {
            assertThat(AsmKit.descriptorReturns("()Ljava/lang/String;", "java/lang/String"), is(true));
            assertThat(AsmKit.descriptorReturns("(IF)Ljava/lang/String;", "java/lang/String"), is(true));
            assertThat(AsmKit.descriptorReturns("()Ljava/lang/String;", "java/lang/Object"), is(false));
            assertThat(AsmKit.descriptorReturns("()V", "java/lang/String"), is(false));
            assertThat(AsmKit.descriptorReturns("()I", "I"), is(false));
        }

        @Test
        @DisplayName("descriptorReturns handles malformed input safely")
        void descriptorReturnsBad() {
            assertThat(AsmKit.descriptorReturns("garbage", "X"), is(false));
            assertThat(AsmKit.descriptorReturns("", "X"), is(false));
        }

        @Test
        @DisplayName("argSlotCount counts wide types as 2")
        void argSlotCountWide() {
            assertThat(AsmKit.argSlotCount("()V"), equalTo(0));
            assertThat(AsmKit.argSlotCount("(I)V"), equalTo(1));
            assertThat(AsmKit.argSlotCount("(IJ)V"), equalTo(3));
            assertThat(AsmKit.argSlotCount("(D)V"), equalTo(2));
            assertThat(AsmKit.argSlotCount("(JDI)V"), equalTo(5));
            assertThat(AsmKit.argSlotCount("(Ljava/lang/String;I)V"), equalTo(2));
        }

        @Test
        @DisplayName("argTypes and returnType delegate to ASM Type")
        void argAndReturnTypes() {
            Type[] args = AsmKit.argTypes("(IF)Ljava/lang/String;");
            assertThat(args.length, equalTo(2));
            assertThat(args[0].getSort(), equalTo(Type.INT));
            assertThat(args[1].getSort(), equalTo(Type.FLOAT));
            assertThat(AsmKit.returnType("(IF)Ljava/lang/String;").getInternalName(), equalTo("java/lang/String"));
        }

        @Test
        @DisplayName("internalNameOfRef unwraps L...; and array refs")
        void internalNameOfRef() {
            assertThat(AsmKit.internalNameOfRef("Ljava/lang/String;"), equalTo("java/lang/String"));
            assertThat(AsmKit.internalNameOfRef("[Ljava/lang/String;"), equalTo("java/lang/String"));
            assertThat(AsmKit.internalNameOfRef("[[Ljava/lang/String;"), equalTo("java/lang/String"));
            assertThat(AsmKit.internalNameOfRef("I"), is(nullValue()));
            assertThat(AsmKit.internalNameOfRef("V"), is(nullValue()));
            assertThat(AsmKit.internalNameOfRef("[I"), is(nullValue()));
        }

    }

    @Nested
    @DisplayName("typed invoke predicates")
    class TypedInvokePredicates {

        private final MethodInsnNode staticCall = new MethodInsnNode(Opcodes.INVOKESTATIC, "A", "f", "()V");
        private final MethodInsnNode virtualCall = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "A", "f", "()V");
        private final MethodInsnNode specialCall = new MethodInsnNode(Opcodes.INVOKESPECIAL, "A", "<init>", "()V");
        private final MethodInsnNode interfaceCall = new MethodInsnNode(Opcodes.INVOKEINTERFACE, "A", "f", "()V");

        @Test
        @DisplayName("isInvokeStatic matches only INVOKESTATIC")
        void invokeStatic() {
            assertThat(AsmKit.isInvokeStatic(staticCall, "A", "f"), is(true));
            assertThat(AsmKit.isInvokeStatic(virtualCall, "A", "f"), is(false));
            assertThat(AsmKit.isInvokeStatic(staticCall, "A", "f", "()V"), is(true));
            assertThat(AsmKit.isInvokeStatic(staticCall, "A", "f", "(I)V"), is(false));
        }

        @Test
        @DisplayName("isInvokeVirtual matches only INVOKEVIRTUAL")
        void invokeVirtual() {
            assertThat(AsmKit.isInvokeVirtual(virtualCall, "A", "f"), is(true));
            assertThat(AsmKit.isInvokeVirtual(staticCall, "A", "f"), is(false));
        }

        @Test
        @DisplayName("isInvokeSpecial matches only INVOKESPECIAL")
        void invokeSpecial() {
            assertThat(AsmKit.isInvokeSpecial(specialCall, "A", "<init>"), is(true));
            assertThat(AsmKit.isInvokeSpecial(virtualCall, "A", "<init>"), is(false));
        }

        @Test
        @DisplayName("isInvokeInterface matches only INVOKEINTERFACE")
        void invokeInterface() {
            assertThat(AsmKit.isInvokeInterface(interfaceCall, "A", "f"), is(true));
            assertThat(AsmKit.isInvokeInterface(virtualCall, "A", "f"), is(false));
        }

    }

    @Nested
    @DisplayName("field-access predicates")
    class FieldAccessPredicates {

        private final FieldInsnNode getStatic = new FieldInsnNode(Opcodes.GETSTATIC, "A", "X", "I");
        private final FieldInsnNode putStatic = new FieldInsnNode(Opcodes.PUTSTATIC, "A", "X", "I");
        private final FieldInsnNode getField = new FieldInsnNode(Opcodes.GETFIELD, "A", "X", "I");
        private final FieldInsnNode putField = new FieldInsnNode(Opcodes.PUTFIELD, "A", "X", "I");

        @Test
        @DisplayName("isGetStatic (owner-only) ignores field name")
        void getStaticOwnerOnly() {
            assertThat(AsmKit.isGetStatic(getStatic, "A"), is(true));
            assertThat(AsmKit.isGetStatic(getStatic, "B"), is(false));
            assertThat(AsmKit.isGetStatic(putStatic, "A"), is(false));
        }

        @Test
        @DisplayName("isGetStatic (owner + name) matches name")
        void getStaticOwnerAndName() {
            assertThat(AsmKit.isGetStatic(getStatic, "A", "X"), is(true));
            assertThat(AsmKit.isGetStatic(getStatic, "A", "Y"), is(false));
        }

        @Test
        @DisplayName("isPutStatic matches owner and optional field name")
        void putStaticPredicates() {
            assertThat(AsmKit.isPutStatic(putStatic, "A"), is(true));
            assertThat(AsmKit.isPutStatic(putStatic, "A", "X"), is(true));
            assertThat(AsmKit.isPutStatic(putStatic, "A", "Y"), is(false));
            assertThat(AsmKit.isPutStatic(getStatic, "A"), is(false));
        }

        @Test
        @DisplayName("isGetField and isPutField require name match")
        void instanceFieldOps() {
            assertThat(AsmKit.isGetField(getField, "A", "X"), is(true));
            assertThat(AsmKit.isGetField(getField, "A", "Y"), is(false));
            assertThat(AsmKit.isPutField(putField, "A", "X"), is(true));
            assertThat(AsmKit.isPutField(getField, "A", "X"), is(false));
        }

    }

    @Nested
    @DisplayName("isLambdaInvokeDynamic")
    class LambdaInvokeDynamic {

        @Test
        @DisplayName("matches LambdaMetafactory.metafactory bootstrap")
        void matchesMetafactory() {
            Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);
            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode("apply", "()LFoo;", bsm);
            assertThat(AsmKit.isLambdaInvokeDynamic(indy), is(true));
        }

        @Test
        @DisplayName("matches LambdaMetafactory.altMetafactory bootstrap")
        void matchesAltMetafactory() {
            Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "altMetafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false);
            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode("apply", "()LFoo;", bsm);
            assertThat(AsmKit.isLambdaInvokeDynamic(indy), is(true));
        }

        @Test
        @DisplayName("rejects non-metafactory bootstrap")
        void rejectsForeignBootstrap() {
            Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/runtime/ObjectMethods", "bootstrap",
                "()V", false);
            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode("apply", "()V", bsm);
            assertThat(AsmKit.isLambdaInvokeDynamic(indy), is(false));
        }

        @Test
        @DisplayName("rejects non-INVOKEDYNAMIC node")
        void rejectsNonIndy() {
            assertThat(AsmKit.isLambdaInvokeDynamic(new InsnNode(Opcodes.NOP)), is(false));
        }

    }

    @Nested
    @DisplayName("pseudo-node helpers")
    class PseudoNodes {

        @Test
        @DisplayName("isPseudoNode returns true for label / frame / line nodes")
        void isPseudo() {
            assertThat(AsmKit.isPseudoNode(new LabelNode()), is(true));
            assertThat(AsmKit.isPseudoNode(new LineNumberNode(42, new LabelNode())), is(true));
            assertThat(AsmKit.isPseudoNode(new InsnNode(Opcodes.NOP)), is(false));
        }

        @Test
        @DisplayName("previousReal skips pseudo nodes")
        void previousRealSkips() {
            InsnList list = new InsnList();
            InsnNode first = new InsnNode(Opcodes.NOP);
            LabelNode label = new LabelNode();
            LineNumberNode ln = new LineNumberNode(1, label);
            InsnNode last = new InsnNode(Opcodes.ICONST_0);
            list.add(first);
            list.add(label);
            list.add(ln);
            list.add(last);

            assertThat(AsmKit.previousReal(last), sameInstance(first));
        }

        @Test
        @DisplayName("previousReal on null returns null")
        void previousRealNullInput() {
            assertThat(AsmKit.previousReal(null), is(nullValue()));
        }

        @Test
        @DisplayName("nextReal skips pseudo nodes")
        void nextRealSkips() {
            InsnList list = new InsnList();
            InsnNode first = new InsnNode(Opcodes.ICONST_0);
            LabelNode label = new LabelNode();
            LineNumberNode ln = new LineNumberNode(1, label);
            InsnNode last = new InsnNode(Opcodes.NOP);
            list.add(first);
            list.add(label);
            list.add(ln);
            list.add(last);

            assertThat(AsmKit.nextReal(first), sameInstance(last));
        }

    }

    @Nested
    @DisplayName("findPreceding (passthrough walker)")
    class FindPrecedingTests {

        @Test
        @DisplayName("returns the first matching node")
        void firstMatch() {
            InsnList list = new InsnList();
            InsnNode a = new InsnNode(Opcodes.NOP);
            FieldInsnNode target = new FieldInsnNode(Opcodes.GETSTATIC, "A", "X", "I");
            InsnNode passthrough = new InsnNode(Opcodes.ICONST_3);
            InsnNode tail = new InsnNode(Opcodes.NOP);
            list.add(a);
            list.add(target);
            list.add(passthrough);
            list.add(tail);

            AbstractInsnNode found = AsmKit.findPreceding(tail,
                n -> AsmKit.isGetStatic(n, "A", "X"),
                op -> op == Opcodes.ICONST_3);
            assertThat(found, sameInstance(target));
        }

        @Test
        @DisplayName("pseudo-nodes are always skipped")
        void pseudoNodesSkipped() {
            InsnList list = new InsnList();
            FieldInsnNode target = new FieldInsnNode(Opcodes.GETSTATIC, "A", "X", "I");
            LabelNode label = new LabelNode();
            LineNumberNode ln = new LineNumberNode(1, label);
            InsnNode tail = new InsnNode(Opcodes.NOP);
            list.add(target);
            list.add(label);
            list.add(ln);
            list.add(tail);

            AbstractInsnNode found = AsmKit.findPreceding(tail,
                n -> AsmKit.isGetStatic(n, "A", "X"),
                op -> false);
            assertThat(found, sameInstance(target));
        }

        @Test
        @DisplayName("returns null when a non-passthrough mismatch is hit before match (R2 contract)")
        void mismatchAbortsWalk() {
            // Reproduces the EntityBlockOverlayResolver.findPrecedingAxisField contract:
            // an ALOAD between the call and the target GETSTATIC must abort the walk.
            InsnList list = new InsnList();
            FieldInsnNode target = new FieldInsnNode(Opcodes.GETSTATIC, "Axis", "YP", "Lfoo/Axis;");
            VarInsnNode aload = new VarInsnNode(Opcodes.ALOAD, 0); // unwanted real op
            InsnNode floatLit = new InsnNode(Opcodes.FCONST_1);
            InsnNode tail = new InsnNode(Opcodes.NOP);
            list.add(target);
            list.add(aload);
            list.add(floatLit);
            list.add(tail);

            AbstractInsnNode found = AsmKit.findPreceding(tail,
                n -> AsmKit.isGetStatic(n, "Axis", "YP"),
                op -> op == Opcodes.FCONST_0 || op == Opcodes.FCONST_1 || op == Opcodes.FCONST_2);
            assertThat(found, is(nullValue()));
        }

        @Test
        @DisplayName("returns null at start of method")
        void noPredecessor() {
            InsnList list = new InsnList();
            InsnNode only = new InsnNode(Opcodes.NOP);
            list.add(only);
            assertThat(AsmKit.findPreceding(only, n -> true, op -> false), is(nullValue()));
        }

    }

    @Nested
    @DisplayName("findFollowingPutStatic")
    class FindFollowingPutStaticTests {

        @Test
        @DisplayName("returns matching field name")
        void findsField() {
            InsnList list = new InsnList();
            InsnNode head = new InsnNode(Opcodes.NOP);
            InsnNode noise = new InsnNode(Opcodes.NOP);
            FieldInsnNode put = new FieldInsnNode(Opcodes.PUTSTATIC, "A", "FOO", "I");
            list.add(head);
            list.add(noise);
            list.add(put);

            String name = AsmKit.findFollowingPutStatic(head, "A", n -> false);
            assertThat(name, equalTo("FOO"));
        }

        @Test
        @DisplayName("stop anchor aborts walk with null return")
        void stopAnchorAborts() {
            InsnList list = new InsnList();
            InsnNode head = new InsnNode(Opcodes.NOP);
            InsnNode anchor = new InsnNode(Opcodes.RETURN);
            FieldInsnNode put = new FieldInsnNode(Opcodes.PUTSTATIC, "A", "FOO", "I");
            list.add(head);
            list.add(anchor);
            list.add(put);

            String name = AsmKit.findFollowingPutStatic(head, "A", n -> n.getOpcode() == Opcodes.RETURN);
            assertThat(name, is(nullValue()));
        }

        @Test
        @DisplayName("returns null when no PUTSTATIC follows")
        void noMatch() {
            InsnList list = new InsnList();
            InsnNode head = new InsnNode(Opcodes.NOP);
            list.add(head);
            assertThat(AsmKit.findFollowingPutStatic(head, "A", n -> false), is(nullValue()));
        }

    }

    @Nested
    @DisplayName("containsInvoke / containsFieldOp")
    class ContainsHelpers {

        @Test
        @DisplayName("containsInvoke (name only) returns true on match")
        void containsInvokeNameOnly() {
            MethodNode m = newMethod("body", "()V");
            m.instructions.add(new InsnNode(Opcodes.NOP));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "A", "f", "()V"));
            m.instructions.add(new InsnNode(Opcodes.RETURN));

            assertThat(AsmKit.containsInvoke(m, Opcodes.INVOKEVIRTUAL, "A", "f"), is(true));
            assertThat(AsmKit.containsInvoke(m, Opcodes.INVOKESTATIC, "A", "f"), is(false));
            assertThat(AsmKit.containsInvoke(m, Opcodes.INVOKEVIRTUAL, "A", "g"), is(false));
        }

        @Test
        @DisplayName("containsInvoke with descriptor disambiguates")
        void containsInvokeWithDescriptor() {
            MethodNode m = newMethod("body", "()V");
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "A", "f", "(I)V"));

            assertThat(AsmKit.containsInvoke(m, Opcodes.INVOKEVIRTUAL, "A", "f", "(I)V"), is(true));
            assertThat(AsmKit.containsInvoke(m, Opcodes.INVOKEVIRTUAL, "A", "f", "(J)V"), is(false));
        }

        @Test
        @DisplayName("containsFieldOp matches GETSTATIC / PUTSTATIC")
        void containsFieldOpStatic() {
            MethodNode m = newMethod("body", "()V");
            m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "A", "X", "I"));

            assertThat(AsmKit.containsFieldOp(m, Opcodes.GETSTATIC, "A", "X"), is(true));
            assertThat(AsmKit.containsFieldOp(m, Opcodes.GETSTATIC, "A", "Y"), is(false));
            assertThat(AsmKit.containsFieldOp(m, Opcodes.PUTSTATIC, "A", "X"), is(false));
        }

    }

    @Nested
    @DisplayName("detectIntForLoop")
    class DetectIntForLoop {

        /**
         * Builds the canonical test-at-top {@code for (int i = init; i < bound; i += step)} pattern.
         * Returns the first instruction (the {@code ICONST/BIPUSH init}).
         */
        private static AbstractInsnNode buildLoop(InsnList list, int slot, int init, int bound, int step, AbstractInsnNode... bodyInsns) {
            // init; istore <slot>; test_label: iload <slot>; <bound>; if_icmpge exit; <body>; iinc slot,step; goto test_label; exit:
            AbstractInsnNode initNode = init >= -1 && init <= 5 ? new InsnNode(Opcodes.ICONST_0 + init) : new IntInsnNode(Opcodes.BIPUSH, init);
            list.add(initNode);
            list.add(new VarInsnNode(Opcodes.ISTORE, slot));
            LabelNode testLabel = new LabelNode();
            LabelNode exitLabel = new LabelNode();
            list.add(testLabel);
            list.add(new VarInsnNode(Opcodes.ILOAD, slot));
            list.add(bound >= -1 && bound <= 5 ? new InsnNode(Opcodes.ICONST_0 + bound) : new IntInsnNode(Opcodes.BIPUSH, bound));
            list.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IF_ICMPGE, exitLabel));
            for (AbstractInsnNode body : bodyInsns) list.add(body);
            list.add(new org.objectweb.asm.tree.IincInsnNode(slot, step));
            list.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, testLabel));
            list.add(exitLabel);
            return initNode;
        }

        @Test
        @DisplayName("squid-shape for(i=0; i<8; i++) detects with slot=6, init=0, bound=8, step=1")
        void canonicalForLoop() {
            MethodNode m = newMethod("body", "()V");
            AbstractInsnNode bodyMarker = new InsnNode(Opcodes.NOP);
            AbstractInsnNode initNode = buildLoop(m.instructions, 6, 0, 8, 1, bodyMarker);
            // append an instruction after the loop so firstInsnAfterLoop has something to find
            m.instructions.add(new InsnNode(Opcodes.RETURN));

            AsmKit.IntForLoop loop = AsmKit.detectIntForLoop(initNode);
            assertThat("loop detected", loop, notNullValue());
            assertThat(loop.iteratorSlot(), equalTo(6));
            assertThat(loop.initValue(), equalTo(0));
            assertThat(loop.boundExclusive(), equalTo(8));
            assertThat(loop.step(), equalTo(1));
            assertThat(loop.iterations(), equalTo(8));
            assertThat(loop.firstBodyInsn(), sameInstance(bodyMarker));
            assertThat(loop.firstInsnAfterLoop().getOpcode(), equalTo(Opcodes.RETURN));
        }

        @Test
        @DisplayName("blaze-shape for(i=4; i<8; i++) with BIPUSH bound")
        void bipushBoundLoop() {
            MethodNode m = newMethod("body", "()V");
            // We test a loop that starts at i=4 (still small enough for ICONST_4) and goes to 8 (BIPUSH).
            AbstractInsnNode initNode = buildLoop(m.instructions, 4, 4, 8, 1, new InsnNode(Opcodes.NOP));
            m.instructions.add(new InsnNode(Opcodes.RETURN));

            AsmKit.IntForLoop loop = AsmKit.detectIntForLoop(initNode);
            assertThat(loop, notNullValue());
            assertThat(loop.initValue(), equalTo(4));
            assertThat(loop.boundExclusive(), equalTo(8));
            assertThat(loop.iterations(), equalTo(4));
        }

        @Test
        @DisplayName("step=2 captured from IINC operand")
        void stepGreaterThanOne() {
            MethodNode m = newMethod("body", "()V");
            AbstractInsnNode initNode = buildLoop(m.instructions, 3, 0, 10, 2, new InsnNode(Opcodes.NOP));
            m.instructions.add(new InsnNode(Opcodes.RETURN));

            AsmKit.IntForLoop loop = AsmKit.detectIntForLoop(initNode);
            assertThat(loop, notNullValue());
            assertThat(loop.step(), equalTo(2));
            assertThat(loop.iterations(), equalTo(5));
        }

        @Test
        @DisplayName("non-init opcode at candidateInit returns null")
        void rejectNonInit() {
            // ALOAD_0 isn't a numeric literal; not a loop init.
            AbstractInsnNode notInit = new VarInsnNode(Opcodes.ALOAD, 0);
            assertThat(AsmKit.detectIntForLoop(notInit), is(nullValue()));
        }

        @Test
        @DisplayName("missing ISTORE after init returns null")
        void rejectMissingStore() {
            MethodNode m = newMethod("body", "()V");
            // ICONST_0 followed by something other than ISTORE.
            AbstractInsnNode init = new InsnNode(Opcodes.ICONST_0);
            m.instructions.add(init);
            m.instructions.add(new InsnNode(Opcodes.POP));
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            assertThat(AsmKit.detectIntForLoop(init), is(nullValue()));
        }

        @Test
        @DisplayName("IINC against a different slot inside body returns null")
        void rejectIincOnDifferentSlot() {
            MethodNode m = newMethod("body", "()V");
            // Build a malformed loop where the IINC inside body targets slot 99, not the iterator.
            AbstractInsnNode init = new InsnNode(Opcodes.ICONST_0);
            m.instructions.add(init);
            m.instructions.add(new VarInsnNode(Opcodes.ISTORE, 5));
            LabelNode test = new LabelNode();
            LabelNode exit = new LabelNode();
            m.instructions.add(test);
            m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
            m.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 4));
            m.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IF_ICMPGE, exit));
            m.instructions.add(new org.objectweb.asm.tree.IincInsnNode(99, 1));   // wrong slot
            m.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, test));
            m.instructions.add(exit);
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            assertThat(AsmKit.detectIntForLoop(init), is(nullValue()));
        }

        @Test
        @DisplayName("iterations() returns 0 for empty range")
        void zeroIterations() {
            AsmKit.IntForLoop empty = new AsmKit.IntForLoop(0, 5, 3, 1,
                new InsnNode(Opcodes.NOP), new InsnNode(Opcodes.NOP));
            assertThat(empty.iterations(), equalTo(0));
        }

    }

    @Nested
    @DisplayName("lambda metafactory helpers")
    class LambdaMetafactoryHelpers {

        @Test
        @DisplayName("extractLambdaHandle returns bsmArgs[1]")
        void extractFromValidIndy() {
            Handle target = new Handle(Opcodes.H_NEWINVOKESPECIAL, "Foo", "<init>", "()V", false);
            Object samType = Type.getMethodType("()V");
            Object handleArg = target;
            Object instType = Type.getMethodType("()V");
            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode("apply", "()LIFoo;",
                metafactoryBsm(), samType, handleArg, instType);
            assertThat(AsmKit.extractLambdaHandle(indy), sameInstance(target));
        }

        @Test
        @DisplayName("extractLambdaHandle returns null on short bsmArgs")
        void extractFromShortBsmArgs() {
            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode("apply", "()V",
                metafactoryBsm(), Type.getMethodType("()V"));
            assertThat(AsmKit.extractLambdaHandle(indy), is(nullValue()));
        }

        @Test
        @DisplayName("resolveLambdaTargetClass handles H_NEWINVOKESPECIAL constructor refs")
        void resolveCtorRef() {
            Handle ctor = new Handle(Opcodes.H_NEWINVOKESPECIAL, "MyRenderer", "<init>", "()V", false);
            InvokeDynamicInsnNode indy = makeIndy(ctor);
            ClassNode owner = newClass("Caller");
            assertThat(AsmKit.resolveLambdaTargetClass(indy, owner), equalTo("MyRenderer"));
        }

        @Test
        @DisplayName("resolveLambdaTargetClass handles H_INVOKESTATIC synthetic lambda body")
        void resolveStaticLambda() {
            Handle lambdaRef = new Handle(Opcodes.H_INVOKESTATIC, "Caller", "lambda$0", "()LFoo;", false);
            InvokeDynamicInsnNode indy = makeIndy(lambdaRef);
            ClassNode caller = newClass("Caller");
            MethodNode lambda = newMethod("lambda$0", "()LFoo;");
            lambda.instructions.add(new TypeInsnNode(Opcodes.NEW, "MyRenderer"));
            lambda.instructions.add(new InsnNode(Opcodes.DUP));
            lambda.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "MyRenderer", "<init>", "()V"));
            lambda.instructions.add(new InsnNode(Opcodes.ARETURN));
            caller.methods.add(lambda);

            assertThat(AsmKit.resolveLambdaTargetClass(indy, caller), equalTo("MyRenderer"));
        }

        @Test
        @DisplayName("walkLambdaBody invokes visitor for every body node (R4 contract)")
        void walkLambdaBodyVisitor() {
            Handle lambdaRef = new Handle(Opcodes.H_INVOKESTATIC, "Caller", "lambda$0", "()LFoo;", false);
            InvokeDynamicInsnNode indy = makeIndy(lambdaRef);
            ClassNode caller = newClass("Caller");
            MethodNode lambda = newMethod("lambda$0", "()LFoo;");
            lambda.instructions.add(new TypeInsnNode(Opcodes.NEW, "MyRenderer"));
            lambda.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "ModelLayers", "PIG", "Lfoo/ModelLayerLocation;"));
            lambda.instructions.add(new InsnNode(Opcodes.ARETURN));
            caller.methods.add(lambda);

            List<Integer> seenOpcodes = new ArrayList<>();
            String target = AsmKit.walkLambdaBody(indy, caller, n -> seenOpcodes.add(n.getOpcode()));

            assertThat(target, equalTo("MyRenderer"));
            assertThat(seenOpcodes, hasSize(3));
            assertThat(seenOpcodes.get(0), equalTo(Opcodes.NEW));
            assertThat(seenOpcodes.get(1), equalTo(Opcodes.GETSTATIC));
        }

        @Test
        @DisplayName("walkLambdaBody returns ctor owner without invoking visitor for H_NEWINVOKESPECIAL")
        void walkLambdaBodyCtor() {
            Handle ctor = new Handle(Opcodes.H_NEWINVOKESPECIAL, "MyRenderer", "<init>", "()V", false);
            InvokeDynamicInsnNode indy = makeIndy(ctor);
            ClassNode caller = newClass("Caller");
            List<AbstractInsnNode> visited = new ArrayList<>();
            String target = AsmKit.walkLambdaBody(indy, caller, visited::add);
            assertThat(target, equalTo("MyRenderer"));
            assertThat(visited, is(empty()));
        }

    }

    @Nested
    @DisplayName("string concat helpers")
    class StringConcatHelpers {

        private InvokeDynamicInsnNode makeConcatIndy(String descriptor, Object... bsmArgs) {
            Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                AsmKit.STRING_CONCAT_FACTORY, "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false);
            return new InvokeDynamicInsnNode("makeConcatWithConstants", descriptor, bsm, bsmArgs);
        }

        @Test
        @DisplayName("resolveStringConcatRecipe returns recipe for typical (I)String concat")
        void resolveTypicalRecipe() {
            InvokeDynamicInsnNode indy = makeConcatIndy("(I)Ljava/lang/String;", "tentacle");
            assertThat(AsmKit.resolveStringConcatRecipe(indy), equalTo("tentacle"));
        }

        @Test
        @DisplayName("resolveStringConcatRecipe returns null when bsm is not StringConcatFactory")
        void rejectsForeignBsm() {
            Handle foreignBsm = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false);
            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode("makeConcatWithConstants",
                "(I)Ljava/lang/String;", foreignBsm, "tentacle");
            assertThat(AsmKit.resolveStringConcatRecipe(indy), is(nullValue()));
        }

        @Test
        @DisplayName("resolveStringConcatRecipe returns null when indy name doesn't match")
        void rejectsWrongName() {
            Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                AsmKit.STRING_CONCAT_FACTORY, "makeConcatWithConstants", "()V", false);
            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode("apply",
                "(I)Ljava/lang/String;", bsm, "tentacle");
            assertThat(AsmKit.resolveStringConcatRecipe(indy), is(nullValue()));
        }

        @Test
        @DisplayName("resolveStringConcatRecipe returns null when bsmArgs[0] is not a String")
        void rejectsNonStringRecipe() {
            InvokeDynamicInsnNode indy = makeConcatIndy("(I)Ljava/lang/String;", 42);
            assertThat(AsmKit.resolveStringConcatRecipe(indy), is(nullValue()));
        }

        @Test
        @DisplayName("applyStringConcatRecipeWithInt substitutes the dynamic placeholder")
        void applySubstitutesPlaceholder() {
            assertThat(AsmKit.applyStringConcatRecipeWithInt("tentacle", 3), equalTo("tentacle3"));
            assertThat(AsmKit.applyStringConcatRecipeWithInt("_tentacle", 3), equalTo("3_tentacle"));
            assertThat(AsmKit.applyStringConcatRecipeWithInt("seg_", 7), equalTo("seg7_7"));
        }

        @Test
        @DisplayName("applyStringConcatRecipeWithInt returns input unchanged when no placeholder")
        void applyNoPlaceholderUnchanged() {
            assertThat(AsmKit.applyStringConcatRecipeWithInt("plain", 99), equalTo("plain"));
        }

        @Test
        @DisplayName("findStringConcatRecipeIn walks helper for first matching indy")
        void findInHelper() {
            MethodNode helper = newMethod("createTentacleName", "(I)Ljava/lang/String;");
            helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
            helper.instructions.add(makeConcatIndy("(I)Ljava/lang/String;", "tentacle"));
            helper.instructions.add(new InsnNode(Opcodes.ARETURN));
            assertThat(AsmKit.findStringConcatRecipeIn(helper), equalTo("tentacle"));
        }

        @Test
        @DisplayName("findStringConcatRecipeIn returns null when helper has no concat indy")
        void findInHelperWithoutConcat() {
            MethodNode helper = newMethod("getCachedName", "(I)Ljava/lang/String;");
            helper.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "PartNames", "TENTACLE_0", "Ljava/lang/String;"));
            helper.instructions.add(new InsnNode(Opcodes.ARETURN));
            assertThat(AsmKit.findStringConcatRecipeIn(helper), is(nullValue()));
        }
    }

    @Nested
    @DisplayName("extractGenericTypeParameter")
    class GenericSignatureParser {

        @Test
        @DisplayName("extracts inner type from L<outer><L<inner>;>;")
        void simpleParameter() {
            String sig = "Lnet/minecraft/world/entity/EntityType<Lnet/minecraft/world/entity/monster/Zombie;>;";
            String inner = AsmKit.extractGenericTypeParameter(sig, "net/minecraft/world/entity/EntityType");
            assertThat(inner, equalTo("net/minecraft/world/entity/monster/Zombie"));
        }

        @Test
        @DisplayName("returns null on wildcard parameter")
        void wildcardReturnsNull() {
            String sig = "Lnet/minecraft/world/entity/EntityType<*>;";
            assertThat(AsmKit.extractGenericTypeParameter(sig, "net/minecraft/world/entity/EntityType"), is(nullValue()));
        }

        @Test
        @DisplayName("returns null when outer type doesn't match")
        void wrongOuterReturnsNull() {
            String sig = "Lfoo/Other<Lbar/Inner;>;";
            assertThat(AsmKit.extractGenericTypeParameter(sig, "foo/Different"), is(nullValue()));
        }

    }

    @Nested
    @DisplayName("diagnostic formatters")
    class DiagnosticFormatters {

        @Test
        @DisplayName("diagMissingClass emits canonical WARN")
        void missingClass() {
            Diagnostics diag = new Diagnostics();
            AsmKit.diagMissingClass(diag, "Foo", "registry walk");
            assertThat(diag.entries(), contains("WARN: 'Foo' class missing from jar - registry walk"));
        }

        @Test
        @DisplayName("diagMissingMethod with descriptor")
        void missingMethodWithDesc() {
            Diagnostics diag = new Diagnostics();
            AsmKit.diagMissingMethod(diag, "Foo", "bar", "(I)V", "init walk");
            assertThat(diag.entries(), contains("WARN: Method 'Foo.bar(I)V' missing - init walk"));
        }

        @Test
        @DisplayName("diagMissingMethod without descriptor")
        void missingMethodNoDesc() {
            Diagnostics diag = new Diagnostics();
            AsmKit.diagMissingMethod(diag, "Foo", "bar", null, "init walk");
            assertThat(diag.entries(), contains("WARN: Method 'Foo.bar' missing - init walk"));
        }

        @Test
        @DisplayName("diagMissingField emits canonical WARN")
        void missingField() {
            Diagnostics diag = new Diagnostics();
            AsmKit.diagMissingField(diag, "Foo", "X", "tint walk");
            assertThat(diag.entries(), contains("WARN: Field 'Foo.X' missing - tint walk"));
        }

        @Test
        @DisplayName("diagUnexpectedPattern emits canonical WARN")
        void unexpectedPattern() {
            Diagnostics diag = new Diagnostics();
            AsmKit.diagUnexpectedPattern(diag, "Builder.of call", "BlockColors.<clinit>", "INVOKESTATIC X.f");
            assertThat(diag.entries(), contains(
                "WARN: Unexpected 'Builder.of call' at BlockColors.<clinit> (found: INVOKESTATIC X.f)"
            ));
        }

    }

    @Nested
    @DisplayName("LiteralStack pushNonLiteral + popXOrZero")
    class LiteralStackNonLiteral {

        @Test
        @DisplayName("popIntOrZero on empty stack returns 0 silently")
        void emptyIsSilentZero() {
            Diagnostics diag = new Diagnostics();
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            assertThat(stack.popIntOrZero(diag, "minecraft:foo", "site-A"), equalTo(0));
            assertThat(diag.isEmpty(), is(true));
        }

        @Test
        @DisplayName("popIntOrZero on a non-literal marker returns 0 and emits WARN")
        void markerEmitsWarn() {
            Diagnostics diag = new Diagnostics();
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.pushNonLiteral();
            assertThat(stack.popIntOrZero(diag, "minecraft:foo", "addBox y"), equalTo(0));
            assertThat(diag.entries(), contains(containsString(
                "minecraft:foo at addBox y: non-literal argument consumed"
            )));
            assertThat(stack.isEmpty(), is(true));
        }

        @Test
        @DisplayName("popIntOrZero on matching int returns value, no WARN")
        void intReturnsValue() {
            Diagnostics diag = new Diagnostics();
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push(42);
            assertThat(stack.popIntOrZero(diag, "ctx", "site"), equalTo(42));
            assertThat(diag.isEmpty(), is(true));
        }

        @Test
        @DisplayName("popIntOrZero coerces a Number-but-not-Integer top (Float on numStack) silently")
        void numericCoercion() {
            Diagnostics diag = new Diagnostics();
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push(2.7f);
            // Number subclass: coerces via Number.intValue() without WARN. Matches the
            // original ToolingBlockEntities Parser behaviour where popIntWithDiagnostics
            // called .intValue() on whatever Number sat on top.
            assertThat(stack.popIntOrZero(diag, "ctx", "site"), equalTo(2));
            assertThat(diag.isEmpty(), is(true));
        }

        @Test
        @DisplayName("popIntOrZero on a String (non-Number) top emits WARN and returns 0")
        void nonNumericWrongType() {
            Diagnostics diag = new Diagnostics();
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push("oops");
            assertThat(stack.popIntOrZero(diag, "ctx", "site"), equalTo(0));
            assertThat(diag.entries(), contains(containsString("type mismatch popping int")));
        }

        @Test
        @DisplayName("popFloatOrZero mirrors popIntOrZero across the four cases")
        void floatVariantSameShape() {
            Diagnostics diag = new Diagnostics();
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            // empty
            assertThat(stack.popFloatOrZero(diag, "ctx", "a"), equalTo(0f));
            // marker
            stack.pushNonLiteral();
            assertThat(stack.popFloatOrZero(diag, "ctx", "b"), equalTo(0f));
            // value
            stack.push(3.14f);
            assertThat(stack.popFloatOrZero(diag, "ctx", "c"), equalTo(3.14f));
            // numeric coercion
            stack.push(7);
            assertThat(stack.popFloatOrZero(diag, "ctx", "d"), equalTo(7f));
            assertThat(diag.entries().size(), equalTo(1)); // only the marker fired
        }

        @Test
        @DisplayName("pushNonLiteral respects capacity (oldest evicted on overflow)")
        void nonLiteralEviction() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(2);
            stack.push("a");
            stack.pushNonLiteral();
            stack.push("c");
            assertThat(stack.size(), equalTo(2));
            // First entry "a" was evicted; marker is at slot 0, "c" at slot 1.
        }

        @Test
        @DisplayName("removeFirst pops the oldest entry (FIFO)")
        void removeFirstFifo() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            stack.push("first");
            stack.push("second");
            stack.push("third");
            assertThat(stack.removeFirst(), equalTo("first"));
            assertThat(stack.size(), equalTo(2));
            assertThat(stack.peek(), equalTo("third"));
        }

        @Test
        @DisplayName("removeFirst on empty returns null")
        void removeFirstEmpty() {
            AsmKit.LiteralStack stack = new AsmKit.LiteralStack(4);
            assertThat(stack.removeFirst(), is(nullValue()));
        }

    }

    @Nested
    @DisplayName("SlotTracker")
    class SlotTrackerTests {

        @Test
        @DisplayName("store then load returns the stored value")
        void storeLoad() {
            AsmKit.SlotTracker<String> tracker = new AsmKit.SlotTracker<>();
            tracker.store(0, "this");
            tracker.store(3, "bodyRot");
            assertThat(tracker.load(0), equalTo("this"));
            assertThat(tracker.load(3), equalTo("bodyRot"));
            assertThat(tracker.load(5), is(nullValue()));
        }

        @Test
        @DisplayName("store replaces previous binding")
        void storeOverwrites() {
            AsmKit.SlotTracker<Integer> tracker = new AsmKit.SlotTracker<>();
            tracker.store(0, 1);
            tracker.store(0, 2);
            assertThat(tracker.load(0), equalTo(2));
            assertThat(tracker.size(), equalTo(1));
        }

        @Test
        @DisplayName("clear removes single slot")
        void clearOne() {
            AsmKit.SlotTracker<String> tracker = new AsmKit.SlotTracker<>();
            tracker.store(0, "a");
            tracker.store(1, "b");
            assertThat(tracker.clear(0), equalTo("a"));
            assertThat(tracker.load(0), is(nullValue()));
            assertThat(tracker.load(1), equalTo("b"));
        }

        @Test
        @DisplayName("reset clears all bindings")
        void resetAll() {
            AsmKit.SlotTracker<String> tracker = new AsmKit.SlotTracker<>();
            tracker.store(0, "a");
            tracker.store(1, "b");
            tracker.reset();
            assertThat(tracker.isEmpty(), is(true));
            assertThat(tracker.size(), equalTo(0));
        }

    }

    @Nested
    @DisplayName("loadClass / requireClass against a real zip")
    class LoadClassRealZip {

        @Test
        @DisplayName("loadClass returns a populated ClassNode")
        void loadsExisting(@TempDir Path tmp) throws IOException {
            byte[] bytes = writeClass("Foo", "java/lang/Object", List.of(), List.of(new MethodDef("doIt", "()V")));
            try (ZipFile zip = makeJar(tmp, Map.of("Foo", bytes))) {
                ClassNode cn = AsmKit.loadClass(zip, "Foo");
                assertThat(cn, notNullValue());
                assertThat(cn.name, equalTo("Foo"));
                assertThat(AsmKit.findMethod(cn, "doIt"), notNullValue());
            }
        }

        @Test
        @DisplayName("loadClass returns null for missing entries")
        void loadsMissing(@TempDir Path tmp) throws IOException {
            try (ZipFile zip = makeJar(tmp, Map.of())) {
                assertThat(AsmKit.loadClass(zip, "Missing"), is(nullValue()));
            }
        }

        @Test
        @DisplayName("requireClass throws for missing entries")
        void requireMissing(@TempDir Path tmp) throws IOException {
            try (ZipFile zip = makeJar(tmp, Map.of())) {
                ToolingException ex = assertThrows(ToolingException.class,
                    () -> AsmKit.requireClass(zip, "Missing", "test"));
                assertThat(ex.getMessage(), containsString("Missing"));
                assertThat(ex.getMessage(), containsString("obfuscated"));
            }
        }

    }

    // ============================================================================================
    // Fixture helpers — synthetic ClassWriter bytecode + in-memory jars
    // ============================================================================================

    private static ClassNode newClass(String internalName) {
        ClassNode cn = new ClassNode();
        cn.name = internalName;
        cn.superName = "java/lang/Object";
        cn.access = Opcodes.ACC_PUBLIC;
        cn.version = Opcodes.V21;
        return cn;
    }

    private static MethodNode newMethod(String name, String desc) {
        return new MethodNode(Opcodes.ACC_STATIC, name, desc, null, null);
    }

    private static Handle metafactoryBsm() {
        return new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false);
    }

    private static InvokeDynamicInsnNode makeIndy(Handle target) {
        return new InvokeDynamicInsnNode("apply", "()Ljava/util/function/Supplier;",
            metafactoryBsm(),
            Type.getMethodType("()Ljava/lang/Object;"),
            target,
            Type.getMethodType("()Ljava/lang/Object;"));
    }

    private record FieldDef(String name, String desc) {}
    private record MethodDef(String name, String desc) {}

    private static byte[] writeClass(String internalName, String superName, List<FieldDef> fields, List<MethodDef> methods) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        for (FieldDef f : fields)
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, f.name(), f.desc(), null, null).visitEnd();
        for (MethodDef m : methods) {
            org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                m.name().equals("<init>") ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                m.name(), m.desc(), null, null);
            mv.visitCode();
            if (m.name().equals("<init>")) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ZipFile makeJar(Path dir, Map<String, byte[]> classes) throws IOException {
        Path jar = dir.resolve("fixture.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            // Preserve insertion order so tests with chained classes always read consistently.
            Map<String, byte[]> ordered = new LinkedHashMap<>(classes);
            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return new ZipFile(jar.toFile());
    }

}
