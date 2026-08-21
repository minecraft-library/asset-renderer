package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One enum's constants, in declaration order, carrying the values its static initialiser bound each
 * of them with.
 *
 * <p>An enum constant is a compile-time fact wearing a run-time shape: the constructor arguments are
 * literals in {@code <clinit>} and never change, so what a constant's field holds is answerable from
 * the class file alone. That is what lets a walk that knows WHICH constant it is looking at answer
 * questions the constant's own methods would otherwise have to be run to answer.
 *
 * <p><b>A constant is recognised by its {@code putstatic}, never by its {@code new}.</b> A constant
 * with a body of its own is constructed as an anonymous subclass, so keying on the allocated type
 * silently drops it - and drops it in the way that hurts most, leaving an enum that looks complete
 * and is short exactly the constant nobody tests. The field write is the one instruction every
 * constant has in common.
 *
 * @param type the enum's internal name
 * @param constants its constants, in declaration order
 */
public record EnumConstantTable(@NotNull String type, @NotNull List<Constant> constants) {

    /**
     * One constant of an enum.
     *
     * <p>{@code fields} holds only what could be read as a number. A constructor argument that is
     * not a literal is absent rather than guessed, so a question about it refuses at the point it is
     * asked instead of being answered with a zero.
     *
     * @param name the constant's own name
     * @param ordinal its position in declaration order
     * @param fields the instance fields the constructor bound, by field name
     */
    public record Constant(@NotNull String name, int ordinal, @NotNull Map<String, Double> fields) {}

    /** Where an enum's superclass chain stops, and the only thing a real enum extends. */
    private static final @NotNull String ENUM_ROOT = "java/lang/Enum";

    /** The two arguments every enum constructor takes before its own, which name it and place it. */
    private static final int DECLARED_ARGUMENTS_BEFORE_OWN = 2;

    /**
     * Reads an enum's constants.
     *
     * @param cache the open client jar
     * @param type the candidate enum's internal name
     * @return the table, or empty when the type is not an enum or is not in the jar
     * @throws IllegalStateException if the type is an enum whose initialiser has an unreadable shape
     */
    public static @NotNull Optional<EnumConstantTable> of(@NotNull ClassNodeCache cache, @NotNull String type) {
        ClassNode node = cache.load(type);
        if (node == null || !ENUM_ROOT.equals(node.superName)) return Optional.empty();
        if (ClassKit.findClinit(cache, type) == null)
            throw new IllegalStateException("reads " + ClassKit.simpleName(type) + ", which runs no static initialiser");

        List<Built> built = declarations(cache, type);
        List<Constant> constants = new ArrayList<>(built.size());
        for (int ordinal = 0; ordinal < built.size(); ordinal++) {
            Built one = built.get(ordinal);
            if (one.ordinal != ordinal)
                throw new IllegalStateException("declares " + ClassKit.simpleName(type) + "." + one.name
                    + " at position " + ordinal + " and numbers it " + one.ordinal);
            constants.add(new Constant(one.name, ordinal, bind(cache, type, one)));
        }
        return Optional.of(new EnumConstantTable(type, List.copyOf(constants)));
    }

    /**
     * The constant of this enum a name refers to.
     *
     * @param name the constant's own name
     * @return the constant, or empty when this enum declares none of that name
     */
    public @NotNull Optional<Constant> byName(@NotNull String name) {
        return this.constants.stream().filter(constant -> constant.name().equals(name)).findFirst();
    }

    // ------------------------------------------------------------------------------------

    /** One constant as its declaration reads, before its arguments are matched to fields. */
    private record Built(
        @NotNull String name,
        int ordinal,
        @NotNull String constructedType,
        @NotNull String constructorDescriptor,
        @NotNull List<@Nullable Double> arguments
    ) {}

    /** How far back a constant's own arguments may reach before the walk stops looking for its {@code new}. */
    private static final int MAX_DECLARATION_SPAN = 64;

    /** Everything the initialiser constructs and parks in a field of the enum's own type. */
    private static @NotNull List<Built> declarations(@NotNull ClassNodeCache cache, @NotNull String type) {
        String selfDescriptor = "L" + type + ";";
        List<Built> out = new ArrayList<>();
        AsmWalker.clinit(cache, type)
            .on(Insn.putStatic(type), put -> {
                if (!selfDescriptor.equals(put.desc)) return;
                if (!(AsmWalker.previousReal(put) instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL || !ClassKit.INIT.equals(constructor.name))
                    throw new IllegalStateException("parks " + put.name + " without constructing it first");
                out.add(built(constructor, put));
            })
            .run();
        return out;
    }

    /**
     * One declaration, read backwards off the instructions its constructor was handed.
     *
     * <p>Read backwards from the call rather than forwards from the allocation, and required to be
     * one literal push per parameter, because a constant whose argument is an expression pushes
     * literals of its own. Accumulating everything between the two and lining it up by position
     * would then bind a value from inside one argument to the field of the next - which is a wrong
     * number under a right name, and reads as a working extraction.
     *
     * <p>A name and a position survive that refusal, because javac writes them as the first two
     * pushes of every constant there is and nothing can be nested inside them.
     */
    private static @NotNull Built built(@NotNull MethodInsnNode constructor, @NotNull FieldInsnNode put) {
        int arity = ClassKit.argTypes(constructor.desc).length;
        if (arity < DECLARED_ARGUMENTS_BEFORE_OWN)
            throw new IllegalStateException("builds " + put.name + " through a constructor that names it nothing");

        List<AbstractInsnNode> pushes = new ArrayList<>();
        AbstractInsnNode cursor = AsmWalker.previousReal(constructor);
        for (int step = 0; cursor != null && step < MAX_DECLARATION_SPAN; step++) {
            if (cursor.getOpcode() == Opcodes.DUP) break;
            pushes.addFirst(cursor);
            cursor = AsmWalker.previousReal(cursor);
        }

        if (pushes.size() < DECLARED_ARGUMENTS_BEFORE_OWN
            || !(literal(pushes.getFirst()) instanceof String name)
            || !(literal(pushes.get(1)) instanceof Double ordinal))
            throw new IllegalStateException("declares " + put.name + " without a readable name and position");

        List<Double> arguments = new ArrayList<>();
        // One push per parameter or nothing: a shorter run means an argument was computed, and there
        // is then no way to say which push belonged to which parameter.
        if (pushes.size() == arity)
            for (AbstractInsnNode push : pushes.subList(DECLARED_ARGUMENTS_BEFORE_OWN, pushes.size()))
                arguments.add(literal(push) instanceof Double number ? number : null);
        return new Built(name, (int) (double) ordinal, constructor.owner, constructor.desc, arguments);
    }

    /** A constant's own fields, by matching what its constructor was handed to what it stores. */
    private static @NotNull Map<String, Double> bind(
        @NotNull ClassNodeCache cache, @NotNull String type, @NotNull Built built) {

        Map<Integer, String> bySlot = storedParameters(cache, type, built);
        Type[] parameters = ClassKit.argTypes(built.constructorDescriptor());

        Map<String, Double> out = new LinkedHashMap<>();
        int slot = 1;
        for (int index = 0; index < parameters.length; index++) {
            String field = bySlot.get(slot);
            slot += parameters[index].getSize();
            int own = index - DECLARED_ARGUMENTS_BEFORE_OWN;
            if (field == null || own < 0 || own >= built.arguments().size()) continue;
            Double value = built.arguments().get(own);
            if (value != null) out.put(field, value);
        }
        return out;
    }

    /**
     * Which constructor parameter each stored field came from.
     *
     * <p>A constant carrying a body of its own is built through an anonymous subclass whose
     * constructor only hands its arguments on, so the fields are declared a level up. That hop is
     * followed only once and only when the arguments provably travel unchanged - a subclass that
     * reordered or replaced one would bind the wrong value to the right name, which reads as a
     * working extraction.
     */
    private static @NotNull Map<Integer, String> storedParameters(
        @NotNull ClassNodeCache cache, @NotNull String type, @NotNull Built built) {

        Map<Integer, String> bySlot = stores(cache, built.constructedType(), built.constructorDescriptor());
        if (!bySlot.isEmpty() || built.constructedType().equals(type)) return bySlot;
        if (!handsArgumentsOn(cache, built.constructedType(), built.constructorDescriptor(), type))
            throw new IllegalStateException("builds " + built.name() + " through "
                + ClassKit.simpleName(built.constructedType()) + ", which does not hand its arguments straight on");
        return stores(cache, type, built.constructorDescriptor());
    }

    /** Every {@code putfield} on the constructed object whose value came straight off a parameter. */
    private static @NotNull Map<Integer, String> stores(
        @NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String descriptor) {

        MethodNode constructor = constructorOf(cache, owner, descriptor);
        Map<Integer, String> bySlot = new LinkedHashMap<>();
        AsmWalker.over(constructor).on(Insn.ofType(FieldInsnNode.class), put -> {
            if (put.getOpcode() != Opcodes.PUTFIELD) return;
            AbstractInsnNode value = AsmWalker.previousReal(put);
            AbstractInsnNode receiver = value == null ? null : AsmWalker.previousReal(value);
            if (!(value instanceof VarInsnNode load) || !isThis(receiver)) return;
            bySlot.put(load.var, put.name);
        }).run();
        return bySlot;
    }

    /** Whether a subclass constructor is nothing but a call up with the arguments it was given. */
    private static boolean handsArgumentsOn(
        @NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String descriptor, @NotNull String target) {

        MethodNode constructor = constructorOf(cache, owner, descriptor);
        MethodInsnNode call = AsmWalker.over(constructor)
            .first(Insn.invokeSpecial(target, ClassKit.INIT));
        if (call == null || !descriptor.equals(call.desc)) return false;

        AbstractInsnNode first = constructor.instructions.getFirst();
        if (first != null && AsmWalker.isPseudoNode(first)) first = AsmWalker.nextReal(first);

        int slot = 0;
        for (AbstractInsnNode in = first; in != null && in != call; in = AsmWalker.nextReal(in)) {
            if (!(in instanceof VarInsnNode load) || load.var != slot) return false;
            slot += slot == 0 ? 1 : sizeAt(descriptor, slot);
        }
        return slot > 0;
    }

    /** The size of the parameter occupying a slot, so the next expected load is known. */
    private static int sizeAt(@NotNull String descriptor, int slot) {
        int at = 1;
        for (Type parameter : ClassKit.argTypes(descriptor)) {
            if (at == slot) return parameter.getSize();
            at += parameter.getSize();
        }
        return 1;
    }

    private static @NotNull MethodNode constructorOf(
        @NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String descriptor) {

        MethodNode constructor = ClassKit.findMethodInHierarchy(cache, owner, ClassKit.INIT, descriptor);
        if (constructor == null)
            throw new IllegalStateException("builds through " + ClassKit.simpleName(owner)
                + ClassKit.INIT + descriptor + ", whose body is not in the jar");
        return constructor;
    }

    private static boolean isThis(@Nullable AbstractInsnNode node) {
        return node instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0;
    }

    /** A literal an initialiser pushes, as the name it carries or the number it is. */
    private static @Nullable Object literal(@NotNull AbstractInsnNode node) {
        if (node instanceof LdcInsnNode ldc) {
            if (ldc.cst instanceof String text) return text;
            if (ldc.cst instanceof Number number) return number.doubleValue();
            return null;
        }
        Integer whole = AsmWalker.intLiteral(node);
        if (whole != null) return (double) (int) whole;
        return switch (node.getOpcode()) {
            case Opcodes.FCONST_0, Opcodes.DCONST_0, Opcodes.LCONST_0 -> 0d;
            case Opcodes.FCONST_1, Opcodes.DCONST_1, Opcodes.LCONST_1 -> 1d;
            case Opcodes.FCONST_2 -> 2d;
            default -> null;
        };
    }

}
