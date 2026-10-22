package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.asm.Insn;
import lib.minecraft.renderer.tooling.interp.Cells;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * The movement and flying speeds each subject's attribute supplier declares, its baby's movement
 * speed, and whether its spawn rolls the movement speed afresh.
 *
 * <p><b>The supplier is joined the way a renderer is.</b> {@code DefaultAttributes.<clinit>} reads an
 * {@code EntityType} field, then calls the static creator whose builder it registers, pair after
 * pair. The field names the subject through the class its type is typed with; a class two types share
 * names neither, and that subject joins no supplier.
 *
 * <p><b>The chain is evaluated in order</b>: a call to another creator - resolved up from the owner the
 * instruction names, which javac writes as the leaf for an inherited static - is evaluated first and its
 * attributes taken, and each {@code add} then declares its attribute, the last add of one winning as the
 * builder's own map does. An add with no value takes the default the attribute is declared with in
 * {@code Attributes.<clinit>}, read there rather than typed.
 *
 * <p><b>What a spawned mob does to its own attribute afterwards is not read</b>, but for the two writes
 * a subject's age and its spawn make: the modifier the nearest {@code setBaby} adds to the movement
 * speed, applied to the base by its own operation, and whether the nearest {@code randomizeAttributes}
 * rolls the movement speed - a subject that does walks at a mean its policy row declares, never at the
 * supplier's default.
 */
@UtilityClass
final class EntityMovementSpeedResolver {

    /** The descriptor of {@code setBaby}. */
    private static final @NotNull String SET_BABY_DESC = "(Z)V";

    /** The descriptor of {@code randomizeAttributes}. */
    private static final @NotNull String RANDOMIZE_DESC =
        SourceClasses.Descs.of("V", SourceClasses.Descs.ref(SourceClasses.Types.RANDOM_SOURCE));

    /** The descriptor of a field holding a modifier. */
    private static final @NotNull String MODIFIER_REF = SourceClasses.Descs.ref(SourceClasses.Types.ATTRIBUTE_MODIFIER);

    /**
     * One subject's declared speeds.
     *
     * @param movement the movement speed the supplier declares
     * @param flying the flying speed, present only where the supplier's chain adds one
     * @param babyMovement the movement speed a baby walks on, present only where its {@code setBaby} adds a modifier
     * @param rolled whether the subject's spawn rolls its movement speed afresh
     */
    record MovementSpeeds(double movement, @NotNull OptionalDouble flying, @NotNull OptionalDouble babyMovement, boolean rolled) { }

    /**
     * One modifier a static field holds.
     *
     * @param amount the amount it carries
     * @param operation the name of the operation it applies
     */
    private record Modifier(double amount, @NotNull String operation) { }

    /**
     * Every subject's declared speeds, in subject order. A subject joining no supplier, or whose chain
     * declares no readable movement speed, is absent.
     *
     * @param cache the session's jar cache
     * @param subjects the subjects read, in registry order
     * @param diagnostics the consulting scope
     * @return each joined subject's speeds, keyed by entity id
     */
    static @NotNull Map<String, MovementSpeeds> resolve(
        @NotNull ClassNodeCache cache, @NotNull List<EntitySubject> subjects, @NotNull Diagnostics diagnostics) {

        Map<String, MovementSpeeds> out = new LinkedHashMap<>();
        ClassNode entityType = cache.load(SourceClasses.Types.ENTITY_TYPE);
        if (entityType == null) return out;

        Map<String, List<String>> fieldsOfClass = new LinkedHashMap<>();
        EntityRegistryDiscovery.collectEntityTypeFieldClasses(entityType)
            .forEach((field, type) -> fieldsOfClass.computeIfAbsent(type, key -> new ArrayList<>()).add(field));
        Map<String, MethodInsnNode> suppliers = suppliers(cache);
        Map<String, Double> defaults = defaults(cache);

        for (EntitySubject subject : subjects) {
            List<String> fields = fieldsOfClass.getOrDefault(subject.entityClass(), List.of());
            MethodInsnNode creator = fields.size() == 1 ? suppliers.get(fields.getFirst()) : null;
            if (creator == null) continue;
            Map<String, Double> declared = evaluate(cache, creator.owner, creator.name, creator.desc, defaults, new HashSet<>());
            Double movement = declared.get(SourceClasses.Fields.MOVEMENT_SPEED);
            if (movement == null) continue;
            Double flying = declared.get(SourceClasses.Fields.FLYING_SPEED);
            out.put(subject.entityId(), new MovementSpeeds(
                movement,
                flying == null ? OptionalDouble.empty() : OptionalDouble.of(flying),
                babyMovement(cache, subject, movement, diagnostics),
                rolled(cache, subject.entityClass())));
        }
        return out;
    }

    /** Each type field {@code DefaultAttributes} registers, to the creator call it pairs with. */
    private static @NotNull Map<String, MethodInsnNode> suppliers(@NotNull ClassNodeCache cache) {
        Map<String, MethodInsnNode> out = new LinkedHashMap<>();
        AsmWalker.clinit(cache, SourceClasses.Types.DEFAULT_ATTRIBUTES)
            .latch(in -> AsmWalker.isGetStatic(in, SourceClasses.Types.ENTITY_TYPE) ? ((FieldInsnNode) in).name : null)
            .commitAt(MethodInsnNode.class, EntityMovementSpeedResolver::isCreator)
            .forEach(commit -> {
                if (commit.value() != null) out.putIfAbsent(commit.value(), commit.node());
            });
        return out;
    }

    /** Each attribute {@code Attributes} declares, to the default it is constructed with - the first double after its construction opens. */
    private static @NotNull Map<String, Double> defaults(@NotNull ClassNodeCache cache) {
        return AsmWalker.clinit(cache, SourceClasses.Types.ATTRIBUTES)
            .latch(AsmWalker::doubleLiteral)
            .firstWins()
            .resetAt(Insn.new_(SourceClasses.Types.RANGED_ATTRIBUTE))
            .commitAt(FieldInsnNode.class, put -> put.getOpcode() == Opcodes.PUTSTATIC
                && SourceClasses.Types.ATTRIBUTES.equals(put.owner))
            .toMap(put -> put.name, values -> values.isEmpty() ? null : values.getFirst());
    }

    /**
     * The attributes one creator's chain declares, its parent creators first. An add whose value is no
     * literal leaves its attribute undeclared rather than guessed.
     */
    private static @NotNull Map<String, Double> evaluate(
        @NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String name, @NotNull String desc,
        @NotNull Map<String, Double> defaults, @NotNull Set<String> evaluating) {

        String key = owner + "." + name + desc;
        MethodNode creator = ClassKit.findMethodInHierarchy(cache, owner, name, desc);
        if (creator == null || !evaluating.add(key)) return Map.of();
        Map<String, Double> state = new LinkedHashMap<>();
        Cells.Latch<String> attribute = Cells.latch();
        AsmWalker.over(creator)
            .feed(attribute)
            .on(Insn.of(MethodInsnNode.class, EntityMovementSpeedResolver::isCreator),
                parent -> state.putAll(evaluate(cache, parent.owner, parent.name, parent.desc, defaults, evaluating)))
            .on(Insn.getStatic(SourceClasses.Types.ATTRIBUTES), read -> attribute.set(read.name))
            .commitAt(Insn.of(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && SourceClasses.Types.ATTRIBUTE_SUPPLIER_BUILDER.equals(call.owner)
                && SourceClasses.Methods.BUILDER_ADD.equals(call.name)), add -> {
                String declared = attribute.get();
                if (declared == null) return;
                Double value = ClassKit.argTypes(add.desc).length == 2
                    ? AsmWalker.doubleLiteral(AsmWalker.previousReal(add))
                    : defaults.get(declared);
                if (value == null) state.remove(declared);
                else state.put(declared, value);
            })
            .run();
        evaluating.remove(key);
        return state;
    }

    /** Whether a call is a static creator answering a supplier's builder. */
    private static boolean isCreator(@NotNull MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC
            && ClassKit.descriptorReturns(call.desc, SourceClasses.Types.ATTRIBUTE_SUPPLIER_BUILDER);
    }

    /**
     * The movement speed a baby walks on - the base with the modifier the nearest {@code setBaby} adds
     * to the movement speed applied by its operation.
     */
    private static @NotNull OptionalDouble babyMovement(
        @NotNull ClassNodeCache cache, @NotNull EntitySubject subject, double base, @NotNull Diagnostics diagnostics) {

        ClassNode declaring = ClassKit.walkSuperChainUntil(cache, subject.entityClass(),
            node -> ClassKit.findMethod(node, SourceClasses.Methods.SET_BABY, SET_BABY_DESC) != null);
        if (declaring == null) return OptionalDouble.empty();
        MethodNode setBaby = ClassKit.findMethod(declaring, SourceClasses.Methods.SET_BABY, SET_BABY_DESC);

        List<MethodInsnNode> adds = new ArrayList<>();
        AsmWalker.over(setBaby)
            .latch(in -> AsmWalker.isGetStatic(in, SourceClasses.Types.ATTRIBUTES) ? ((FieldInsnNode) in).name : null)
            .commitAt(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && SourceClasses.Types.ATTRIBUTE_INSTANCE.equals(call.owner)
                && SourceClasses.Methods.ADD_TRANSIENT_MODIFIER.equals(call.name))
            .forEach(commit -> {
                if (SourceClasses.Fields.MOVEMENT_SPEED.equals(commit.value())) adds.add(commit.node());
            });
        if (adds.isEmpty()) return OptionalDouble.empty();

        Optional<Modifier> modifier = adds.size() == 1 && AsmWalker.previousReal(adds.getFirst()) instanceof FieldInsnNode field
            && field.getOpcode() == Opcodes.GETSTATIC && MODIFIER_REF.equals(field.desc)
            ? modifierOf(cache, field.owner, field.name)
            : Optional.empty();
        if (modifier.isPresent()) {
            double amount = modifier.get().amount();
            OptionalDouble applied = switch (modifier.get().operation()) {
                case SourceClasses.Fields.ADD_VALUE -> OptionalDouble.of(base + amount);
                case SourceClasses.Fields.ADD_MULTIPLIED_BASE -> OptionalDouble.of(base + base * amount);
                case SourceClasses.Fields.ADD_MULTIPLIED_TOTAL -> OptionalDouble.of(base * (1 + amount));
                default -> OptionalDouble.empty();
            };
            if (applied.isPresent()) return applied;
        }
        diagnostics.info("%s adds a baby movement modifier of a shape this reads no number from: %s",
            subject.entityId(), modifier.map(Modifier::toString).orElse(adds.size() + " add(s) in " + ClassKit.simpleName(declaring.name)));
        return OptionalDouble.empty();
    }

    /** The amount and operation a static modifier field is built with, read where its class initialises it. */
    private static @NotNull Optional<Modifier> modifierOf(@NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String field) {
        Modifier[] found = {null};
        ClassKit.walkSuperChainUntil(cache, owner, node -> {
            MethodNode clinit = ClassKit.findMethod(node, ClassKit.CLINIT);
            if (clinit == null) return false;
            Cells.Latch<Double> amount = Cells.latch();
            Cells.Latch<String> operation = Cells.latch();
            AsmWalker.over(clinit)
                .feed(amount)
                .feed(operation)
                .on(Insn.of(AbstractInsnNode.class, in -> AsmWalker.doubleLiteral(in) != null),
                    in -> amount.set(AsmWalker.doubleLiteral(in)))
                .on(Insn.getStatic(SourceClasses.Types.ATTRIBUTE_MODIFIER_OPERATION), read -> operation.set(read.name))
                .commitAt(Insn.of(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKESPECIAL
                    && SourceClasses.Types.ATTRIBUTE_MODIFIER.equals(call.owner) && ClassKit.INIT.equals(call.name)), init -> {
                    if (found[0] == null && amount.get() != null && operation.get() != null
                        && AsmWalker.nextReal(init) instanceof FieldInsnNode put
                        && AsmWalker.isPutStatic(put, node.name, field))
                        found[0] = new Modifier(amount.get(), operation.get());
                })
                .run();
            return found[0] != null;
        });
        return Optional.ofNullable(found[0]);
    }

    /** Whether the nearest {@code randomizeAttributes} up a class rolls its movement speed. */
    private static boolean rolled(@NotNull ClassNodeCache cache, @NotNull String entityClass) {
        ClassNode declaring = ClassKit.walkSuperChainUntil(cache, entityClass,
            node -> ClassKit.findMethod(node, SourceClasses.Methods.RANDOMIZE_ATTRIBUTES, RANDOMIZE_DESC) != null);
        if (declaring == null) return false;
        MethodNode roll = ClassKit.findMethod(declaring, SourceClasses.Methods.RANDOMIZE_ATTRIBUTES, RANDOMIZE_DESC);
        return AsmWalker.over(roll).any(in -> AsmWalker.isGetStatic(in, SourceClasses.Types.ATTRIBUTES, SourceClasses.Fields.MOVEMENT_SPEED));
    }

}
