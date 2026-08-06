package lib.minecraft.renderer.tooling.walk;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.function.Predicate;

/**
 * The {@link Match} factories, one per recognized instruction shape. Every factory takes
 * vanilla internal names verbatim - nothing here normalises a dialect. An off-grid invoke
 * (an {@code INVOKEINTERFACE}, an opcode-parameterised match) spells
 * {@code opcode(op).ofType(..).where(..)} on the walk instead of a wider factory.
 */
public final class Insn {

    private Insn() {}

    /**
     * Builds a recognizer from a node-class token and a predicate on the narrowed type.
     *
     * @param type the node class to narrow to
     * @param test the predicate on the narrowed node
     * @return the recognizer
     */
    public static <N extends AbstractInsnNode> @NotNull Match<N> of(@NotNull Class<N> type, @NotNull Predicate<? super N> test) {
        return new Match<>() {
            @Override public @NotNull Class<N> type() { return type; }
            @Override public boolean test(@NotNull N node) { return test.test(node); }
        };
    }

    /**
     * Matches an {@code INVOKEVIRTUAL} by owner and name; descriptor is not consulted.
     *
     * @param owner the owner's JVM internal name
     * @param name the method name
     * @return the recognizer
     */
    public static @NotNull Match<MethodInsnNode> invokeVirtual(@NotNull String owner, @NotNull String name) {
        return invoke(Opcodes.INVOKEVIRTUAL, owner, name);
    }

    /**
     * Matches an {@code INVOKESTATIC} by owner and name; descriptor is not consulted.
     *
     * @param owner the owner's JVM internal name
     * @param name the method name
     * @return the recognizer
     */
    public static @NotNull Match<MethodInsnNode> invokeStatic(@NotNull String owner, @NotNull String name) {
        return invoke(Opcodes.INVOKESTATIC, owner, name);
    }

    /**
     * Matches an {@code INVOKESTATIC} by owner, name and descriptor.
     *
     * @param owner the owner's JVM internal name
     * @param name the method name
     * @param desc the method descriptor
     * @return the recognizer
     */
    public static @NotNull Match<MethodInsnNode> invokeStatic(@NotNull String owner, @NotNull String name, @NotNull String desc) {
        return of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
            && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc));
    }

    /**
     * Matches an {@code INVOKESPECIAL} by owner and name; descriptor is not consulted.
     *
     * @param owner the owner's JVM internal name
     * @param name the method name
     * @return the recognizer
     */
    public static @NotNull Match<MethodInsnNode> invokeSpecial(@NotNull String owner, @NotNull String name) {
        return invoke(Opcodes.INVOKESPECIAL, owner, name);
    }

    private static @NotNull Match<MethodInsnNode> invoke(int opcode, @NotNull String owner, @NotNull String name) {
        return of(MethodInsnNode.class, mi -> mi.getOpcode() == opcode
            && mi.owner.equals(owner) && mi.name.equals(name));
    }

    /**
     * Matches a {@code GETSTATIC} on the given owner, any field.
     *
     * @param owner the owner's JVM internal name
     * @return the recognizer
     */
    public static @NotNull Match<FieldInsnNode> getStatic(@NotNull String owner) {
        return of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.GETSTATIC && fi.owner.equals(owner));
    }

    /**
     * Matches a {@code GETSTATIC} on the given owner and field name.
     *
     * @param owner the owner's JVM internal name
     * @param name the field name
     * @return the recognizer
     */
    public static @NotNull Match<FieldInsnNode> getStatic(@NotNull String owner, @NotNull String name) {
        return of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.GETSTATIC
            && fi.owner.equals(owner) && fi.name.equals(name));
    }

    /**
     * Matches a {@code PUTSTATIC} on the given owner, any field.
     *
     * @param owner the owner's JVM internal name
     * @return the recognizer
     */
    public static @NotNull Match<FieldInsnNode> putStatic(@NotNull String owner) {
        return of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(owner));
    }

    /**
     * Matches a {@code PUTSTATIC} on the given owner and field name.
     *
     * @param owner the owner's JVM internal name
     * @param name the field name
     * @return the recognizer
     */
    public static @NotNull Match<FieldInsnNode> putStatic(@NotNull String owner, @NotNull String name) {
        return of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC
            && fi.owner.equals(owner) && fi.name.equals(name));
    }

    /**
     * Matches a {@code GETFIELD} by field name and descriptor; the owner is not consulted.
     *
     * @param name the field name
     * @param desc the field descriptor
     * @return the recognizer
     */
    public static @NotNull Match<FieldInsnNode> getField(@NotNull String name, @NotNull String desc) {
        return of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.GETFIELD
            && fi.name.equals(name) && fi.desc.equals(desc));
    }

    /**
     * Matches a {@code PUTFIELD} by owner and field name; descriptor is not consulted.
     *
     * @param owner the owner's JVM internal name
     * @param name the field name
     * @return the recognizer
     */
    public static @NotNull Match<FieldInsnNode> putField(@NotNull String owner, @NotNull String name) {
        return of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTFIELD
            && fi.owner.equals(owner) && fi.name.equals(name));
    }

    /**
     * Matches a {@code NEW} whose target internal name starts with the given prefix - the
     * "any subclass in this package" test. A source that tests equality spells
     * {@code ofType(TypeInsnNode.class).and(t -> t.desc.equals(..))} instead.
     *
     * @param internalNamePrefix the target-type internal-name prefix
     * @return the recognizer
     */
    public static @NotNull Match<TypeInsnNode> new_(@NotNull String internalNamePrefix) {
        return of(TypeInsnNode.class, ti -> ti.getOpcode() == Opcodes.NEW && ti.desc.startsWith(internalNamePrefix));
    }

    /**
     * Matches an {@code INVOKEDYNAMIC} bootstrapped by the lambda metafactory - the shape every
     * javac-emitted lambda call site routes through.
     *
     * @return the recognizer
     */
    public static @NotNull Match<InvokeDynamicInsnNode> lambdaIndy() {
        return of(InvokeDynamicInsnNode.class, AsmWalker::isLambdaInvokeDynamic);
    }

    /**
     * Matches any instruction whose opcode is one of the given values.
     *
     * @param ops the accepted opcodes
     * @return the recognizer
     */
    public static @NotNull Match<AbstractInsnNode> opcode(int @NotNull ... ops) {
        int[] copy = ops.clone();
        return of(AbstractInsnNode.class, in -> {
            for (int op : copy)
                if (in.getOpcode() == op) return true;
            return false;
        });
    }

    /**
     * Matches the opcodes that end a straight-line region - conditional and unconditional
     * jumps, switches, returns and {@code ATHROW}.
     *
     * @return the recognizer
     */
    public static @NotNull Match<AbstractInsnNode> branch() {
        return of(AbstractInsnNode.class, in -> AsmWalker.isBranchInsn(in.getOpcode()));
    }

    /**
     * Matches any instruction of the given node class.
     *
     * @param type the node class to narrow to
     * @return the recognizer
     */
    public static <N extends AbstractInsnNode> @NotNull Match<N> ofType(@NotNull Class<N> type) {
        return of(type, node -> true);
    }

}
