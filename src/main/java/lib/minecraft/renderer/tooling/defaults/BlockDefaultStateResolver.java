package lib.minecraft.renderer.tooling.defaults;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
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

    /**
     * {@code EnumProperty.create(name, class, Enum[])} descriptor tail (first value = index-0 element).
     */
    private static final @NotNull String ENUM_ARRAY_CREATE_TAIL =
        "[Ljava/lang/Enum;)" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.ENUM_PROPERTY);

    /**
     * {@code EnumProperty.create(name, class, Predicate)} descriptor tail (Plane filter -> first direction in plane).
     */
    private static final @NotNull String ENUM_PREDICATE_CREATE_TAIL =
        "Ljava/util/function/Predicate;)" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.ENUM_PROPERTY);

    private final @NotNull ClassNodeCache cache;
    private final @NotNull PropertyDefinitionResolver properties;

    /**
     * owner+'.'+field -> any()-default value; null reserved before recursion (cycle guard).
     */
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
        Cells.Window<Integer> lastInt = Cells.window(AsmWalker::intLiteral, 1);
        Cells.Latch<String> pendingProp = Cells.latch();
        Cells.Latch<String> pendingValue = Cells.latch();
        AsmWalker.over(ctor)
            .until(Insn.of(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && VanillaSourceClasses.Methods.REGISTER_DEFAULT_STATE.equals(call.name)))
            .feed(lastInt)
            .feed(pendingProp)
            .feed(pendingValue)
            .on(Insn.of(FieldInsnNode.class, field -> field.getOpcode() == Opcodes.GETSTATIC), field -> {
                if (PropertyDefinitionResolver.isPropertyFieldRef(field.desc)) {
                    String name = this.properties.resolvePropertyName(field.owner, field.name);
                    if (name != null) pendingProp.set(name);
                    else pendingProp.clear();
                } else pendingValue.set(this.properties.enumSerializedName(field.owner, field.name));
            })
            .on(Insn.of(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKESTATIC
                && VALUE_OF.equals(call.name)), call -> {
                if (lastInt.size() == 0) return;
                int held = lastInt.values().getLast();
                if (INTEGER_BOXED.equals(call.owner)) pendingValue.set(Integer.toString(held));
                else if (BOOLEAN_BOXED.equals(call.owner)) pendingValue.set(Boolean.toString(held != 0));
            })
            .commitAt(Insn.of(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && VanillaSourceClasses.Methods.SET_VALUE.equals(call.name)), setValue -> {
                String prop = pendingProp.get();
                String value = pendingValue.get();
                if (prop != null && value != null) pairs.put(prop, value);
            })
            .run();
        return pairs;
    }

    /**
     * The declared-but-unset {@code any()}-default of a property, memoised with a cycle guard.
     */
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
            Integer min = AsmWalker.before(create).real()
                .until(Insn.of(AbstractInsnNode.class, node -> AsmWalker.stringLiteral(node) != null))
                .mapNotNull(AsmWalker::intLiteral)
                .last();
            return min == null ? null : Integer.toString(min);
        }
        if (VanillaSourceClasses.Types.BOOLEAN_PROPERTY.equals(create.owner))
            return BlockStatePolicies.booleanDefault();

        // EnumProperty - the class arg is the nearest preceding class literal.
        AbstractInsnNode classNode = AsmKit.findPreceding(create, n -> AsmWalker.typeLiteral(n) != null, op -> true);
        Type classLiteral = classNode == null ? null : AsmWalker.typeLiteral(classNode);
        if (classLiteral == null) return null;
        String enumOwner = classLiteral.getInternalName();

        if (create.desc.endsWith(ENUM_ARRAY_CREATE_TAIL)) {
            // create(name, class, Enum[]): first value = the array's index-0 GETSTATIC.
            AbstractInsnNode anewarray = AsmKit.findPreceding(create, n -> n.getOpcode() == Opcodes.ANEWARRAY, op -> true);
            FieldInsnNode value = AsmWalker.from(anewarray)
                .until(create)
                .ofType(FieldInsnNode.class)
                .first(field -> field.getOpcode() == Opcodes.GETSTATIC);
            return value != null
                ? this.properties.enumSerializedName(value.owner, value.name)
                : this.properties.firstEnumConstant(enumOwner);
        }
        if (create.desc.endsWith(ENUM_PREDICATE_CREATE_TAIL)) {
            // create(name, class, predicate): a Direction.Plane filter resolves to the first direction
            // in that plane (derived from the Plane construction); any other predicate falls back.
            AbstractInsnNode predicate = AsmWalker.previousReal(create);
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
        return AsmWalker.clinit(this.cache, planeField.owner)
            .latch(node -> node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode dir
                && dir.owner.equals(enumOwner) ? dir.name : null)
            .firstWins()
            .commitAt(Insn.putStatic(planeField.owner))
            .firstNotNull(commit -> {
                String firstDirection = commit.value();
                return commit.node().name.equals(planeField.name) && firstDirection != null
                    ? this.properties.enumSerializedName(enumOwner, firstDirection) : null;
            });
    }

}
