package lib.minecraft.renderer.tooling.kernel;

import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class-axis ASM primitives for the bytecode-walking tooling flows - class and member
 * lookup, superclass-chain walks and descriptor arithmetic, cache-only with zero vanilla
 * knowledge. Every class-fetch entry point takes the session {@link ClassNodeCache}; no jar
 * handle appears anywhere on this surface, and nothing here iterates instructions - an
 * instruction walk is an {@link AsmWalker} chain.
 *
 * <p>The kit owns these families of primitives:
 * <ul>
 *   <li><b>Class / member loading</b> - jar entry to {@link ClassNode}, name and descriptor
 *       method / field lookups (including superclass-chain variants), throwing {@code require*}
 *       variants for callers that want a tooling-canonical "obfuscated or unsupported version"
 *       error instead of a null return, plus {@link #findClinit findClinit} for the silent arm
 *       of the load-then-look-up pair - the reporting arm is the fused walker opener's
 *       {@code missing()} switch, in caller hands.</li>
 *   <li><b>Class-hierarchy walks</b> - {@link #walkConstructorChain walkConstructorChain},
 *       {@link #walkSuperChain walkSuperChain}, {@link #walkSuperChainUntil walkSuperChainUntil},
 *       and {@link #extendsClass extendsClass}, each stopping before
 *       {@link #OBJECT_INTERNAL java/lang/Object}, all cache-fed.</li>
 *   <li><b>Descriptor utilities</b> - {@link #descriptorReturns descriptorReturns},
 *       {@link #argSlotCount argSlotCount}, {@link #argTypes argTypes},
 *       {@link #returnType returnType}, and {@link #internalNameOfRef internalNameOfRef}
 *       for reading method / field descriptors without allocating intermediate strings.</li>
 *   <li><b>String-concatenation helpers</b> - {@link #applyStringConcatRecipeWithInt
 *       applyStringConcatRecipeWithInt} substitutes a decoded {@code makeConcatWithConstants}
 *       indy recipe for procedural-loop part naming.</li>
 *   <li><b>Generic-signature parsing</b> - {@link #extractGenericTypeParameter
 *       extractGenericTypeParameter} pulls the single concrete type parameter out of an
 *       {@code Outer<LInner;>} field signature.</li>
 *   <li><b>Diagnostic formatters</b> - {@code diagMissingClass} / {@code diagMissingMethod} /
 *       {@code diagMissingField} / {@code diagUnexpectedPattern} offer canonical {@code WARN}
 *       phrasings for a {@link Diagnostics} sink. Nothing calls them: a tooling walk that
 *       loses a class or a member reports it at {@code ERROR} and bails, so these four are an
 *       available phrasing rather than the one in force. <b>Do not adopt them to collapse those
 *       hand-rolled arms</b>: the strict gate fails on an {@code ERROR} always and on a
 *       {@code WARN} only under {@code -Dasset.tooling.strict=warn}, so swapping the severity
 *       moves every one of those failures out of the default gate. The walk openers'
 *       {@code missing()} ERROR arms are the reporting shape that keeps it.</li>
 * </ul>
 *
 * <p>That inventory is capability, not a call graph. Nine of the names it lists have no
 * production caller and are kept deliberately, a bytecode primitive being cheaper to carry
 * than to re-derive on a Minecraft version bump: both {@code requireMethod} forms,
 * {@link #requireClinit}, {@link #requireField}, {@link #findFieldInHierarchy},
 * {@link #argSlotCount}, and all four {@code diag*} formatters. Of those, only
 * {@code findFieldInHierarchy} is reached at all, by this kit's own unit test.
 *
 * <p>None of the helpers here know about the vanilla semantic patterns the callers are
 * hunting for (tint sources, effect colours, cube literals, layer dispatch, lambda targets).
 * Those stay in the individual resolvers, and the instruction-level shapes they match stay
 * in {@link AsmWalker} chains - this class stops at classes, members and descriptors.
 */
@UtilityClass
public final class ClassKit {

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
     * Instance-constructor method name (JVM {@code <init>}). Centralized for the same reason
     * as {@link #CLINIT}.
     */
    public static final @NotNull String INIT = "<init>";

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
     * Returns the simple name of a JVM internal name - {@code a/b/Outer$Inner} yields
     * {@code Outer$Inner}, so a nested class keeps its {@code $} form.
     *
     * @param internalName the JVM internal name
     * @return the text after the last {@code /}
     */
    public static @NotNull String simpleName(@NotNull String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    /**
     * Returns a class's {@code <clinit>}, or {@code null} when the jar holds no such class or the
     * class runs no static initialiser. The two misses answer alike, which is what every walk
     * reading a static table off a class it may not find wants; a caller that has to tell them
     * apart, or that reads the {@link ClassNode} itself, loads it and calls {@link #findMethod}.
     *
     * @param cache the per-session cache to consult / populate
     * @param internalName the class's JVM internal name
     * @return the static initialiser, or {@code null} when the class or the initialiser is absent
     */
    public static @Nullable MethodNode findClinit(@NotNull ClassNodeCache cache, @NotNull String internalName) {
        ClassNode classNode = cache.load(internalName);
        return classNode == null ? null : findMethod(classNode, CLINIT);
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
    // String concatenation (invokedynamic makeConcatWithConstants)
    // ----------------------------------------------------------------------------------------

    /**
     * Substitutes {@link AsmWalker#STRING_CONCAT_DYNAMIC_PLACEHOLDER} occurrences in {@code recipe} with
     * the string form of {@code intValue}. Returns the substituted result. Constant-string
     * placeholders (the {@code \u0002} variant) are not currently substituted - they would
     * need {@code indy.bsmArgs[1..]} threading, which none of the vanilla 26.1 procedural-loop
     * factories use.
     *
     * @param recipe the recipe from {@link AsmWalker#resolveStringConcatRecipe}
     * @param intValue the value to substitute at each dynamic placeholder
     * @return the substituted result, or {@code recipe} when no placeholders are present
     */
    public static @NotNull String applyStringConcatRecipeWithInt(@NotNull String recipe, int intValue) {
        if (recipe.indexOf(AsmWalker.STRING_CONCAT_DYNAMIC_PLACEHOLDER) < 0) return recipe;
        return recipe.replace(String.valueOf(AsmWalker.STRING_CONCAT_DYNAMIC_PLACEHOLDER), Integer.toString(intValue));
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

}
