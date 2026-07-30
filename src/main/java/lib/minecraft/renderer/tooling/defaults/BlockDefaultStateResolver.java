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

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decodes a block's default blockstate: declared properties (from
 * {@link PropertyDefinitionResolver}) folded with explicit {@code registerDefaultState} overrides
 * (leaf-wins across the ctor chain) and, for unset declared properties, the property's
 * {@code any()}-default. The boolean {@code any()}-default rides {@link BlockStatePolicies}.
 *
 * <p>Session-scoped: the per-property default memo lives here. Result keys are sorted
 * ({@code TreeMap}) so the emitted default object is byte-stable.
 */
final class BlockDefaultStateResolver {

    private static final @NotNull String INTEGER_BOXED = "java/lang/Integer";
    private static final @NotNull String BOOLEAN_BOXED = "java/lang/Boolean";
    private static final @NotNull String VALUE_OF = "valueOf";

    /** {@code EnumProperty.create(name, class, Enum[])} descriptor tail (first value = index-0 element). */
    private static final @NotNull String ENUM_ARRAY_CREATE_TAIL =
        "[Ljava/lang/Enum;)" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.ENUM_PROPERTY);

    /** {@code EnumProperty.create(name, class, Predicate)} descriptor tail (Plane filter -> first direction in plane). */
    private static final @NotNull String ENUM_PREDICATE_CREATE_TAIL =
        "Ljava/util/function/Predicate;)" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.ENUM_PROPERTY);

    private final @NotNull ClassNodeCache cache;
    private final @NotNull PropertyDefinitionResolver properties;

    /** owner+'.'+field -> any()-default value; null reserved before recursion (cycle guard). */
    private final @NotNull Map<String, String> defaultValueCache = new HashMap<>();

    BlockDefaultStateResolver(@NotNull ClassNodeCache cache, @NotNull PropertyDefinitionResolver properties) {
        this.cache = cache;
        this.properties = properties;
    }

    /**
     * Resolves a block's default blockstate: property -> value, keys sorted.
     *
     * @param blockClass the block class internal name
     * @return the sorted default map ({@code {}} when the block declares no resolvable properties)
     */
    @NotNull Map<String, String> resolve(@NotNull String blockClass) {
        Map<String, PropertyDefinitionResolver.FieldRef> declared = this.properties.collectDeclaredProperties(blockClass);

        // Explicit setValue overrides across the whole ctor chain; the leaf class is visited first,
        // so putIfAbsent gives it precedence over parent defaults.
        Map<String, String> explicit = new HashMap<>();
        AsmKit.walkConstructorChain(this.cache, blockClass,
            ctor -> extractSetValues(ctor).forEach(explicit::putIfAbsent));

        Map<String, String> resolved = new TreeMap<>();
        for (Map.Entry<String, PropertyDefinitionResolver.FieldRef> entry : declared.entrySet()) {
            String value = explicit.get(entry.getKey());
            if (value == null) value = anyDefault(entry.getValue());
            if (value != null) resolved.put(entry.getKey(), value);
        }
        // Defensive: surface any explicitly-set property the state-definition walk missed.
        explicit.forEach(resolved::putIfAbsent);
        return resolved;
    }

    /**
     * Decodes the {@code registerDefaultState(any().setValue(P, v)...)} chain in one ctor into
     * explicit property -> value overrides. Int / boolean values arrive boxed ({@code Integer.valueOf}
     * / {@code Boolean.valueOf}); enum values arrive as a bare {@code GETSTATIC} constant.
     */
    private @NotNull Map<String, String> extractSetValues(@NotNull MethodNode ctor) {
        Map<String, String> pairs = new HashMap<>();
        String pendingProp = null;
        String pendingValue = null;
        Integer lastInt = null;
        for (AbstractInsnNode node : ctor.instructions) {
            Integer intLiteral = AsmKit.readIntLiteral(node);
            if (intLiteral != null) {
                lastInt = intLiteral;
                continue;
            }
            if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode field) {
                if (isPropertyFieldRef(field.desc)) pendingProp = this.properties.resolvePropertyName(field.owner, field.name);
                else pendingValue = this.properties.enumSerializedName(field.owner, field.name);
                continue;
            }
            if (node instanceof MethodInsnNode call) {
                if (call.getOpcode() == Opcodes.INVOKESTATIC && VALUE_OF.equals(call.name)) {
                    if (INTEGER_BOXED.equals(call.owner) && lastInt != null) pendingValue = Integer.toString(lastInt);
                    else if (BOOLEAN_BOXED.equals(call.owner) && lastInt != null) pendingValue = Boolean.toString(lastInt != 0);
                    continue;
                }
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && VanillaSourceClasses.Methods.SET_VALUE.equals(call.name)) {
                    if (pendingProp != null && pendingValue != null) pairs.put(pendingProp, pendingValue);
                    pendingProp = null;
                    pendingValue = null;
                    lastInt = null;
                    continue;
                }
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && VanillaSourceClasses.Methods.REGISTER_DEFAULT_STATE.equals(call.name))
                    break;
            }
        }
        return pairs;
    }

    /** The declared-but-unset {@code any()}-default of a property, memoised with a cycle guard. */
    private @Nullable String anyDefault(@NotNull PropertyDefinitionResolver.FieldRef ref) {
        String key = ref.owner() + '.' + ref.field();
        if (this.defaultValueCache.containsKey(key)) return this.defaultValueCache.get(key);
        this.defaultValueCache.put(key, null);
        MethodInsnNode create = this.properties.resolvePropertyCreateSite(ref.owner(), ref.field(), 0);
        String value = create == null ? null : defaultFromCreate(create);
        this.defaultValueCache.put(key, value);
        return value;
    }

    /**
     * The {@code any()}-default from a property's {@code XProperty.create(...)} site, per KIND:
     * IntegerProperty -> min; BooleanProperty -> {@code false}; EnumProperty -> the first
     * allowed constant (array index-0 / Plane first-direction / first declared).
     */
    private @Nullable String defaultFromCreate(@NotNull MethodInsnNode create) {
        if (VanillaSourceClasses.Types.INTEGER_PROPERTY.equals(create.owner)) {
            // create(name, min, max): walking back, the int nearest the name string is the min.
            Integer min = null;
            for (AbstractInsnNode node = create.getPrevious(); node != null; node = node.getPrevious()) {
                if (AsmKit.isPseudoNode(node)) continue;
                if (AsmKit.readStringLiteral(node) != null) break;
                Integer literal = AsmKit.readIntLiteral(node);
                if (literal != null) min = literal;
            }
            return min == null ? null : Integer.toString(min);
        }
        if (VanillaSourceClasses.Types.BOOLEAN_PROPERTY.equals(create.owner))
            return BlockStatePolicies.booleanDefault();

        // EnumProperty - the class arg is the nearest preceding class literal.
        AbstractInsnNode classNode = AsmKit.findPreceding(create, n -> AsmKit.readTypeLiteral(n) != null, op -> true);
        Type classLiteral = classNode == null ? null : AsmKit.readTypeLiteral(classNode);
        if (classLiteral == null) return null;
        String enumOwner = classLiteral.getInternalName();

        if (create.desc.endsWith(ENUM_ARRAY_CREATE_TAIL)) {
            // create(name, class, Enum[]): first value = the array's index-0 GETSTATIC.
            AbstractInsnNode anewarray = AsmKit.findPreceding(create, n -> n.getOpcode() == Opcodes.ANEWARRAY, op -> true);
            if (anewarray != null)
                for (AbstractInsnNode node = anewarray; node != null && node != create; node = node.getNext())
                    if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode value)
                        return this.properties.enumSerializedName(value.owner, value.name);
            return this.properties.firstEnumConstant(enumOwner);
        }
        if (create.desc.endsWith(ENUM_PREDICATE_CREATE_TAIL)) {
            // create(name, class, predicate): a Direction.Plane filter resolves to the first direction
            // in that plane (derived from the Plane construction); any other predicate falls back.
            AbstractInsnNode predicate = AsmKit.previousReal(create);
            if (predicate != null && predicate.getOpcode() == Opcodes.GETSTATIC && predicate instanceof FieldInsnNode plane) {
                String planeFirst = resolvePlaneFirstDirection(plane, enumOwner);
                if (planeFirst != null) return planeFirst;
            }
            return this.properties.firstEnumConstant(enumOwner);
        }
        // create(name, class): all constants, first by declaration order.
        return this.properties.firstEnumConstant(enumOwner);
    }

    /**
     * The serialised name of the first {@code enumOwner} constant referenced in the construction of
     * the {@code planeField} constant - the first direction the {@code Direction.Plane} filter admits
     * (HORIZONTAL -> north, VERTICAL -> down), derived rather than declared. Returns {@code null} when
     * {@code planeField} is not a plane-style filter constant.
     */
    private @Nullable String resolvePlaneFirstDirection(@NotNull FieldInsnNode planeField, @NotNull String enumOwner) {
        ClassNode planeClass = this.cache.load(planeField.owner);
        MethodNode clinit = planeClass == null ? null : AsmKit.findMethod(planeClass, AsmKit.CLINIT);
        if (clinit == null) return null;
        String firstDirection = null;
        for (AbstractInsnNode node : clinit.instructions) {
            if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode dir
                && dir.owner.equals(enumOwner) && firstDirection == null)
                firstDirection = dir.name;
            if (AsmKit.isPutStatic(node, planeField.owner) && node instanceof FieldInsnNode put) {
                if (put.name.equals(planeField.name) && firstDirection != null)
                    return this.properties.enumSerializedName(enumOwner, firstDirection);
                firstDirection = null;
            }
        }
        return null;
    }

    /** Reports whether a field descriptor references a block-state property (scalar or array). */
    private static boolean isPropertyFieldRef(@NotNull String desc) {
        String internal = AsmKit.internalNameOfRef(desc);
        return internal != null && internal.startsWith(VanillaSourceClasses.Types.STATE_PROPERTIES_PACKAGE) && internal.endsWith("Property");
    }

}
