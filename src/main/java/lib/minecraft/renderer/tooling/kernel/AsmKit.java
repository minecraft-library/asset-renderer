package lib.minecraft.renderer.tooling.kernel;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared ASM scaffolding used by every bytecode-walking tooling flow - cache-only
 * primitives with zero vanilla knowledge. Every class-fetch entry point takes
 * the session {@link ClassNodeCache}; no jar handle appears anywhere on this surface.
 *
 * <p>The kit owns these families of primitives:
 * <ul>
 *   <li><b>Class / member loading</b> - jar entry to {@link ClassNode}, name and descriptor
 *       method / field lookups (including superclass-chain variants), throwing {@code require*}
 *       variants for callers that want a tooling-canonical "obfuscated or unsupported version"
 *       error instead of a null return, plus {@link #findEnumDefaultName findEnumDefaultName}
 *       for the {@code GETSTATIC value; PUTSTATIC <default>} enum-default idiom.</li>
 *   <li><b>Class-hierarchy walks</b> - {@link #walkConstructorChain walkConstructorChain},
 *       {@link #walkSuperChain walkSuperChain}, {@link #walkSuperChainUntil walkSuperChainUntil},
 *       and {@link #extendsClass extendsClass}, each stopping before
 *       {@link #OBJECT_INTERNAL java/lang/Object}, all cache-fed.</li>
 *   <li><b>Literal decoding</b> - turn {@code ICONST_*} / {@code BIPUSH} / {@code SIPUSH} /
 *       {@code LDC} bytecode literal pushes back into boxed {@link Integer} / {@link Long} /
 *       {@link Float} / {@link Double} / {@link String} / {@link Type} values, plus the
 *       boolean-narrowed {@link #readBooleanLiteral readBooleanLiteral} and the
 *       type-dispatching {@link #readAnyLiteral readAnyLiteral}.</li>
 *   <li><b>Boolean-store decoding</b> - {@link #decodeBooleanStore decodeBooleanStore} turns the
 *       value expression before a {@code :Z} store into a {@link BooleanStore}
 *       ({@link ConstantStore} / {@link FieldStore}) carrying a {@link Polarity}, folding the
 *       {@code javac} compiled-{@code !flag} branch shape into one {@code POSITIVE} /
 *       {@code NEGATIVE} discriminator.</li>
 *   <li><b>Descriptor utilities</b> - {@link #descriptorReturns descriptorReturns},
 *       {@link #argSlotCount argSlotCount}, {@link #argTypes argTypes},
 *       {@link #returnType returnType}, and {@link #internalNameOfRef internalNameOfRef}
 *       for reading method / field descriptors without allocating intermediate strings.</li>
 *   <li><b>Instruction predicates</b> - {@code isInvokeStatic} / {@code isInvokeVirtual} /
 *       {@code isInvokeSpecial} / {@code isInvokeInterface} (with optional descriptor match),
 *       {@code isGetStatic} / {@code isPutStatic} / {@code isGetField} / {@code isPutField}
 *       (with optional field-name match), plus {@code isNewInstance},
 *       {@code isLambdaInvokeDynamic}, and the {@link #isPseudoNode(AbstractInsnNode)
 *       isPseudoNode} / {@link #previousReal(AbstractInsnNode) previousReal} /
 *       {@link #nextReal(AbstractInsnNode) nextReal} skip helpers.</li>
 *   <li><b>Method-body traversal</b> - {@link #findPreceding findPreceding},
 *       {@link #findFollowingPutStatic findFollowingPutStatic},
 *       {@link #containsInvoke(MethodNode, int, String, String) containsInvoke}, and
 *       {@link #containsFieldOp containsFieldOp}.</li>
 *   <li><b>Dissolvers</b> - {@link #scanPendingBindings scanPendingBindings} (the
 *       LDC-to-PUTSTATIC scanner shape behind nine-plus legacy clones) and
 *       {@link #readStaticEnumMap readStaticEnumMap} (enum-keyed map construction: coat /
 *       crackiness / markings / oxidation) absorb the duplicated walkers.</li>
 *   <li><b>Integer for-loop detection</b> - {@link #detectIntForLoop detectIntForLoop} matches
 *       the canonical javac {@code for (int i = INIT; i < BOUND; i += STEP)} scaffold into an
 *       {@link IntForLoop} record, with {@link #evaluateIntComparison evaluateIntComparison} for
 *       static branch resolution.</li>
 *   <li><b>Branch classification</b> - {@link #isBranchInsn isBranchInsn} flags the opcodes that
 *       terminate a straight-line region (used by linear scans that stay inside one basic block).</li>
 *   <li><b>Static scaling-factor reader</b> - {@link #resolveStaticScalingFactor
 *       resolveStaticScalingFactor} recovers a {@code static final} field's literal single-float
 *       factory factor ({@code LDC F; INVOKESTATIC factory(F); PUTSTATIC}) from its
 *       {@code <clinit>}.</li>
 *   <li><b>Lambda metafactory helpers</b> - {@link #extractLambdaHandle extractLambdaHandle},
 *       {@link #findBsmHandleByName findBsmHandleByName},
 *       {@link #resolveLambdaTargetClass resolveLambdaTargetClass}, and
 *       {@link #walkLambdaBody walkLambdaBody} recover the target class of a {@code javac}
 *       lambda call site.</li>
 *   <li><b>String-concatenation helpers</b> - {@link #resolveStringConcatRecipe
 *       resolveStringConcatRecipe} / {@link #applyStringConcatRecipeWithInt
 *       applyStringConcatRecipeWithInt} / {@link #findStringConcatRecipeIn findStringConcatRecipeIn}
 *       decode {@code makeConcatWithConstants} indy recipes for procedural-loop part naming.</li>
 *   <li><b>Generic-signature parsing</b> - {@link #extractGenericTypeParameter
 *       extractGenericTypeParameter} pulls the single concrete type parameter out of an
 *       {@code Outer<LInner;>} field signature.</li>
 *   <li><b>Diagnostic formatters</b> - {@code diagMissingClass} / {@code diagMissingMethod} /
 *       {@code diagMissingField} / {@code diagUnexpectedPattern} offer canonical {@code WARN}
 *       phrasings for a {@link Diagnostics} sink. Nothing calls them: a tooling walk that
 *       loses a class or a member reports it at {@code ERROR} and bails, so these four are an
 *       available phrasing rather than the one in force.</li>
 *   <li><b>Retention / state classes</b> - {@link LiteralStack} accumulates recent literal
 *       pushes so a builder-dispatch instruction can pop them in LIFO order, and
 *       {@link SlotTracker} models the {@code ASTORE n} / {@code ALOAD n} local-variable dance
 *       for parsers that must remember a slot's value across intervening instructions.</li>
 * </ul>
 *
 * <p>That inventory is capability, not a call graph. Fourteen of the names it lists have no
 * production caller and are kept deliberately, a bytecode primitive being cheaper to carry
 * than to re-derive on a Minecraft version bump: both {@code requireMethod} forms,
 * {@link #requireClinit}, {@link #requireField}, {@link #findFieldInHierarchy},
 * {@link #readAnyLiteral}, {@link #argSlotCount}, both {@code isInvokeInterface} forms,
 * {@link #isGetField}, both {@code containsInvoke} forms, {@link #containsFieldOp}, and all
 * four {@code diag*} formatters. Of those, only {@code requireClinit} and
 * {@code findFieldInHierarchy} are reached at all, by this kit's own unit test.
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

    /**
     * The {@code java.lang.Object} JVM internal name. Used as the canonical stop sentinel for
     * the superclass-chain walks that never want to visit {@code Object}:
     * {@link #walkSuperChain}, {@link #walkConstructorChain} (via {@code walkSuperChain}), and
     * {@link #extendsClass}. The {@code *InHierarchy} lookups
     * ({@link #findMethodInHierarchy}, {@link #findFieldInHierarchy}) instead walk to a
     * {@code null} superName and do not special-case this name.
     */
    public static final @NotNull String OBJECT_INTERNAL = "java/lang/Object";

    /**
     * Static-initializer method name (JVM {@code <clinit>}). Centralized to avoid the
     * literal {@code "<clinit>"} re-appearing in every parser.
     */
    public static final @NotNull String CLINIT = "<clinit>";

    /**
     * The javac-synthesised static-lambda body prefix ({@code lambda$static$N}) - a
     * stable javac naming convention, NOT a JVM-spec guarantee: a compiler change would
     * surface as missed lambda bodies in the texture / variant-coat walks, so the single
     * declaration lives here.
     */
    public static final @NotNull String LAMBDA_STATIC_PREFIX = "lambda$static$";

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
     * Loads a class through the supplied {@link ClassNodeCache} (which owns its jar handle),
     * throwing a {@link ToolingException} with a context-tagged "obfuscated or unsupported
     * version" message when the class is missing.
     *
     * @param cache the per-session cache to consult / populate
     * @param internalName the class's JVM internal name
     * @param context a short label identifying the caller in the error message
     * @return the populated {@link ClassNode}
     * @throws ToolingException if the class is not in the jar or cannot be read
     */
    public static @NotNull ClassNode requireClass(@NotNull ClassNodeCache cache, @NotNull String internalName, @NotNull String context) {
        ClassNode classNode = cache.load(internalName);
        if (classNode == null)
            throw new ToolingException(
                "Jar does not contain '%s.class' for %s - the jar is either obfuscated or from an unsupported version",
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
     * Looks up a method by (class, name, descriptor), walking the superclass chain through
     * the cache as the JVM would for {@code invokestatic} / {@code invokevirtual} resolution.
     * The walk follows {@code superName} until it hits {@code null} (top of the hierarchy) -
     * unlike {@link #walkSuperChain} it does not stop early at {@link #OBJECT_INTERNAL}, so a
     * jar that actually contains {@code Object} would have it searched. Returns {@code null}
     * when the method isn't found anywhere in the hierarchy or when any link of the chain is
     * missing from the jar.
     *
     * @param cache the per-session cache to consult / populate
     * @param startInternalName the class to begin the walk at (that class and all its ancestors are searched)
     * @param name the method name
     * @param descriptor the method descriptor, or {@code null} to match on name alone
     * @return the matching {@link MethodNode}, or {@code null} when the walk finds nothing
     */
    public static @Nullable MethodNode findMethodInHierarchy(
        @NotNull ClassNodeCache cache,
        @NotNull String startInternalName,
        @NotNull String name,
        @Nullable String descriptor
    ) {
        String current = startInternalName;
        while (current != null) {
            ClassNode classNode = cache.load(current);
            if (classNode == null) return null;
            MethodNode m = descriptor == null ? findMethod(classNode, name) : findMethod(classNode, name, descriptor);
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
                "Class '%s' does not expose a '%s' method for %s - the jar is either obfuscated or from an unsupported version",
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
                "Class '%s' does not expose a '%s%s' method for %s - the jar is either obfuscated or from an unsupported version",
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
     * Looks up a field by (class, name), walking the superclass chain through the cache as
     * the JVM would for field resolution. The walk follows {@code superName} until it hits
     * {@code null}; like {@link #findMethodInHierarchy} (and unlike {@link #walkSuperChain})
     * it does not stop early at {@link #OBJECT_INTERNAL}. Returns {@code null} when the field
     * isn't found anywhere in the hierarchy or when a link is missing from the jar.
     *
     * @param cache the per-session cache to consult / populate
     * @param startInternalName the class to begin the walk at
     * @param name the field name
     * @return the matching {@link FieldNode}, or {@code null} when the walk finds nothing
     */
    public static @Nullable FieldNode findFieldInHierarchy(@NotNull ClassNodeCache cache, @NotNull String startInternalName, @NotNull String name) {
        String current = startInternalName;
        while (current != null) {
            ClassNode classNode = cache.load(current);
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
                "Class '%s' does not expose a '%s' field for %s - the jar is either obfuscated or from an unsupported version",
                classNode.name, name, context
            );
        return field;
    }

    /**
     * Walks the enum class's {@code <clinit>} for the canonical
     * {@code GETSTATIC <enum_value>; PUTSTATIC <defaultFieldName>} pair and returns the enum
     * value's declared name (uppercase, e.g. {@code "RED"} for
     * {@code MushroomCow$Variant.DEFAULT = RED}). Returns {@code null} when the class is
     * missing from the jar, has no {@code <clinit>}, or has no matching static field
     * initialised by the simple GETSTATIC-then-PUTSTATIC pattern. The field name is a
     * PARAMETER supplied by the calling policy - the kit stays vanilla-blind.
     *
     * <p>Used by texture / variant resolvers that need to recover the canonical zero-state
     * variant from an enum-typed render-state field. The match is owner-strict on both
     * GETSTATIC and PUTSTATIC sides so unrelated static-init reads in the same {@code <clinit>}
     * don't pollute the pending-field running state.
     *
     * @param classNodes the per-session cache to consult / populate
     * @param enumInternalName the variant enum class's JVM internal name
     * @param defaultFieldName the default-holding static field's name (policy-supplied)
     * @return the name of the enum constant the default field is initialised to,
     *     or {@code null} when no match
     */
    public static @Nullable String findEnumDefaultName(@NotNull ClassNodeCache classNodes, @NotNull String enumInternalName, @NotNull String defaultFieldName) {
        ClassNode cn = classNodes.load(enumInternalName);
        if (cn == null) return null;
        MethodNode clinit = findMethod(cn, CLINIT);
        if (clinit == null) return null;
        String pendingFieldName = null;
        for (AbstractInsnNode in : clinit.instructions) {
            if (in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && enumInternalName.equals(fi.owner)) {
                pendingFieldName = fi.name;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && enumInternalName.equals(fi.owner)
                && defaultFieldName.equals(fi.name)
                && pendingFieldName != null)
                return pendingFieldName;
        }
        return null;
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
     * @param cache the per-session cache to consult / populate
     * @param startInternalName the class to begin the walk at
     * @param callback invoked once per matching constructor in walk order
     */
    public static void walkConstructorChain(
        @NotNull ClassNodeCache cache,
        @NotNull String startInternalName,
        @NotNull Consumer<MethodNode> callback
    ) {
        walkSuperChain(cache, startInternalName, classNode -> {
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
     * @param cache the per-session cache to consult / populate
     * @param startInternalName the class to begin the walk at
     * @param classCallback invoked once per visited class
     */
    public static void walkSuperChain(
        @NotNull ClassNodeCache cache,
        @NotNull String startInternalName,
        @NotNull Consumer<ClassNode> classCallback
    ) {
        String current = startInternalName;
        while (current != null && !OBJECT_INTERNAL.equals(current)) {
            ClassNode classNode = cache.load(current);
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
     * @param cache the per-session cache to consult / populate
     * @param startInternalName the class to begin the walk at
     * @param targetInternalName the candidate ancestor
     * @return {@code true} when {@code startInternalName} extends or equals {@code targetInternalName}
     */
    public static boolean extendsClass(
        @NotNull ClassNodeCache cache,
        @NotNull String startInternalName,
        @NotNull String targetInternalName
    ) {
        String current = startInternalName;
        while (current != null && !OBJECT_INTERNAL.equals(current)) {
            if (targetInternalName.equals(current)) return true;
            ClassNode classNode = cache.load(current);
            if (classNode == null) return false;
            current = classNode.superName;
        }
        return false;
    }

    /**
     * Walks the superclass chain starting at {@code startInternalName}, stopping before
     * {@link #OBJECT_INTERNAL java/lang/Object}, and returns the first class {@code stop}
     * accepts - the early-out form the three legacy open-coded walks shared. Returns
     * {@code null} when no class matches or a link is missing from the jar.
     *
     * @param cache the per-session cache to consult / populate
     * @param startInternalName the class to begin the walk at
     * @param stop the acceptance predicate; the walk returns the first accepted class
     * @return the first accepted class, or {@code null} when none matches
     */
    public static @Nullable ClassNode walkSuperChainUntil(
        @NotNull ClassNodeCache cache,
        @NotNull String startInternalName,
        @NotNull Predicate<ClassNode> stop
    ) {
        String current = startInternalName;
        while (current != null && !OBJECT_INTERNAL.equals(current)) {
            ClassNode classNode = cache.load(current);
            if (classNode == null) return null;
            if (stop.test(classNode)) return classNode;
            current = classNode.superName;
        }
        return null;
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
     * Decodes a boolean literal push, returning {@link Boolean#FALSE} for {@code ICONST_0} and
     * {@link Boolean#TRUE} for {@code ICONST_1}. Returns {@code null} for every other node -
     * including {@code ICONST_M1} and {@code ICONST_2}..{@code ICONST_5}, which are not JVM
     * boolean literals. Narrowed counterpart of {@link #readIntLiteral} for the {@code 0}/{@code 1}
     * domain a {@code :Z} store or a compiled boolean branch draws from.
     *
     * @param node the instruction to decode
     * @return {@code TRUE} / {@code FALSE} for {@code ICONST_1} / {@code ICONST_0}, or {@code null} otherwise
     */
    public static @Nullable Boolean readBooleanLiteral(@NotNull AbstractInsnNode node) {
        int opcode = node.getOpcode();
        if (opcode == Opcodes.ICONST_0) return Boolean.FALSE;
        if (opcode == Opcodes.ICONST_1) return Boolean.TRUE;
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
     * Returns {@code true} when local-variable {@code slot} is a parameter of {@code method}
     * declared with the given reference type - the test that tells a value loaded by
     * {@code ALOAD slot} apart from a class-local constant. A renderer that takes its layer
     * type as a constructor parameter reads it this way, so the value has to be recovered from
     * the construction site rather than from the class itself.
     *
     * @param method the method owning the slot
     * @param slot the local-variable slot an {@code ALOAD} names
     * @param internalName the expected parameter type's JVM internal name
     * @return {@code true} when the slot is a parameter of that type
     */
    public static boolean isParameterOfType(@NotNull MethodNode method, int slot, @NotNull String internalName) {
        int current = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;   // instance methods hold `this` in slot 0
        for (Type arg : Type.getArgumentTypes(method.desc)) {
            if (current == slot)
                return arg.getSort() == Type.OBJECT && arg.getInternalName().equals(internalName);
            current += arg.getSize();
        }
        return false;
    }


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
    // Boolean-store decoding
    // ----------------------------------------------------------------------------------------

    /**
     * Relationship between a decoded boolean r-value and the field it reads. {@link #POSITIVE}:
     * the value equals the field ({@code x = flag}). {@link #NEGATIVE}: the value is the field's
     * compiled negation ({@code x = !flag}), which javac emits as an
     * {@code IF..; ICONST; GOTO; ICONST} two-constant select.
     */
    public enum Polarity { POSITIVE, NEGATIVE }

    /**
     * A boolean r-value decoded from the instructions preceding a boolean store
     * ({@code PUTFIELD <...>:Z} or similar). Sealed over the shapes {@code javac} emits: a
     * compile-time literal ({@link ConstantStore}) or a read of an object's {@code :Z} field,
     * direct or compiled-negated ({@link FieldStore}). {@link #valueStart()} is the earliest
     * instruction of the value expression, so a caller reaches the store target with a single
     * {@code previousReal(store.valueStart())} - no re-derivation of the decoded shape. Purely
     * structural: it names no owner, field, or method as meaningful; the caller applies its own
     * semantic guards (which owner is the model, which flag name gates, and so on).
     */
    public sealed interface BooleanStore permits ConstantStore, FieldStore {

        /**
         * The lowest-address instruction of the decoded value expression - the node a caller
         * walks back from ({@code previousReal(valueStart())}) to reach the store target.
         *
         * @return the earliest instruction of the value expression
         */
        @NotNull AbstractInsnNode valueStart();
    }

    /**
     * A boolean r-value that is a compile-time literal ({@code ICONST_0} / {@code ICONST_1}).
     *
     * @param value the literal value ({@code false} = {@code ICONST_0}, {@code true} = {@code ICONST_1})
     * @param valueStart the {@code ICONST} node
     */
    public record ConstantStore(boolean value, @NotNull AbstractInsnNode valueStart) implements BooleanStore {}

    /**
     * A boolean r-value read from an object's {@code :Z} field: {@code <receiver>; GETFIELD f:Z}
     * for {@link Polarity#POSITIVE}, or that read wrapped in {@code javac}'s compiled-negation
     * select ({@code GETFIELD f:Z; IF{EQ,NE}; ICONST; GOTO; ICONST}) for {@link Polarity#NEGATIVE}.
     * A {@code NEGATIVE} result is returned only when the branch is a genuine boolean select whose
     * two constants form a distinct {@code 0}/{@code 1} pair; a non-{@code 0/1} or equal-constant
     * branch decodes to {@link ConstantStore} instead.
     *
     * @param field the {@code GETFIELD} reading the flag ({@code owner} / {@code name}, {@code desc == "Z"})
     * @param receiver the instruction pushing the flag's receiver (the node before {@code GETFIELD})
     * @param polarity {@code POSITIVE} for a direct read, {@code NEGATIVE} for the compiled {@code !flag} select
     * @param valueAtFieldFalse the boolean produced when the flag is {@code false} - always
     *     {@code false} for {@code POSITIVE}; the branch-resolved constant for {@code NEGATIVE}
     *     ({@code cond == IFNE ? fallConst : branchConst})
     * @param valueStart the receiver load (earliest instruction of the value expression)
     */
    public record FieldStore(
        @NotNull FieldInsnNode field,
        @NotNull AbstractInsnNode receiver,
        @NotNull Polarity polarity,
        boolean valueAtFieldFalse,
        @NotNull AbstractInsnNode valueStart
    ) implements BooleanStore {}

    /**
     * Decodes the boolean r-value produced by {@code valueInsn}, the real instruction
     * immediately preceding a boolean store ({@code previousReal(putfield)}; the caller has
     * already validated the {@code :Z} store). Recognises the three {@code javac} boolean-store
     * shapes:
     * <ul>
     *   <li>{@code ICONST_0/1} - {@link ConstantStore}</li>
     *   <li>{@code <recv>; GETFIELD f:Z} - {@link FieldStore} {@link Polarity#POSITIVE}</li>
     *   <li>{@code <recv>; GETFIELD f:Z; IF{EQ,NE}; ICONST; GOTO; ICONST} (compiled {@code !f},
     *       distinct {@code 0}/{@code 1} select) - {@link FieldStore} {@link Polarity#NEGATIVE}</li>
     * </ul>
     * Disambiguation of the trailing {@code ICONST}: when its {@code previousReal} is a
     * {@code GOTO} the {@code NEGATIVE} select is attempted; if that decode fails (constants not a
     * distinct {@code 0}/{@code 1} pair, missing {@code IF}, non-{@code :Z} field) the node falls
     * back to {@link ConstantStore}. Returns {@code null} when no shape matches.
     *
     * @param valueInsn the value-producing instruction immediately before a boolean store
     * @return the decoded store, or {@code null} when the shape is unrecognised
     */
    public static @Nullable BooleanStore decodeBooleanStore(@NotNull AbstractInsnNode valueInsn) {
        Boolean literal = readBooleanLiteral(valueInsn);
        if (literal != null) {
            AbstractInsnNode prev = previousReal(valueInsn);
            if (prev != null && prev.getOpcode() == Opcodes.GOTO) {
                FieldStore negated = decodeNegatedBranch(valueInsn, literal);
                if (negated != null) return negated;
            }
            return new ConstantStore(literal, valueInsn);
        }
        if (valueInsn.getOpcode() == Opcodes.GETFIELD
            && valueInsn instanceof FieldInsnNode field
            && "Z".equals(field.desc)) {
            AbstractInsnNode receiver = previousReal(valueInsn);
            if (receiver == null) return null;
            return new FieldStore(field, receiver, Polarity.POSITIVE, false, receiver);
        }
        return null;
    }

    /**
     * Attempts to decode the compiled {@code !flag} select tail whose branch-target constant is
     * {@code branchConstNode} (the {@code ICONST} whose {@code previousReal} is the closing
     * {@code GOTO}). Reads backward {@code GOTO; ICONST(fall); IF{EQ,NE}; GETFIELD f:Z; <receiver>}
     * and returns the {@link FieldStore}, or {@code null} when the shape is not a genuine
     * {@code GETFIELD f:Z} negation with a distinct {@code 0}/{@code 1} constant pair.
     * {@code branchValue} is {@code branchConstNode}'s already-decoded boolean.
     */
    private static @Nullable FieldStore decodeNegatedBranch(@NotNull AbstractInsnNode branchConstNode, boolean branchValue) {
        AbstractInsnNode gotoInsn = previousReal(branchConstNode);
        if (gotoInsn == null || gotoInsn.getOpcode() != Opcodes.GOTO) return null;
        AbstractInsnNode fallNode = previousReal(gotoInsn);
        if (fallNode == null) return null;
        Boolean fallValue = readBooleanLiteral(fallNode);
        if (fallValue == null || fallValue.booleanValue() == branchValue) return null;
        AbstractInsnNode condInsn = previousReal(fallNode);
        if (condInsn == null) return null;
        int cond = condInsn.getOpcode();
        if (cond != Opcodes.IFEQ && cond != Opcodes.IFNE) return null;
        AbstractInsnNode fieldInsn = previousReal(condInsn);
        if (fieldInsn == null
            || fieldInsn.getOpcode() != Opcodes.GETFIELD
            || !(fieldInsn instanceof FieldInsnNode field)
            || !"Z".equals(field.desc)) return null;
        AbstractInsnNode receiver = previousReal(fieldInsn);
        if (receiver == null) return null;
        boolean valueAtFieldFalse = cond == Opcodes.IFNE ? fallValue : branchValue;
        return new FieldStore(field, receiver, Polarity.NEGATIVE, valueAtFieldFalse, receiver);
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
     * <p>Mirrors {@code EntityRegistryDiscovery.findFollowingPutStatic}: after a registration
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
        for (AbstractInsnNode node : method.instructions)
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
        for (AbstractInsnNode node : method.instructions)
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
        for (AbstractInsnNode node : method.instructions) {
            if (node.getOpcode() != opcode) continue;
            if (!(node instanceof FieldInsnNode fieldInsn)) continue;
            if (fieldInsn.owner.equals(owner) && fieldInsn.name.equals(name)) return true;
        }
        return false;
    }

    // ----------------------------------------------------------------------------------------
    // Dissolvers - shared walk shapes that absorbed N duplicated scanners
    // ----------------------------------------------------------------------------------------

    /**
     * One {@code <value>; PUTSTATIC} binding recovered by {@link #scanPendingBindings}.
     *
     * @param owner the {@code PUTSTATIC} owner's JVM internal name
     * @param field the bound static field's name
     * @param value the decoded pending value
     * @param site the {@code PUTSTATIC} instruction node
     */
    public record StaticBinding<V>(@NotNull String owner, @NotNull String field, @NotNull V value, @NotNull AbstractInsnNode site) {}

    /**
     * Scans {@code method} for {@code <pending value>; PUTSTATIC} pairs - the shape behind
     * the nine-plus legacy LDC-to-PUTSTATIC scanner clones. {@code pendingReader} decodes a
     * candidate value from each instruction (typically one of the {@code readXLiteral}
     * helpers); a following {@code PUTSTATIC} accepted by {@code putStaticFilter} commits the
     * pending value to that field. Reset semantics follow
     * {@link #resolveStaticScalingFactor}'s strict model: any other real instruction clears
     * the pending value so a stale literal never binds to a later store; pseudo-nodes are
     * skipped transparently.
     *
     * @param method the method to scan (typically a {@code <clinit>})
     * @param pendingReader decodes a candidate pending value from an instruction, or {@code null}
     * @param putStaticFilter accepts the {@code PUTSTATIC} instructions that commit a binding
     * @return the bindings in encounter order
     */
    public static <V> @NotNull List<StaticBinding<V>> scanPendingBindings(
        @NotNull MethodNode method,
        @NotNull Function<AbstractInsnNode, @Nullable V> pendingReader,
        @NotNull Predicate<FieldInsnNode> putStaticFilter
    ) {
        List<StaticBinding<V>> out = new ArrayList<>();
        V pending = null;
        for (AbstractInsnNode node : method.instructions) {
            if (isPseudoNode(node)) continue;
            V value = pendingReader.apply(node);
            if (value != null) {
                pending = value;
                continue;
            }
            if (node.getOpcode() == Opcodes.PUTSTATIC && node instanceof FieldInsnNode field && putStaticFilter.test(field)) {
                if (pending != null) out.add(new StaticBinding<>(field.owner, field.name, pending, field));
                pending = null;
                continue;
            }
            pending = null;
        }
        return out;
    }

    /**
     * Reads a static enum-keyed map's {@code <clinit>} construction into a
     * {@code Map<enum-constant-name, decoded value>} - the shape behind the legacy coat /
     * crackiness / markings / oxidation walks. Each map entry pushes its enum key
     * ({@code GETSTATIC <Enum>.<NAME>: L<Enum>;} - an enum-constant read is recognised by its
     * field descriptor matching its owner) followed by the value expression; the FIRST value
     * {@code valueReader} decodes after a key binds to it, and the walk commits at the
     * {@code PUTSTATIC <owner>.<mapField>} that stores the finished map. Returns entries in
     * {@code <clinit>} (enum-declaration) order; empty when the class, its {@code <clinit>},
     * or any binding is missing.
     *
     * @param cache the per-session cache to consult / populate
     * @param owner the class whose {@code <clinit>} builds the map
     * @param mapField the static field the finished map is stored into
     * @param valueReader decodes a candidate value from an instruction, or {@code null}
     * @return enum-constant name to decoded value, in encounter order
     */
    public static <V> @NotNull Map<String, V> readStaticEnumMap(
        @NotNull ClassNodeCache cache,
        @NotNull String owner,
        @NotNull String mapField,
        @NotNull Function<AbstractInsnNode, @Nullable V> valueReader
    ) {
        Map<String, V> out = new LinkedHashMap<>();
        ClassNode cn = cache.load(owner);
        if (cn == null) return out;
        MethodNode clinit = findMethod(cn, CLINIT);
        if (clinit == null) return out;
        String pendingKey = null;
        for (AbstractInsnNode node : clinit.instructions) {
            if (isPutStatic(node, owner, mapField)) break;
            if (node.getOpcode() == Opcodes.GETSTATIC
                && node instanceof FieldInsnNode field
                && field.desc.equals("L" + field.owner + ";")) {
                pendingKey = field.name;
                continue;
            }
            if (pendingKey == null) continue;
            V value = valueReader.apply(node);
            if (value != null) {
                out.putIfAbsent(pendingKey, value);
                pendingKey = null;
            }
        }
        return out;
    }

    // ----------------------------------------------------------------------------------------
    // Static scaling-factor reader
    // ----------------------------------------------------------------------------------------

    /**
     * Per-cache-instance memo for {@link #resolveStaticScalingFactor} (must permit
     * {@code null} values - "resolved to null" is distinct from "not yet walked").
     * Single-threaded per session by tooling convention.
     */
    private static final @NotNull Map<ClassNodeCache, Map<String, Float>> SCALING_MEMO = new WeakHashMap<>();

    /**
     * Resolves a {@code static final} field whose {@code <clinit>} initialiser is a literal
     * single-float factory call - {@code LDC F; INVOKESTATIC <factoryOwner>.<factoryMethod>(F)...;
     * PUTSTATIC <owner>.<field>:<fieldDesc>} - to that {@code F}. Walks the owning class's
     * {@code <clinit>} once and memoises <b>every</b> canonical field it encounters, keyed
     * {@code owner + "." + field}, so sibling fields on the same class resolve without a re-walk;
     * a non-canonical initialiser (indy-backed, arithmetic on {@code F}, compound) memoises
     * {@code null}. The memo's own {@code containsKey} distinguishes "resolved to null" from "not
     * yet walked" and short-circuits before {@link ClassNodeCache#load}, so a hit never touches
     * the jar.
     *
     * <p>Kept vanilla-agnostic: the caller supplies the factory owner / method and the field
     * descriptor (for the mesh-transformer walkers these are {@code MeshTransformer} /
     * {@code "scaling"} / {@code L...MeshTransformer;}). The factory is matched as
     * {@code "(F)" + fieldDesc} - a single {@code float} argument returning the field type.
     * {@link #SCALING_MEMO} is keyed per {@link ClassNodeCache} instance and lives here in the kit
     * so the cache stays storage-only; it is weakly keyed so a closed session's entries vanish
     * with its cache.
     *
     * <p>An intervening {@code LDC F} between a {@code scaling(F)} call and its {@code PUTSTATIC}
     * clears the pending scaled value, so a stale factor never binds to a later field. This is a
     * defensive stance: a looser reset would satisfy every shipped {@code <clinit>} equally well,
     * so the stricter reset is adopted unconditionally rather than tuned to the corpus.
     *
     * @param cache the per-session cache to consult / populate (also the memo key)
     * @param owner the field's owning class internal name (memo key + {@code PUTSTATIC} owner match)
     * @param fieldName the static field name being resolved
     * @param factoryOwner the scaling factory's owner internal name
     * @param factoryMethod the scaling factory's method name
     * @param fieldDesc the field's JVM descriptor (also the factory's return type)
     * @return the resolved factor, or {@code null} for a non-canonical / missing initialiser
     */
    public static @Nullable Float resolveStaticScalingFactor(
        @NotNull ClassNodeCache cache,
        @NotNull String owner,
        @NotNull String fieldName,
        @NotNull String factoryOwner,
        @NotNull String factoryMethod,
        @NotNull String fieldDesc
    ) {
        Map<String, Float> memo = SCALING_MEMO.computeIfAbsent(cache, instance -> new LinkedHashMap<>());
        String key = owner + "." + fieldName;
        if (memo.containsKey(key)) return memo.get(key);

        ClassNode cls = cache.load(owner);
        MethodNode clinit = cls != null ? findMethod(cls, CLINIT) : null;
        if (clinit == null) {
            memo.put(key, null);
            return null;
        }

        String factoryDesc = "(F)" + fieldDesc;
        Float pendingFloat = null;
        Float pendingScaled = null;
        for (AbstractInsnNode in : clinit.instructions) {
            int op = in.getOpcode();
            if (op < 0) continue; // labels / line numbers / frames
            if (in instanceof LdcInsnNode ldc && ldc.cst instanceof Float f) {
                pendingFloat = f;
                pendingScaled = null;
            } else if (in instanceof MethodInsnNode mi
                && op == Opcodes.INVOKESTATIC
                && factoryOwner.equals(mi.owner)
                && factoryMethod.equals(mi.name)
                && factoryDesc.equals(mi.desc)
                && pendingFloat != null) {
                pendingScaled = pendingFloat;
                pendingFloat = null;
            } else if (in instanceof FieldInsnNode fi
                && op == Opcodes.PUTSTATIC
                && fieldDesc.equals(fi.desc)
                && fi.owner.equals(owner)) {
                memo.put(owner + "." + fi.name, pendingScaled);
                pendingScaled = null;
                pendingFloat = null;
            } else {
                // Any unrelated instruction clears the pending literal so a stale F never binds to
                // a later putstatic; pendingScaled survives no-op-ish instructions so the canonical
                // ldc / invokestatic / putstatic triplet still binds.
                pendingFloat = null;
            }
        }

        memo.putIfAbsent(key, null);
        return memo.get(key);
    }

    // ----------------------------------------------------------------------------------------
    // Integer for-loop detection
    // ----------------------------------------------------------------------------------------

    /**
     * Evaluates a JVM integer-comparison jump opcode given concrete operand values. Used by
     * parsers that resolve {@code IF<cond>} / {@code IF_ICMP<cond>} branch decisions at static
     * analysis time once both sides are known compile-time literals. Unary opcodes
     * ({@code IFEQ}, {@code IFNE}, {@code IFLT}, {@code IFGE}, {@code IFGT}, {@code IFLE})
     * ignore {@code rhs} - the JVM specifies their predicate as {@code lhs <op> 0}, so callers
     * may pass any value (zero is conventional). Binary opcodes ({@code IF_ICMPEQ},
     * {@code IF_ICMPNE}, {@code IF_ICMPLT}, {@code IF_ICMPGE}, {@code IF_ICMPGT},
     * {@code IF_ICMPLE}) use {@code lhs <op> rhs}.
     *
     * @param opcode the JVM jump opcode (one of {@link Opcodes#IFEQ}..{@link Opcodes#IF_ICMPLE})
     * @param lhs the left-hand operand (or the only operand for unary opcodes)
     * @param rhs the right-hand operand (ignored for unary opcodes)
     * @return {@code true} when the comparison would take the branch, {@code false} for
     *     falls-through or for any unmodelled opcode
     */
    public static boolean evaluateIntComparison(int opcode, int lhs, int rhs) {
        return switch (opcode) {
            case Opcodes.IFEQ -> lhs == 0;
            case Opcodes.IFNE -> lhs != 0;
            case Opcodes.IFLT -> lhs < 0;
            case Opcodes.IFGE -> lhs >= 0;
            case Opcodes.IFGT -> lhs > 0;
            case Opcodes.IFLE -> lhs <= 0;
            case Opcodes.IF_ICMPEQ -> lhs == rhs;
            case Opcodes.IF_ICMPNE -> lhs != rhs;
            case Opcodes.IF_ICMPLT -> lhs < rhs;
            case Opcodes.IF_ICMPGE -> lhs >= rhs;
            case Opcodes.IF_ICMPGT -> lhs > rhs;
            case Opcodes.IF_ICMPLE -> lhs <= rhs;
            default -> false;
        };
    }

    /**
     * Returns {@code true} when {@code opcode} does not fall through to the next instruction
     * linearly - a conditional or unconditional jump ({@code IFEQ}..{@code IF_ACMPNE},
     * {@code GOTO}, {@code JSR}, {@code IFNULL}, {@code IFNONNULL}), a switch
     * ({@code TABLESWITCH}, {@code LOOKUPSWITCH}), or a method exit
     * ({@code RETURN} / {@code IRETURN} / {@code LRETURN} / {@code FRETURN} / {@code DRETURN} /
     * {@code ARETURN}, {@code ATHROW}). The exact opcode set a straight-line scan splits on
     * when it wants to stay inside a single basic block.
     *
     * @param opcode the JVM opcode
     * @return whether the opcode terminates a straight-line region
     */
    public static boolean isBranchInsn(int opcode) {
        if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE) return true;
        return opcode == Opcodes.GOTO
            || opcode == Opcodes.JSR
            || opcode == Opcodes.IFNULL
            || opcode == Opcodes.IFNONNULL
            || opcode == Opcodes.TABLESWITCH
            || opcode == Opcodes.LOOKUPSWITCH
            || opcode == Opcodes.RETURN
            || opcode == Opcodes.IRETURN
            || opcode == Opcodes.LRETURN
            || opcode == Opcodes.FRETURN
            || opcode == Opcodes.DRETURN
            || opcode == Opcodes.ARETURN
            || opcode == Opcodes.ATHROW;
    }

    /**
     * Description of a detected Java {@code for (int i = INIT; i < BOUND; i += STEP)} loop.
     *
     * @param iteratorSlot the JVM local-variable slot that holds the iterator
     * @param initValue the initial value pushed by the {@code ICONST_N} / {@code BIPUSH} /
     *     {@code SIPUSH} / {@code LDC} before the iterator's {@code ISTORE}
     * @param boundExclusive the {@code IF_ICMPGE} comparison value - the loop body executes
     *     while {@code i < boundExclusive}
     * @param step the iterator increment, captured from the body's {@code IINC <slot>, +step}.
     *     Positive for ascending loops; negative loops are not detected
     * @param firstBodyInsn the first real instruction inside the loop body (after the
     *     {@code IF_ICMPGE}); guaranteed non-pseudo
     * @param firstInsnAfterLoop the instruction that follows the loop's exit (the target of
     *     {@code IF_ICMPGE}, after skipping pseudo-instructions); guaranteed non-pseudo
     */
    public record IntForLoop(
        int iteratorSlot,
        int initValue,
        int boundExclusive,
        int step,
        @NotNull AbstractInsnNode firstBodyInsn,
        @NotNull AbstractInsnNode firstInsnAfterLoop
    ) {
        /**
         * Returns the number of times the body executes, or {@code 0} when {@code initValue >=
         * boundExclusive} or {@code step <= 0}.
         */
        public int iterations() {
            if (this.step <= 0 || this.initValue >= this.boundExclusive) return 0;
            return (this.boundExclusive - this.initValue + this.step - 1) / this.step;
        }
    }

    /**
     * Detects the canonical javac {@code for (int i = INIT; i < BOUND; i += STEP)} loop
     * starting at {@code candidateInit}. The expected bytecode shape:
     * <pre>
     *   {@code <numeric literal init>}  ICONST_N / BIPUSH / SIPUSH / LDC int  (candidateInit)
     *   ISTORE &lt;slot&gt;
     * test:
     *   ILOAD &lt;slot&gt;
     *   {@code <numeric literal bound>}
     *   IF_ICMPGE exit
     *   ... body ...
     *   IINC &lt;slot&gt;, +STEP
     *   GOTO test
     * exit:
     * </pre>
     *
     * <p>Returns {@code null} when any part of the pattern doesn't match - different opcode at
     * a slot, non-literal bound, IINC against a different slot, missing GOTO back to the test
     * label, etc. The walk is purely shape-matching: it doesn't validate that the body is
     * well-formed, only that the control-flow scaffold is present.
     *
     * <p>Only the standard test-at-top javac pattern is detected. Test-at-bottom layouts
     * ({@code init; GOTO test; body; INC; test: ILOAD; bound; IF_ICMPLT body}) and
     * non-monotonic loops ({@code i--}, {@code while}, {@code do-while}) return {@code null}.
     *
     * @param candidateInit the instruction to test as the loop's initial-value push
     * @return the loop description, or {@code null} when {@code candidateInit} doesn't open a
     *     for-loop with the expected shape
     */
    public static @Nullable IntForLoop detectIntForLoop(@NotNull AbstractInsnNode candidateInit) {
        if (isPseudoNode(candidateInit)) return null;
        Integer initValue = readIntLiteral(candidateInit);
        if (initValue == null) return null;

        AbstractInsnNode storeNode = nextReal(candidateInit);
        if (!(storeNode instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ISTORE) return null;
        int slot = store.var;

        // The test label is the first real instruction after the ISTORE.
        AbstractInsnNode testLoad = nextReal(storeNode);
        if (!(testLoad instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD || load.var != slot) return null;

        AbstractInsnNode boundNode = nextReal(testLoad);
        if (boundNode == null) return null;
        Integer boundValue = readIntLiteral(boundNode);
        if (boundValue == null) return null;

        AbstractInsnNode cmpNode = nextReal(boundNode);
        if (!(cmpNode instanceof JumpInsnNode cmp) || cmp.getOpcode() != Opcodes.IF_ICMPGE) return null;
        LabelNode exitLabel = cmp.label;

        AbstractInsnNode bodyFirst = nextReal(cmpNode);
        if (bodyFirst == null) return null;

        // Walk forward from the body to find the IINC + GOTO that closes the loop. Stop at the
        // exit label or end-of-stream; abort if a different IINC slot or a non-GOTO-back-to-test
        // pattern shows up before then.
        IincInsnNode iinc = null;
        AbstractInsnNode goBack = null;
        for (AbstractInsnNode cursor = bodyFirst; cursor != null; cursor = cursor.getNext()) {
            if (cursor == exitLabel) break;
            if (!(cursor instanceof IincInsnNode candidateIinc) || candidateIinc.var != slot) continue;
            AbstractInsnNode after = nextReal(candidateIinc);
            if (!(after instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.GOTO || jump.label != testLoad.getPrevious() && !isLabelOf(jump.label, testLoad)) continue;
            iinc = candidateIinc;
            goBack = after;
            break;
        }
        if (iinc == null) return null;

        AbstractInsnNode firstAfter = nextReal(exitLabel);
        if (firstAfter == null) return null;

        return new IntForLoop(slot, initValue, boundValue, iinc.incr, bodyFirst, firstAfter);
    }

    /**
     * Returns {@code true} when {@code label} is a label node whose immediately-following real
     * instruction is {@code target} - i.e. {@code label} marks the position of {@code target}.
     * Used by {@link #detectIntForLoop} to verify the closing {@code GOTO} jumps back to the
     * loop's test header.
     */
    private static boolean isLabelOf(@Nullable LabelNode label, @NotNull AbstractInsnNode target) {
        if (label == null) return false;
        for (AbstractInsnNode cursor = label; cursor != null; cursor = cursor.getNext()) {
            if (!isPseudoNode(cursor)) return cursor == target;
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
     * Returns the first bootstrap-method argument of {@code indy} that is a {@link Handle} whose
     * {@link Handle#getName()} equals {@code name}, scanning {@code bsmArgs} in order; {@code null}
     * when none is present. Unlike {@link #extractLambdaHandle} (which reads the positional
     * {@code bsmArgs[1]} lambda target), this matches by handle name, so it suits call sites
     * hunting a specific method reference regardless of its argument position - e.g. a
     * {@code SomeClass::factory} reference carried as a bootstrap argument.
     *
     * @param indy the invokedynamic to inspect
     * @param name the target handle name
     * @return the first matching handle, or {@code null} when none is present
     */
    public static @Nullable Handle findBsmHandleByName(@NotNull InvokeDynamicInsnNode indy, @NotNull String name) {
        if (indy.bsmArgs == null) return null;
        for (Object arg : indy.bsmArgs)
            if (arg instanceof Handle handle && name.equals(handle.getName()))
                return handle;
        return null;
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
            for (AbstractInsnNode node : lambda.instructions)
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
            for (AbstractInsnNode node : lambda.instructions) {
                visitor.accept(node);
                if (found == null && node instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW)
                    found = type.desc;
            }
            return found;
        }
        return null;
    }

    // ----------------------------------------------------------------------------------------
    // String concatenation (invokedynamic makeConcatWithConstants)
    // ----------------------------------------------------------------------------------------

    /**
     * Internal name of {@code java.lang.invoke.StringConcatFactory}, the bootstrap host of
     * the {@code makeConcatWithConstants} indy used by javac for {@code String + X} concat.
     */
    public static final @NotNull String STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";

    /**
     * Placeholder character javac embeds in the {@code makeConcatWithConstants} recipe at each
     * spot where a dynamic argument should be substituted. Defined by JEP 280 / JLS 15.18.1.
     */
    public static final char STRING_CONCAT_DYNAMIC_PLACEHOLDER = '\u0001';

    /**
     * Returns the recipe string of a {@code makeConcatWithConstants} invokedynamic, where the
     * placeholder character {@link #STRING_CONCAT_DYNAMIC_PLACEHOLDER} marks each dynamic-arg
     * substitution point. For {@code "tentacle" + i} javac emits an indy whose recipe is
     * {@code "tentacle"}; for {@code i + "_tentacle"} the recipe is
     * {@code "_tentacle"}. Returns {@code null} when the indy isn't a
     * {@code makeConcatWithConstants} call or {@code bsmArgs[0]} is missing / non-String.
     *
     * <p>Vanilla 26.1 procedural-loop factories use the indy directly inline
     * ({@code GhastModel.createBodyLayer} - {@code "tentacle" + i}) or wrapped in a helper that
     * encapsulates the concat ({@code SquidModel.createTentacleName(I)} -
     * {@code "tentacle" + i}; {@code BlazeModel.getPartName(I)} - {@code "part" + i}). The
     * helper case is resolved by walking the helper body for an inner invokedynamic that
     * matches this signature.
     *
     * @param indy the instruction to inspect
     * @return the recipe string, or {@code null} when the shape doesn't match
     */
    public static @Nullable String resolveStringConcatRecipe(@NotNull InvokeDynamicInsnNode indy) {
        if (!"makeConcatWithConstants".equals(indy.name)) return null;
        if (indy.bsm == null || !STRING_CONCAT_FACTORY.equals(indy.bsm.getOwner())) return null;
        if (indy.bsmArgs == null || indy.bsmArgs.length == 0) return null;
        return indy.bsmArgs[0] instanceof String recipe ? recipe : null;
    }

    /**
     * Substitutes {@link #STRING_CONCAT_DYNAMIC_PLACEHOLDER} occurrences in {@code recipe} with
     * the string form of {@code intValue}. Returns the substituted result. Constant-string
     * placeholders (the {@code \u0002} variant) are not currently substituted - they would
     * need {@code indy.bsmArgs[1..]} threading, which none of the vanilla 26.1 procedural-loop
     * factories use.
     *
     * @param recipe the recipe from {@link #resolveStringConcatRecipe}
     * @param intValue the value to substitute at each dynamic placeholder
     * @return the substituted result, or {@code recipe} when no placeholders are present
     */
    public static @NotNull String applyStringConcatRecipeWithInt(@NotNull String recipe, int intValue) {
        if (recipe.indexOf(STRING_CONCAT_DYNAMIC_PLACEHOLDER) < 0) return recipe;
        return recipe.replace(String.valueOf(STRING_CONCAT_DYNAMIC_PLACEHOLDER), Integer.toString(intValue));
    }

    /**
     * Walks {@code helper}'s instructions for the first {@code makeConcatWithConstants}
     * invokedynamic and returns its recipe (see {@link #resolveStringConcatRecipe}). Used by
     * the parser to follow {@code invokestatic <Owner>.<helper>(I)Ljava/lang/String;}
     * factories like {@code SquidModel.createTentacleName(I)} to their underlying recipe.
     *
     * @param helper the method whose body holds the indy
     * @return the recipe string, or {@code null} when no matching indy is present
     */
    public static @Nullable String findStringConcatRecipeIn(@NotNull MethodNode helper) {
        for (AbstractInsnNode node : helper.instructions) {
            if (node instanceof InvokeDynamicInsnNode indy) {
                String recipe = resolveStringConcatRecipe(indy);
                if (recipe != null) return recipe;
            }
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
     * <p>Mirrors {@code EntityRegistryDiscovery}'s {@code EntityType<LFoo;>} signature
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
     * Emits a canonical {@link Diagnostics.Severity#WARN} entry of the form
     * {@code "'%s' class missing from jar - %s"} for a walk that has lost a class and can
     * still finish without it.
     *
     * <p>The severity is the whole of the choice, so this is not the guard for a class a walk
     * cannot continue without. {@link ToolingSession#failOnStrictGate()} fails a run on an
     * {@link Diagnostics.Severity#ERROR} unconditionally but on a {@code WARN} only under
     * {@code -Dasset.tooling.strict=warn}, so a guard that bails records the class it could not
     * load at {@code ERROR} in a message of its own. Every missing-class guard in the tooling
     * bails, which is why nothing calls this.
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
     * entities) the oldest entry is evicted, matching the removeFirst eviction the legacy
     * parsers used (backed by {@link ArrayDeque}). Typed pop helpers
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
     * {@code GeometryParser} accounting).
     */
    public static final class LiteralStack {

        /**
         * Sentinel singleton pushed by {@link #pushNonLiteral()}. Extends {@link Number} with
         * zero values so an arithmetic site that pops the sentinel and calls
         * {@code .intValue()} / {@code .floatValue()} silently produces zero (matching the
         * upstream {@code GeometryParser} semantics: non-literal values that
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
        private final @NotNull Deque<Object> entries = new ArrayDeque<>();

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
            this.entries.addLast(value);
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
            this.entries.addLast(NON_LITERAL);
            if (this.entries.size() > this.capacity)
                this.entries.removeFirst();
        }

        /**
         * Silent counterpart of {@link #popIntOrZero(Diagnostics, String, String)}: pops the top
         * as an {@code int} via {@link #popInt()} and returns {@code 0} when the stack is empty or
         * the top is not an {@link Integer} (the wrong-type top is left in place, matching
         * {@code popInt}). No diagnostic is emitted - for callers that treat an absent or wrong-type
         * operand as a benign zero (the {@code BlockTintSources.constant} literal grab).
         *
         * @return the popped int, or {@code 0} on an empty stack or non-int top
         */
        public int popIntOrZero() {
            Integer value = popInt();
            return value != null ? value : 0;
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
         * Like {@link #popNumber} but returns {@code null} when the top entry is the
         * {@link #pushNonLiteral non-literal marker}, distinguishing "real compile-time literal
         * on top" from "computed-or-unknown placeholder on top". Used by parsers whose
         * branch-following logic must NOT take a branch when the comparison value isn't a real
         * literal (the marker's {@code .intValue() == 0} would otherwise spuriously satisfy
         * {@code IFLE} / {@code IFEQ} / {@code IF_ICMPLE} predicates).
         *
         * <p>Always consumes the top entry (matching JVM stack semantics - the operand IS
         * popped even when the parser can't evaluate the predicate). Returns the literal value
         * when it's a real number, or {@code null} when the stack is empty / the top is non-
         * numeric / the top is the non-literal marker. A {@code null} return means "the caller
         * should fall through linearly rather than follow the branch" while still keeping the
         * JVM stack aligned for the post-comparison code.
         *
         * @return the popped number when it's a real literal, or {@code null} when the stack
         *     is empty, the top is non-numeric, or the top is the non-literal marker
         */
        public @Nullable Number popLiteralNumber() {
            if (this.entries.isEmpty()) return null;
            Object top = this.entries.removeLast();
            if (top == NON_LITERAL) return null;
            if (!(top instanceof Number n)) return null;
            return n;
        }

        /**
         * Removes and returns the bottom-most (oldest) entry, or {@code null} when the stack
         * is empty. Mirrors the removeFirst semantics the legacy parsers used; needed
         * alongside {@link #pop} for callers that maintain a FIFO discard policy beyond the
         * built-in capacity-overflow eviction.
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
     * <p>It serves the walk whose operand has to survive an intervening call: a value is
     * produced, parked in a slot, and read back several instructions later once the argument it
     * belongs to is finally pushed. A walk that can read its operand from the instruction just
     * before it wants {@link AsmKit#previousReal previousReal}; one that needs the last few
     * literals in LIFO order wants {@link LiteralStack}.
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
