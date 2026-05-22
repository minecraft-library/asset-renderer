package lib.minecraft.renderer.tooling.util;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.exception.ToolingException;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Shared ASM scaffolding used by every bytecode-walking tooling parser in the
 * {@link lib.minecraft.renderer.tooling} package
 * ({@code ToolingBlockTints}, {@code ToolingPotionColors}, {@code ToolingBlockEntities},
 * plus the {@code blockentity} and {@code entity} sub-package resolvers).
 *
 * <p>The kit owns four families of primitives:
 * <ul>
 *   <li><b>Class / member loading</b> - jar entry to {@link ClassNode}, name and descriptor
 *       method / field lookups (including superclass-chain variants), throwing {@code require*}
 *       variants for callers that want a tooling-canonical "obfuscated or unsupported version"
 *       error instead of a null return.</li>
 *   <li><b>Literal decoding</b> - turn {@code ICONST_*} / {@code BIPUSH} / {@code SIPUSH} /
 *       {@code LDC} bytecode literal pushes back into boxed {@link Integer} / {@link Long} /
 *       {@link Float} / {@link Double} / {@link String} / {@link Type} values; plus a
 *       {@link LiteralStack} retention class for parsers that need to remember the last N
 *       pushes across intervening instructions.</li>
 *   <li><b>Instruction predicates</b> - {@code isInvokeStatic} / {@code isInvokeVirtual} /
 *       {@code isInvokeSpecial} / {@code isInvokeInterface} (with optional descriptor match),
 *       {@code isGetStatic} / {@code isPutStatic} / {@code isGetField} / {@code isPutField}
 *       (with optional field-name match), plus {@code isNewInstance},
 *       {@code isLambdaInvokeDynamic}, and the {@link #isPseudoNode(AbstractInsnNode)
 *       isPseudoNode} / {@link #previousReal(AbstractInsnNode) previousReal} /
 *       {@link #nextReal(AbstractInsnNode) nextReal} skip helpers.</li>
 *   <li><b>Traversal walkers</b> - {@link #walkConstructorChain walkConstructorChain},
 *       {@link #walkSuperChain walkSuperChain}, {@link #extendsClass extendsClass},
 *       {@link #findPreceding findPreceding}, {@link #findFollowingPutStatic
 *       findFollowingPutStatic}, {@link #containsInvoke(MethodNode, int, String, String)
 *       containsInvoke}, {@link #containsFieldOp containsFieldOp}, plus the lambda
 *       metafactory helpers {@link #extractLambdaHandle extractLambdaHandle},
 *       {@link #resolveLambdaTargetClass resolveLambdaTargetClass}, and
 *       {@link #walkLambdaBody walkLambdaBody}.</li>
 * </ul>
 *
 * <p>None of the helpers here know about the vanilla semantic patterns the callers are
 * hunting for (tint sources, effect colours, cube literals, layer dispatch, lambda targets).
 * Those stay in the individual resolvers - this class purely owns the bytecode-level
 * primitives. Anything that drifted into duplicate "this is what the bytecode looks like"
 * boilerplate across two or more parsers lives here.
 */
@UtilityClass
public final class AsmKit {

    // ----------------------------------------------------------------------------------------
    // Constants
    // ----------------------------------------------------------------------------------------

    private static final @NotNull String CLASS_SUFFIX = ".class";

    /**
     * The {@code java.lang.Object} JVM internal name. Used as the canonical stop sentinel for
     * superclass-chain walks ({@link #walkSuperChain}, {@link #walkConstructorChain},
     * {@link #extendsClass}, {@link #findMethodInHierarchy}, {@link #findFieldInHierarchy}).
     */
    public static final @NotNull String OBJECT_INTERNAL = "java/lang/Object";

    /**
     * Static-initializer method name (JVM {@code <clinit>}). Centralized to avoid the
     * literal {@code "<clinit>"} re-appearing in every parser.
     */
    public static final @NotNull String CLINIT = "<clinit>";

    /**
     * Instance-constructor method name (JVM {@code <init>}). Centralized for the same reason
     * as {@link #CLINIT}.
     */
    public static final @NotNull String INIT = "<init>";

    private static final @NotNull String LAMBDA_METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";
    private static final @NotNull String LAMBDA_METAFACTORY_METHOD = "metafactory";
    private static final @NotNull String LAMBDA_ALTMETAFACTORY_METHOD = "altMetafactory";

    /**
     * Per-target-type cache for the generic-signature parser; rebuilt lazily because the
     * outer type is supplied per call and the pattern body is target-specific.
     */
    private static final @NotNull Map<String, Pattern> GENERIC_PARAMETER_PATTERN_CACHE = new ConcurrentHashMap<>();

    // ----------------------------------------------------------------------------------------
    // Class loading
    // ----------------------------------------------------------------------------------------

    /**
     * Loads a class from the supplied jar through ASM's tree model, returning {@code null}
     * when the class is not present in the archive.
     *
     * @param zip the jar to read from
     * @param internalName the class's JVM internal name (e.g. {@code net/minecraft/X})
     * @return the populated {@link ClassNode}, or {@code null} if the archive has no matching entry
     * @throws ToolingException if the jar entry exists but cannot be read
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
            throw new ToolingException(ex, "Failed to read class '%s' from jar", internalName);
        }
    }

    /**
     * Loads a class from the supplied jar, throwing a {@link ToolingException} with
     * a context-tagged "obfuscated or unsupported version" message when the class is missing.
     *
     * @param zip the jar to read from
     * @param internalName the class's JVM internal name
     * @param context a short label (e.g. {@code "BlockColors"}, {@code "MobEffects"}) identifying the caller in the error message
     * @return the populated {@link ClassNode}
     * @throws ToolingException if the class is not in the jar or cannot be read
     */
    public static @NotNull ClassNode requireClass(@NotNull ZipFile zip, @NotNull String internalName, @NotNull String context) {
        ClassNode classNode = loadClass(zip, internalName);
        if (classNode == null)
            throw new ToolingException(
                "Jar does not contain '%s.class' for %s - the jar is either obfuscated (pre-26.1) or from an unsupported version",
                internalName, context
            );
        return classNode;
    }

    // ----------------------------------------------------------------------------------------
    // Method / field lookup
    // ----------------------------------------------------------------------------------------

    /**
     * Returns the first method on {@code classNode} whose name matches, or {@code null} when
     * no such method exists. Matches on name only - use the descriptor-qualified overload
     * when overloads matter.
     *
     * @param classNode the class to scan
     * @param name the method name
     * @return the matching method, or {@code null} when none is found
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
     *
     * @param classNode the class to scan
     * @param name the method name
     * @param descriptor the method descriptor
     * @return the matching method, or {@code null} when none is found
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
     * Looks up a method like {@link #findMethod(ClassNode, String)} but throws a
     * canonical {@link ToolingException} when no match exists, mirroring
     * {@link #requireClass}'s error shape so log scrapers see one phrasing.
     *
     * @param classNode the class to scan
     * @param name the method name
     * @param context a short label identifying the caller in the error message
     * @return the matching method
     * @throws ToolingException if no method with the given name exists
     */
    public static @NotNull MethodNode requireMethod(@NotNull ClassNode classNode, @NotNull String name, @NotNull String context) {
        MethodNode method = findMethod(classNode, name);
        if (method == null)
            throw new ToolingException(
                "Class '%s' does not expose a '%s' method for %s - the jar is either obfuscated (pre-26.1) or from an unsupported version",
                classNode.name, name, context
            );
        return method;
    }

    /**
     * Descriptor-qualified variant of {@link #requireMethod(ClassNode, String, String)}.
     *
     * @param classNode the class to scan
     * @param name the method name
     * @param descriptor the method descriptor
     * @param context a short label identifying the caller in the error message
     * @return the matching method
     * @throws ToolingException if no method with the given name + descriptor exists
     */
    public static @NotNull MethodNode requireMethod(
        @NotNull ClassNode classNode,
        @NotNull String name,
        @NotNull String descriptor,
        @NotNull String context
    ) {
        MethodNode method = findMethod(classNode, name, descriptor);
        if (method == null)
            throw new ToolingException(
                "Class '%s' does not expose a '%s%s' method for %s - the jar is either obfuscated (pre-26.1) or from an unsupported version",
                classNode.name, name, descriptor, context
            );
        return method;
    }

    /**
     * Convenience alias for {@code requireMethod(classNode, "<clinit>", context)}.
     *
     * @param classNode the class to scan
     * @param context a short label identifying the caller in the error message
     * @return the static initializer method
     * @throws ToolingException if the class has no {@code <clinit>}
     */
    public static @NotNull MethodNode requireClinit(@NotNull ClassNode classNode, @NotNull String context) {
        return requireMethod(classNode, CLINIT, context);
    }

    /**
     * Returns the first field on {@code classNode} whose name matches, or {@code null} when
     * no such field exists.
     *
     * @param classNode the class to scan
     * @param name the field name
     * @return the matching field, or {@code null} when none is found
     */
    public static @Nullable FieldNode findField(@NotNull ClassNode classNode, @NotNull String name) {
        for (FieldNode f : classNode.fields) {
            if (f.name.equals(name))
                return f;
        }
        return null;
    }

    /**
     * Returns the field on {@code classNode} matching both {@code name} and {@code descriptor},
     * or {@code null} when no such field exists.
     *
     * @param classNode the class to scan
     * @param name the field name
     * @param descriptor the field descriptor
     * @return the matching field, or {@code null} when none is found
     */
    public static @Nullable FieldNode findField(@NotNull ClassNode classNode, @NotNull String name, @NotNull String descriptor) {
        for (FieldNode f : classNode.fields) {
            if (f.name.equals(name) && f.desc.equals(descriptor))
                return f;
        }
        return null;
    }

    /**
     * Looks up a field by (class, name), walking the superclass chain as the JVM would for
     * field resolution. Returns {@code null} when the field isn't found anywhere in the
     * hierarchy or when any link of the chain can't be loaded from the jar.
     *
     * @param zip the jar to read from
     * @param startInternalName the class to begin the walk at
     * @param name the field name
     * @return the matching {@link FieldNode}, or {@code null} when the walk finds nothing
     */
    public static @Nullable FieldNode findFieldInHierarchy(@NotNull ZipFile zip, @NotNull String startInternalName, @NotNull String name) {
        String current = startInternalName;
        while (current != null) {
            ClassNode classNode = loadClass(zip, current);
            if (classNode == null) return null;
            FieldNode f = findField(classNode, name);
            if (f != null) return f;
            current = classNode.superName;
        }
        return null;
    }

    /**
     * Looks up a field like {@link #findField(ClassNode, String)} but throws a canonical
     * {@link ToolingException} when no match exists.
     *
     * @param classNode the class to scan
     * @param name the field name
     * @param context a short label identifying the caller in the error message
     * @return the matching field
     * @throws ToolingException if no field with the given name exists
     */
    public static @NotNull FieldNode requireField(@NotNull ClassNode classNode, @NotNull String name, @NotNull String context) {
        FieldNode field = findField(classNode, name);
        if (field == null)
            throw new ToolingException(
                "Class '%s' does not expose a '%s' field for %s - the jar is either obfuscated (pre-26.1) or from an unsupported version",
                classNode.name, name, context
            );
        return field;
    }

    // ----------------------------------------------------------------------------------------
    // Class-hierarchy walks
    // ----------------------------------------------------------------------------------------

    /**
     * Walks every {@code <init>} constructor on the given class and on every superclass up
     * to (but not including) {@link #OBJECT_INTERNAL java/lang/Object}, invoking
     * {@code callback} for each constructor encountered. Stops silently when any link of the
     * chain fails to load from the jar.
     *
     * <p>Used by entity-renderer scanners that match {@code addLayer(new XLayer(...))}
     * patterns inside the renderer's constructor chain.
     *
     * @param zip the jar to read from
     * @param startInternalName the class to begin the walk at
     * @param callback invoked once per matching constructor in walk order
     */
    public static void walkConstructorChain(
        @NotNull ZipFile zip,
        @NotNull String startInternalName,
        @NotNull Consumer<MethodNode> callback
    ) {
        walkSuperChain(zip, startInternalName, classNode -> {
            for (MethodNode method : classNode.methods)
                if (INIT.equals(method.name)) callback.accept(method);
        });
    }

    /**
     * Walks each class up the superclass chain starting at {@code startInternalName} and
     * stopping before {@link #OBJECT_INTERNAL java/lang/Object} (so the visitor never sees
     * the Object class, which typically isn't even in the deobfuscated client jar). Stops
     * silently when any link can't be loaded.
     *
     * @param zip the jar to read from
     * @param startInternalName the class to begin the walk at
     * @param classCallback invoked once per visited class (start class first, then ancestors)
     */
    public static void walkSuperChain(
        @NotNull ZipFile zip,
        @NotNull String startInternalName,
        @NotNull Consumer<ClassNode> classCallback
    ) {
        String current = startInternalName;
        while (current != null && !OBJECT_INTERNAL.equals(current)) {
            ClassNode classNode = loadClass(zip, current);
            if (classNode == null) return;
            classCallback.accept(classNode);
            current = classNode.superName;
        }
    }

    /**
     * Returns {@code true} when {@code startInternalName} (or any of its ancestors) equals
     * {@code targetInternalName}. Walks the superclass chain stopping at {@code null} or at
     * {@link #OBJECT_INTERNAL java/lang/Object}. Returns {@code false} when any link can't
     * be loaded from the jar (treating "missing ancestor" as "not extends").
     *
     * @param zip the jar to read from
     * @param startInternalName the class to begin the walk at
     * @param targetInternalName the candidate ancestor
     * @return {@code true} when {@code startInternalName} extends or equals {@code targetInternalName}
     */
    public static boolean extendsClass(@NotNull ZipFile zip, @NotNull String startInternalName, @NotNull String targetInternalName) {
        String current = startInternalName;
        while (current != null && !OBJECT_INTERNAL.equals(current)) {
            if (targetInternalName.equals(current)) return true;
            ClassNode classNode = loadClass(zip, current);
            if (classNode == null) return false;
            current = classNode.superName;
        }
        return false;
    }

    // ----------------------------------------------------------------------------------------
    // Literal decoding
    // ----------------------------------------------------------------------------------------

    /**
     * Decodes an {@code int} literal from a bytecode instruction, returning {@code null} for
     * nodes that do not push a compile-time integer constant onto the operand stack. Handles
     * {@code ICONST_M1} through {@code ICONST_5}, {@code BIPUSH}, {@code SIPUSH}, and
     * {@code LDC Integer}.
     *
     * @param node the instruction to decode
     * @return the boxed int constant, or {@code null} when the node is not a literal int push
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
     *
     * @param node the instruction to decode
     * @return the boxed long constant, or {@code null} when the node is not a literal long push
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
     *
     * @param node the instruction to decode
     * @return the boxed float constant, or {@code null} when the node is not a literal float push
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
     *
     * @param node the instruction to decode
     * @return the boxed double constant, or {@code null} when the node is not a literal double push
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
     *
     * @param node the instruction to decode
     * @return the string constant, or {@code null} when the node is not a literal string push
     */
    public static @Nullable String readStringLiteral(@NotNull AbstractInsnNode node) {
        if (node.getOpcode() == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof String value)
            return value;
        return null;
    }

    /**
     * Decodes a {@code Class<?>} literal from a bytecode instruction, returning {@code null}
     * for nodes that are not an {@code LDC} of a {@link Type} constant.
     *
     * @param node the instruction to decode
     * @return the type constant, or {@code null} when the node is not a literal class push
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
     *
     * @param node the instruction to decode
     * @return the boxed literal value, or {@code null} when the node is not a literal push
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

    // ----------------------------------------------------------------------------------------
    // Descriptor utilities
    // ----------------------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code descriptor} is a method descriptor whose return type
     * is the reference type {@code Lreturn-internal-name;}. Equivalent to
     * {@code descriptor.endsWith(")L" + returnTypeInternalName + ";")} but slightly
     * cheaper because no intermediate string is built.
     *
     * @param descriptor the method descriptor
     * @param returnTypeInternalName the JVM internal name of the expected return type
     * @return {@code true} when {@code descriptor} returns the given type
     */
    public static boolean descriptorReturns(@NotNull String descriptor, @NotNull String returnTypeInternalName) {
        int closeParen = descriptor.lastIndexOf(')');
        if (closeParen < 0) return false;
        int tailLen = descriptor.length() - closeParen - 1;
        int expectedLen = returnTypeInternalName.length() + 2; // 'L' + name + ';'
        if (tailLen != expectedLen) return false;
        if (descriptor.charAt(closeParen + 1) != 'L') return false;
        if (descriptor.charAt(descriptor.length() - 1) != ';') return false;
        return descriptor.regionMatches(closeParen + 2, returnTypeInternalName, 0, returnTypeInternalName.length());
    }

    /**
     * Returns the total number of operand-stack slots required by the argument list of
     * {@code methodDescriptor}, treating LONG and DOUBLE as 2 slots and every other type
     * as 1.
     *
     * @param methodDescriptor the method descriptor
     * @return the total argument slot count
     */
    public static int argSlotCount(@NotNull String methodDescriptor) {
        int total = 0;
        for (Type t : Type.getArgumentTypes(methodDescriptor))
            total += t.getSize();
        return total;
    }

    /**
     * Returns the argument types of {@code methodDescriptor} as an ASM {@link Type} array.
     * Thin wrapper around {@link Type#getArgumentTypes(String)} for cross-call-site
     * consistency.
     *
     * @param methodDescriptor the method descriptor
     * @return the argument types in declaration order
     */
    public static Type @NotNull [] argTypes(@NotNull String methodDescriptor) {
        return Type.getArgumentTypes(methodDescriptor);
    }

    /**
     * Returns the return type of {@code methodDescriptor} as an ASM {@link Type}. Thin
     * wrapper around {@link Type#getReturnType(String)}.
     *
     * @param methodDescriptor the method descriptor
     * @return the return type
     */
    public static @NotNull Type returnType(@NotNull String methodDescriptor) {
        return Type.getReturnType(methodDescriptor);
    }

    /**
     * Extracts the JVM internal name of a reference-type descriptor. {@code L<name>;}
     * returns {@code <name>}; {@code [L<name>;} (and longer array prefixes) returns
     * {@code <name>}; primitives, {@code V}, and bare-array primitives return {@code null}.
     *
     * @param descriptor a field or array-element descriptor
     * @return the unwrapped internal name, or {@code null} for primitive / void / non-reference descriptors
     */
    public static @Nullable String internalNameOfRef(@NotNull String descriptor) {
        int i = 0;
        while (i < descriptor.length() && descriptor.charAt(i) == '[') i++;
        if (i >= descriptor.length() || descriptor.charAt(i) != 'L') return null;
        if (descriptor.charAt(descriptor.length() - 1) != ';') return null;
        return descriptor.substring(i + 1, descriptor.length() - 1);
    }

    // ----------------------------------------------------------------------------------------
    // Instruction predicates - invokes
    // ----------------------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code node} is a {@link MethodInsnNode} with the given
     * opcode, owner, and name. Descriptor is ignored - use the descriptor-qualified overload
     * when overloads matter.
     *
     * @param node the instruction to test
     * @param opcode the expected JVM invoke opcode
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @return {@code true} when {@code node} matches
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
     *
     * @param node the instruction to test
     * @param opcode the expected JVM invoke opcode
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @param descriptor the expected method descriptor
     * @return {@code true} when {@code node} matches
     */
    public static boolean isInvoke(@NotNull AbstractInsnNode node, int opcode, @NotNull String owner, @NotNull String name, @NotNull String descriptor) {
        return node.getOpcode() == opcode
            && node instanceof MethodInsnNode methodInsn
            && methodInsn.owner.equals(owner)
            && methodInsn.name.equals(name)
            && methodInsn.desc.equals(descriptor);
    }

    /**
     * Specialised {@link #isInvoke(AbstractInsnNode, int, String, String) isInvoke} for
     * {@code INVOKESTATIC}, ignoring descriptor.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @return {@code true} when {@code node} is the matching static invoke
     */
    public static boolean isInvokeStatic(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return isInvoke(node, Opcodes.INVOKESTATIC, owner, name);
    }

    /**
     * Descriptor-qualified variant of {@link #isInvokeStatic(AbstractInsnNode, String, String)}.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @param descriptor the expected method descriptor
     * @return {@code true} when {@code node} is the matching static invoke
     */
    public static boolean isInvokeStatic(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name, @NotNull String descriptor) {
        return isInvoke(node, Opcodes.INVOKESTATIC, owner, name, descriptor);
    }

    /**
     * Specialised {@link #isInvoke(AbstractInsnNode, int, String, String) isInvoke} for
     * {@code INVOKEVIRTUAL}, ignoring descriptor.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @return {@code true} when {@code node} is the matching virtual invoke
     */
    public static boolean isInvokeVirtual(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return isInvoke(node, Opcodes.INVOKEVIRTUAL, owner, name);
    }

    /**
     * Descriptor-qualified variant of {@link #isInvokeVirtual(AbstractInsnNode, String, String)}.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @param descriptor the expected method descriptor
     * @return {@code true} when {@code node} is the matching virtual invoke
     */
    public static boolean isInvokeVirtual(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name, @NotNull String descriptor) {
        return isInvoke(node, Opcodes.INVOKEVIRTUAL, owner, name, descriptor);
    }

    /**
     * Specialised {@link #isInvoke(AbstractInsnNode, int, String, String) isInvoke} for
     * {@code INVOKESPECIAL}, ignoring descriptor.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @return {@code true} when {@code node} is the matching special invoke
     */
    public static boolean isInvokeSpecial(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return isInvoke(node, Opcodes.INVOKESPECIAL, owner, name);
    }

    /**
     * Descriptor-qualified variant of {@link #isInvokeSpecial(AbstractInsnNode, String, String)}.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @param descriptor the expected method descriptor
     * @return {@code true} when {@code node} is the matching special invoke
     */
    public static boolean isInvokeSpecial(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name, @NotNull String descriptor) {
        return isInvoke(node, Opcodes.INVOKESPECIAL, owner, name, descriptor);
    }

    /**
     * Specialised {@link #isInvoke(AbstractInsnNode, int, String, String) isInvoke} for
     * {@code INVOKEINTERFACE}, ignoring descriptor.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @return {@code true} when {@code node} is the matching interface invoke
     */
    public static boolean isInvokeInterface(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return isInvoke(node, Opcodes.INVOKEINTERFACE, owner, name);
    }

    /**
     * Descriptor-qualified variant of {@link #isInvokeInterface(AbstractInsnNode, String, String)}.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @param descriptor the expected method descriptor
     * @return {@code true} when {@code node} is the matching interface invoke
     */
    public static boolean isInvokeInterface(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name, @NotNull String descriptor) {
        return isInvoke(node, Opcodes.INVOKEINTERFACE, owner, name, descriptor);
    }

    // ----------------------------------------------------------------------------------------
    // Instruction predicates - field access
    // ----------------------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code node} is a {@code GETSTATIC} on the given owner class.
     * Field name is ignored - use the name-qualified overload to match a specific field.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @return {@code true} when {@code node} is a GETSTATIC on the given owner
     */
    public static boolean isGetStatic(@NotNull AbstractInsnNode node, @NotNull String owner) {
        return node.getOpcode() == Opcodes.GETSTATIC
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner);
    }

    /**
     * Name-qualified variant of {@link #isGetStatic(AbstractInsnNode, String)}.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected field name
     * @return {@code true} when {@code node} is a matching GETSTATIC
     */
    public static boolean isGetStatic(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return node.getOpcode() == Opcodes.GETSTATIC
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner)
            && fieldInsn.name.equals(name);
    }

    /**
     * Returns {@code true} when {@code node} is a {@code PUTSTATIC} on the given owner class.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @return {@code true} when {@code node} is a PUTSTATIC on the given owner
     */
    public static boolean isPutStatic(@NotNull AbstractInsnNode node, @NotNull String owner) {
        return node.getOpcode() == Opcodes.PUTSTATIC
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner);
    }

    /**
     * Name-qualified variant of {@link #isPutStatic(AbstractInsnNode, String)}.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected field name
     * @return {@code true} when {@code node} is a matching PUTSTATIC
     */
    public static boolean isPutStatic(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return node.getOpcode() == Opcodes.PUTSTATIC
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner)
            && fieldInsn.name.equals(name);
    }

    /**
     * Returns {@code true} when {@code node} is a {@code GETFIELD} on the given owner class
     * and field name.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected field name
     * @return {@code true} when {@code node} is a matching GETFIELD
     */
    public static boolean isGetField(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return node.getOpcode() == Opcodes.GETFIELD
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner)
            && fieldInsn.name.equals(name);
    }

    /**
     * Returns {@code true} when {@code node} is a {@code PUTFIELD} on the given owner class
     * and field name.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected field name
     * @return {@code true} when {@code node} is a matching PUTFIELD
     */
    public static boolean isPutField(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return node.getOpcode() == Opcodes.PUTFIELD
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner)
            && fieldInsn.name.equals(name);
    }

    // ----------------------------------------------------------------------------------------
    // Instruction predicates - other
    // ----------------------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code node} is a {@code NEW} whose target type's internal
     * name starts with {@code internalNamePrefix}. Useful for "any subclass of X" matches
     * where only the package matters.
     *
     * @param node the instruction to test
     * @param internalNamePrefix the expected target-type internal-name prefix
     * @return {@code true} when {@code node} is a matching NEW
     */
    public static boolean isNewInstance(@NotNull AbstractInsnNode node, @NotNull String internalNamePrefix) {
        return node.getOpcode() == Opcodes.NEW
            && node instanceof TypeInsnNode typeInsn
            && typeInsn.desc.startsWith(internalNamePrefix);
    }

    /**
     * Returns {@code true} when {@code node} is an {@code INVOKEDYNAMIC} whose bootstrap
     * method is {@code LambdaMetafactory.metafactory} or {@code LambdaMetafactory.altMetafactory} -
     * the two bootstrap methods every {@code javac}-emitted lambda call site routes through.
     * Useful as a precondition before reaching for {@link #extractLambdaHandle} or
     * {@link #resolveLambdaTargetClass}.
     *
     * @param node the instruction to test
     * @return {@code true} when {@code node} is a lambda-metafactory INVOKEDYNAMIC
     */
    public static boolean isLambdaInvokeDynamic(@NotNull AbstractInsnNode node) {
        if (!(node instanceof InvokeDynamicInsnNode indy)) return false;
        Handle bsm = indy.bsm;
        return bsm != null
            && LAMBDA_METAFACTORY_OWNER.equals(bsm.getOwner())
            && (LAMBDA_METAFACTORY_METHOD.equals(bsm.getName()) || LAMBDA_ALTMETAFACTORY_METHOD.equals(bsm.getName()));
    }

    // ----------------------------------------------------------------------------------------
    // Pseudo-node helpers
    // ----------------------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code node} is a pseudo-instruction (label, frame, or
     * line-number node) - detected via {@link AbstractInsnNode#getOpcode()} returning a
     * negative value. These nodes carry no real opcode and should be skipped when
     * pattern-matching against the instruction stream.
     *
     * @param node the instruction to test
     * @return {@code true} when {@code node} is a label / frame / line-number node
     */
    public static boolean isPseudoNode(@NotNull AbstractInsnNode node) {
        return node.getOpcode() < 0;
    }

    /**
     * Walks backwards from {@code node}, skipping pseudo-instructions (labels / frames /
     * line-numbers), and returns the first node with a real opcode. Returns {@code null}
     * when no such node exists or when {@code node} itself is {@code null}.
     *
     * @param node the starting instruction (the previous-of-this is the first node inspected)
     * @return the previous real instruction, or {@code null} when none exists
     */
    public static @Nullable AbstractInsnNode previousReal(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        for (AbstractInsnNode prev = node.getPrevious(); prev != null; prev = prev.getPrevious())
            if (prev.getOpcode() >= 0) return prev;
        return null;
    }

    /**
     * Forward counterpart to {@link #previousReal}. Walks forward from {@code node},
     * skipping pseudo-instructions, and returns the first node with a real opcode.
     *
     * @param node the starting instruction (the next-of-this is the first node inspected)
     * @return the next real instruction, or {@code null} when none exists
     */
    public static @Nullable AbstractInsnNode nextReal(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        for (AbstractInsnNode next = node.getNext(); next != null; next = next.getNext())
            if (next.getOpcode() >= 0) return next;
        return null;
    }

    // ----------------------------------------------------------------------------------------
    // Method-body traversal helpers
    // ----------------------------------------------------------------------------------------

    /**
     * Walks backwards from {@code from}, returning the first node that satisfies
     * {@code matcher}. Pseudo-nodes are always skipped silently. Nodes whose opcode
     * matches {@code passthrough} are skipped too (used for "ignore literal pushes between
     * me and my target"). Any other unmatched real instruction terminates the walk with
     * a {@code null} return - it represents "we've left the pattern's scope without finding
     * a match".
     *
     * <p>Equivalent to the hand-rolled walks in
     * {@code EntityBlockOverlayResolver.findPrecedingAxisField} (passthrough = float
     * literals) and {@code EntityBlockOverlayResolver.findPrecedingBoneAccessor}
     * (passthrough = nothing).
     *
     * @param from the starting instruction (the previous-of-this is the first node inspected)
     * @param matcher invoked once per non-pseudo, non-passthrough predecessor; the walk returns the first node it accepts
     * @param passthrough invoked with each non-pseudo predecessor's opcode; {@code true} means continue walking past this node
     * @return the first matching predecessor, or {@code null} when none was found before a non-passthrough mismatch
     */
    public static @Nullable AbstractInsnNode findPreceding(
        @NotNull AbstractInsnNode from,
        @NotNull Predicate<AbstractInsnNode> matcher,
        @NotNull IntPredicate passthrough
    ) {
        for (AbstractInsnNode node = from.getPrevious(); node != null; node = node.getPrevious()) {
            if (isPseudoNode(node)) continue;
            if (matcher.test(node)) return node;
            if (passthrough.test(node.getOpcode())) continue;
            return null;
        }
        return null;
    }

    /**
     * Walks forward from {@code from} until a {@code PUTSTATIC} on the given owner is seen,
     * or until {@code stopAnchor} returns {@code true} (which aborts and returns
     * {@code null}). Returns the matching field name when found.
     *
     * <p>Mirrors {@code MobRegistryDiscovery.findFollowingPutStatic}: after a registration
     * triple has been observed, the next PUTSTATIC on {@code EntityType} is the field the
     * triple assigns into; a {@code Builder.of} call before the PUTSTATIC aborts the walk
     * because it indicates we've fallen off the registration into the next one.
     *
     * @param from the starting instruction (the next-of-this is the first node inspected)
     * @param ownerInternalName the expected PUTSTATIC owner's JVM internal name
     * @param stopAnchor invoked once per node; {@code true} aborts the walk with a {@code null} return
     * @return the matching field name, or {@code null} when none is found before the anchor (or end of method)
     */
    public static @Nullable String findFollowingPutStatic(
        @NotNull AbstractInsnNode from,
        @NotNull String ownerInternalName,
        @NotNull Predicate<AbstractInsnNode> stopAnchor
    ) {
        for (AbstractInsnNode node = from.getNext(); node != null; node = node.getNext()) {
            if (isPutStatic(node, ownerInternalName)) {
                FieldInsnNode field = (FieldInsnNode) node;
                return field.name;
            }
            if (stopAnchor.test(node)) return null;
        }
        return null;
    }

    /**
     * Returns {@code true} when {@code method}'s body contains at least one
     * {@link MethodInsnNode} matching the given opcode, owner, and name. Descriptor is
     * ignored.
     *
     * @param method the method to scan
     * @param opcode the expected invoke opcode
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @return {@code true} when at least one matching invoke exists in the body
     */
    public static boolean containsInvoke(@NotNull MethodNode method, int opcode, @NotNull String owner, @NotNull String name) {
        for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext())
            if (isInvoke(node, opcode, owner, name)) return true;
        return false;
    }

    /**
     * Descriptor-qualified variant of {@link #containsInvoke(MethodNode, int, String, String)}.
     *
     * @param method the method to scan
     * @param opcode the expected invoke opcode
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @param descriptor the expected method descriptor
     * @return {@code true} when at least one matching invoke exists in the body
     */
    public static boolean containsInvoke(
        @NotNull MethodNode method,
        int opcode,
        @NotNull String owner,
        @NotNull String name,
        @NotNull String descriptor
    ) {
        for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext())
            if (isInvoke(node, opcode, owner, name, descriptor)) return true;
        return false;
    }

    /**
     * Returns {@code true} when {@code method}'s body contains at least one field-access
     * instruction matching the given opcode, owner, and name. Mirrors
     * {@link #containsInvoke} for {@code GETSTATIC} / {@code PUTSTATIC} / {@code GETFIELD} /
     * {@code PUTFIELD}.
     *
     * @param method the method to scan
     * @param opcode the expected field-access opcode
     * @param owner the expected owner's JVM internal name
     * @param name the expected field name
     * @return {@code true} when at least one matching field access exists in the body
     */
    public static boolean containsFieldOp(@NotNull MethodNode method, int opcode, @NotNull String owner, @NotNull String name) {
        for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node.getOpcode() != opcode) continue;
            if (!(node instanceof FieldInsnNode fieldInsn)) continue;
            if (fieldInsn.owner.equals(owner) && fieldInsn.name.equals(name)) return true;
        }
        return false;
    }

    // ----------------------------------------------------------------------------------------
    // Lambda metafactory helpers
    // ----------------------------------------------------------------------------------------

    /**
     * Returns the primary target {@link Handle} of a {@code LambdaMetafactory}-built
     * {@code INVOKEDYNAMIC}. Vanilla {@code javac} stores it at {@code bsmArgs[1]} (after the
     * sam-method type at index 0). Returns {@code null} when the shape doesn't match.
     *
     * @param indy the instruction to inspect
     * @return the target handle, or {@code null} when not a lambda metafactory call or bsmArgs is malformed
     */
    public static @Nullable Handle extractLambdaHandle(@NotNull InvokeDynamicInsnNode indy) {
        if (indy.bsmArgs == null || indy.bsmArgs.length < 2) return null;
        if (!(indy.bsmArgs[1] instanceof Handle handle)) return null;
        return handle;
    }

    /**
     * Resolves a {@code LambdaMetafactory}-built {@code INVOKEDYNAMIC} to the JVM internal
     * name of the class the lambda produces. Two patterns are handled:
     * <ul>
     *   <li><b>{@code H_NEWINVOKESPECIAL}</b> - direct constructor reference
     *       ({@code Foo::new}). The lambda body is just {@code new Foo(...)} and the handle
     *       points at {@code Foo.<init>}; this returns {@code Foo}'s internal name.</li>
     *   <li><b>{@code H_INVOKESTATIC}</b> targeting {@code ownerClass} - synthetic lambda
     *       wrapper ({@code () -> new Foo(...)}). The handle points at a
     *       {@code lambda$static$N} method in the enclosing class; this loads that method
     *       and returns the internal name of the first {@code NEW} it executes.</li>
     * </ul>
     * Returns {@code null} for any other shape (different bootstrap, foreign-owner static
     * lambda, missing body, missing {@code NEW} in body).
     *
     * @param indy the instruction to resolve
     * @param ownerClass the class containing the indy (used to look up synthetic lambda bodies)
     * @return the target class's JVM internal name, or {@code null} when unresolved
     */
    public static @Nullable String resolveLambdaTargetClass(@NotNull InvokeDynamicInsnNode indy, @NotNull ClassNode ownerClass) {
        Handle handle = extractLambdaHandle(indy);
        if (handle == null) return null;
        if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL && INIT.equals(handle.getName()))
            return handle.getOwner();
        if (handle.getTag() == Opcodes.H_INVOKESTATIC && handle.getOwner().equals(ownerClass.name)) {
            MethodNode lambda = findMethod(ownerClass, handle.getName(), handle.getDesc());
            if (lambda == null) return null;
            for (AbstractInsnNode node = lambda.instructions.getFirst(); node != null; node = node.getNext())
                if (node instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW)
                    return type.desc;
        }
        return null;
    }

    /**
     * Like {@link #resolveLambdaTargetClass} but also feeds every body node of a synthetic
     * {@code H_INVOKESTATIC} lambda to the supplied {@code visitor}. Callers that need to
     * collect side-channel data (e.g. {@code ModelLayers.X} GETSTATICs the lambda body
     * reads) can accumulate it inside the visitor while still receiving the first-{@code NEW}
     * target as the return value.
     *
     * <p>For {@code H_NEWINVOKESPECIAL} (direct constructor ref) there is no body to walk,
     * so the visitor is never invoked; this method just returns the constructor's owning
     * class.
     *
     * @param indy the instruction to resolve
     * @param ownerClass the class containing the indy
     * @param visitor invoked once per body node when {@code indy} is a static lambda whose owner matches {@code ownerClass}
     * @return the target class's JVM internal name, or {@code null} when unresolved
     */
    public static @Nullable String walkLambdaBody(
        @NotNull InvokeDynamicInsnNode indy,
        @NotNull ClassNode ownerClass,
        @NotNull Consumer<AbstractInsnNode> visitor
    ) {
        Handle handle = extractLambdaHandle(indy);
        if (handle == null) return null;
        if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL && INIT.equals(handle.getName()))
            return handle.getOwner();
        if (handle.getTag() == Opcodes.H_INVOKESTATIC && handle.getOwner().equals(ownerClass.name)) {
            MethodNode lambda = findMethod(ownerClass, handle.getName(), handle.getDesc());
            if (lambda == null) return null;
            String found = null;
            for (AbstractInsnNode node = lambda.instructions.getFirst(); node != null; node = node.getNext()) {
                visitor.accept(node);
                if (found == null && node instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW)
                    found = type.desc;
            }
            return found;
        }
        return null;
    }

    // ----------------------------------------------------------------------------------------
    // Generic signature parsing
    // ----------------------------------------------------------------------------------------

    /**
     * Parses a field signature of the form {@code L<outer>;<L<inner>;>;} and returns the
     * inner-type internal name. Returns {@code null} when the signature doesn't match this
     * exact single-concrete-type-parameter shape (wildcards, generic variables, primitive
     * parameters, and nested generics all drop out).
     *
     * <p>Mirrors {@code MobRegistryDiscovery}'s {@code EntityType<LFoo;>} signature
     * extraction. Patterns are cached per outer-type so repeated calls within a single
     * scan don't re-compile the regex.
     *
     * @param signature the field generic signature
     * @param outerTypeInternalName the JVM internal name of the outer (generic-holding) type
     * @return the inner type's JVM internal name, or {@code null} when the signature doesn't match
     */
    public static @Nullable String extractGenericTypeParameter(@NotNull String signature, @NotNull String outerTypeInternalName) {
        Pattern pattern = GENERIC_PARAMETER_PATTERN_CACHE.computeIfAbsent(outerTypeInternalName,
            outer -> Pattern.compile("^L" + Pattern.quote(outer) + "<L([^;<>]+);>;$"));
        Matcher matcher = pattern.matcher(signature);
        if (!matcher.matches()) return null;
        return matcher.group(1);
    }

    // ----------------------------------------------------------------------------------------
    // Diagnostic formatters
    // ----------------------------------------------------------------------------------------

    /**
     * Emits a canonical {@code WARN} entry of the form
     * {@code "'%s' class missing from jar - %s"}. Use after a {@link #loadClass} returned
     * {@code null} when the caller wants to continue with a degraded result.
     *
     * @param diagnostics the diagnostic sink
     * @param internalName the missing class's JVM internal name
     * @param context a short label describing what the caller was trying to do
     */
    public static void diagMissingClass(@NotNull Diagnostics diagnostics, @NotNull String internalName, @NotNull String context) {
        diagnostics.warn("'%s' class missing from jar - %s", internalName, context);
    }

    /**
     * Emits a canonical {@code WARN} entry of the form
     * {@code "Method '%s.%s%s' missing - %s"} (descriptor omitted from the message when
     * {@code methodDescriptor} is {@code null}).
     *
     * @param diagnostics the diagnostic sink
     * @param classInternalName the owning class's JVM internal name
     * @param methodName the missing method name
     * @param methodDescriptor the missing method descriptor, or {@code null} when descriptor was not part of the lookup
     * @param context a short label describing what the caller was trying to do
     */
    public static void diagMissingMethod(
        @NotNull Diagnostics diagnostics,
        @NotNull String classInternalName,
        @NotNull String methodName,
        @Nullable String methodDescriptor,
        @NotNull String context
    ) {
        if (methodDescriptor == null)
            diagnostics.warn("Method '%s.%s' missing - %s", classInternalName, methodName, context);
        else
            diagnostics.warn("Method '%s.%s%s' missing - %s", classInternalName, methodName, methodDescriptor, context);
    }

    /**
     * Emits a canonical {@code WARN} entry of the form {@code "Field '%s.%s' missing - %s"}.
     *
     * @param diagnostics the diagnostic sink
     * @param classInternalName the owning class's JVM internal name
     * @param fieldName the missing field name
     * @param context a short label describing what the caller was trying to do
     */
    public static void diagMissingField(
        @NotNull Diagnostics diagnostics,
        @NotNull String classInternalName,
        @NotNull String fieldName,
        @NotNull String context
    ) {
        diagnostics.warn("Field '%s.%s' missing - %s", classInternalName, fieldName, context);
    }

    /**
     * Emits a canonical {@code WARN} entry of the form
     * {@code "Unexpected '%s' at %s (found: %s)"}. Use when a parser hit an instruction
     * shape it doesn't recognise but wants to continue.
     *
     * @param diagnostics the diagnostic sink
     * @param pattern the pattern label the parser was expecting (e.g. {@code "Builder.of call"})
     * @param where a short label describing the parse location
     * @param foundDescription a short description of what was actually observed
     */
    public static void diagUnexpectedPattern(
        @NotNull Diagnostics diagnostics,
        @NotNull String pattern,
        @NotNull String where,
        @NotNull String foundDescription
    ) {
        diagnostics.warn("Unexpected '%s' at %s (found: %s)", pattern, where, foundDescription);
    }

    // ----------------------------------------------------------------------------------------
    // LiteralStack - bounded literal accumulator
    // ----------------------------------------------------------------------------------------

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
     *
     * <p>For parsers that need to flag "I observed a value here but it came from a non-literal
     * source (a local variable populated by computation, a method-call result, ...)" - distinct
     * from "the stack was empty" or "the value was the wrong type" - {@link #pushNonLiteral()}
     * pushes a sentinel marker. The {@code *OrZero} family
     * ({@link #popIntOrZero(Diagnostics, String, String) popIntOrZero},
     * {@link #popFloatOrZero(Diagnostics, String, String) popFloatOrZero}) consume that
     * sentinel by returning a primitive zero <i>and</i> emitting a contextualised WARN. Empty
     * stack on these methods is silent zero (matches the original
     * {@code ToolingBlockEntities.Parser} accounting).
     */
    public static final class LiteralStack {

        /**
         * Sentinel singleton pushed by {@link #pushNonLiteral()}. Extends {@link Number} with
         * zero values so an arithmetic site that pops the sentinel and calls
         * {@code .intValue()} / {@code .floatValue()} silently produces zero (matching the
         * upstream {@code ToolingBlockEntities.Parser} semantics: non-literal values that
         * survive into arithmetic resolve to zero without WARNing - the WARN fires only
         * when the marker is consumed by a builder-dispatch pop site that gates on it via
         * the {@code *OrZero} variants).
         */
        private static final @NotNull Number NON_LITERAL = new Number() {
            @Override public int intValue() { return 0; }
            @Override public long longValue() { return 0L; }
            @Override public float floatValue() { return 0f; }
            @Override public double doubleValue() { return 0d; }
            @Override public @NotNull String toString() { return "<non-literal>"; }
        };

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
         *
         * @param value the value to push
         */
        public void push(@NotNull Object value) {
            this.entries.add(value);
            if (this.entries.size() > this.capacity)
                this.entries.removeFirst();
        }

        /**
         * Removes and returns the top of the stack, or {@code null} when the stack is empty.
         *
         * @return the popped value, or {@code null} when the stack is empty
         */
        public @Nullable Object pop() {
            if (this.entries.isEmpty()) return null;
            return this.entries.removeLast();
        }

        /**
         * Returns the top of the stack without removing it, or {@code null} when the stack
         * is empty.
         *
         * @return the top value, or {@code null} when the stack is empty
         */
        public @Nullable Object peek() {
            if (this.entries.isEmpty()) return null;
            return this.entries.getLast();
        }

        /**
         * Removes and returns the top of the stack as an {@link Integer}, returning
         * {@code null} when the stack is empty or when the top entry is not an int.
         *
         * @return the popped int, or {@code null} on empty stack or wrong-type top
         */
        public @Nullable Integer popInt() {
            if (this.entries.isEmpty()) return null;
            Object top = this.entries.getLast();
            if (!(top instanceof Integer value)) return null;
            this.entries.removeLast();
            return value;
        }

        /**
         * Removes and returns the top of the stack as a {@link Float}, returning {@code null}
         * when the stack is empty or when the top entry is not a float.
         *
         * @return the popped float, or {@code null} on empty stack or wrong-type top
         */
        public @Nullable Float popFloat() {
            if (this.entries.isEmpty()) return null;
            Object top = this.entries.getLast();
            if (!(top instanceof Float value)) return null;
            this.entries.removeLast();
            return value;
        }

        /**
         * Removes and returns the top of the stack as a {@link String}, returning
         * {@code null} when the stack is empty or when the top entry is not a string.
         *
         * @return the popped string, or {@code null} on empty stack or wrong-type top
         */
        public @Nullable String popString() {
            if (this.entries.isEmpty()) return null;
            Object top = this.entries.getLast();
            if (!(top instanceof String value)) return null;
            this.entries.removeLast();
            return value;
        }

        /**
         * Pushes a sentinel marker indicating "the JVM operand stack contained a value here,
         * but its source was a non-literal (a local variable populated by computation, a
         * method-call result, a {@code GETFIELD} of an opaque field, ...) and the parser
         * cannot resolve it to a constant". Consumed by the {@code *OrZero} pop variants
         * to produce a contextualised WARN instead of silently zero-filling, which would
         * mask the parser's accounting gap as a coordinate of {@code 0}.
         *
         * <p>The marker is type-agnostic - one {@code pushNonLiteral} matches both
         * {@link #popIntOrZero(Diagnostics, String, String) popIntOrZero} and
         * {@link #popFloatOrZero(Diagnostics, String, String) popFloatOrZero}.
         */
        public void pushNonLiteral() {
            this.entries.add(NON_LITERAL);
            if (this.entries.size() > this.capacity)
                this.entries.removeFirst();
        }

        /**
         * Pops an int from the stack, returning {@code 0} on three cases with different
         * diagnostic behaviour:
         * <ul>
         *   <li><b>empty stack</b> - silent zero. Mirrors the upstream parser convention that
         *       an underflow at the pop site is typically a benign accounting boundary
         *       (descriptor with fewer args than the stack carried) rather than a bug.</li>
         *   <li><b>{@code pushNonLiteral()} sentinel on top</b> - returns zero AND emits a
         *       WARN formatted as
         *       <pre>{@code "<contextPrefix> at <popSite>: non-literal argument consumed - a local variable populated from a computation, resolved to 0"}</pre>.
         *       The {@code contextPrefix} is the caller's tagging string (typically an entity
         *       id) prepended so a multi-source parse run can attribute the warning to a
         *       specific source.</li>
         *   <li><b>wrong-type on top</b> - same WARN shape as the marker case but with
         *       {@code "type mismatch (found <SimpleName>)"} appended. Still returns zero and
         *       still consumes the top so the parse can continue.</li>
         *   <li><b>matching int on top</b> - pops and returns the value, no WARN.</li>
         * </ul>
         *
         * @param diagnostics the diagnostic sink (must be non-null; use {@link #popInt()} for the no-diag variant)
         * @param contextPrefix a short label prepended to WARN messages (typically the source's entity id)
         * @param popSite a short label identifying the caller's pop site (e.g. {@code "addBox(name,FFFIIIII) v"})
         * @return the popped int, or {@code 0} on empty / marker / wrong-type
         */
        public int popIntOrZero(@NotNull Diagnostics diagnostics, @NotNull String contextPrefix, @NotNull String popSite) {
            if (this.entries.isEmpty()) return 0;
            Object top = this.entries.removeLast();
            if (top == NON_LITERAL) {
                diagnostics.warn(
                    "%s at %s: non-literal argument consumed - a local variable populated from a computation, resolved to 0",
                    contextPrefix, popSite
                );
                return 0;
            }
            if (top instanceof Integer value) return value;
            if (top instanceof Number n) return n.intValue();
            diagnostics.warn(
                "%s at %s: type mismatch popping int (found %s), resolved to 0",
                contextPrefix, popSite, top.getClass().getSimpleName()
            );
            return 0;
        }

        /**
         * Float-typed counterpart of {@link #popIntOrZero(Diagnostics, String, String)}.
         *
         * @param diagnostics the diagnostic sink
         * @param contextPrefix a short label prepended to WARN messages
         * @param popSite a short label identifying the caller's pop site
         * @return the popped float, or {@code 0f} on empty / marker / wrong-type
         */
        public float popFloatOrZero(@NotNull Diagnostics diagnostics, @NotNull String contextPrefix, @NotNull String popSite) {
            if (this.entries.isEmpty()) return 0f;
            Object top = this.entries.removeLast();
            if (top == NON_LITERAL) {
                diagnostics.warn(
                    "%s at %s: non-literal argument consumed - a local variable populated from a computation, resolved to 0",
                    contextPrefix, popSite
                );
                return 0f;
            }
            if (top instanceof Float value) return value;
            if (top instanceof Number n) return n.floatValue();
            diagnostics.warn(
                "%s at %s: type mismatch popping float (found %s), resolved to 0",
                contextPrefix, popSite, top.getClass().getSimpleName()
            );
            return 0f;
        }

        /**
         * Removes and returns the top of the stack as a {@link Number}, or {@code null} when
         * the stack is empty or the top is not a {@code Number}. Used by parsers that walk
         * arithmetic expressions ({@code FADD}, {@code I2F}, {@code Math.cos}, ...) over a
         * mixed Integer / Float / Double / Long stack where the result type is wider than any
         * single typed pop method. The {@link #pushNonLiteral non-literal marker} returns as a
         * {@code Number} whose {@code intValue() / floatValue() / doubleValue()} all yield
         * zero - matching the silent-zero arithmetic policy upstream parsers rely on.
         *
         * @return the popped number, or {@code null} when the stack is empty or top is non-numeric
         */
        public @Nullable Number popNumber() {
            if (this.entries.isEmpty()) return null;
            Object top = this.entries.getLast();
            if (!(top instanceof Number n)) return null;
            this.entries.removeLast();
            return n;
        }

        /**
         * Removes and returns the bottom-most (oldest) entry, or {@code null} when the stack
         * is empty. Mirrors the {@code ConcurrentList.removeFirst} semantics used by older
         * parsers; needed alongside {@link #pop} for callers that maintain a FIFO discard
         * policy beyond the built-in capacity-overflow eviction.
         *
         * @return the oldest value, or {@code null} when the stack is empty
         */
        public @Nullable Object removeFirst() {
            if (this.entries.isEmpty()) return null;
            return this.entries.removeFirst();
        }

        /**
         * Clears every entry from the stack.
         */
        public void reset() {
            this.entries.clear();
        }

        /**
         * The current number of entries on the stack.
         *
         * @return the entry count
         */
        public int size() {
            return this.entries.size();
        }

        /**
         * {@code true} when {@link #size()} is zero.
         *
         * @return whether the stack is empty
         */
        public boolean isEmpty() {
            return this.entries.isEmpty();
        }

    }

    // ----------------------------------------------------------------------------------------
    // SlotTracker - local-variable slot -> typed value map
    // ----------------------------------------------------------------------------------------

    /**
     * Tracks the most recently observed value bound to each local-variable slot during a
     * bytecode walk. Use to model the JVM's {@code ASTORE n} / {@code ALOAD n} dance when a
     * parser needs to remember "slot 3 currently holds a {@code LayerDefinition}" across
     * intervening instructions.
     *
     * <p>This is a thin typed map - {@code int → T} - with no automatic observation of
     * instructions. The caller drives it explicitly via {@link #store(int, Object)} on
     * {@code ASTORE}-like events and {@link #load(int)} on {@code ALOAD}-like events.
     *
     * <p>Used today by {@code ToolingBlockEntities.Parser} (cube-deformation tracking),
     * {@code EntityLayerDefinitionResolver} (layer-definition tracking through fluent
     * apply chains), and {@code InventoryTransformDecomposer} (matrix / vector tracking).
     *
     * @param <T> the tracked value type
     */
    public static final class SlotTracker<T> {

        private final @NotNull Map<Integer, T> slots = new LinkedHashMap<>();

        /**
         * Binds {@code value} to {@code slot}, replacing any previous binding.
         *
         * @param slot the local-variable slot index
         * @param value the value to bind
         */
        public void store(int slot, @NotNull T value) {
            this.slots.put(slot, value);
        }

        /**
         * Returns the value currently bound to {@code slot}, or {@code null} when no value
         * has been stored or the slot has been cleared.
         *
         * @param slot the local-variable slot index
         * @return the bound value, or {@code null}
         */
        public @Nullable T load(int slot) {
            return this.slots.get(slot);
        }

        /**
         * Clears the binding for {@code slot} (returns the previous value if any).
         *
         * @param slot the local-variable slot index
         * @return the previously bound value, or {@code null}
         */
        public @Nullable T clear(int slot) {
            return this.slots.remove(slot);
        }

        /**
         * Clears every binding.
         */
        public void reset() {
            this.slots.clear();
        }

        /**
         * The number of currently bound slots.
         *
         * @return the binding count
         */
        public int size() {
            return this.slots.size();
        }

        /**
         * {@code true} when no slot is bound.
         *
         * @return whether the tracker is empty
         */
        public boolean isEmpty() {
            return this.slots.isEmpty();
        }

    }

}
