package lib.minecraft.renderer.tooling.defaults;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves which blockstate properties a block declares (the five {@code createBlockStateDefinition}
 * shapes) and each property's serialised name. Session-scoped: the property-name and
 * enum-name memos live here, keyed per class read (the {@link ClassNodeCache} already collapses the
 * class-read half).
 *
 * <p>The three class-graph BFS walkers ({@code findPutStaticNode} / {@code resolveMethodReturnedProperty} /
 * {@code findAccessorMapField}) collapse to ONE probe-parameterised {@link #bfsClassGraph} (a private
 * helper, promoted to AsmKit only if a second flow needs it). The {@code "Property;"} suffix match
 * is tightened to the properties-package prefix to avoid over-broad matches.
 */
final class PropertyDefinitionResolver {

    /** A static property field: owner internal name + field name. */
    record FieldRef(@NotNull String owner, @NotNull String field) {}

    private static final @NotNull String LIST_REF = "Ljava/util/List;";
    private static final @NotNull String LIST_INTERNAL = "java/util/List";
    private static final @NotNull String LIST_RETURN_SUFFIX = ")Ljava/util/List;";
    private static final @NotNull String MAP_REF = "Ljava/util/Map;";

    private final @NotNull ClassNodeCache cache;

    /** owner+'.'+field -> serialised property name; null reserved before recursion (cycle guard). */
    private final @NotNull Map<String, String> propNameCache = new HashMap<>();

    /** enum owner -> (constant field name -> serialised name), clinit-ordered. */
    private final @NotNull Map<String, Map<String, String>> enumNameCache = new HashMap<>();

    PropertyDefinitionResolver(@NotNull ClassNodeCache cache) {
        this.cache = cache;
    }

    // ------------------------------------------------------------------------------------
    // the five declaration shapes
    // ------------------------------------------------------------------------------------

    /**
     * Collects every property a block declares in {@code createBlockStateDefinition} up the super
     * chain, keyed serialised-name to defining {@link FieldRef} in declaration order (first writer
     * wins - the leaf declaration's field is retained for the default lookup).
     *
     * @param blockClass the block class internal name
     * @return the declared properties, serialised-name to field ref (declaration order)
     */
    @NotNull Map<String, FieldRef> collectDeclaredProperties(@NotNull String blockClass) {
        Map<String, FieldRef> declared = new LinkedHashMap<>();
        AsmKit.walkSuperChain(this.cache, blockClass, cn -> {
            MethodNode method = AsmKit.findMethod(cn, VanillaSourceClasses.Methods.CREATE_BLOCK_STATE_DEFINITION);
            if (method == null) return;
            for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext())
                scanDeclarationNode(blockClass, node, declared);
        });
        return declared;
    }

    /** Matches one instruction against the five declaration shapes, appending any property it declares. */
    private void scanDeclarationNode(@NotNull String blockClass, @NotNull AbstractInsnNode node, @NotNull Map<String, FieldRef> declared) {
        int opcode = node.getOpcode();
        if (opcode == Opcodes.GETSTATIC && node instanceof FieldInsnNode field) {
            if (isPropertyFieldRef(field.desc)) {
                if (field.desc.charAt(0) == '[') {                                        // (b) Property[] element
                    AbstractInsnNode index = AsmKit.nextReal(node);
                    AbstractInsnNode aaload = AsmKit.nextReal(index);
                    Integer idx = index == null ? null : AsmKit.readIntLiteral(index);
                    if (idx != null && aaload != null && aaload.getOpcode() == Opcodes.AALOAD)
                        addDeclared(declared, readStaticPropertyArrayElement(field.owner, field.name, idx));
                } else {                                                                  // (a) direct GETSTATIC
                    addDeclared(declared, new FieldRef(field.owner, field.name));
                }
            } else if (LIST_REF.equals(field.desc) && isFollowedByListForEach(node)) {     // (e) List.forEach
                for (FieldRef ref : resolvePropertyListElements(field.owner, field.name)) addDeclared(declared, ref);
            }
            return;
        }
        if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESTATIC) && node instanceof MethodInsnNode call) {
            if (call.desc.startsWith("()") && isPropertyReturnRef(call.desc))              // (c) no-arg accessor
                addDeclared(declared, resolveMethodReturnedProperty(blockClass, call.name, call.desc));
            else if (!call.desc.startsWith("()") && isPropertyReturnRef(call.desc))         // (d) Map<Direction,Property> accessor
                for (FieldRef ref : resolveDirectionPropertyMapValues(call.owner, call.name, call.desc)) addDeclared(declared, ref);
        }
    }

    /** Resolves a ref to its serialised name and records it (first writer wins; null refs / names dropped). */
    private void addDeclared(@NotNull Map<String, FieldRef> declared, @Nullable FieldRef ref) {
        if (ref == null) return;
        String name = resolvePropertyName(ref.owner(), ref.field());
        if (name != null) declared.putIfAbsent(name, ref);
    }

    /** Reports whether {@code node.getNext()..} contains an {@code INVOKEINTERFACE List.forEach}. */
    private boolean isFollowedByListForEach(@NotNull AbstractInsnNode start) {
        for (AbstractInsnNode node = start.getNext(); node != null; node = node.getNext())
            if (node.getOpcode() == Opcodes.INVOKEINTERFACE && node instanceof MethodInsnNode call
                && LIST_INTERNAL.equals(call.owner) && VanillaSourceClasses.Methods.FOR_EACH.equals(call.name))
                return true;
        return false;
    }

    /**
     * Resolves the {@code index}-th element of a static {@code Property[]} field by walking its
     * {@code <clinit>} array build ({@code ANEWARRAY .. DUP; <i>; GETSTATIC prop; AASTORE}).
     */
    private @Nullable FieldRef readStaticPropertyArrayElement(@NotNull String owner, @NotNull String arrayField, int index) {
        FieldInsnNode put = findPutStaticNode(owner, arrayField);
        if (put == null) return null;
        AbstractInsnNode anewarray = AsmKit.findPreceding(put, n -> n.getOpcode() == Opcodes.ANEWARRAY, op -> true);
        if (anewarray == null) return null;
        Integer lastInt = null;
        for (AbstractInsnNode node = anewarray; node != null && node != put; node = node.getNext()) {
            Integer literal = AsmKit.readIntLiteral(node);
            if (literal != null) lastInt = literal;
            if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode prop
                && isPropertyFieldRef(prop.desc) && prop.desc.charAt(0) != '['
                && lastInt != null && lastInt == index)
                return new FieldRef(prop.owner, prop.name);
        }
        return null;
    }

    /** BFS the class graph for a no-arg accessor's first {@code GETSTATIC Property} (shape c). */
    private @Nullable FieldRef resolveMethodReturnedProperty(@NotNull String startClass, @NotNull String method, @NotNull String desc) {
        return bfsClassGraph(startClass, cn -> {
            MethodNode body = AsmKit.findMethod(cn, method, desc);
            if (body == null || body.instructions.size() == 0) return null;
            for (AbstractInsnNode node = body.instructions.getFirst(); node != null; node = node.getNext())
                if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode prop
                    && isPropertyFieldRef(prop.desc) && prop.desc.charAt(0) != '[')
                    return new FieldRef(prop.owner, prop.name);
            return null;
        });
    }

    /**
     * Resolves every value of a {@code Map<Direction,Property>} single-arg accessor (shape d):
     * find the map field, follow {@code MAP = OtherClass.MAP} redirects, then collect every
     * property {@code GETSTATIC} in the declaring class's {@code <clinit>} deduped by serialised name.
     */
    private @NotNull List<FieldRef> resolveDirectionPropertyMapValues(@NotNull String startClass, @NotNull String method, @NotNull String desc) {
        FieldInsnNode mapField = findAccessorMapField(startClass, method, desc);
        for (int depth = 0; depth < 8 && mapField != null; depth++) {
            AbstractInsnNode init = findFieldInitValue(mapField.owner, mapField.name);
            // Follow MAP = OtherClass.MAP redirects to the class that actually builds the map
            // (MultifaceBlock.PROPERTY_BY_DIRECTION = PipeBlock.PROPERTY_BY_DIRECTION).
            if (init != null && init.getOpcode() == Opcodes.GETSTATIC && init instanceof FieldInsnNode redirect && MAP_REF.equals(redirect.desc)) {
                mapField = redirect;
                continue;
            }
            break;
        }
        List<FieldRef> out = new ArrayList<>();
        if (mapField == null) return out;
        ClassNode owner = this.cache.load(mapField.owner);
        MethodNode clinit = owner == null ? null : AsmKit.findMethod(owner, AsmKit.CLINIT);
        if (clinit == null) return out;
        Set<String> byName = new HashSet<>();
        for (AbstractInsnNode node = clinit.instructions.getFirst(); node != null; node = node.getNext())
            if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode prop
                && isPropertyFieldRef(prop.desc) && prop.desc.charAt(0) != '[') {
                String name = resolvePropertyName(prop.owner, prop.name);
                if (name != null && byName.add(name)) out.add(new FieldRef(prop.owner, prop.name));
            }
        return out;
    }

    /** BFS the class graph for a single-arg accessor's first {@code GETSTATIC Map} field (shape d). */
    private @Nullable FieldInsnNode findAccessorMapField(@NotNull String startClass, @NotNull String method, @NotNull String desc) {
        return bfsClassGraph(startClass, cn -> {
            MethodNode body = AsmKit.findMethod(cn, method, desc);
            if (body == null || body.instructions.size() == 0) return null;
            for (AbstractInsnNode node = body.instructions.getFirst(); node != null; node = node.getNext())
                if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode map && MAP_REF.equals(map.desc))
                    return map;
            return null;
        });
    }

    /**
     * Resolves the element properties of a static {@code List<Property>} field (shape e): find its
     * {@code List.of(...)} build, then restore declaration order by pushing each property
     * {@code GETSTATIC} while walking BACK to the previous statement and reading the deque head-to-tail.
     */
    private @NotNull List<FieldRef> resolvePropertyListElements(@NotNull String owner, @NotNull String field) {
        List<FieldRef> out = new ArrayList<>();
        FieldInsnNode put = findPutStaticNode(owner, field);
        AbstractInsnNode of = put == null ? null : AsmKit.previousReal(put);
        if (!(of instanceof MethodInsnNode build) || of.getOpcode() != Opcodes.INVOKESTATIC || !build.desc.endsWith(LIST_RETURN_SUFFIX))
            return out;
        Deque<FieldRef> ordered = new ArrayDeque<>();
        for (AbstractInsnNode node = AsmKit.previousReal(of); node != null; node = AsmKit.previousReal(node)) {
            if (node.getOpcode() == Opcodes.PUTSTATIC) break;
            if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode prop
                && isPropertyFieldRef(prop.desc) && prop.desc.charAt(0) != '[')
                ordered.push(new FieldRef(prop.owner, prop.name));
        }
        out.addAll(ordered);
        return out;
    }

    // ------------------------------------------------------------------------------------
    // name resolution (shared with BlockDefaultStateResolver)
    // ------------------------------------------------------------------------------------

    /**
     * The serialised name of a property field ({@code FACING -> "facing"}), memoised with a
     * reserve-null-before-recurse cycle guard.
     *
     * @param owner the field owner internal name
     * @param field the field name
     * @return the serialised name, or {@code null} when neither field-init shape resolves
     */
    @Nullable String resolvePropertyName(@NotNull String owner, @NotNull String field) {
        String key = owner + '.' + field;
        if (this.propNameCache.containsKey(key)) return this.propNameCache.get(key);
        this.propNameCache.put(key, null);
        String resolved = resolvePropertyNameUncached(owner, field);
        this.propNameCache.put(key, resolved);
        return resolved;
    }

    private @Nullable String resolvePropertyNameUncached(@NotNull String owner, @NotNull String field) {
        AbstractInsnNode value = findFieldInitValue(owner, field);
        if (value == null) return null;
        if (value.getOpcode() == Opcodes.GETSTATIC && value instanceof FieldInsnNode src && isPropertyFieldRef(src.desc))
            return resolvePropertyName(src.owner, src.name);                              // redirect
        if (value instanceof MethodInsnNode call && VanillaSourceClasses.Methods.PROPERTY_CREATE.equals(call.name)) {
            AbstractInsnNode name = AsmKit.findPreceding(call, n -> AsmKit.readStringLiteral(n) != null, op -> true);
            return name == null ? null : AsmKit.readStringLiteral(name);
        }
        return null;
    }

    /** The value-producing instruction assigned to a static field (before its {@code PUTSTATIC}), or {@code null}. */
    @Nullable AbstractInsnNode findFieldInitValue(@NotNull String owner, @NotNull String field) {
        FieldInsnNode put = findPutStaticNode(owner, field);
        return put == null ? null : AsmKit.previousReal(put);
    }

    /** BFS the owner + super + interface graph for the {@code PUTSTATIC owner.field} in a {@code <clinit>}. */
    private @Nullable FieldInsnNode findPutStaticNode(@NotNull String owner, @NotNull String field) {
        return bfsClassGraph(owner, cn -> {
            MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
            if (clinit == null) return null;
            for (AbstractInsnNode node = clinit.instructions.getFirst(); node != null; node = node.getNext())
                if (AsmKit.isPutStatic(node, cn.name, field)) return (FieldInsnNode) node;
            return null;
        });
    }

    /**
     * BFS over a class's super + interface graph (most-derived first), returning the first non-null
     * probe result.
     */
    private <T> @Nullable T bfsClassGraph(@NotNull String start, @NotNull Function<ClassNode, T> probe) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (AsmKit.OBJECT_INTERNAL.equals(current) || !visited.add(current)) continue;
            ClassNode cn = this.cache.load(current);
            if (cn == null) continue;
            T result = probe.apply(cn);
            if (result != null) return result;
            if (cn.superName != null) queue.add(cn.superName);
            if (cn.interfaces != null) queue.addAll(cn.interfaces);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // enum serialised names (shared with BlockDefaultStateResolver)
    // ------------------------------------------------------------------------------------

    /**
     * The serialised name of an enum constant ({@code Direction.NORTH -> "north"}) via the
     * 2nd-constructor-string heuristic, lowercased-field fallback.
     *
     * @param enumOwner the enum class internal name
     * @param constant the enum constant field name
     * @return the serialised name
     */
    @NotNull String enumSerializedName(@NotNull String enumOwner, @NotNull String constant) {
        Map<String, String> names = this.enumNameCache.computeIfAbsent(enumOwner, this::buildEnumNameMap);
        String serialized = names.get(constant);
        return serialized != null ? serialized : constant.toLowerCase(Locale.ROOT);
    }

    /** The first (ordinal-0) enum constant's serialised name, or {@code null} for an empty / missing enum. */
    @Nullable String firstEnumConstant(@NotNull String enumOwner) {
        Map<String, String> names = this.enumNameCache.computeIfAbsent(enumOwner, this::buildEnumNameMap);
        return names.isEmpty() ? null : names.values().iterator().next();
    }

    /**
     * Walks an enum {@code <clinit>} pairing each {@code NEW; DUP; LDC "NAME"; <ordinal>; (LDC
     * "serialized")?; INVOKESPECIAL <init>; PUTSTATIC NAME}, mapping the constant field to its
     * serialised name (2nd string literal, else lowercased field / first string). Clinit order.
     */
    private @NotNull Map<String, String> buildEnumNameMap(@NotNull String enumOwner) {
        Map<String, String> map = new LinkedHashMap<>();
        ClassNode cn = this.cache.load(enumOwner);
        MethodNode clinit = cn == null ? null : AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return map;
        boolean collecting = false;
        List<String> strings = new ArrayList<>();
        List<String> pending = null;
        for (AbstractInsnNode node = clinit.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node.getOpcode() == Opcodes.NEW && node instanceof org.objectweb.asm.tree.TypeInsnNode type && type.desc.equals(enumOwner)) {
                collecting = true;
                strings = new ArrayList<>();
                continue;
            }
            if (collecting) {
                String literal = AsmKit.readStringLiteral(node);
                if (literal != null) {
                    strings.add(literal);
                    continue;
                }
                if (AsmKit.isInvokeSpecial(node, enumOwner, AsmKit.INIT)) {
                    collecting = false;
                    pending = strings;
                    continue;
                }
            }
            if (AsmKit.isPutStatic(node, enumOwner) && pending != null) {
                String fieldName = ((FieldInsnNode) node).name;
                String serialized = pending.size() >= 2 ? pending.get(1)
                    : pending.isEmpty() ? fieldName.toLowerCase(Locale.ROOT)
                    : pending.getFirst().toLowerCase(Locale.ROOT);
                map.put(fieldName, serialized);
                pending = null;
            }
        }
        return map;
    }

    // ------------------------------------------------------------------------------------
    // create-site (consumed by BlockDefaultStateResolver.defaultFromCreate)
    // ------------------------------------------------------------------------------------

    /**
     * Resolves the {@code XProperty.create(...)} site a property field initialises to, following
     * block-local {@code FOO = BlockStateProperties.BAR} redirects (depth-bounded 8).
     *
     * @param owner the property field owner internal name
     * @param field the property field name
     * @param depth the redirect recursion depth
     * @return the {@code create} invocation, or {@code null}
     */
    @Nullable MethodInsnNode resolvePropertyCreateSite(@NotNull String owner, @NotNull String field, int depth) {
        if (depth > 8) return null;
        AbstractInsnNode value = findFieldInitValue(owner, field);
        if (value == null) return null;
        if (value.getOpcode() == Opcodes.GETSTATIC && value instanceof FieldInsnNode src && isPropertyFieldRef(src.desc))
            return resolvePropertyCreateSite(src.owner, src.name, depth + 1);
        if (value instanceof MethodInsnNode call && VanillaSourceClasses.Methods.PROPERTY_CREATE.equals(call.name))
            return call;
        return null;
    }

    // ------------------------------------------------------------------------------------
    // descriptor gates - the tightened "is this a Property" test
    // ------------------------------------------------------------------------------------

    /** Reports whether a field descriptor references a block-state property (scalar or array). */
    private static boolean isPropertyFieldRef(@NotNull String desc) {
        String internal = AsmKit.internalNameOfRef(desc);
        return internal != null && internal.startsWith(VanillaSourceClasses.Types.STATE_PROPERTIES_PACKAGE) && internal.endsWith("Property");
    }

    /** Reports whether a method descriptor returns a (non-array) block-state property. */
    private static boolean isPropertyReturnRef(@NotNull String methodDesc) {
        Type returned = AsmKit.returnType(methodDesc);
        return returned.getSort() == Type.OBJECT
            && returned.getInternalName().startsWith(VanillaSourceClasses.Types.STATE_PROPERTIES_PACKAGE)
            && returned.getInternalName().endsWith("Property");
    }

}
