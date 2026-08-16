package lib.minecraft.renderer.tooling.kernel;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.AllArgsConstructor;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.Trace;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The one engine that replays a {@link Navigation.Dataflow} coordinate, cache-fed. It knows the
 * {@link Trace.Step} vocabulary and nothing whatever about the subject a trace recovers a fact for,
 * so one instance serves every trace and a new declared trace needs no code here.
 *
 * <p>The machine holds a CURSOR - the member the replay stands in and the instruction it reads from
 * - and a RUNNING VALUE. Each step reads the cursor, may move it, and may answer the next running
 * value; the value the last step leaves is what the replay recovers, as an {@code Integer} or a
 * {@code String}. Every step compiles to an {@link AsmWalker} chain, and re-entry rides the loud
 * {@code ClassKit} forms, so drift in the walked jar surfaces as a named failure.
 *
 * <p>A trace that cannot complete throws {@link ToolingException} naming the step index, the
 * coordinate and what the step could not find. There is no warn-and-continue arm and no silent
 * {@code null}: a coordinate declaring a recipe asserts the recipe still runs, and a partial replay
 * would answer a fact nothing derived.
 */
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
public final class TraceReplay {

    /** The caller label the loud re-entries tag their drift message with. */
    private static final @NotNull String CONTEXT = "a dataflow trace replay";

    private final @NotNull ClassNodeCache cache;

    /**
     * Replays a coordinate's trace and answers the value its last step leaves.
     *
     * @param coordinate the declared coordinate and the steps to walk from it
     * @return the recovered value - an {@code Integer} or a {@code String}
     * @throws ToolingException if the trace is empty, a step cannot complete, or no step answered a value
     */
    public @NotNull Object replay(@NotNull Navigation.Dataflow coordinate) {
        List<Trace.Step> steps = coordinate.trace().steps();
        if (steps.isEmpty())
            throw new ToolingException("Trace at '%s.%s' declares no steps", coordinate.owner(), coordinate.member());

        ClassNode owner = ClassKit.requireClass(this.cache, coordinate.owner(), CONTEXT);
        MethodNode member = requireSoleMember(owner, coordinate);
        AbstractInsnNode entry = member.instructions.getFirst();
        if (entry == null)
            throw new ToolingException(
                "Trace at '%s.%s' enters a member with no body - the jar is either obfuscated or from an unsupported version",
                coordinate.owner(), coordinate.member());

        Cursor cursor = new Cursor(member, entry);
        Object value = null;
        for (int index = 0; index < steps.size(); index++)
            value = apply(steps.get(index), cursor, value, coordinate, index);

        if (value == null) throw drift(coordinate, steps.size() - 1, "leaves the replay with no recovered value");
        return value;
    }

    /**
     * Answers the entered member, refusing a name the anchor class overloads. A coordinate carries
     * no descriptor, so where a class declares the name twice there is nothing in the trace that
     * picks one, and taking the first would answer whichever the compiler happened to emit first.
     *
     * @param owner the anchor class
     * @param coordinate the coordinate naming the member
     * @return the sole member of that name
     * @throws ToolingException if the class declares no member of that name, or declares several
     */
    private static @NotNull MethodNode requireSoleMember(@NotNull ClassNode owner, @NotNull Navigation.Dataflow coordinate) {
        MethodNode member = ClassKit.requireMethod(owner, coordinate.member(), CONTEXT);
        long declared = owner.methods.stream().filter(candidate -> candidate.name.equals(coordinate.member())).count();
        if (declared > 1)
            throw new ToolingException(
                "Trace at '%s.%s' enters a member class '%s' declares %d times - a coordinate carries no descriptor to tell them apart",
                coordinate.owner(), coordinate.member(), owner.name, declared);
        return member;
    }

    /** Applies one step, moving the cursor and answering the running value the next step sees. */
    private @Nullable Object apply(
        @NotNull Trace.Step step,
        @NotNull Cursor cursor,
        @Nullable Object value,
        @NotNull Navigation.Dataflow coordinate,
        int index
    ) {
        return switch (step) {
            case Trace.Step.ReadIntLiteral read ->
                readLiteral(cursor, AsmWalker::intLiteral, coordinate, index, "int literal");
            case Trace.Step.ReadStringLiteral read ->
                readLiteral(cursor, AsmWalker::stringLiteral, coordinate, index, "string literal");
            case Trace.Step.ReadTypeLiteral read ->
                readLiteral(cursor, TraceReplay::internalNameOf, coordinate, index, "class literal");
            case Trace.Step.FollowPutStatic follow -> {
                followPutStatic(cursor, follow.name(), coordinate, index);
                yield value;
            }
            case Trace.Step.FollowGetField follow -> {
                followGetField(cursor, follow.name(), coordinate, index);
                yield value;
            }
            case Trace.Step.IndexArrayInit indexed -> {
                indexArrayInit(cursor, value, coordinate, index);
                yield value;
            }
            case Trace.Step.MapParamSlot mapped -> mapParamSlot(cursor, coordinate, index);
            case Trace.Step.FollowInvoke follow -> {
                followInvoke(cursor, follow.member(), follow.desc(), coordinate, index);
                yield value;
            }
        };
    }

    // ------------------------------------------------------------------------------------
    // the steps - one AsmWalker chain each
    // ------------------------------------------------------------------------------------

    /** Reads the first literal the decoder accepts at or after the cursor, moving the cursor to it. */
    private static <V> @NotNull V readLiteral(
        @NotNull Cursor cursor,
        @NotNull Function<AbstractInsnNode, @Nullable V> decoder,
        @NotNull Navigation.Dataflow coordinate,
        int index,
        @NotNull String what
    ) {
        AbstractInsnNode node = AsmWalker.from(cursor.node)
            .real()
            .first(Insn.of(AbstractInsnNode.class, candidate -> decoder.apply(candidate) != null));
        V decoded = node == null ? null : decoder.apply(node);
        if (node == null || decoded == null) throw drift(coordinate, index, "reads no " + what);
        cursor.node = node;
        return decoded;
    }

    /**
     * Moves the cursor from the entered member's read of a static field to that field's binding. The
     * read is what names the owning class, so a member reading that name on two classes is refused -
     * the step names no owner, and picking the first would pick a class at random.
     */
    private void followPutStatic(@NotNull Cursor cursor, @NotNull String name, @NotNull Navigation.Dataflow coordinate, int index) {
        Set<String> owners = AsmWalker.over(cursor.member)
            .ofType(FieldInsnNode.class)
            .where(field -> field.getOpcode() == Opcodes.GETSTATIC && field.name.equals(name))
            .map(read -> read.owner)
            .toSet();
        if (owners.isEmpty()) throw drift(coordinate, index, "enters a member reading no static field '" + name + "'");
        if (owners.size() > 1) throw drift(coordinate, index, "reads static field '" + name + "' on several classes");

        String fieldOwner = owners.iterator().next();
        ClassNode owner = ClassKit.requireClass(this.cache, fieldOwner, CONTEXT);
        MethodNode initialiser = ClassKit.requireClinit(owner, CONTEXT);
        FieldInsnNode binding = AsmWalker.over(initialiser).first(Insn.putStatic(fieldOwner, name));
        if (binding == null) throw drift(coordinate, index, "finds no binding of static field '" + fieldOwner + "." + name + "'");

        cursor.member = initialiser;
        cursor.node = binding;
    }

    /**
     * Moves the cursor to an instance-field read in the entered member. The read names the class
     * {@link Trace.Step.MapParamSlot} would enter, so an unnamed step meeting several reads and a
     * named one meeting that name on several classes are both refused.
     */
    private static void followGetField(@NotNull Cursor cursor, @Nullable String name, @NotNull Navigation.Dataflow coordinate, int index) {
        List<FieldInsnNode> reads = AsmWalker.over(cursor.member)
            .ofType(FieldInsnNode.class)
            .where(field -> field.getOpcode() == Opcodes.GETFIELD && (name == null || field.name.equals(name)))
            .toList();
        if (reads.isEmpty()) throw drift(coordinate, index, "enters a member reading no instance field");
        if (name == null && reads.size() > 1)
            throw drift(coordinate, index, "names no field where the member reads several");
        if (reads.stream().map(read -> read.owner).distinct().count() > 1)
            throw drift(coordinate, index, "reads a field of that name on several classes");
        cursor.node = reads.getFirst();
    }

    /**
     * Moves the cursor onto the value expression of one element of the array the cursor's binding
     * site consumes. The walk anchors on the allocation and stops at the binding, so literals
     * elsewhere in the initialiser cannot bind an ordinal, and the ordinal is the FIRST literal of
     * each element window because a value expression may push ints of its own.
     */
    private static void indexArrayInit(@NotNull Cursor cursor, @Nullable Object value, @NotNull Navigation.Dataflow coordinate, int index) {
        if (!(value instanceof Integer ordinal))
            throw drift(coordinate, index, "indexes an array with no int running value");

        AbstractInsnNode allocation = AsmWalker.before(cursor.node).first(Insn.opcode(Opcodes.ANEWARRAY));
        if (allocation == null) throw drift(coordinate, index, "finds no array construction before the cursor");

        AbstractInsnNode ordinalPush = AsmWalker.after(allocation)
            .until(cursor.node)
            .latch(node -> AsmWalker.intLiteral(node) != null ? node : null)
            .firstWins()
            .commitAt(Insn.opcode(Opcodes.AASTORE))
            .firstNotNull(commit -> {
                AbstractInsnNode latched = commit.value();
                return latched != null && ordinal.equals(AsmWalker.intLiteral(latched)) ? latched : null;
            });
        if (ordinalPush == null) throw drift(coordinate, index, "binds no array element at the running ordinal");

        AbstractInsnNode start = AsmWalker.nextReal(ordinalPush);
        if (start == null) throw drift(coordinate, index, "binds an array element with no value expression");
        cursor.node = start;
    }

    /**
     * Answers the constructor parameter index the field at the cursor is assigned from. The
     * constructor's own descriptor is what the slot divides by - an anonymous class's printed
     * signature elides its captured parameters and the descriptor does not.
     *
     * <p>{@code <init>} is the one member name every class may declare several times, so the
     * constructor is selected by the ASSIGNMENT rather than by the name: the one whose body binds the
     * field. A telescoping constructor delegates instead of re-assigning, so exactly one binds it,
     * and a class where two do is refused rather than resolved to whichever came first.
     */
    private @NotNull Integer mapParamSlot(@NotNull Cursor cursor, @NotNull Navigation.Dataflow coordinate, int index) {
        if (!(cursor.node instanceof FieldInsnNode field))
            throw drift(coordinate, index, "maps a parameter slot with the cursor off a field access");

        ClassNode owner = ClassKit.requireClass(this.cache, field.owner, CONTEXT);
        List<MethodNode> binding = new ArrayList<>();
        FieldInsnNode assignment = null;
        for (MethodNode candidate : owner.methods) {
            if (!ClassKit.INIT.equals(candidate.name)) continue;
            FieldInsnNode found = AsmWalker.over(candidate).first(Insn.putField(field.owner, field.name));
            if (found == null) continue;
            binding.add(candidate);
            assignment = found;
        }
        if (assignment == null)
            throw drift(coordinate, index, "finds no constructor assignment of field '" + field.name + "'");
        if (binding.size() > 1)
            throw drift(coordinate, index, "finds field '" + field.name + "' assigned by several constructors");

        MethodNode constructor = binding.getFirst();
        if (!(AsmWalker.previousReal(assignment) instanceof VarInsnNode load))
            throw drift(coordinate, index, "finds no variable load feeding the assignment of field '" + field.name + "'");

        int slot = (constructor.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;   // an instance ctor holds `this` in slot 0
        int position = 0;
        for (Type argument : ClassKit.argTypes(constructor.desc)) {
            if (slot == load.var) {
                cursor.member = constructor;
                cursor.node = assignment;
                return position;
            }
            slot += argument.getSize();
            position++;
        }
        throw drift(coordinate, index, "loads a slot the constructor descriptor declares no parameter at");
    }

    /** Moves the cursor into the callee of a named invoke at or after it. */
    private void followInvoke(
        @NotNull Cursor cursor,
        @NotNull String member,
        @Nullable String desc,
        @NotNull Navigation.Dataflow coordinate,
        int index
    ) {
        MethodInsnNode call = AsmWalker.from(cursor.node).first(Insn.of(MethodInsnNode.class,
            invoke -> invoke.name.equals(member) && (desc == null || invoke.desc.equals(desc))));
        if (call == null) throw drift(coordinate, index, "finds no invoke of member '" + member + "'");

        ClassNode owner = ClassKit.requireClass(this.cache, call.owner, CONTEXT);
        MethodNode callee = ClassKit.requireMethod(owner, call.name, call.desc, CONTEXT);
        AbstractInsnNode entry = callee.instructions.getFirst();
        if (entry == null) throw drift(coordinate, index, "follows an invoke into a member with no body");

        cursor.member = callee;
        cursor.node = entry;
    }

    // ------------------------------------------------------------------------------------
    // shared reads
    // ------------------------------------------------------------------------------------

    /** Decodes a class literal to its JVM internal name, or {@code null} for any other node. */
    private static @Nullable String internalNameOf(@NotNull AbstractInsnNode node) {
        Type type = AsmWalker.typeLiteral(node);
        return type == null ? null : type.getInternalName();
    }

    /**
     * Builds the canonical failure of a step that could not complete.
     *
     * @param coordinate the coordinate being replayed
     * @param index the failing step's position in the trace
     * @param what what the step could not do, as a third-person fragment
     * @return the failure to throw
     */
    private static @NotNull ToolingException drift(@NotNull Navigation.Dataflow coordinate, int index, @NotNull String what) {
        return new ToolingException(
            "Trace step %d of '%s.%s' %s - the jar is either obfuscated or from an unsupported version",
            index, coordinate.owner(), coordinate.member(), what);
    }

    /** The replay's position - the member it stands in, and the instruction it reads from. */
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Cursor {

        private @NotNull MethodNode member;
        private @NotNull AbstractInsnNode node;

    }

}
