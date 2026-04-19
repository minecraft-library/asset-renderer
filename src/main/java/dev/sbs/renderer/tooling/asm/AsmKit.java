package dev.sbs.renderer.tooling.asm;

import dev.sbs.renderer.exception.AssetPipelineException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Shared ASM scaffolding used by the block-entity tooling parsers
 * ({@link dev.sbs.renderer.tooling.ToolingBlockTints},
 * {@link dev.sbs.renderer.tooling.ToolingPotionColors},
 * {@link dev.sbs.renderer.tooling.ToolingBlockEntities}).
 * <p>
 * Each parser walks a bytecode method looking for the same primitives - decoding integer /
 * float / string literals off {@code LDC} / {@code BIPUSH} / {@code ICONST_X} / {@code FCONST_X},
 * accumulating recent literals into a bounded stack that survives across intervening
 * instructions, and loading classes out of the deobfuscated client jar with a consistent
 * "obfuscated jar" error. Before extraction those routines were duplicated three times with
 * slight drift between copies; this utility centralises them so a version-bump fix lands in
 * one place.
 * <p>
 * None of the helpers here know about the vanilla semantic patterns the callers are hunting
 * for (tint sources, effect colours, cube literals). Those stay in the individual parsers -
 * this class purely owns the bytecode primitives.
 *
 * @see dev.sbs.renderer.tooling.ToolingBlockTints
 * @see dev.sbs.renderer.tooling.ToolingPotionColors
 * @see dev.sbs.renderer.tooling.ToolingBlockEntities
 */
@UtilityClass
public final class AsmKit {

    private static final @NotNull String CLASS_SUFFIX = ".class";

    /**
     * Loads a class from the supplied jar through ASM's tree model, returning {@code null}
     * when the class is not present in the archive.
     *
     * @param zip the jar to read from
     * @param internalName the class's JVM internal name (e.g. {@code net/minecraft/X})
     * @return the populated {@link ClassNode}, or {@code null} if the archive has no matching entry
     * @throws AssetPipelineException if the jar entry exists but cannot be read
     */
    public static @Nullable ClassNode loadClass(@NotNull ZipFile zip, @NotNull String internalName) {
        ZipEntry entry = zip.getEntry(internalName + CLASS_SUFFIX);
        if (entry == null) return null;

        try (InputStream stream = zip.getInputStream(entry)) {
            byte[] bytes = stream.readAllBytes();
            ClassNode classNode = new ClassNode();
            new ClassReader(bytes).accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return classNode;
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to read class '%s' from jar", internalName);
        }
    }

    /**
     * Loads a class from the supplied jar, throwing an {@link AssetPipelineException} with
     * a context-tagged "obfuscated or unsupported version" message when the class is missing.
     *
     * @param zip the jar to read from
     * @param internalName the class's JVM internal name
     * @param context a short label (e.g. {@code "BlockColors"}, {@code "MobEffects"}) identifying the caller in the error message
     * @return the populated {@link ClassNode}
     * @throws AssetPipelineException if the class is not in the jar or cannot be read
     */
    public static @NotNull ClassNode requireClass(@NotNull ZipFile zip, @NotNull String internalName, @NotNull String context) {
        ClassNode classNode = loadClass(zip, internalName);
        if (classNode == null)
            throw new AssetPipelineException(
                "Jar does not contain '%s.class' for %s - the jar is either obfuscated (pre-26.1) or from an unsupported version",
                internalName, context
            );
        return classNode;
    }

    /**
     * Returns the first method on {@code classNode} whose name matches, or {@code null} when
     * no such method exists. Matches on name only - use the descriptor-qualified overload
     * when overloads matter.
     */
    public static @Nullable MethodNode findMethod(@NotNull ClassNode classNode, @NotNull String name) {
        for (MethodNode m : classNode.methods) {
            if (m.name.equals(name))
                return m;
        }
        return null;
    }

    /**
     * Returns the method on {@code classNode} matching both {@code name} and {@code descriptor},
     * or {@code null} when no such method exists.
     */
    public static @Nullable MethodNode findMethod(@NotNull ClassNode classNode, @NotNull String name, @NotNull String descriptor) {
        for (MethodNode m : classNode.methods) {
            if (m.name.equals(name) && m.desc.equals(descriptor))
                return m;
        }
        return null;
    }

    /**
     * Looks up a method by (class, name, descriptor), walking the superclass chain as the
     * JVM would for {@code invokestatic} / {@code invokevirtual} resolution. Returns
     * {@code null} when the method isn't found anywhere in the hierarchy or when any link
     * of the chain can't be loaded from the jar.
     *
     * @param zip the jar to read from
     * @param startInternalName the class to begin the walk at (that class and all its ancestors are searched)
     * @param name the method name
     * @param descriptor the method descriptor
     * @return the matching {@link MethodNode}, or {@code null} when the walk finds nothing or fails
     */
    public static @Nullable MethodNode findMethodInHierarchy(
        @NotNull ZipFile zip,
        @NotNull String startInternalName,
        @NotNull String name,
        @NotNull String descriptor
    ) {
        String current = startInternalName;
        while (current != null) {
            ZipEntry entry = zip.getEntry(current + CLASS_SUFFIX);
            if (entry == null) return null;
            ClassNode classNode = new ClassNode();
            try (InputStream stream = zip.getInputStream(entry)) {
                new ClassReader(stream.readAllBytes()).accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (IOException ex) {
                return null;
            }
            MethodNode m = findMethod(classNode, name, descriptor);
            if (m != null) return m;
            current = classNode.superName;
        }
        return null;
    }

    /**
     * Decodes an {@code int} literal from a bytecode instruction, returning {@code null} for
     * nodes that do not push a compile-time integer constant onto the operand stack. Handles
     * {@code ICONST_M1} through {@code ICONST_5}, {@code BIPUSH}, {@code SIPUSH}, and
     * {@code LDC Integer}.
     */
    public static @Nullable Integer readIntLiteral(@NotNull AbstractInsnNode node) {
        int opcode = node.getOpcode();

        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
            return opcode - Opcodes.ICONST_0;

        if ((opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && node instanceof IntInsnNode intInsn)
            return intInsn.operand;

        if (opcode == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value)
            return value;

        return null;
    }

    /**
     * Decodes a {@code long} literal from a bytecode instruction, returning {@code null} for
     * nodes that do not push a compile-time long constant. Handles {@code LCONST_0},
     * {@code LCONST_1}, and {@code LDC Long}.
     */
    public static @Nullable Long readLongLiteral(@NotNull AbstractInsnNode node) {
        int opcode = node.getOpcode();

        if (opcode == Opcodes.LCONST_0) return 0L;
        if (opcode == Opcodes.LCONST_1) return 1L;

        if (opcode == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Long value)
            return value;

        return null;
    }

    /**
     * Decodes a {@code float} literal from a bytecode instruction, returning {@code null} for
     * nodes that do not push a compile-time float constant. Handles {@code FCONST_0} through
     * {@code FCONST_2} and {@code LDC Float}.
     */
    public static @Nullable Float readFloatLiteral(@NotNull AbstractInsnNode node) {
        int opcode = node.getOpcode();

        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2)
            return (float) (opcode - Opcodes.FCONST_0);

        if (opcode == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Float value)
            return value;

        return null;
    }

    /**
     * Decodes a {@code double} literal from a bytecode instruction, returning {@code null}
     * for nodes that do not push a compile-time double constant. Handles {@code DCONST_0},
     * {@code DCONST_1}, and {@code LDC Double}.
     */
    public static @Nullable Double readDoubleLiteral(@NotNull AbstractInsnNode node) {
        int opcode = node.getOpcode();

        if (opcode == Opcodes.DCONST_0) return 0.0;
        if (opcode == Opcodes.DCONST_1) return 1.0;

        if (opcode == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Double value)
            return value;

        return null;
    }

    /**
     * Decodes a {@code String} literal from a bytecode instruction, returning {@code null}
     * for nodes that are not an {@code LDC} of a {@link String} constant.
     */
    public static @Nullable String readStringLiteral(@NotNull AbstractInsnNode node) {
        if (node.getOpcode() == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof String value)
            return value;
        return null;
    }

    /**
     * Decodes a {@code Class<?>} literal from a bytecode instruction, returning {@code null}
     * for nodes that are not an {@code LDC} of a {@link Type} constant.
     */
    public static @Nullable Type readTypeLiteral(@NotNull AbstractInsnNode node) {
        if (node.getOpcode() == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Type value)
            return value;
        return null;
    }

    /**
     * Decodes any supported literal from a bytecode instruction, returning the boxed value
     * ({@link Integer}, {@link Long}, {@link Float}, {@link Double}, {@link String}, or
     * {@link Type}) or {@code null} when the node is not a literal push.
     */
    public static @Nullable Object readAnyLiteral(@NotNull AbstractInsnNode node) {
        Integer i = readIntLiteral(node);
        if (i != null) return i;
        Long l = readLongLiteral(node);
        if (l != null) return l;
        Float f = readFloatLiteral(node);
        if (f != null) return f;
        Double d = readDoubleLiteral(node);
        if (d != null) return d;
        String s = readStringLiteral(node);
        if (s != null) return s;
        return readTypeLiteral(node);
    }

    /**
     * Returns {@code true} when {@code node} is a {@link MethodInsnNode} with the given
     * opcode, owner, and name. Descriptor is ignored - use the descriptor-qualified overload
     * when overloads matter.
     */
    public static boolean isInvoke(@NotNull AbstractInsnNode node, int opcode, @NotNull String owner, @NotNull String name) {
        return node.getOpcode() == opcode
            && node instanceof MethodInsnNode methodInsn
            && methodInsn.owner.equals(owner)
            && methodInsn.name.equals(name);
    }

    /**
     * Returns {@code true} when {@code node} is a {@link MethodInsnNode} matching all four
     * fields (opcode, owner, name, descriptor) exactly.
     */
    public static boolean isInvoke(@NotNull AbstractInsnNode node, int opcode, @NotNull String owner, @NotNull String name, @NotNull String descriptor) {
        return node.getOpcode() == opcode
            && node instanceof MethodInsnNode methodInsn
            && methodInsn.owner.equals(owner)
            && methodInsn.name.equals(name)
            && methodInsn.desc.equals(descriptor);
    }

    /**
     * Returns {@code true} when {@code node} is a {@code GETSTATIC} on the given owner class.
     */
    public static boolean isGetStatic(@NotNull AbstractInsnNode node, @NotNull String owner) {
        return node.getOpcode() == Opcodes.GETSTATIC
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner);
    }

    /**
     * Returns {@code true} when {@code node} is a {@code NEW} whose target type's internal
     * name starts with {@code internalNamePrefix}. Useful for "any subclass of X" matches
     * where only the package matters.
     */
    public static boolean isNewInstance(@NotNull AbstractInsnNode node, @NotNull String internalNamePrefix) {
        return node.getOpcode() == Opcodes.NEW
            && node instanceof TypeInsnNode typeInsn
            && typeInsn.desc.startsWith(internalNamePrefix);
    }

    /**
     * Bounded FIFO/LIFO hybrid for accumulating bytecode literals between control points -
     * each parser feeds its {@code readXLiteral} results in through {@link #push(Object)},
     * then pops them in LIFO order when a builder-dispatch instruction (e.g.
     * {@code constant(int, int)} or {@code addBox(FFFFFF)}) is encountered. When the
     * per-parser capacity is exceeded (4 for tints, 8 for potion colours, 16 for block
     * entities) the oldest entry is evicted to match the {@code ConcurrentList.removeFirst}
     * pattern the original parsers used. Typed pop helpers
     * ({@link #popInt()}, {@link #popFloat()}, {@link #popString()}) return {@code null}
     * when the stack is empty <i>or</i> when the top entry is not of the requested type,
     * so a caller hunting ints after a float-consuming descriptor mismatch doesn't
     * silently pick up a wrong-type value.
     */
    public static final class LiteralStack {

        private final int capacity;
        private final @NotNull ConcurrentList<Object> entries = Concurrent.newList();

        /**
         * Constructs a new {@code LiteralStack} with the given retention capacity.
         *
         * @param capacity the maximum number of retained entries; overflow evicts the oldest
         */
        public LiteralStack(int capacity) {
            this.capacity = capacity;
        }

        /**
         * Pushes a value onto the top of the stack, evicting the oldest entry when capacity
         * is exceeded.
         */
        public void push(@NotNull Object value) {
            this.entries.add(value);
            if (this.entries.size() > this.capacity)
                this.entries.removeFirst();
        }

        /**
         * Removes and returns the top of the stack, or {@code null} when the stack is empty.
         */
        public @Nullable Object pop() {
            if (this.entries.isEmpty()) return null;
            return this.entries.remove(this.entries.size() - 1);
        }

        /**
         * Returns the top of the stack without removing it, or {@code null} when the stack
         * is empty.
         */
        public @Nullable Object peek() {
            if (this.entries.isEmpty()) return null;
            return this.entries.get(this.entries.size() - 1);
        }

        /**
         * Removes and returns the top of the stack as an {@link Integer}, returning
         * {@code null} when the stack is empty or when the top entry is not an int.
         */
        public @Nullable Integer popInt() {
            if (this.entries.isEmpty()) return null;
            Object top = this.entries.get(this.entries.size() - 1);
            if (!(top instanceof Integer value)) return null;
            this.entries.removeLast();
            return value;
        }

        /**
         * Removes and returns the top of the stack as a {@link Float}, returning {@code null}
         * when the stack is empty or when the top entry is not a float.
         */
        public @Nullable Float popFloat() {
            if (this.entries.isEmpty()) return null;
            Object top = this.entries.get(this.entries.size() - 1);
            if (!(top instanceof Float value)) return null;
            this.entries.removeLast();
            return value;
        }

        /**
         * Removes and returns the top of the stack as a {@link String}, returning
         * {@code null} when the stack is empty or when the top entry is not a string.
         */
        public @Nullable String popString() {
            if (this.entries.isEmpty()) return null;
            Object top = this.entries.get(this.entries.size() - 1);
            if (!(top instanceof String value)) return null;
            this.entries.removeLast();
            return value;
        }

        /**
         * Clears every entry from the stack.
         */
        public void reset() {
            this.entries.clear();
        }

        /**
         * The current number of entries on the stack.
         */
        public int size() {
            return this.entries.size();
        }

        /**
         * {@code true} when {@link #size()} is zero.
         */
        public boolean isEmpty() {
            return this.entries.isEmpty();
        }

    }

}
