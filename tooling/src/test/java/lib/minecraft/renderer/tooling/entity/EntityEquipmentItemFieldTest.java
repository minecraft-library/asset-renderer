package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins which render-state field an equipment layer's item getter reads - the link the pose flow folds
 * a wearer's body by. Read off the getter's own implementation, the shape {@code HappyGhastRenderer}
 * builds its harness layer with ({@code state -> state.bodyItem}), and nothing for a getter that is
 * more than one field read.
 */
@DisplayName("the field an equipment layer's item getter reads")
class EntityEquipmentItemFieldTest {

    private static final @NotNull String RENDERER = "fixture/HappyGhastRenderer";

    private static final @NotNull String STATE = "fixture/HappyGhastRenderState";

    private static final @NotNull String STACK_DESC = "Lfixture/ItemStack;";

    private static final @NotNull String GETTER_DESC = "(L" + STATE + ";)" + STACK_DESC;

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @AfterEach
    void close() {
        if (this.cache != null) this.cache.close();
    }

    @Test
    @DisplayName("a lambda reading one render-state field names that field")
    void oneFieldReadNamesTheField() throws IOException {
        open(lambda("lambda$new$0", read("bodyItem")));
        assertEquals("bodyItem", EntityEquipmentResolver.itemFieldOf(this.cache, indy("lambda$new$0")));
    }

    @Test
    @DisplayName("a lambda doing more than reading the field names nothing")
    void aComputedStackNamesNothing() throws IOException {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new FieldInsnNode(Opcodes.GETFIELD, STATE, "bodyItem", STACK_DESC));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "fixture/ItemStack", "copy", "()" + STACK_DESC));
        body.add(new InsnNode(Opcodes.ARETURN));
        open(lambda("lambda$new$1", body));
        assertNull(EntityEquipmentResolver.itemFieldOf(this.cache, indy("lambda$new$1")),
            "a copy of the stack is not one field the body could ask about");
    }

    @Test
    @DisplayName("a getter whose implementation is missing names nothing")
    void aMissingImplementationNamesNothing() throws IOException {
        open(lambda("lambda$new$0", read("bodyItem")));
        assertNull(EntityEquipmentResolver.itemFieldOf(this.cache, indy("lambda$new$9")));
    }

    // ------------------------------------------------------------------------------------

    /** The body {@code state -> state.<field>}. */
    private static @NotNull InsnList read(@NotNull String field) {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new FieldInsnNode(Opcodes.GETFIELD, STATE, field, STACK_DESC));
        body.add(new InsnNode(Opcodes.ARETURN));
        return body;
    }

    /** The renderer class holding one static synthetic lambda with the given body. */
    private static @NotNull ClassNode lambda(@NotNull String name, @NotNull InsnList body) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = RENDERER;
        node.superName = "java/lang/Object";
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            name, GETTER_DESC, null, null);
        method.instructions = body;
        node.methods.add(method);
        return node;
    }

    /** The {@code invokedynamic} a renderer constructor builds a {@code Function} getter with. */
    private static @NotNull InvokeDynamicInsnNode indy(@NotNull String implementation) {
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                + "Ljava/lang/invoke/CallSite;",
            false);
        return new InvokeDynamicInsnNode("apply", "()Ljava/util/function/Function;", metafactory,
            Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
            new Handle(Opcodes.H_INVOKESTATIC, RENDERER, implementation, GETTER_DESC, false),
            Type.getType(GETTER_DESC));
    }

    private void open(@NotNull ClassNode @NotNull ... fixtures) throws IOException {
        Path jar = this.tempDir.resolve("fixtures.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (ClassNode fixture : fixtures) {
                zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                fixture.accept(writer);
                zip.write(writer.toByteArray());
                zip.closeEntry();
            }
        }
        this.cache = ClassNodeCache.open(jar);
    }

}
