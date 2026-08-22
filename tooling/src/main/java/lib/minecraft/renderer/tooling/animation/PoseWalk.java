package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Insn;
import lib.minecraft.renderer.tooling.walk.Interp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Walks a model's {@code setupAnim} body into the pose it computes.
 *
 * <p>The chassis owns the operand stack, the slot table and the arithmetic; this owns everything a
 * pose body is actually made of, which is field access and calls. That split is why the walk is
 * short: the interesting half of a pose is which bone a write lands on, and a bone is decided at
 * construction rather than at animation time, so it resolves to a name rather than to a value.
 *
 * <p><b>The reset is the seed, and it is why reading a channel needs no special case.</b> Every
 * body begins by calling up to {@code Model.resetPose}, which restores each bone's authored pose.
 * So a channel read before anything writes it reads that authored value, which this records as a
 * read of the bone rather than as a number - the evaluator seeds it from the mesh. A channel read
 * after a write reads the write. Both fall out of holding one expression per channel and
 * substituting it where it is read.
 *
 * <p><b>Control flow is followed wherever it is decided, and kept where it is not.</b> A loop
 * counter is a literal this walk can see, so the test closing a loop decides itself and the body is
 * walked again with the next value - loops need no detector, a bounded loop and a frozen
 * conditional being one mechanism rather than two. A branch that genuinely turns on the render
 * state is neither decided nor refused: both arms run and the channels they disagree about become a
 * choice, so what comes out is every pose the model can take rather than whichever one an arbitrary
 * arm would have produced.
 *
 * <p>Both arms run as far as the point their control flow rejoins, and everything they disagree
 * about there becomes a choice - a channel, a value still on the stack, a local one of them stored.
 * Merging at the join is what keeps a choice around the operand that actually differed rather than
 * over all the arithmetic that happened to follow it: a fish that swims at one speed and flaps at
 * another chooses a speed, and its tail sweep is written once. Arms that never rejoin, one returning
 * early while the other carries on, have no such point and run to the end of the body instead, where
 * only the pose can be merged.
 *
 * <p>Anything not modelled is refused rather than approximated: an unresolvable bone, a call this
 * cannot enter, a write to the render state or to the model itself. A pose quietly missing a term
 * is the one outcome worth failing to avoid, because it renders - it just renders wrongly, and
 * nothing downstream can tell.
 */
@UtilityClass
public final class PoseWalk {

    /** The erased override every model carries beside its typed one; walking it finds no body. */
    private static final @NotNull String ERASED_SETUP_ANIM = "(Ljava/lang/Object;)V";

    /** {@code Enum.ordinal}, matched whole so nothing else of that name is mistaken for it. */
    private static final @NotNull String ORDINAL_DESCRIPTOR = "()I";

    /** What javac calls the table it hides beside a class so a switch over an enum can be a jump. */
    private static final @NotNull String SWITCH_MAP_PREFIX = "$SwitchMap$";

    /** That table's type - an int per constant, which is why indexing it is not an array read. */
    private static final @NotNull String SWITCH_MAP_DESCRIPTOR = "[I";

    /** Where the super chain stops being a pose and becomes the reset. */
    private static final @NotNull List<String> RESET_ROOTS =
        List.of(VanillaSourceClasses.Types.ENTITY_MODEL, VanillaSourceClasses.Types.MODEL);

    private static final @NotNull PoseValue.Opaque OPAQUE = new PoseValue.Opaque();

    /**
     * What the container a mesh flattened away is called while a walk is holding its channels.
     *
     * <p>Spelled so no mesh can name a bone the same way, the vocabulary a bone name is drawn from
     * being the literals vanilla hands {@code addOrReplaceChild}. It never reaches a pose: the fold
     * takes it out again, and a walk that left one standing fails the assertion that every posed bone
     * is one its mesh declares.
     */
    private static final @NotNull String MESH_ROOT = "<mesh root>";

    /** The arithmetic the chassis routes here, keyed by opcode, at the width the opcode names. */
    private static final @NotNull Map<Integer, PoseOperator> ARITHMETIC = arithmetic();

    /** The calls a pose body makes that are arithmetic by another name. */
    private static final @NotNull Map<String, PoseOperator> CALLS = calls();

    /**
     * The questions a pose body asks of a reference the render state holds, which are inputs.
     *
     * <p>Declared as whole coordinates rather than by a rule about shape, because a rule would
     * absorb the next zero-argument call anyone adds and answer it with an input nothing supplies.
     * Every row takes no arguments, and the descriptor in the key is what says so - a question with
     * arguments answers a different thing per argument and belongs on the reference's own name
     * rather than on the question asked of it.
     */
    private static final @NotNull Set<String> STATE_QUESTIONS = stateQuestions();

    /**
     * The calls that read a named component off a reference the render state holds.
     *
     * <p>A hop rather than a question: what comes back is another reference, reached by naming a
     * declared static, so it is carried under the path that reached it and asked its own questions
     * afterwards. The component's own field name is the whole of what the hop adds.
     */
    private static final @NotNull Set<String> STATE_COMPONENTS = stateComponents();

    /**
     * The calls that re-present a reference the render state holds as the figures derived from it.
     *
     * <p>A body this walk cannot enter and must not refuse: what it computes lives in server data
     * nothing offline carries, so entering it would reach the same wall one frame later. Naming it
     * instead keeps the reference it was derived FROM, and the questions asked of the result become
     * questions asked of that reference - which is what a caller can actually answer.
     *
     * <p>Every other operand has to be a number the expression already carries, so what the row
     * drops is nothing: the derivation's only other input here is a field of the same render state,
     * one per state rather than one per reference, so a caller answering the questions is answering
     * them at that same value whether the name mentions it or not.
     */
    private static final @NotNull Set<String> STATE_DERIVATIONS = stateDerivations();

    /**
     * The two calls that put a float in a box and take it back out again.
     *
     * <p>Keyed on the whole coordinate rather than on the method name, because the name is shared
     * with real arithmetic: {@code Double.floatValue} is a narrowing and {@code Float.doubleValue}
     * a widening, and folding either into an identity would drop a rounding vanilla performs. Not
     * rows of {@link #CALLS} either - a box is not an operation, and giving it a token would put it
     * in the shipped table as though it were one.
     */
    private static final @NotNull Set<String> BOXES = boxes();

    /** The handle kinds a lambda's target may be, which here is the one javac writes. */
    private static final int STATIC_HANDLE = Opcodes.H_INVOKESTATIC;

    private static final Interp.Domain<PoseValue> DOMAIN = new Interp.Domain<>() {

        @Override
        public @Nullable PoseValue decode(@NotNull AbstractInsnNode node) {
            if (node instanceof LdcInsnNode ldc) {
                if (ldc.cst instanceof Float value) return num(PoseExpr.Const.of((float) value));
                if (ldc.cst instanceof Double value) return num(PoseExpr.Const.of((double) value));
                if (ldc.cst instanceof Integer value) return num(PoseExpr.Const.of((int) value));
                return null;
            }
            if (node instanceof IntInsnNode push
                && (push.getOpcode() == Opcodes.BIPUSH || push.getOpcode() == Opcodes.SIPUSH))
                return num(PoseExpr.Const.of(push.operand));
            if (!(node instanceof InsnNode)) return null;
            return switch (node.getOpcode()) {
                case Opcodes.FCONST_0 -> num(PoseExpr.Const.of(0f));
                case Opcodes.FCONST_1 -> num(PoseExpr.Const.of(1f));
                case Opcodes.FCONST_2 -> num(PoseExpr.Const.of(2f));
                case Opcodes.DCONST_0 -> num(PoseExpr.Const.of(0d));
                case Opcodes.DCONST_1 -> num(PoseExpr.Const.of(1d));
                case Opcodes.ICONST_M1 -> num(PoseExpr.Const.of(-1));
                case Opcodes.ICONST_0 -> num(PoseExpr.Const.of(0));
                case Opcodes.ICONST_1 -> num(PoseExpr.Const.of(1));
                case Opcodes.ICONST_2 -> num(PoseExpr.Const.of(2));
                case Opcodes.ICONST_3 -> num(PoseExpr.Const.of(3));
                case Opcodes.ICONST_4 -> num(PoseExpr.Const.of(4));
                case Opcodes.ICONST_5 -> num(PoseExpr.Const.of(5));
                default -> null;
            };
        }

        @Override
        public @NotNull PoseValue unknown() {
            return OPAQUE;
        }

        @Override
        public @NotNull PoseValue underflow() {
            return OPAQUE;
        }

        @Override
        public @Nullable PoseValue binary(int opcode, @NotNull PoseValue left, @NotNull PoseValue right) {
            if (opcode >= Opcodes.LCMP && opcode <= Opcodes.DCMPG) {
                // The three-way compare a float test is spelled as. Folding it is what lets the
                // branch after it decide; when it does not fold, the operands travel to the branch
                // rather than collapsing, so the branch can say what it turned on.
                Double lhs = literal(left);
                Double rhs = literal(right);
                if (lhs != null && rhs != null && !lhs.isNaN() && !rhs.isNaN())
                    return num(PoseExpr.Const.of(Double.compare(lhs, rhs)));
                if (left instanceof PoseValue.Num first && right instanceof PoseValue.Num second)
                    return new PoseValue.Comparison(first.expr(), second.expr());
                return null;
            }
            PoseOperator operator = ARITHMETIC.get(opcode);
            if (operator == null || !(left instanceof PoseValue.Num lhs) || !(right instanceof PoseValue.Num rhs))
                return null;
            return num(PoseExpr.Op.of(operator, lhs.expr(), rhs.expr()));
        }

        private @Nullable Double literal(@NotNull PoseValue value) {
            return value instanceof PoseValue.Num number && number.expr() instanceof PoseExpr.Const held
                ? held.value() : null;
        }

        @Override
        public @Nullable PoseValue unary(int opcode, @NotNull PoseValue operand) {
            PoseOperator operator = ARITHMETIC.get(opcode);
            if (operator == null || !(operand instanceof PoseValue.Num value)) return null;
            return num(PoseExpr.Op.of(operator, value.expr()));
        }

    };

    /**
     * How deep a chain of inlined helpers may go before the walk calls it a runaway.
     *
     * <p>The corpus does not come close: the longest real chain is a leaf reaching its base's
     * {@code setupAnim}, which reaches a helper of its own. The cap exists so a cycle vanilla does
     * not have cannot become a stack overflow here.
     */
    private static final int MAX_INLINE_DEPTH = 8;

    /**
     * How many instructions one body may take before the walk calls it a runaway.
     *
     * <p>Following jumps means the walk no longer terminates by construction, and a counter this
     * walk misreads would spin rather than refuse. The bound is far above the corpus - the widest
     * unrolled loop is a dragon's twelve segments - so reaching it is a fault rather than a limit.
     */
    private static final int MAX_STEPS = 200_000;

    /**
     * How many undecided branches one extraction may fork on.
     *
     * <p>Each fork runs both arms to the end of the body it is in, so the cost compounds with
     * branches on one path rather than with branches overall. The bound is what keeps a model whose
     * pose is mostly conditional from being paid for in full before it is refused.
     *
     * <p>The widest the corpus reaches is a humanoid, whose two arms each turn on an eleven-constant
     * pose and whose spear arm holds a run of six more branches: seven thousand and a hundred, at
     * which the whole roster still walks in seconds. The bound is set above that rather than at it,
     * being a backstop against a body that does not terminate rather than a budget.
     */
    private static final int MAX_FORKS = 16_384;

    /**
     * How many constants an enum may declare before a split over it is called a runaway.
     *
     * <p>Every arm re-walks the rest of the body it is in, so the cost is the enum's size times what
     * follows the question. The widest one a pose turns on is eleven arm poses; the bound is what
     * keeps a body that happens to ask something of a hundred-constant registry from being paid for
     * before it is refused.
     */
    private static final int MAX_SPLIT_ARMS = 32;

    /**
     * Raised where the walk needs a fact only a specific constant of an enum can supply.
     *
     * <p>Carried rather than refused because the answer is a case split, and the place to take it is
     * not the place that discovers it is needed. A question asked inside a helper is answered by
     * splitting the caller, whose arms each write to a definite bone; splitting the helper would
     * leave two bones in the same place, which is the one shape a pose must never take.
     */
    private static final class NeedsConstant extends IllegalStateException {

        private final transient @NotNull PoseValue.StateRef reference;

        private NeedsConstant(@NotNull PoseValue.StateRef reference) {
            super("turns on the render state's '" + reference.member() + "', which nothing here resolved to a constant");
            this.reference = reference;
        }

    }

    /** Raised where two arms leave things in one place that cannot be chosen between. */
    private static final class UnmergeableArms extends IllegalStateException {

        private UnmergeableArms(@NotNull String message) {
            super(message);
        }

    }

    /**
     * One extraction in progress - what the whole walk needs and what the inlined bodies share.
     *
     * <p>The leaf is carried because a virtual call has to resolve against it rather than against
     * whichever class declared the body being walked: two models sharing an inherited
     * {@code setupAnim} reach different overrides through the same instruction.
     */
    private record Context(
        @NotNull ClassNodeCache cache,
        @NotNull String leaf,
        @NotNull PosePartIndex parts,
        @NotNull Map<String, String> fieldToClip,
        @NotNull Interp<PoseValue> stack,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> pose,
        @NotNull Map<PoseExpr, PoseExpr> assigned,
        @NotNull Set<String> accumulated,
        @NotNull List<PoseClipSite> clipSites,
        @NotNull Map<String, Optional<EnumConstantTable>> enums,
        @NotNull Map<String, PoseValue.EnumConstant> bound,
        @NotNull Map<String, String> referenceTypes,
        @NotNull Map<String, Map<Integer, Integer>> switchMaps,
        int @NotNull [] forks
    ) {

        /** One enum's constants, read once per extraction however many times they are asked for. */
        @NotNull Optional<EnumConstantTable> enumeration(@NotNull String type) {
            return this.enums.computeIfAbsent(type, named -> EnumConstantTable.of(this.cache, named));
        }

        /** The constant a render-state member is standing for on this path, if a split named one. */
        @Nullable PoseValue.EnumConstant boundTo(@NotNull String member) {
            return this.bound.get(member);
        }

        /** One switch table's cases by constant position, read once per extraction. */
        @NotNull Map<Integer, Integer> switchCases(@NotNull PoseValue.SwitchMap map) {
            return this.switchMaps.computeIfAbsent(map.owner() + "." + map.field(), key -> readSwitchMap(this, map));
        }

    }

    /**
     * Extracts one model's pose.
     *
     * @param cache the open client jar
     * @param modelClass the leaf model's internal name
     * @param rootBones the bones this model's mesh names at top level, empty when it names none
     * @param diagnostics the scope findings are recorded against
     * @return the pose, or empty when the body holds a shape this does not model
     */
    public static @NotNull PoseOutcome extract(
        @NotNull ClassNodeCache cache, @NotNull String modelClass, @NotNull Set<String> rootBones,
        @NotNull Diagnostics diagnostics) {

        MethodNode body = findSetupAnim(cache, modelClass);
        // A model whose whole chain declares only the erased override poses nothing: what it
        // inherits is the reset. That is an empty pose rather than a refusal, and the two have to
        // stay distinguishable or a walk that failed reads as a subject that simply holds still.
        if (body == null) return new PoseOutcome.Extracted(
            new PoseProgram(ClassKit.simpleName(modelClass), Map.of(), List.of()));

        Context context = new Context(cache, modelClass, PosePartIndex.of(cache, modelClass, diagnostics),
            ClipBindingResolver.fieldToClip(cache, modelClass),
            Interp.of(DOMAIN, Interp.OnUnknown.SILENT, Interp.Width.BY_OPERANDS),
            new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>(), new ArrayList<>(),
            new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
            new int[1]);

        try {
            walkBody(body, context, 0);
            foldMeshRoot(context, rootBones);
            requireAccumulated(context);
        } catch (IllegalStateException error) {
            // Narrowed to what the walk itself raises. A jar that lost a class raises something
            // else, and shipping that as a pose's reason would turn a broken run into a table
            // saying the model holds still.
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            diagnostics.info("%s not extracted: %s", ClassKit.simpleName(modelClass), reason);
            return new PoseOutcome.Refused(reason);
        }
        return new PoseOutcome.Extracted(new PoseProgram(
            ClassKit.simpleName(modelClass), freeze(context.pose()), List.copyOf(context.clipSites())));
    }

    /**
     * Applies one body, following its control flow wherever the flow is decided.
     *
     * <p>The cursor moves by jump rather than by position, which is what makes a loop unroll: a
     * counter this walk can see is a literal on every pass, so the test that closes the loop decides
     * itself and the body is simply walked again with the next value. The same step resolves a
     * branch whose condition folds, and the two need no separate machinery - a bounded loop and a
     * frozen conditional are one mechanism looked at from two directions.
     *
     * <p>A refusal is a thrown exception rather than a returned flag, because a body reached through
     * three inlined helpers has to abandon all three at once - and because the message is built
     * where the shape was met, which is the only place that knows what it was.
     */
    private static void walkBody(@NotNull MethodNode body, @NotNull Context context, int depth) {
        AbstractInsnNode cursor = body.instructions.getFirst();
        walkFrom(body, cursor != null && AsmWalker.isPseudoNode(cursor) ? AsmWalker.nextReal(cursor) : cursor,
            null, context, depth);
    }

    /**
     * Applies instructions from a point until the body returns, runs out, or reaches {@code stop}.
     *
     * @return {@code true} when it stopped on {@code stop} rather than ending
     */
    private static boolean walkFrom(
        @NotNull MethodNode body, @Nullable AbstractInsnNode start, @Nullable AbstractInsnNode stop,
        @NotNull Context context, int depth) {

        AbstractInsnNode cursor = start;
        int steps = 0;
        while (cursor != null) {
            if (cursor == stop) return true;
            if (++steps > MAX_STEPS)
                throw new IllegalStateException("did not settle within " + MAX_STEPS + " instructions");
            if (isReturn(cursor.getOpcode())) return false;
            if (cursor instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.GOTO) {
                PoseValue tested = context.stack().pop();
                PoseValue against = takesTwoOperands(jump.getOpcode()) ? context.stack().pop() : null;
                Integer decided = decide(jump.getOpcode(), tested, against);
                if (decided == null) {
                    cursor = fork(body, jump, predicate(jump.getOpcode(), tested, against), stop, context, depth);
                    if (cursor == null) return false;
                    continue;
                }
                cursor = AsmWalker.nextReal(decided != 0 ? jump.label : cursor);
                continue;
            }
            // A question only one constant can answer is met inside the instruction that asks it,
            // and answered by re-walking from here once per constant. The state has to be taken
            // before the step rather than after it, because the step that failed may have written
            // channels on its way to the wall.
            Held before = mayAskForAConstant(cursor) ? held(context) : null;
            AbstractInsnNode target;
            try {
                target = step(cursor, context, depth);
            } catch (NeedsConstant needed) {
                if (before == null || context.boundTo(needed.reference.member()) != null) throw needed;
                return split(body, cursor, before, needed.reference, stop, context, depth);
            }
            cursor = AsmWalker.nextReal(target != null ? target : cursor);
        }
        return false;
    }

    /** Whether an instruction is one that can turn out to need a constant it was not given. */
    private static boolean mayAskForAConstant(@NotNull AbstractInsnNode in) {
        return switch (in.getOpcode()) {
            case Opcodes.GETFIELD, Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL,
                 Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE -> true;
            default -> false;
        };
    }

    /**
     * Runs both arms of a branch nothing offline decides, and keeps what they disagree about.
     *
     * <p>Both arms run from the same starting state as far as the point their control flow rejoins,
     * and everything they disagree about there becomes a choice - a channel, a value still on the
     * stack, a local one of them stored. Merging at the join rather than at the end of the body is
     * what keeps a choice around the operand that actually differed instead of over all the
     * arithmetic that happened to follow it: a fish that swims at one speed and flaps at another
     * ends up choosing a speed, not choosing between two whole tail sweeps.
     *
     * <p>A channel one arm writes and the other does not keeps whatever it held before the branch,
     * which is the authored pose when nothing wrote it - the same value an untouched channel already
     * reads, so the merge needs no third case.
     *
     * <p>Arms that never rejoin - one returning early while the other carries on - have no join to
     * merge at, so both run to the end of the body instead and only the pose is merged. That is the
     * same answer, reached the expensive way, and it is why a fork inside an inlined helper is still
     * local: the worst case is the helper's own return.
     *
     * @return where to continue, or {@code null} when both arms ended the body
     */
    private static @Nullable AbstractInsnNode fork(
        @NotNull MethodNode body, @NotNull JumpInsnNode jump, @NotNull PosePredicate condition,
        @Nullable AbstractInsnNode stop, @NotNull Context context, int depth) {

        if (++context.forks()[0] > MAX_FORKS)
            throw new IllegalStateException("forks on more than " + MAX_FORKS + " undecided branches");

        AbstractInsnNode taken = AsmWalker.nextReal(jump.label);
        AbstractInsnNode fallen = AsmWalker.nextReal(jump);
        AbstractInsnNode join = findJoin(body, taken, fallen, stop);

        Held before = held(context);

        boolean takenRejoined = walkFrom(body, taken, join, context, depth);
        Held afterTaken = held(context);

        restore(context, before);
        boolean fallenRejoined = walkFrom(body, fallen, join, context, depth);

        if (join != null && takenRejoined && fallenRejoined) {
            restore(context, joined(condition, afterTaken, held(context), context));
            return join;
        }

        // One arm left the body without meeting the other, so there is no point part way through at
        // which their states line up. Both run to the end instead, where there is: what a body
        // leaves at its own return is as comparable as what it leaves at a join. Re-running the arm
        // that stopped early is what keeps the two comparable at all - merging a partial arm against
        // a finished one would drop every write the partial one had not reached yet.
        //
        // Only when there WAS a point to stop at. A branch whose arms never meet was already run to
        // the end above, twice would be the same walk twice, and each such branch inside another
        // doubles what that costs - which for a body holding a run of them is the difference between
        // walking its tail a few times and walking it thousands.
        Held endedTaken = afterTaken;
        if (join != null) {
            restore(context, before);
            walkFrom(body, taken, null, context, depth);
            endedTaken = held(context);

            restore(context, before);
            walkFrom(body, fallen, null, context, depth);
        }

        // The answer a body computed is left standing at its return, so putting the machine back to
        // before the branch discards it. A helper shaped as a choice between two answers is the
        // commonest thing there is, and one that lost them reported that it had returned nothing.
        // What it left in its own slots is gone either way, which is what withoutLocals says.
        Held endedFallen = held(context);
        restore(context, joined(condition,
            returned(endedTaken, before), returned(endedFallen, before), context));
        return null;
    }

    /** An arm that ran a body to its end, with the slots that body alone could see taken as gone. */
    private static @NotNull Held returned(@NotNull Held ended, @NotNull Held before) {
        return new Held(withoutLocals(ended.machine(), before.machine()),
            ended.pose(), ended.assigned(), ended.clipSites());
    }

    /**
     * Two arms as one - every part of a walk chosen between where the arms disagree.
     *
     * <p>One place rather than one per part, because a part this forgot would not fail: the arms
     * would simply agree about it, having been handed whatever the LAST of them left, and the fork
     * would report a pose that is one arm's answer wearing both arms' name.
     */
    private static @NotNull Held joined(
        @NotNull PosePredicate condition, @NotNull Held taken, @NotNull Held fallen,
        @NotNull Context context) {

        return new Held(
            reconciled(condition, taken.machine(), fallen.machine(), context),
            merge(condition, taken.pose(), fallen.pose()),
            mergeAssigned(condition, taken.assigned(), fallen.assigned()),
            bothPlayed(taken.clipSites(), fallen.clipSites()));
    }

    /**
     * The two arms' machine states as one, escalating rather than refusing when they cannot be.
     *
     * <p>Two arms leaving two different bones in one place is not always a refusal: when the branch
     * that chose between them turns on an enum, it means the choice was taken too deep. An accessor
     * that hands back one of two bones cannot answer with both, but its CALLER can be walked twice,
     * once per constant, and then each walk writes to a bone it has already been given. Carrying the
     * question outward is what turns that from a wall into a case split.
     */
    private static @NotNull Interp.Snapshot<PoseValue> reconciled(
        @NotNull PosePredicate condition, @NotNull Interp.Snapshot<PoseValue> taken,
        @NotNull Interp.Snapshot<PoseValue> fallen, @NotNull Context context) {

        try {
            return reconcile(condition, taken, fallen);
        } catch (UnmergeableArms unmergeable) {
            PoseValue.StateRef asked = unresolved(condition, context);
            if (asked == null) throw unmergeable;
            throw new NeedsConstant(asked);
        }
    }

    /**
     * The enum a condition turns on, when this path has not been told which constant it is.
     *
     * <p>Searched through the whole condition rather than at its head, because the question is not
     * always what a branch was spelled with. A helper handed a boolean its caller computed from an
     * enum tests that boolean against zero, so the branch is a comparison and the enum is an operand
     * of one of its sides - two levels down, under the choice the caller left there. Reading only
     * the head answers nothing for exactly the bodies a split exists to serve.
     *
     * @param condition what the branch turns on
     * @param context the extraction in progress
     * @return the reference to split over, or {@code null} when the condition names none
     */
    private static @Nullable PoseValue.StateRef unresolved(
        @NotNull PosePredicate condition, @NotNull Context context) {

        return switch (condition) {
            case PosePredicate.EnumEq test -> {
                String type = context.referenceTypes().get(test.field());
                yield type == null || context.boundTo(test.field()) != null
                    ? null : new PoseValue.StateRef(test.field(), type);
            }
            case PosePredicate.Not not -> unresolved(not.operand(), context);
            case PosePredicate.Compare compare -> {
                PoseValue.StateRef asked = unresolved(compare.left(), context);
                yield asked != null ? asked : unresolved(compare.right(), context);
            }
            default -> null;
        };
    }

    /** The enum an expression turns on, wherever inside itself it asks about one. */
    private static @Nullable PoseValue.StateRef unresolved(
        @NotNull PoseExpr expr, @NotNull Context context) {

        switch (expr) {
            case PoseExpr.Select select -> {
                PoseValue.StateRef asked = unresolved(select.condition(), context);
                if (asked != null) return asked;
                asked = unresolved(select.whenTrue(), context);
                return asked != null ? asked : unresolved(select.whenFalse(), context);
            }
            case PoseExpr.Op op -> {
                for (PoseExpr operand : op.operands()) {
                    PoseValue.StateRef asked = unresolved(operand, context);
                    if (asked != null) return asked;
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Everything a walk can be put back to - the machine, the pose so far, what the body has
     * written to the render state, and the clips played.
     *
     * @param machine the operand stack and the slot frames
     * @param pose the channels written so far, by bone
     * @param assigned what the body has assigned, by the read each assignment answers
     * @param clipSites the authored clips applied so far
     */
    private record Held(
        @NotNull Interp.Snapshot<PoseValue> machine,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> pose,
        @NotNull Map<PoseExpr, PoseExpr> assigned,
        @NotNull List<PoseClipSite> clipSites
    ) {}

    private static @NotNull Held held(@NotNull Context context) {
        return new Held(context.stack().snapshot(), copy(context.pose()),
            new LinkedHashMap<>(context.assigned()), List.copyOf(context.clipSites()));
    }

    private static void restore(@NotNull Context context, @NotNull Held held) {
        context.stack().restore(held.machine());
        replace(context.pose(), held.pose());
        replaceAssigned(context, held.assigned());
        replaceSites(context, held.clipSites());
    }

    /**
     * Runs the rest of a body once per constant of an enum the render state holds, and keeps what
     * the arms disagree about.
     *
     * <p>Where a two-armed fork turns on a condition, this turns on an IDENTITY: the question was
     * not "is this true" but "which of these is it", and the only way to answer that offline is to
     * try each. Inside an arm the field is a concrete constant, so everything the body asks of it -
     * a field it holds, its position, an identity comparison, which bone an accessor hands back -
     * decides rather than deferring, and the arm walks to its end like any other.
     *
     * <p>Merged n-ways by folding from the last arm backwards, so the guards nest in declaration
     * order and the last constant is what remains when none of the others matched. That is the same
     * shape javac gives a chain of else-ifs and the same shape a switch means.
     *
     * <p><b>An arm that cannot be merged is escalated, not refused.</b> Two arms leaving two
     * different bones in one place is exactly what happens when the accessor being split over is the
     * thing that PICKS the bone - so the split was taken too deep, and the answer is to take it
     * again further out, where each arm writes to a bone it has already chosen. Rethrowing carries
     * that decision to the caller rather than making it here.
     *
     * @return {@code true} when the arms stopped on {@code stop} rather than ending
     */
    private static boolean split(
        @NotNull MethodNode body, @NotNull AbstractInsnNode from, @NotNull Held before,
        @NotNull PoseValue.StateRef reference, @Nullable AbstractInsnNode stop,
        @NotNull Context context, int depth) {

        List<EnumConstantTable.Constant> constants = context.enumeration(reference.type())
            .orElseThrow(() -> new IllegalStateException("turns on the render state's '" + reference.member()
                + "', which is a " + ClassKit.simpleName(reference.type()) + " rather than an enum"))
            .constants();

        if (constants.isEmpty() || constants.size() > MAX_SPLIT_ARMS)
            throw new IllegalStateException("turns on '" + reference.member() + "', which declares "
                + constants.size() + " constants");
        if ((context.forks()[0] += constants.size() - 1) > MAX_FORKS)
            throw new IllegalStateException("forks on more than " + MAX_FORKS + " undecided branches");

        List<Held> arms = new ArrayList<>(constants.size());
        List<Boolean> rejoined = new ArrayList<>(constants.size());
        for (EnumConstantTable.Constant constant : constants) {
            PoseValue.EnumConstant standing = new PoseValue.EnumConstant(reference.type(), constant.name());
            restore(context, before);
            context.stack().restore(standingIn(before.machine(), reference.member(), standing));
            context.bound().put(reference.member(), standing);
            try {
                boolean met = walkFrom(body, from, stop, context, depth);
                rejoined.add(met);
                Held ended = held(context);
                Interp.Snapshot<PoseValue> machine =
                    unbound(ended.machine(), before.machine(), reference, standing);
                arms.add(new Held(met ? machine : withoutLocals(machine, before.machine()),
                    ended.pose(), ended.assigned(), ended.clipSites()));
            } finally {
                context.bound().remove(reference.member());
            }
        }

        try {
            restore(context, merged(constants, reference.member(), arms));
        } catch (UnmergeableArms unmergeable) {
            restore(context, before);
            throw new NeedsConstant(reference);
        }
        return rejoined.contains(Boolean.TRUE);
    }

    /** The arms folded into one, guarded in declaration order with the last constant as the remainder. */
    private static @NotNull Held merged(
        @NotNull List<EnumConstantTable.Constant> constants, @NotNull String member, @NotNull List<Held> arms) {

        Held folded = arms.getLast();
        for (int index = arms.size() - 2; index >= 0; index--) {
            PosePredicate guard = new PosePredicate.EnumEq(member, constants.get(index).name());
            Held arm = arms.get(index);
            folded = new Held(
                reconcile(guard, arm.machine(), folded.machine()),
                merge(guard, arm.pose(), folded.pose()),
                mergeAssigned(guard, arm.assigned(), folded.assigned()),
                bothPlayed(arm.clipSites(), folded.clipSites()));
        }
        return folded;
    }

    /** A machine state with one render-state member already standing for a constant of its enum. */
    private static @NotNull Interp.Snapshot<PoseValue> standingIn(
        @NotNull Interp.Snapshot<PoseValue> machine, @NotNull String member,
        @NotNull PoseValue.EnumConstant standing) {

        List<PoseValue> stack = new ArrayList<>(machine.stack().size());
        for (PoseValue value : machine.stack()) stack.add(standing(value, member, standing));

        Map<Integer, PoseValue> slots = new LinkedHashMap<>();
        machine.slots().forEach((slot, value) -> slots.put(slot, standing(value, member, standing)));

        List<Map<Integer, PoseValue>> frames = new ArrayList<>(machine.frames().size());
        for (Map<Integer, PoseValue> frame : machine.frames()) {
            Map<Integer, PoseValue> replaced = new LinkedHashMap<>();
            frame.forEach((slot, value) -> replaced.put(slot, standing(value, member, standing)));
            frames.add(replaced);
        }
        return new Interp.Snapshot<>(stack, slots, frames, machine.poisoned());
    }

    /**
     * An arm's machine with the split's own binding taken back out of it.
     *
     * <p>A split hands each arm a different constant, so every place the binding reached ends up
     * holding a DIFFERENT value per arm - and those places are not the arms disagreeing about
     * anything, they are the split's own doing. Merging them would find eleven constants where one
     * reference stood and refuse, which is a refusal about the mechanism rather than about the pose.
     *
     * <p>Put back only where the reference stood BEFORE and the constant is still standing there
     * AFTER. Position alone is not enough - the whole point of the split is that the arm goes on to
     * ask the constant something, and the answer lands in the place the reference was loaded into,
     * so restoring by position would throw the answer away and put the question back.
     */
    private static @NotNull Interp.Snapshot<PoseValue> unbound(
        @NotNull Interp.Snapshot<PoseValue> arm, @NotNull Interp.Snapshot<PoseValue> before,
        @NotNull PoseValue.StateRef reference, @NotNull PoseValue.EnumConstant standing) {

        List<PoseValue> stack = new ArrayList<>(arm.stack());
        for (int index = 0; index < stack.size() && index < before.stack().size(); index++)
            if (untouched(before.stack().get(index), stack.get(index), reference, standing))
                stack.set(index, reference);

        Map<Integer, PoseValue> slots = new LinkedHashMap<>(arm.slots());
        before.slots().forEach((slot, was) -> {
            PoseValue now = slots.get(slot);
            if (now != null && untouched(was, now, reference, standing)) slots.put(slot, reference);
        });

        List<Map<Integer, PoseValue>> frames = new ArrayList<>(arm.frames());
        for (int index = 0; index < frames.size() && index < before.frames().size(); index++) {
            Map<Integer, PoseValue> replaced = new LinkedHashMap<>(frames.get(index));
            before.frames().get(index).forEach((slot, was) -> {
                PoseValue now = replaced.get(slot);
                if (now != null && untouched(was, now, reference, standing)) replaced.put(slot, reference);
            });
            frames.set(index, replaced);
        }
        return new Interp.Snapshot<>(stack, slots, frames, arm.poisoned());
    }

    /** Whether one place held the reference before the split and holds only its constant still. */
    private static boolean untouched(
        @NotNull PoseValue was, @NotNull PoseValue now,
        @NotNull PoseValue.StateRef reference, @NotNull PoseValue.EnumConstant standing) {

        return reference.equals(was) && standing.equals(now);
    }

    /**
     * A machine that has run a body to its end, with that body's locals taken as gone.
     *
     * <p>What a body left in its own slots stops existing the moment it returns, so two arms that
     * ran to the end are not disagreeing when their slots differ - they are describing something
     * neither of them still has. An accessor that picks a bone is exactly this: each arm parks a
     * different bone in a local and then returns, and the bone that matters is the one it wrote
     * through, which is already in the pose.
     */
    private static @NotNull Interp.Snapshot<PoseValue> withoutLocals(
        @NotNull Interp.Snapshot<PoseValue> ended, @NotNull Interp.Snapshot<PoseValue> before) {

        return new Interp.Snapshot<>(ended.stack(), before.slots(), ended.frames(), ended.poisoned());
    }

    /**
     * One held value with the split's member standing for its constant.
     *
     * <p>The binding cannot be a rule about future reads alone: a body reads the render state's
     * enums into locals as its first act and asks about them much later, so an arm that only bound
     * what it read after the split would answer the old question with the new constant's guard.
     *
     * <p>Nor about references alone. A caller that has already ASKED the question is holding the
     * answer as a choice rather than as the reference - which is what a helper taking a boolean is
     * handed - so an arm that bound only the places still holding the reference would walk on with
     * the question standing where its own constant answers it.
     */
    private static @NotNull PoseValue standing(
        @NotNull PoseValue value, @NotNull String member, @NotNull PoseValue.EnumConstant standing) {

        if (value instanceof PoseValue.StateRef reference && reference.member().equals(member)) return standing;
        if (!(value instanceof PoseValue.Num number)) return value;
        PoseExpr settled = decided(number.expr(), member, standing.name());
        return settled == number.expr() ? value : num(settled);
    }

    /**
     * An expression with every test of one member against a constant already answered.
     *
     * <p>Sound because the arm IS that constant, and strictly simplifying: an answered test collapses
     * a choice to the arm it picked, so what comes out is a sub-tree of what went in. That is what
     * turns a question the caller left standing into a branch this arm decides rather than forks on.
     *
     * @param expr the expression to settle
     * @param member the render-state member the arm has been told the constant of
     * @param constant the constant it stands for
     * @return the settled expression, or the same object when nothing in it asked
     */
    private static @NotNull PoseExpr decided(
        @NotNull PoseExpr expr, @NotNull String member, @NotNull String constant) {

        switch (expr) {
            case PoseExpr.Op op -> {
                List<PoseExpr> operands = new ArrayList<>(op.operands().size());
                boolean settled = false;
                for (PoseExpr operand : op.operands()) {
                    PoseExpr answered = decided(operand, member, constant);
                    settled |= answered != operand;
                    operands.add(answered);
                }
                return settled ? PoseExpr.Op.of(op.operator(), operands) : op;
            }
            case PoseExpr.Select select -> {
                PosePredicate condition = decided(select.condition(), member, constant);
                PoseExpr whenTrue = decided(select.whenTrue(), member, constant);
                PoseExpr whenFalse = decided(select.whenFalse(), member, constant);
                if (condition instanceof PosePredicate.Constant answered)
                    return answered.value() ? whenTrue : whenFalse;
                return condition == select.condition() && whenTrue == select.whenTrue()
                    && whenFalse == select.whenFalse()
                    ? select : new PoseExpr.Select(condition, whenTrue, whenFalse);
            }
            default -> {
                return expr;
            }
        }
    }

    /** A predicate with every test of one member against a constant already answered. */
    private static @NotNull PosePredicate decided(
        @NotNull PosePredicate predicate, @NotNull String member, @NotNull String constant) {

        switch (predicate) {
            case PosePredicate.EnumEq test -> {
                return test.field().equals(member)
                    ? new PosePredicate.Constant(test.constant().equals(constant)) : test;
            }
            case PosePredicate.Not not -> {
                PosePredicate operand = decided(not.operand(), member, constant);
                return operand == not.operand() ? not : operand.negate();
            }
            case PosePredicate.Compare compare -> {
                PoseExpr left = decided(compare.left(), member, constant);
                PoseExpr right = decided(compare.right(), member, constant);
                return left == compare.left() && right == compare.right()
                    ? compare : PosePredicate.Compare.of(compare.comparison(), left, right);
            }
            default -> {
                return predicate;
            }
        }
    }

    /**
     * Every clip either arm applied, in the order they were reached and without repeats.
     *
     * <p>A clip only one arm plays is still a clip the model can play, and it is recorded without a
     * condition - which is what the clip table already says about every binding it holds, the drive
     * being the whole of its gate. Recording it twice because both arms walked the same tail would
     * be a fact about this walk rather than about the model.
     */
    private static @NotNull List<PoseClipSite> bothPlayed(
        @NotNull List<PoseClipSite> taken, @NotNull List<PoseClipSite> fallen) {

        List<PoseClipSite> out = new ArrayList<>(taken);
        for (PoseClipSite site : fallen)
            if (!out.contains(site)) out.add(site);
        return out;
    }

    private static void replaceSites(@NotNull Context context, @NotNull List<PoseClipSite> sites) {
        context.clipSites().clear();
        context.clipSites().addAll(sites);
    }

    private static void replaceAssigned(@NotNull Context context, @NotNull Map<PoseExpr, PoseExpr> assigned) {
        context.assigned().clear();
        context.assigned().putAll(assigned);
    }

    /**
     * Everything either arm assigned, as the choice between what each left it at.
     *
     * <p>Keyed by the READ each assignment answers rather than by a name, which is what lets one map
     * serve a field of the render state and a field of the model itself: an arm that assigned nothing
     * leaves the read answering itself, so the key IS the default and no case has to know which kind
     * of thing it was keyed on.
     *
     * <p>Asking a map about an expression is only safe because every key here is a LEAF - an input or
     * a carried figure, each one string - so hashing one is a constant rather than a walk of the tree
     * the graph form exists to avoid.
     */
    private static @NotNull Map<PoseExpr, PoseExpr> mergeAssigned(
        @NotNull PosePredicate condition,
        @NotNull Map<PoseExpr, PoseExpr> taken, @NotNull Map<PoseExpr, PoseExpr> fallen) {

        Map<PoseExpr, PoseExpr> out = new LinkedHashMap<>();
        Set<PoseExpr> reads = new LinkedHashSet<>(taken.keySet());
        reads.addAll(fallen.keySet());

        for (PoseExpr read : reads) {
            PoseExpr whenTaken = taken.getOrDefault(read, read);
            PoseExpr whenNot = fallen.getOrDefault(read, read);
            out.put(read, whenTaken.equals(whenNot)
                ? whenTaken : new PoseExpr.Select(condition, whenTaken, whenNot));
        }
        return out;
    }

    /**
     * Where two arms' control flow comes back together.
     *
     * <p>Taken as the earliest instruction both arms can reach, which is the join for anything javac
     * emits from an {@code if}: the two regions are disjoint and everything after the join is
     * common, so the common set's first member is where they meet. Arms that never meet - an early
     * return on one side - answer nothing, and the caller runs them to the end instead.
     *
     * <p>The point an enclosing region is already waiting at is a candidate like any other, and a
     * nested branch whose arms first meet exactly there is the commonest shape there is - javac
     * emits it for an {@code else if} and for either short-circuit operator. Excluding it would
     * leave those arms looking as though they never met.
     */
    private static @Nullable AbstractInsnNode findJoin(
        @NotNull MethodNode body, @Nullable AbstractInsnNode taken, @Nullable AbstractInsnNode fallen,
        @Nullable AbstractInsnNode stop) {

        Set<AbstractInsnNode> fromTaken = reachable(taken, stop);
        Set<AbstractInsnNode> fromFallen = reachable(fallen, stop);

        AbstractInsnNode earliest = null;
        int best = Integer.MAX_VALUE;
        for (AbstractInsnNode candidate : fromTaken) {
            if (!fromFallen.contains(candidate)) continue;
            int index = body.instructions.indexOf(candidate);
            if (index < best) {
                best = index;
                earliest = candidate;
            }
        }
        return earliest;
    }

    /**
     * Every instruction control can get to from a point, following both arms of anything on the way.
     *
     * <p>{@code stop} is reached but not passed: it belongs to the enclosing region rather than to
     * this one, so it counts as somewhere an arm got to while everything beyond it stays out. Both
     * halves matter - dropping it loses the join of every branch that closes exactly where its
     * enclosing branch was already waiting, and expanding through it makes the whole enclosing tail
     * look like this region's own.
     */
    private static @NotNull Set<AbstractInsnNode> reachable(
        @Nullable AbstractInsnNode start, @Nullable AbstractInsnNode stop) {

        Set<AbstractInsnNode> seen = new LinkedHashSet<>();
        Deque<AbstractInsnNode> pending = new ArrayDeque<>();
        if (start != null) pending.add(start);

        while (!pending.isEmpty()) {
            AbstractInsnNode node = pending.removeLast();
            if (!seen.add(node)) continue;
            if (node == stop) continue;
            if (isReturn(node.getOpcode()) || node.getOpcode() == Opcodes.ATHROW) continue;
            if (node instanceof JumpInsnNode jump) {
                AbstractInsnNode target = AsmWalker.nextReal(jump.label);
                if (target != null) pending.add(target);
                if (jump.getOpcode() == Opcodes.GOTO) continue;
            }
            AbstractInsnNode next = AsmWalker.nextReal(node);
            if (next != null) pending.add(next);
        }
        return seen;
    }

    /**
     * The machine state at a join - one value per stack slot and per local, choosing between the
     * arms exactly where they disagree.
     */
    private static @NotNull Interp.Snapshot<PoseValue> reconcile(
        @NotNull PosePredicate condition,
        @NotNull Interp.Snapshot<PoseValue> taken, @NotNull Interp.Snapshot<PoseValue> fallen) {

        if (taken.stack().size() != fallen.stack().size() || taken.frames().size() != fallen.frames().size())
            throw new UnmergeableArms("arms of a branch reach their join holding different things");

        List<PoseValue> stack = new ArrayList<>(taken.stack().size());
        for (int index = 0; index < taken.stack().size(); index++)
            stack.add(choose(condition, taken.stack().get(index), fallen.stack().get(index)));

        Map<Integer, PoseValue> slots = new LinkedHashMap<>(fallen.slots());
        taken.slots().forEach((slot, value) -> slots.merge(slot, value, (mine, theirs) -> choose(condition, theirs, mine)));

        return new Interp.Snapshot<>(stack, slots, taken.frames(), taken.poisoned() && fallen.poisoned());
    }

    /** One value either arm may have left, as a choice when they differ and as itself when they do not. */
    private static @NotNull PoseValue choose(
        @NotNull PosePredicate condition, @NotNull PoseValue taken, @NotNull PoseValue fallen) {

        if (taken.equals(fallen)) return taken;
        if (taken instanceof PoseValue.Num first && fallen instanceof PoseValue.Num second)
            return num(new PoseExpr.Select(condition, first.expr(), second.expr()));
        // A bone, an array or a receiver cannot be chosen between - a pose that wrote through
        // whichever one a branch happened to pick would name the wrong bone rather than blend two.
        //
        // The condition is deliberately not named. It is a tree of records, and a refusal is shipped
        // text rather than a debugger's view: what a reader needs is the mechanism and the two things
        // that met, which is what a wall is looked up by.
        throw new UnmergeableArms("arms of a branch nothing offline decides leave two different"
            + " things in the same place: " + kindOf(taken) + " and " + kindOf(fallen));
    }

    /** Every channel either arm touched, as the choice between what each left it holding. */
    private static @NotNull Map<String, Map<PoseChannel, PoseExpr>> merge(
        @NotNull PosePredicate condition,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> taken,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> fallen) {

        Map<String, Map<PoseChannel, PoseExpr>> out = new LinkedHashMap<>();
        Set<String> bones = new LinkedHashSet<>(taken.keySet());
        bones.addAll(fallen.keySet());

        for (String bone : bones) {
            Map<PoseChannel, PoseExpr> left = taken.getOrDefault(bone, Map.of());
            Map<PoseChannel, PoseExpr> right = fallen.getOrDefault(bone, Map.of());
            Set<PoseChannel> channels = new LinkedHashSet<>(left.keySet());
            channels.addAll(right.keySet());

            Map<PoseChannel, PoseExpr> merged = new EnumMap<>(PoseChannel.class);
            for (PoseChannel channel : channels) {
                // An arm that did not write leaves the authored pose standing, which is exactly what
                // a read of the untouched channel answers.
                PoseExpr whenTaken = left.getOrDefault(channel, new PoseExpr.BoneRead(bone, channel));
                PoseExpr whenNot = right.getOrDefault(channel, new PoseExpr.BoneRead(bone, channel));
                merged.put(channel, whenTaken.equals(whenNot)
                    ? whenTaken : new PoseExpr.Select(condition, whenTaken, whenNot));
            }
            out.put(bone, merged);
        }
        return out;
    }

    /**
     * Which arm a branch takes, when that is decided.
     *
     * @return {@code 1} to jump, {@code 0} to fall through, or {@code null} when nothing decides it
     */
    private static @Nullable Integer decide(int opcode, @NotNull PoseValue tested, @Nullable PoseValue against) {
        if (opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE) {
            // Two constants of an enum are the same object exactly when they are the same constant,
            // so a comparison between them is decided rather than deferred. Without this a body that
            // has been told which constant it is looking at still branches on the answer.
            if (!(tested instanceof PoseValue.EnumConstant right)
                || !(against instanceof PoseValue.EnumConstant left)) return null;
            boolean same = left.type().equals(right.type()) && left.name().equals(right.name());
            return same == (opcode == Opcodes.IF_ACMPEQ) ? 1 : 0;
        }
        if (against != null) {
            Integer right = literalInt(tested);
            Integer left = literalInt(against);
            if (left == null || right == null) return null;
            return Interp.evaluateIntComparison(opcode, left, right) ? 1 : 0;
        }
        Integer value = literalInt(tested);
        if (value == null) return null;
        return Interp.evaluateIntComparison(opcode, value, 0) ? 1 : 0;
    }

    /** Whether a jump opcode compares two things rather than one thing against zero or null. */
    private static boolean takesTwoOperands(int opcode) {
        return opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE
            || opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE;
    }

    /** What a branch turns on, said in terms of the render state rather than of the stack. */
    private static @NotNull PosePredicate predicate(int opcode, @NotNull PoseValue tested, @Nullable PoseValue against) {
        if (opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE) {
            PosePredicate same = enumEquality(tested, against);
            // The jump is taken when they are equal, or when they are not; the predicate names the
            // arm the jump goes to, so the negation belongs here rather than at the merge.
            return opcode == Opcodes.IF_ACMPEQ ? same : same.negate();
        }
        if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) {
            // Whether a reference is there at all, which a body asks about a component an item may
            // simply not carry. Only a reference the render state was reached through can be asked:
            // anything else is absent or present for reasons this walk did not follow, and answering
            // one of those would guard a term on a fact nothing supplies.
            if (!(tested instanceof PoseValue.StateRef reference))
                throw new IllegalStateException("asks whether " + kindOf(tested)
                    + " is there, which this walk cannot decide");
            PosePredicate present = new PosePredicate.Has(reference.member());
            return opcode == Opcodes.IFNONNULL ? present : present.negate();
        }

        PosePredicate.Comparison comparison = comparisonOf(opcode);
        if (comparison == null)
            throw new IllegalStateException("branches on a reference, which this walk cannot decide");

        if (against != null) {
            if (!(tested instanceof PoseValue.Num right) || !(against instanceof PoseValue.Num left))
                throw new IllegalStateException(undecidable(tested instanceof PoseValue.Num ? against : tested));
            return PosePredicate.Compare.of(comparison, left.expr(), right.expr());
        }
        // A float test arrives as a three-way compare the branch reads the sign of, so the operands
        // it actually compared are the ones to name.
        if (tested instanceof PoseValue.Comparison held)
            return PosePredicate.Compare.of(comparison, held.left(), held.right());
        if (tested instanceof PoseValue.Num value)
            return PosePredicate.Compare.of(comparison, value.expr(), PoseExpr.Const.of(0));
        throw new IllegalStateException(undecidable(tested));
    }

    /**
     * A reference comparison, which in a pose body is always an enum against one of its constants.
     *
     * <p>Nothing else is compared by identity here: a pose asks which arm is swinging, what an arm
     * is holding, which way a thing faces. Anything else reaching this is a shape worth meeting
     * loudly rather than a comparison to approximate.
     */
    private static @NotNull PosePredicate enumEquality(@NotNull PoseValue tested, @Nullable PoseValue against) {
        if (tested instanceof PoseValue.EnumConstant constant && against instanceof PoseValue.StateRef reference)
            return matching(reference, constant);
        if (tested instanceof PoseValue.StateRef reference && against instanceof PoseValue.EnumConstant constant)
            return matching(reference, constant);
        throw new IllegalStateException("compares two references that are not an enum and one of its constants");
    }

    private static @NotNull PosePredicate matching(
        @NotNull PoseValue.StateRef reference, @NotNull PoseValue.EnumConstant constant) {

        if (!reference.type().equals(constant.type()))
            throw new IllegalStateException("compares " + ClassKit.simpleName(reference.type())
                + " against a constant of " + ClassKit.simpleName(constant.type()));
        return new PosePredicate.EnumEq(reference.member(), constant.name());
    }

    private static boolean isPrimitive(@NotNull String descriptor) {
        return descriptor.length() == 1;
    }

    /** The internal name a reference descriptor names, or the descriptor itself when it names none. */
    private static @NotNull String referenced(@NotNull String descriptor) {
        return descriptor.startsWith("L") && descriptor.endsWith(";")
            ? descriptor.substring(1, descriptor.length() - 1) : descriptor;
    }

    private static @Nullable PosePredicate.Comparison comparisonOf(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ, Opcodes.IF_ICMPEQ -> PosePredicate.Comparison.EQ;
            case Opcodes.IFNE, Opcodes.IF_ICMPNE -> PosePredicate.Comparison.NE;
            case Opcodes.IFLT, Opcodes.IF_ICMPLT -> PosePredicate.Comparison.LT;
            case Opcodes.IFGE, Opcodes.IF_ICMPGE -> PosePredicate.Comparison.GE;
            case Opcodes.IFGT, Opcodes.IF_ICMPGT -> PosePredicate.Comparison.GT;
            case Opcodes.IFLE, Opcodes.IF_ICMPLE -> PosePredicate.Comparison.LE;
            default -> null;
        };
    }

    private static @NotNull Map<String, Map<PoseChannel, PoseExpr>> copy(
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> pose) {

        Map<String, Map<PoseChannel, PoseExpr>> out = new LinkedHashMap<>();
        pose.forEach((bone, channels) -> out.put(bone, new EnumMap<>(channels)));
        return out;
    }

    /**
     * Puts a pose back to what a snapshot holds, taking a fresh channel map per bone.
     *
     * <p>The copy is the whole of it. A channel map handed over rather than copied would be the
     * SAME map the snapshot holds, so the next write through the pose would land in the snapshot
     * too - and a fork's second arm, or a split's third, would start from what the one before it
     * wrote rather than from where they all branched.
     */
    private static void replace(
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> target,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> source) {

        target.clear();
        source.forEach((bone, channels) -> target.put(bone, new EnumMap<>(channels)));
    }

    /** Whether an opcode ends the body being walked, leaving any answer it has on the stack. */
    private static boolean isReturn(int opcode) {
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
    }

    /**
     * Applies one instruction, handing the chassis everything that is not a field or a call.
     */
    private static @Nullable AbstractInsnNode step(
        @NotNull AbstractInsnNode in, @NotNull Context context, int depth) {

        Interp<PoseValue> stack = context.stack();

        if (in instanceof JumpInsnNode jump) {
            // Conditional jumps never reach here - the walk decides or forks on them itself.
            if (jump.getOpcode() == Opcodes.GOTO) return jump.label;
            throw new IllegalStateException("branches on a reference, which this walk cannot decide");
        }
        if (in.getOpcode() == Opcodes.TABLESWITCH || in.getOpcode() == Opcodes.LOOKUPSWITCH)
            return switchArm(in, context);
        if (in instanceof IincInsnNode increment) {
            advance(stack, increment);
            return null;
        }

        switch (in.getOpcode()) {
            case Opcodes.GETFIELD -> readField((FieldInsnNode) in, context);
            case Opcodes.PUTFIELD -> writeField((FieldInsnNode) in, context);
            case Opcodes.GETSTATIC -> {
                // An enum constant is the static a pose body reads for its own sake: it is compared
                // against a render-state field to ask which arm, which pose, which way round. The
                // rest are read to be handed on as the name of what to fetch, so they are carried
                // as their coordinate rather than lost - a component key that arrived as an unknown
                // would leave the fetch it names unnameable.
                FieldInsnNode constant = (FieldInsnNode) in;
                if (("L" + constant.owner + ";").equals(constant.desc))
                    stack.push(new PoseValue.EnumConstant(constant.owner, constant.name));
                else if (SWITCH_MAP_DESCRIPTOR.equals(constant.desc) && constant.name.startsWith(SWITCH_MAP_PREFIX))
                    stack.push(new PoseValue.SwitchMap(constant.owner, constant.name));
                else stack.push(new PoseValue.StaticRef(constant.owner, constant.name));
            }
            case Opcodes.ARRAYLENGTH -> {
                if (!(stack.pop() instanceof PoseValue.PartArray array))
                    throw new IllegalStateException("measures something that is not an array of bones");
                stack.push(num(PoseExpr.Const.of(context.parts().arrayBones()
                    .getOrDefault(array.field(), List.of()).size())));
            }
            case Opcodes.AALOAD -> {
                PoseValue index = stack.pop();
                PoseValue array = stack.pop();
                stack.push(element(context.parts(), array, index));
            }
            // The chassis leaves these to the walk site, and leaving them unread is not neutral: an
            // opcode that neither pops nor pushes leaves the array where a number should be, and the
            // arithmetic after it goes on to compute something well formed out of the index.
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
                 Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> {
                PoseValue index = stack.pop();
                PoseValue array = stack.pop();
                stack.push(num(array instanceof PoseValue.SwitchMap map
                    ? PoseExpr.Const.of(switchCase(context, map, index))
                    : numberElement(array, index)));
            }
            case Opcodes.CHECKCAST -> { /* a cast changes the type, never the value */ }
            case Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE ->
                call((MethodInsnNode) in, context, depth);
            case Opcodes.AASTORE, Opcodes.NEW, Opcodes.ANEWARRAY ->
                throw new IllegalStateException("allocates while posing, which this walk does not model");
            case Opcodes.INVOKEDYNAMIC -> callSite((InvokeDynamicInsnNode) in, context);
            default -> stack.step(in);
        }
        return null;
    }

    /**
     * Which arm a switch takes, which it decides itself once its selector is a literal.
     *
     * <p>The same mechanism as a frozen conditional and a bounded loop, read a third way: a switch
     * needs no detector either, only a selector the walk can see. What makes one visible is the case
     * split - inside an arm the enum is a constant, so its position is a number and the table picks
     * an arm the way the running client would.
     */
    private static @NotNull AbstractInsnNode switchArm(@NotNull AbstractInsnNode in, @NotNull Context context) {
        Integer key = literalInt(context.stack().pop());
        if (key == null) throw new IllegalStateException("switches on a value, which this walk does not decide");
        if (in instanceof TableSwitchInsnNode table) {
            int index = key - table.min;
            return index >= 0 && index < table.labels.size() ? table.labels.get(index) : table.dflt;
        }
        LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) in;
        int at = lookup.keys.indexOf(key);
        return at >= 0 ? lookup.labels.get(at) : lookup.dflt;
    }

    /**
     * Why a branch could not be turned into a predicate, naming what it was handed.
     *
     * <p>A branch this walk cannot phrase is a shape to go and look at, so the message says which
     * kind of value arrived rather than only that one did - the difference between "there is a
     * branch here" and "a call this walk models as nothing ended up being tested".
     */
    private static @NotNull String undecidable(@NotNull PoseValue tested) {
        return "branches on " + kindOf(tested);
    }

    /** What kind of thing a value is, said the way a refusal wants to name it. */
    private static @NotNull String kindOf(@NotNull PoseValue tested) {
        return switch (tested) {
            case PoseValue.Opaque ignored -> "a value this walk models as nothing";
            case PoseValue.StateRef reference -> "the render state's '" + reference.member() + "'";
            case PoseValue.EnumConstant constant ->
                "the constant " + ClassKit.simpleName(constant.type()) + "." + constant.name();
            case PoseValue.Part part -> "the bone '" + part.bone() + "'";
            case PoseValue.MeshRoot ignored -> "the container this mesh flattened away";
            case PoseValue.PartArray array -> "the array '" + array.field() + "'";
            case PoseValue.StateArray array -> "the whole of the render state's '" + array.member() + "'";
            case PoseValue.StaticRef named ->
                "the static " + ClassKit.simpleName(named.owner()) + "." + named.name();
            case PoseValue.Lambda lambda ->
                "the lambda " + ClassKit.simpleName(lambda.owner()) + "." + lambda.name();
            case PoseValue.SwitchMap map -> "the switch table '" + map.field() + "'";
            case PoseValue.Clip clip -> "the clip '" + clip.coordinate() + "'";
            case PoseValue.Comparison ignored -> "a comparison in a place one cannot be phrased";
            case PoseValue.Num ignored -> "a number this walk cannot phrase a test of";
        };
    }

    /** An integral literal, or {@code null} when the value is not one this walk can read. */
    private static @Nullable Integer literalInt(@NotNull PoseValue value) {
        if (!(value instanceof PoseValue.Num number) || !(number.expr() instanceof PoseExpr.Const literal))
            return null;
        return (int) literal.value();
    }

    /** Steps a counter the walk can see; one it cannot is what makes a loop refuse rather than spin. */
    private static void advance(@NotNull Interp<PoseValue> stack, @NotNull IincInsnNode increment) {
        PoseValue held = stack.slot(increment.var);
        Integer value = held == null ? null : literalInt(held);
        if (value == null) throw new IllegalStateException("steps a counter it cannot follow");
        stack.store(increment.var, num(PoseExpr.Const.of(value + increment.incr)));
    }

    /** A field read: a bone, an array of bones, a channel's current value, or an input. */
    private static void readField(@NotNull FieldInsnNode field, @NotNull Context context) {
        Interp<PoseValue> stack = context.stack();
        PosePartIndex parts = context.parts();
        Map<String, Map<PoseChannel, PoseExpr>> pose = context.pose();
        PoseValue receiver = stack.pop();

        if (VanillaSourceClasses.Types.MODEL_PART.equals(field.owner)) {
            PoseChannel channel = PoseChannel.ofField(field.name);
            if (channel == null) throw new IllegalStateException("reads ModelPart." + field.name + ", which is not a channel");
            if (receiver instanceof PoseValue.MeshRoot) {
                stack.push(num(current(pose, MESH_ROOT, channel)));
                return;
            }
            if (!(receiver instanceof PoseValue.Part part))
                throw new IllegalStateException("reads a channel off a bone it could not name");
            stack.push(num(current(pose, part.bone(), channel)));
            return;
        }
        if (partDesc().equals(field.desc)) {
            String bone = parts.boneOf(field.name);
            // The root every model inherits, which is a bone exactly when a constructor narrowed it
            // to one. Where none did it is the baked mesh root, a container the mesh flattens into
            // several parented at nothing - so it is carried as the container it is, and what a
            // write to it means is settled at the end, against the bones it holds.
            if (bone == null && isModelRoot(context, field)) {
                if (parts.rootBone().isEmpty()) {
                    stack.push(new PoseValue.MeshRoot());
                    return;
                }
                bone = parts.rootBone().orElseThrow();
            }
            if (bone == null)
                throw new IllegalStateException("uses part field '" + field.name + "', which no constructor binds");
            stack.push(new PoseValue.Part(bone));
            return;
        }
        if (partArrayDesc().equals(field.desc)) {
            stack.push(new PoseValue.PartArray(field.name));
            return;
        }
        if (clipDesc().equals(field.desc)) {
            String clip = context.fieldToClip().get(field.name);
            if (clip == null)
                throw new IllegalStateException("plays through '" + field.name + "', which no constructor binds");
            stack.push(new PoseValue.Clip(clip));
            return;
        }
        if (field.owner.startsWith(VanillaSourceClasses.Types.ENTITY_RENDER_STATE_PACKAGE)) {
            // A render state holds numbers and it holds references, and only the numbers are
            // arithmetic on. Reading a reference as though it were a float would put a thing with no
            // numeric value into an expression and only find out at the sink.
            //
            // A number the body has assigned reads that assignment, the way a written channel reads
            // its write; one it has not reads what the render state carries.
            if (isPrimitive(field.desc)) stack.push(num(assigned(context, new PoseExpr.Input(field.name))));
            // An array is neither: it answers no question and matches no constant, and calling one a
            // reference on the strength of not being primitive is how a float array ends up being
            // compared against an enum constant.
            else if (field.desc.startsWith("[")) stack.push(new PoseValue.StateArray(field.name));
            else {
                // Remembered by name, because a predicate names the member alone and an escalation
                // has to be able to say which enum that member is one of.
                context.referenceTypes().putIfAbsent(field.name, referenced(field.desc));
                PoseValue.EnumConstant standing = context.boundTo(field.name);
                stack.push(standing != null ? standing : new PoseValue.StateRef(field.name, referenced(field.desc)));
            }
            return;
        }
        if (receiver instanceof PoseValue.EnumConstant constant) {
            stack.push(num(constantField(context, constant, field)));
            return;
        }
        // Reading a field OF a reference the render state holds is a question only one constant can
        // answer, and every enum accessor is exactly that read.
        if (receiver instanceof PoseValue.StateRef reference) throw new NeedsConstant(reference);
        // A figure the model keeps for itself, which is not a function of anything the render state
        // carries. Answered as the carried figure it is; what makes that safe is the WRITE, which
        // only ever adds to what the field already held.
        if (isModelLogic(field.owner) && isPrimitive(field.desc)) {
            stack.push(num(assigned(context, new PoseExpr.Carried(field.name))));
            return;
        }
        stack.push(OPAQUE);
    }

    /** What a read answers - the assignment the path made to it, or the read itself. */
    private static @NotNull PoseExpr assigned(@NotNull Context context, @NotNull PoseExpr read) {
        return context.assigned().getOrDefault(read, read);
    }

    /**
     * Whether a write to a model's own field only ADDS to what that field already held.
     *
     * <p>The one shape that leaves the field's starting point standing as a term of its own, which is
     * what makes naming that starting point an input honest: everything else about the value is the
     * model's own arithmetic, so a caller who has nothing to supply supplies nothing and gets the
     * frame vanilla computes the first time it poses the model.
     *
     * <p>Exactly one side, and the other side must not reach it either - a field built out of its own
     * value twice is a multiple of it rather than a step along it, and would read as a step.
     *
     * @param written what the body is leaving the field holding
     * @param held what the field held before the write
     * @return whether the write is a step along the field rather than a fresh value for it
     */
    private static boolean accumulates(@NotNull PoseExpr written, @NotNull PoseExpr held) {
        if (!(written instanceof PoseExpr.Op op) || op.operands().size() != 2) return false;
        if (op.operator() != PoseOperator.ADD && op.operator() != PoseOperator.SUB) return false;

        PoseExpr left = op.operands().getFirst();
        PoseExpr right = op.operands().getLast();
        if (left.equals(held)) return !mentions(right, held);
        return op.operator() == PoseOperator.ADD && right.equals(held) && !mentions(left, held);
    }

    /** Whether an expression reaches another anywhere inside itself. */
    private static boolean mentions(@NotNull PoseExpr expr, @NotNull PoseExpr sought) {
        if (expr.equals(sought)) return true;
        return switch (expr) {
            case PoseExpr.Op op -> op.operands().stream().anyMatch(operand -> mentions(operand, sought));
            case PoseExpr.Select select ->
                mentions(select.whenTrue(), sought) || mentions(select.whenFalse(), sought);
            default -> false;
        };
    }

    /**
     * Refuses a pose naming a figure the model carries but never stepped along.
     *
     * <p>A field only ever read is one a constructor settled, so naming it an input would ask a
     * caller for a number the model already has and would answer a different pose when they guessed.
     * Only a field the body accumulates has a starting point to be handed.
     */
    private static void requireAccumulated(@NotNull Context context) {
        for (Map<PoseChannel, PoseExpr> channels : context.pose().values())
            for (PoseExpr expr : channels.values()) {
                List<PoseExpr.Carried> named = new ArrayList<>();
                carried(expr, named, Collections.newSetFromMap(new IdentityHashMap<>()));
                for (PoseExpr.Carried figure : named)
                    if (!context.accumulated().contains(figure.field()))
                        throw new IllegalStateException("poses off " + figure.field()
                            + ", which it keeps for itself and never steps along");
            }
    }

    /** Every carried figure an expression names, walking the graph once rather than each of its paths. */
    private static void carried(
        @NotNull PoseExpr expr, @NotNull List<PoseExpr.Carried> out, @NotNull Set<Object> walked) {

        if (!walked.add(expr)) return;
        switch (expr) {
            case PoseExpr.Carried figure -> out.add(figure);
            case PoseExpr.Op op -> op.operands().forEach(operand -> carried(operand, out, walked));
            case PoseExpr.Select select -> {
                carried(select.whenTrue(), out, walked);
                carried(select.whenFalse(), out, walked);
            }
            default -> { /* a leaf names none */ }
        }
    }

    /**
     * Whether a part field is the root every model inherits rather than one the model declares.
     *
     * <p>Decided by resolving the field to the class that DECLARES it, never by the class named on
     * the instruction: javac writes the leaf as the owner of an inherited field's reference, so the
     * name there is the class that used the field and says nothing about which field it is. Reading
     * the owner instead leaves the inherited root looking like a field nobody bound.
     *
     * @param context the extraction in progress
     * @param field the field being read
     * @return whether the field resolves to the root a model is built around
     */
    private static boolean isModelRoot(@NotNull Context context, @NotNull FieldInsnNode field) {
        ClassNode declaring = ClassKit.walkSuperChainUntil(context.cache(), field.owner,
            node -> ClassKit.findField(node, field.name, field.desc) != null);
        return declaring != null && RESET_ROOTS.contains(declaring.name);
    }

    /** A field write. Only a channel of a bone is one; anything else the walk refuses. */
    private static void writeField(@NotNull FieldInsnNode field, @NotNull Context context) {
        Interp<PoseValue> stack = context.stack();
        Map<String, Map<PoseChannel, PoseExpr>> pose = context.pose();
        PoseValue value = stack.pop();
        PoseValue receiver = stack.pop();

        // A body may assign a number to the render state it was handed and read it straight back,
        // which is not a dependence on anything: the value is the body's own, and the read after it
        // answers the assignment the way a channel answers its last write. Held per path like the
        // pose, so an arm that assigns and an arm that does not stay two different states - and a
        // reference is refused, because what a body does with one of those is where the interesting
        // part of it is and an assignment would hide that.
        if (field.owner.startsWith(VanillaSourceClasses.Types.ENTITY_RENDER_STATE_PACKAGE)
            && isPrimitive(field.desc)) {

            if (!(value instanceof PoseValue.Num written))
                throw new IllegalStateException("assigns the render state's '" + field.name
                    + "' a value it could not model");
            context.assigned().put(new PoseExpr.Input(field.name), written.expr());
            return;
        }

        // A figure the model keeps for itself, which is a function of how many times it has been
        // posed rather than of anything the render state carries. Named as an input rather than
        // refused, on the same ground as a figure that lives in server data: binding it to nothing
        // reproduces a real vanilla frame - the first one after the model was built - bit for bit.
        //
        // Only an ACCUMULATION, which is the whole of what makes it nameable. A field a body assigns
        // outright, or builds out of its own value by anything other than adding to it, is not a
        // figure with a starting point a caller can be handed.
        if (isModelLogic(field.owner) && isPrimitive(field.desc)) {
            PoseExpr carried = new PoseExpr.Carried(field.name);
            PoseExpr held = assigned(context, carried);
            if (!(value instanceof PoseValue.Num written) || !accumulates(written.expr(), held))
                throw new IllegalStateException("writes " + ClassKit.simpleName(field.owner) + "."
                    + field.name + ", so its pose is not a function of its inputs alone");
            context.assigned().put(carried, written.expr());
            context.accumulated().add(field.name);
            return;
        }

        if (!VanillaSourceClasses.Types.MODEL_PART.equals(field.owner))
            throw new IllegalStateException("writes " + ClassKit.simpleName(field.owner) + "." + field.name
                + ", so its pose is not a function of its inputs alone");

        PoseChannel channel = PoseChannel.ofField(field.name);
        if (channel == null) throw new IllegalStateException("writes ModelPart." + field.name + ", which is not a channel");
        String bone = receiver instanceof PoseValue.MeshRoot ? MESH_ROOT
            : receiver instanceof PoseValue.Part part ? part.bone() : null;
        if (bone == null)
            throw new IllegalStateException("writes a channel to a bone it could not name");
        if (!(value instanceof PoseValue.Num written))
            throw new IllegalStateException("writes " + bone + "." + channel.token() + " a value it could not model");

        pose.computeIfAbsent(bone, named -> new EnumMap<>(PoseChannel.class)).put(channel, written.expr());
    }

    /** One element of an array of bones, which needs the index to have folded to a literal. */
    private static @NotNull PoseValue element(
        @NotNull PosePartIndex parts, @NotNull PoseValue array, @NotNull PoseValue index) {

        if (!(array instanceof PoseValue.PartArray parked)) return OPAQUE;
        if (!(index instanceof PoseValue.Num number) || !(number.expr() instanceof PoseExpr.Const literal))
            throw new IllegalStateException("indexes '" + parked.field() + "' with something that is not a literal");
        String bone = parts.boneOf(parked.field(), (int) literal.value());
        if (bone == null)
            throw new IllegalStateException("indexes '" + parked.field() + "' past what its constructor allocated");
        return new PoseValue.Part(bone);
    }

    /**
     * What one enum constant's field holds, which its own declaration decided once and for all.
     *
     * <p>This is the whole of what an enum's accessors are: a body that reads a field a constructor
     * bound to a literal. Answering the field answers every method that returns it, through the
     * inliner rather than through a table of method names - a walk that knows which constant it is
     * looking at can simply read it.
     */
    private static @NotNull PoseExpr constantField(
        @NotNull Context context, @NotNull PoseValue.EnumConstant constant, @NotNull FieldInsnNode field) {

        String named = ClassKit.simpleName(constant.type()) + "." + constant.name();
        EnumConstantTable.Constant held = context.enumeration(constant.type())
            .flatMap(table -> table.byName(constant.name()))
            .orElseThrow(() -> new IllegalStateException("reads " + named + ", which declares no such constant"));

        Double value = held.fields().get(field.name);
        if (value == null)
            throw new IllegalStateException("reads " + named + "." + field.name
                + ", which its declaration does not settle on a number");
        return switch (field.desc) {
            case "F" -> PoseExpr.Const.of((float) (double) value);
            case "D" -> PoseExpr.Const.of((double) value);
            case "I", "Z", "B", "C", "S" -> PoseExpr.Const.of((int) (double) value);
            default -> throw new IllegalStateException("reads " + named + "." + field.name
                + ", which is not a number a pose can carry");
        };
    }

    /**
     * A constant's declared position, which is what a switch over an enum actually dispatches on.
     *
     * <p>Answered here rather than by walking {@code ordinal}, whose body is declared in the JDK and
     * is in no client jar. A walk that tried to enter it would report a missing body and say nothing
     * about the enum.
     */
    private static void constantOrdinal(@NotNull MethodInsnNode call, @NotNull Context context) {
        PoseValue receiver = context.stack().pop();
        if (receiver instanceof PoseValue.StateRef reference) throw new NeedsConstant(reference);
        if (!(receiver instanceof PoseValue.EnumConstant constant))
            throw new IllegalStateException("asks the position of something this walk has not resolved to a constant");
        EnumConstantTable.Constant held = context.enumeration(constant.type())
            .flatMap(table -> table.byName(constant.name()))
            .orElseThrow(() -> new IllegalStateException("asks the position of "
                + ClassKit.simpleName(constant.type()) + "." + constant.name() + ", which declares no such constant"));
        context.stack().push(num(PoseExpr.Const.of(held.ordinal())));
    }

    /**
     * One switch table, by constant position, read off the initialiser that fills it.
     *
     * <p>Filled one constant at a time, each entry naming the table it writes to, the constant it is
     * about and the case it answers - so a class holding several tables is read by taking only the
     * entries whose write names this one. The position comes from the enum's own declaration rather
     * than from the entry, which asks for it through a call there is no reason to walk.
     */
    private static @NotNull Map<Integer, Integer> readSwitchMap(
        @NotNull Context context, @NotNull PoseValue.SwitchMap map) {

        Map<String, Integer> byConstant = new LinkedHashMap<>();
        String[] table = new String[1];
        String[] type = new String[1];
        FieldInsnNode[] constant = new FieldInsnNode[1];
        Integer[] answered = new Integer[1];

        AsmWalker.clinit(context.cache(), map.owner())
            .on(Insn.ofType(FieldInsnNode.class), field -> {
                if (field.getOpcode() != Opcodes.GETSTATIC) return;
                if (SWITCH_MAP_DESCRIPTOR.equals(field.desc)) {
                    table[0] = field.name;
                    constant[0] = null;
                    answered[0] = null;
                } else if (("L" + field.owner + ";").equals(field.desc)) constant[0] = field;
            })
            .on(Insn.ofType(InsnNode.class), in -> {
                if (in.getOpcode() != Opcodes.IASTORE) {
                    Integer literal = AsmWalker.intLiteral(in);
                    if (literal != null) answered[0] = literal;
                    return;
                }
                if (map.field().equals(table[0]) && constant[0] != null && answered[0] != null) {
                    type[0] = constant[0].owner;
                    byConstant.putIfAbsent(constant[0].name, answered[0]);
                }
                constant[0] = null;
                answered[0] = null;
            })
            .on(Insn.ofType(IntInsnNode.class), in -> {
                Integer literal = AsmWalker.intLiteral(in);
                if (literal != null) answered[0] = literal;
            })
            .run();

        if (type[0] == null)
            throw new IllegalStateException("reads the switch table '" + map.field() + "', which nothing fills");
        EnumConstantTable declared = context.enumeration(type[0])
            .orElseThrow(() -> new IllegalStateException("reads a switch table over "
                + ClassKit.simpleName(type[0]) + ", which is not an enum"));

        Map<Integer, Integer> byOrdinal = new LinkedHashMap<>();
        byConstant.forEach((name, answer) ->
            declared.byName(name).ifPresent(one -> byOrdinal.put(one.ordinal(), answer)));
        return Map.copyOf(byOrdinal);
    }

    /**
     * The case number a switch over an enum uses for one constant.
     *
     * <p>Read out of the class javac parks the table in, because the table is written there rather
     * than being derivable: it exists precisely so that a switch does not depend on the positions of
     * the constants, which are not stable across a recompile of the enum on its own.
     *
     * <p>A constant the switch does not name answers zero, which is what the array holds for it and
     * therefore what sends it to the default arm - the same answer the running client gets.
     */
    private static int switchCase(
        @NotNull Context context, @NotNull PoseValue.SwitchMap map, @NotNull PoseValue index) {

        Integer ordinal = literalInt(index);
        if (ordinal == null)
            throw new IllegalStateException("reads a switch table at a position this walk did not settle");
        return context.switchCases(map).getOrDefault(ordinal, 0);
    }

    /** One element of an array the render state holds, which needs the index to have folded. */
    private static @NotNull PoseExpr numberElement(@NotNull PoseValue array, @NotNull PoseValue index) {
        if (!(array instanceof PoseValue.StateArray held))
            throw new IllegalStateException("indexes something that is not an array the render state holds");
        if (!(index instanceof PoseValue.Num number) || !(number.expr() instanceof PoseExpr.Const literal))
            throw new IllegalStateException("indexes '" + held.member() + "' with something that is not a literal");
        return new PoseExpr.InputElement(held.member(), (int) literal.value());
    }

    /**
     * A lambda a call site builds, held as the body it will run rather than entered here.
     *
     * <p>Refused rather than passed over when it is anything else. An opcode the chassis does not
     * name has no stack effect, and a call site that pushed nothing where a function was expected
     * leaves every argument after it one place out - which builds a well formed pose out of the
     * wrong operands rather than failing.
     */
    private static void callSite(@NotNull InvokeDynamicInsnNode indy, @NotNull Context context) {
        if (!AsmWalker.isLambdaInvokeDynamic(indy))
            throw new IllegalStateException("builds a call site, which this walk does not model");

        Handle target = AsmWalker.extractLambdaHandle(indy);
        if (target == null)
            throw new IllegalStateException("builds a lambda whose body this walk could not name");
        if (target.getTag() != STATIC_HANDLE)
            throw new IllegalStateException("builds a lambda over " + ClassKit.simpleName(target.getOwner())
                + "." + target.getName() + ", which is not a static body");

        List<PoseValue> captured = context.stack().popArguments(ClassKit.argTypes(indy.desc).length);
        context.stack().push(new PoseValue.Lambda(
            target.getOwner(), target.getName(), target.getDesc(), List.copyOf(captured)));
    }

    /** A call: arithmetic by another name, a bone mutated through a method, the reset, or a body to inline. */
    private static void call(@NotNull MethodInsnNode call, @NotNull Context context, int depth) {
        Interp<PoseValue> stack = context.stack();

        if (BOXES.contains(key(call))) {
            // Both boxes take exactly one operand and answer the same number, so the machine is
            // already right and there is nothing to apply. Asserted rather than assumed: a row whose
            // shape is not that would otherwise leave the stack one place out and say nothing.
            if (ClassKit.argTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1) != 1)
                throw new IllegalStateException("boxes through " + call.name + ", which does not take one operand");
            return;
        }

        PoseOperator operator = CALLS.get(key(call));
        if (operator != null) {
            List<PoseValue> arguments = stack.popArguments(operator.arity());
            List<PoseExpr> operands = new ArrayList<>(arguments.size());
            for (PoseValue argument : arguments) {
                if (!(argument instanceof PoseValue.Num number))
                    throw new IllegalStateException("calls " + call.name + " on a value it could not model");
                operands.add(number.expr());
            }
            stack.push(num(PoseExpr.Op.of(operator, operands)));
            return;
        }

        if (STATE_QUESTIONS.contains(key(call))) {
            stateQuestion(call, context);
            return;
        }

        if (STATE_COMPONENTS.contains(key(call))) {
            stateComponent(call, context);
            return;
        }

        if (STATE_DERIVATIONS.contains(key(call))) {
            stateDerivation(call, context);
            return;
        }

        if (VanillaSourceClasses.Methods.ORDINAL.equals(call.name) && ORDINAL_DESCRIPTOR.equals(call.desc)) {
            constantOrdinal(call, context);
            return;
        }

        if (VanillaSourceClasses.Types.MODEL_PART.equals(call.owner)) {
            partMethod(call, context);
            return;
        }

        if (VanillaSourceClasses.Types.KEYFRAME_ANIMATION.equals(call.owner)) {
            clipSite(call, context);
            return;
        }

        if (isReset(context, call)) {
            // The reset every body opens with. It restores each bone's authored pose, which is
            // exactly what an unwritten channel already reads, so there is nothing to apply.
            stack.popArguments(ClassKit.argTypes(call.desc).length);
            stack.pop();
            return;
        }

        if (isModelLogic(call.owner) || isRenderStateArithmetic(call)) {
            inline(call, context, depth);
            return;
        }

        if (isRenderStateReference(call)) {
            stateReference(call, context);
            return;
        }

        // The last thing a call can be is the application of a lambda a call site built, which is
        // told by the receiver rather than by the method: the interface being called through
        // declares no body, and no model descends from it, so nothing about the instruction says
        // where to go. Taken here, where the alternative is a refusal, so no call that resolves by
        // name pays for the look.
        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            List<PoseValue> arguments = stack.popArguments(ClassKit.argTypes(call.desc).length);
            if (stack.pop() instanceof PoseValue.Lambda lambda) {
                applyLambda(call, lambda, arguments, context, depth);
                return;
            }
        }

        throw new IllegalStateException("calls " + ClassKit.simpleName(call.owner) + "." + call.name
            + ", which is not a body this walk can enter");
    }

    /**
     * A reference the render state names rather than computes, taken with whatever it was asked for.
     *
     * <p>A state that hands back the stack in a hand is naming a thing rather than computing one, so
     * it is the same kind of value a field read of it would have been. What it is asked FOR is part
     * of that name: one method answers a different stack per hand, and a name that dropped the hand
     * would pose both arms off whichever one the walk happened to meet first.
     *
     * <p>Every argument has to be a constant, which is what makes the name derived. An argument this
     * walk resolved to a number would name a different thing per frame and could not be written down
     * at all, so it is refused rather than rendered into the name.
     */
    private static void stateReference(@NotNull MethodInsnNode call, @NotNull Context context) {
        Interp<PoseValue> stack = context.stack();
        List<PoseValue> arguments = stack.popArguments(ClassKit.argTypes(call.desc).length);

        StringJoiner asked = new StringJoiner(", ", "(", ")");
        for (PoseValue argument : arguments) {
            if (!(argument instanceof PoseValue.EnumConstant constant))
                throw new IllegalStateException("asks the render state for " + call.name + " of "
                    + kindOf(argument) + ", which is not a constant it can be named by");
            asked.add(constant.name());
        }

        String member = arguments.isEmpty() ? call.name : call.name + asked;
        String type = ClassKit.returnType(call.desc).getInternalName();
        context.referenceTypes().putIfAbsent(member, type);
        stack.pop();
        stack.push(new PoseValue.StateRef(member, type));
    }

    /** One named component of a reference the render state holds, carried under the path to it. */
    private static void stateComponent(@NotNull MethodInsnNode call, @NotNull Context context) {
        Interp<PoseValue> stack = context.stack();
        PoseValue component = stack.pop();
        PoseValue carrier = stack.pop();

        if (!(component instanceof PoseValue.StaticRef named))
            throw new IllegalStateException("reads " + kindOf(component) + " as a component, which is not a declared one");
        if (!(carrier instanceof PoseValue.StateRef reference))
            throw new IllegalStateException("reads " + named.name() + " off " + kindOf(carrier)
                + ", which is not something the render state holds");

        String member = reference.member() + "." + named.name();
        String type = ClassKit.returnType(call.desc).getInternalName();
        context.referenceTypes().putIfAbsent(member, type);
        stack.push(new PoseValue.StateRef(member, type));
    }

    /** The figures derived from a reference, which stay named after the reference they came from. */
    private static void stateDerivation(@NotNull MethodInsnNode call, @NotNull Context context) {
        Interp<PoseValue> stack = context.stack();
        List<PoseValue> arguments = stack.popArguments(ClassKit.argTypes(call.desc).length);
        if (call.getOpcode() != Opcodes.INVOKESTATIC) stack.pop();

        PoseValue.StateRef derivedFrom = null;
        for (PoseValue argument : arguments) {
            if (argument instanceof PoseValue.StateRef reference) {
                if (derivedFrom != null)
                    throw new IllegalStateException("derives " + call.name
                        + " from two references the render state holds, so it names neither");
                derivedFrom = reference;
                continue;
            }
            if (!(argument instanceof PoseValue.Num))
                throw new IllegalStateException("derives " + call.name + " from " + kindOf(argument)
                    + ", which this walk cannot follow");
        }
        if (derivedFrom == null)
            throw new IllegalStateException("derives " + call.name + " from nothing the render state holds");
        stack.push(derivedFrom);
    }

    /**
     * Asks a reference the render state holds a question, and answers it as an input.
     *
     * <p>The bodies behind these are not arithmetic and there is nothing to walk into: emptiness is
     * a comparison against a singleton and a running animation is a start time against a clock, and
     * a pose depends on the answer the same way it depends on an angle. So the answer is a named
     * input, and it is named after both the reference and the question, because a body that asks
     * one hand and then the other is asking two things.
     *
     * <p>Answered as a number rather than as a condition, which is what a boolean field the state
     * declares outright already is. A model writes one of these straight into a bone's visibility
     * and another passes one as an argument, and both work only because it is a number by the time
     * it gets there.
     */
    private static void stateQuestion(@NotNull MethodInsnNode call, @NotNull Context context) {
        PoseValue asked = context.stack().pop();
        if (!(asked instanceof PoseValue.StateRef reference))
            throw new IllegalStateException("asks " + call.name + " of " + kindOf(asked)
                + ", which is not something the render state holds");
        context.stack().push(num(new PoseExpr.InputFn(reference.member(), call.name)));
    }

    /**
     * Records a place the body applies an authored clip, rather than walking into the clip.
     *
     * <p>The clip's own channels are extracted once into the clip table and shared by every model
     * that plays them, so inlining the application here would be a second copy of a fact the table
     * already holds - and one that could disagree with it. What the table cannot hold is which clip,
     * under which drive, and with what arguments, and that is what is kept.
     *
     * <p>The arguments matter and are the reason this is not simply skipped: a walk-driven clip
     * carries the model's own timing and amplitude constants at its call site and nowhere in the
     * clip, so consuming the call without them would drop how fast the thing moves and how far.
     * Reference arguments are left out rather than placeheld - the animation state a state-driven
     * clip is gated on is not a number, and the drive already says the clip sits behind one.
     */
    private static void clipSite(@NotNull MethodInsnNode call, @NotNull Context context) {
        ClipBinding.Gate drive = driveOf(call.name);
        if (drive == null)
            throw new IllegalStateException("calls KeyframeAnimation." + call.name + ", which is not a way a clip is driven");

        Interp<PoseValue> stack = context.stack();
        Type[] parameters = ClassKit.argTypes(call.desc);
        List<PoseValue> arguments = stack.popArguments(parameters.length);
        PoseValue receiver = stack.pop();

        if (!(receiver instanceof PoseValue.Clip clip))
            throw new IllegalStateException("applies a clip it could not name");

        List<PoseExpr> carried = new ArrayList<>(parameters.length);
        for (int index = 0; index < parameters.length; index++) {
            if (parameters[index].getSort() == Type.OBJECT || parameters[index].getSort() == Type.ARRAY) continue;
            if (!(arguments.get(index) instanceof PoseValue.Num number))
                throw new IllegalStateException("drives '" + clip.coordinate() + "' by a value it could not model");
            carried.add(number.expr());
        }
        context.clipSites().add(new PoseClipSite(clip.coordinate(), drive, List.copyOf(carried)));
    }

    /** Which of the three drives a play site names, matching what the clip table already records. */
    private static @Nullable ClipBinding.Gate driveOf(@NotNull String method) {
        if (VanillaSourceClasses.Methods.APPLY.equals(method)) return ClipBinding.Gate.STATE;
        if (VanillaSourceClasses.Methods.APPLY_WALK.equals(method)) return ClipBinding.Gate.WALK;
        if (VanillaSourceClasses.Methods.APPLY_STATIC.equals(method)) return ClipBinding.Gate.STATIC;
        return null;
    }

    /**
     * The three {@code ModelPart} methods a pose body reaches. Every other way vanilla offers to
     * move a part - {@code setRotation}, {@code offsetPos}, {@code offsetRotation},
     * {@code translateAndRotate} - is called from nowhere the walk goes, so meeting one is a
     * finding rather than a case to add on spec.
     */
    private static void partMethod(@NotNull MethodInsnNode call, @NotNull Context context) {
        Interp<PoseValue> stack = context.stack();

        if ("setPos".equals(call.name)) {
            List<PoseValue> arguments = stack.popArguments(3);
            PoseValue receiver = stack.pop();
            if (!(receiver instanceof PoseValue.Part part))
                throw new IllegalStateException("moves a bone it could not name");
            PoseChannel[] axes = {PoseChannel.X, PoseChannel.Y, PoseChannel.Z};
            for (int axis = 0; axis < axes.length; axis++) {
                if (!(arguments.get(axis) instanceof PoseValue.Num number))
                    throw new IllegalStateException("moves " + part.bone() + " by a value it could not model");
                write(context, part.bone(), axes[axis], number.expr());
            }
            return;
        }
        if ("resetPose".equals(call.name)) {
            PoseValue receiver = stack.pop();
            if (!(receiver instanceof PoseValue.Part part))
                throw new IllegalStateException("resets a bone it could not name");
            // Back to the authored pose, which is what an untouched channel already reads.
            context.pose().remove(part.bone());
            return;
        }
        if (VanillaSourceClasses.Methods.GET_CHILD.equals(call.name)) {
            // Vanilla usually caches its children in the constructor; two sites look one up while
            // posing instead. The child's own name is the bone's, the table being flat.
            AbstractInsnNode named = AsmWalker.previousReal(call);
            stack.pop();
            stack.pop();
            if (!(named instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String bone))
                throw new IllegalStateException("looks a bone up by a name it could not read");
            stack.push(new PoseValue.Part(bone));
            return;
        }
        throw new IllegalStateException("calls ModelPart." + call.name + ", which is not a way this walk moves a bone");
    }

    /**
     * Walks a called body in place, as though its instructions had been written where the call is.
     *
     * <p>The callee gets fresh locals over the same operand stack, which is what
     * {@link Interp#openSlotFrame} is for: its arguments are stored into the slots it will read them
     * from, and whatever it leaves above the stack depth it started at is its return value.
     *
     * <p>A virtual call resolves against the LEAF rather than against whichever class declared the
     * body being walked, because two models sharing an inherited {@code setupAnim} reach different
     * overrides through the same instruction. A {@code super} call is the exception and resolves
     * against the owner it names, which is the whole point of spelling it that way.
     */
    private static void inline(@NotNull MethodInsnNode call, @NotNull Context context, int depth) {
        if (depth >= MAX_INLINE_DEPTH)
            throw new IllegalStateException("inlines more than " + MAX_INLINE_DEPTH + " helpers deep");

        String owner = dispatchOwner(context, call);
        MethodNode target = ClassKit.findMethodInHierarchy(context.cache(), owner, call.name, call.desc);
        if (target == null || target.instructions == null || target.instructions.size() == 0)
            throw new IllegalStateException("calls " + ClassKit.simpleName(call.owner) + "." + call.name
                + ", whose body is not in the jar");

        Interp<PoseValue> stack = context.stack();
        Type[] parameters = ClassKit.argTypes(call.desc);
        List<PoseValue> arguments = stack.popArguments(parameters.length);
        boolean instance = call.getOpcode() != Opcodes.INVOKESTATIC;
        PoseValue receiver = instance ? stack.pop() : null;

        int depthBefore = stack.size();
        stack.openSlotFrame();
        int slot = 0;
        if (receiver != null) stack.store(slot++, receiver);
        for (int index = 0; index < parameters.length; index++) {
            stack.store(slot, arguments.get(index));
            slot += parameters[index].getSize();
        }

        walkBody(target, context, depth + 1);

        PoseValue answered = stack.size() > depthBefore ? stack.pop() : null;
        stack.closeSlotFrame();
        if (Type.getReturnType(call.desc).getSort() != Type.VOID) {
            if (answered == null) throw new IllegalStateException(call.name + " returned nothing this walk could follow");
            stack.push(answered);
        }
    }

    /**
     * Walks a lambda's body in place, the way an inlined helper is walked.
     *
     * <p>The slots are what the call site closed over followed by what the application handed it,
     * which is the order the body's own descriptor declares them in - so a lambda that captured
     * nothing reads its arguments from slot zero and one that captured a bone reads that first.
     *
     * <p>The body is looked up on the class the call site named rather than on the leaf, because a
     * lambda's body is written where the lambda was written and nothing overrides it.
     */
    private static void applyLambda(
        @NotNull MethodInsnNode call, @NotNull PoseValue.Lambda lambda,
        @NotNull List<PoseValue> arguments, @NotNull Context context, int depth) {

        if (depth >= MAX_INLINE_DEPTH)
            throw new IllegalStateException("inlines more than " + MAX_INLINE_DEPTH + " helpers deep");

        String named = ClassKit.simpleName(lambda.owner()) + "." + lambda.name();
        MethodNode target = ClassKit.findMethodInHierarchy(
            context.cache(), lambda.owner(), lambda.name(), lambda.descriptor());
        if (target == null || target.instructions == null || target.instructions.size() == 0)
            throw new IllegalStateException("applies the lambda " + named + ", whose body is not in the jar");

        List<PoseValue> slotted = new ArrayList<>(lambda.captured());
        slotted.addAll(arguments);
        Type[] parameters = ClassKit.argTypes(lambda.descriptor());
        if (slotted.size() != parameters.length)
            throw new IllegalStateException("applies the lambda " + named + " to " + slotted.size()
                + " operand(s), whose body takes " + parameters.length);

        Interp<PoseValue> stack = context.stack();
        int depthBefore = stack.size();
        stack.openSlotFrame();
        int slot = 0;
        for (int index = 0; index < parameters.length; index++) {
            stack.store(slot, slotted.get(index));
            slot += parameters[index].getSize();
        }

        walkBody(target, context, depth + 1);

        PoseValue answered = stack.size() > depthBefore ? stack.pop() : null;
        stack.closeSlotFrame();
        if (Type.getReturnType(call.desc).getSort() != Type.VOID) {
            if (answered == null) throw new IllegalStateException(named + " returned nothing this walk could follow");
            stack.push(answered);
        }
    }

    /**
     * Which class a call's body should be looked up on.
     *
     * <p>A virtual call on the model itself has to resolve against the LEAF, because two models
     * sharing an inherited {@code setupAnim} reach different overrides through the same instruction
     * and resolving from the declaring class would give both of them the base's. A virtual call on
     * anything else must not: an enum asked whether it is two-handed is not the model, and looking
     * that method up on the model finds nothing at all.
     *
     * <p>Told apart by whether the leaf is the owner or descends from it, which is what "called on
     * the model" means, rather than by inspecting the receiver - the receiver of a call on
     * {@code this} is a value the walk deliberately does not model.
     */
    private static @NotNull String dispatchOwner(@NotNull Context context, @NotNull MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKEINTERFACE)
            return call.owner;

        String current = context.leaf();
        for (int depth = 0; current != null && depth < MAX_INLINE_DEPTH; depth++) {
            if (current.equals(call.owner)) return context.leaf();
            ClassNode node = context.cache().load(current);
            if (node == null) break;
            current = node.superName;
        }
        return call.owner;
    }

    /**
     * Whether a call is the reset a pose body opens with rather than an inherited pose to walk.
     *
     * <p>Decided by where the body it reaches is DECLARED, never by the class the instruction
     * happens to name. A leaf spells {@code super.setupAnim} against its immediate superclass, and a
     * superclass that declares no pose of its own inherits the reset - so the same call means two
     * different things depending on a class the instruction does not mention. Reading the owner
     * instead makes a base that adds nothing look like a pose, and the walk goes off into the
     * bone-list loop the reset is built out of.
     *
     * <p>Resolving rather than naming is a widening in one direction only: a call that resolves to
     * a class declaring its own body still answers false, so a base that really does pose is still
     * walked.
     */
    private static boolean isReset(@NotNull Context context, @NotNull MethodInsnNode call) {
        if (!VanillaSourceClasses.Methods.SETUP_ANIM.equals(call.name)) return false;
        String current = call.owner;
        for (int depth = 0; current != null && depth < MAX_INLINE_DEPTH; depth++) {
            ClassNode node = context.cache().load(current);
            if (node == null) return false;
            if (ClassKit.findMethod(node, call.name, call.desc) != null) return RESET_ROOTS.contains(current);
            current = node.superName;
        }
        return false;
    }

    /** Whether a call names a model's own logic, which is a body to walk rather than a fact to know. */
    private static boolean isModelLogic(@NotNull String owner) {
        return owner.startsWith(VanillaSourceClasses.Types.CLIENT_MODEL_ROOT)
            && !owner.startsWith(VanillaSourceClasses.Types.CLIENT_MODEL_GEOM_ROOT);
    }

    /**
     * Whether a call is a render state computing a number out of the fields it already holds.
     *
     * <p>A state that offers a derived angle rather than the two fields it is derived from is
     * offering arithmetic, and arithmetic is walked rather than named: inlining it reaches the same
     * inputs a field read would have, so the expression stays in terms of what the render state
     * actually carries instead of gaining an opaque channel nothing downstream could supply.
     *
     * <p>Bounded to a primitive answer, which is what makes it a number rather than a way of
     * reaching another object. A state method handing back a reference is a different question and
     * keeps its own refusal, because what happens to that reference is where the interesting part
     * of it is.
     */
    private static boolean isRenderStateArithmetic(@NotNull MethodInsnNode call) {
        if (!call.owner.startsWith(VanillaSourceClasses.Types.ENTITY_RENDER_STATE_PACKAGE)) return false;
        int sort = ClassKit.returnType(call.desc).getSort();
        return sort >= Type.BOOLEAN && sort <= Type.DOUBLE;
    }

    /**
     * Whether a call is a render state naming one of the things it holds rather than computing one.
     *
     * <p>Arguments are allowed and become part of the reference's name, which is what
     * {@link #stateReference} builds: one method answering a different thing per hand is as many
     * references as there are hands, and a name that dropped the hand would be one reference asked
     * for twice. What that costs is that every argument has to be a constant, which is enforced
     * there rather than here - the values are on the stack and this reads only the instruction.
     */
    private static boolean isRenderStateReference(@NotNull MethodInsnNode call) {
        return call.owner.startsWith(VanillaSourceClasses.Types.ENTITY_RENDER_STATE_PACKAGE)
            && ClassKit.returnType(call.desc).getSort() == Type.OBJECT;
    }

    /**
     * Folds what a body did to the flattened mesh root onto the bones that hang off it.
     *
     * <p>Children are placed by their container's transform and then their own, so a container
     * carrying no rotation and no scale contributes a translation and nothing else - and a
     * translation composes by addition, so shifting the container by an amount and shifting each of
     * its children by that same amount put every child in the same place. The container is gone from
     * the mesh, which is why the second spelling is the one that can be written down.
     *
     * <p>The guard is the whole of what makes that true and is not optional. A rotation turns the
     * subtree about the container's own pivot, which reaches each child's POSITION as well as its
     * rotation and is no term any one of them can be handed; a scale is the same argument. Neither
     * is refused for being hard - they are refused because there is no answer of this shape.
     *
     * <p>What the fold gives a bone is what the write MOVED the container by rather than what it left
     * it holding, which is why the container's own value never has to be known. It is already inside
     * the children: a mesh that flattened the container placed them where it left them.
     *
     * <p>A bone this names is one the model never wrote to, which is the one thing here a reader
     * would not expect from a pose. It is what the arithmetic says: the shift reaches every child,
     * and a child the model never posed is still a child.
     */
    private static void foldMeshRoot(@NotNull Context context, @NotNull Set<String> rootBones) {
        Map<PoseChannel, PoseExpr> container = context.pose().remove(MESH_ROOT);
        if (container == null) return;

        if (rootBones.isEmpty())
            throw new IllegalStateException("poses through the mesh root, and this mesh names no bone under it");

        for (Map.Entry<PoseChannel, PoseExpr> entry : container.entrySet()) {
            PoseChannel channel = entry.getKey();
            if (channel.kind() != PoseChannel.Kind.POSITION)
                throw new IllegalStateException("poses the mesh root's " + channel.token()
                    + ", which is not a move its children can each be given");

            PoseExpr shift = shiftOf(entry.getValue(), channel);
            if (shift == null)
                throw new IllegalStateException("moves the mesh root's " + channel.token()
                    + " to a place rather than by an amount, which its children cannot each be given");

            for (String bone : rootBones)
                write(context, bone, channel,
                    PoseExpr.Op.of(PoseOperator.ADD, current(context.pose(), bone, channel), shift));
        }
    }

    /**
     * What a write to the container's channel moved it BY, rather than what it left it holding.
     *
     * <p>Read by taking the container's own value as the origin and keeping everything added to it:
     * a channel left as it was found has moved by nothing, and one built by adding or subtracting a
     * term that does not mention it has moved by that term. A write that does not reach the value it
     * is replacing has no such reading and answers nothing, because the amount would be the
     * difference against a value the flattened mesh no longer carries anywhere.
     *
     * @param written what the body left the channel holding
     * @param channel the channel it wrote
     * @return the amount it moved by, or {@code null} when the write is not a move
     */
    private static @Nullable PoseExpr shiftOf(@NotNull PoseExpr written, @NotNull PoseChannel channel) {
        if (written.equals(new PoseExpr.BoneRead(MESH_ROOT, channel))) return PoseExpr.Const.of(0f);

        if (written instanceof PoseExpr.Select select) {
            // Each arm is a move of its own, so the choice between them is a choice of amount.
            PoseExpr whenTrue = shiftOf(select.whenTrue(), channel);
            PoseExpr whenFalse = shiftOf(select.whenFalse(), channel);
            return whenTrue == null || whenFalse == null
                ? null : new PoseExpr.Select(select.condition(), whenTrue, whenFalse);
        }

        if (!(written instanceof PoseExpr.Op op) || op.operands().size() != 2) return null;
        PoseExpr left = op.operands().getFirst();
        PoseExpr right = op.operands().getLast();
        PoseExpr fromLeft = shiftOf(left, channel);
        PoseExpr fromRight = shiftOf(right, channel);
        // Exactly one side, because a term reaching the container's value twice is not one it was
        // moved by - and the operand that does not reach it is carried through as itself.
        if (fromLeft != null && fromRight != null) return null;
        if (op.operator() == PoseOperator.ADD && fromLeft != null)
            return PoseExpr.Op.of(PoseOperator.ADD, fromLeft, right);
        if (op.operator() == PoseOperator.ADD && fromRight != null)
            return PoseExpr.Op.of(PoseOperator.ADD, left, fromRight);
        if (op.operator() == PoseOperator.SUB && fromLeft != null)
            return PoseExpr.Op.of(PoseOperator.SUB, fromLeft, right);
        return null;
    }

    /** Records one channel's new value. */
    private static void write(
        @NotNull Context context, @NotNull String bone, @NotNull PoseChannel channel, @NotNull PoseExpr value) {

        context.pose().computeIfAbsent(bone, key -> new EnumMap<>(PoseChannel.class)).put(channel, value);
    }

    /** A channel's value so far - what a write left, or the authored pose the reset restored. */
    private static @NotNull PoseExpr current(
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> pose, @NotNull String bone, @NotNull PoseChannel channel) {

        Map<PoseChannel, PoseExpr> written = pose.get(bone);
        PoseExpr held = written == null ? null : written.get(channel);
        return held != null ? held : new PoseExpr.BoneRead(bone, channel);
    }

    /** The typed {@code setupAnim} nearest the leaf, skipping the erased override beside it. */
    private static @Nullable MethodNode findSetupAnim(@NotNull ClassNodeCache cache, @NotNull String modelClass) {
        String current = modelClass;
        for (int depth = 0; current != null && depth < 8; depth++) {
            ClassNode node = cache.load(current);
            if (node == null) return null;
            for (MethodNode method : node.methods)
                if (VanillaSourceClasses.Methods.SETUP_ANIM.equals(method.name) && !ERASED_SETUP_ANIM.equals(method.desc))
                    return method;
            current = node.superName;
        }
        return null;
    }

    /**
     * The pose, made unmodifiable without losing the order it was built in.
     *
     * <p>Not {@code Map.copyOf}, whose iteration order is salted per JVM launch. Nothing here reads
     * the order for meaning, but a table written out of one that flaps is a table that fails its own
     * reproducibility check with no other symptom.
     */
    private static @NotNull Map<String, Map<PoseChannel, PoseExpr>> freeze(
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> pose) {

        Map<String, Map<PoseChannel, PoseExpr>> out = new LinkedHashMap<>();
        pose.forEach((bone, channels) -> out.put(bone, Collections.unmodifiableMap(new EnumMap<>(channels))));
        return Collections.unmodifiableMap(out);
    }

    private static @NotNull PoseValue num(@NotNull PoseExpr expr) {
        return new PoseValue.Num(expr);
    }

    private static @NotNull String partDesc() {
        return "L" + VanillaSourceClasses.Types.MODEL_PART + ";";
    }

    private static @NotNull String partArrayDesc() {
        return "[L" + VanillaSourceClasses.Types.MODEL_PART + ";";
    }

    private static @NotNull String clipDesc() {
        return "L" + VanillaSourceClasses.Types.KEYFRAME_ANIMATION + ";";
    }

    private static @NotNull String key(@NotNull MethodInsnNode call) {
        return call.owner + "." + call.name + call.desc;
    }

    private static @NotNull String key(@NotNull String owner, @NotNull String name, @NotNull String desc) {
        return owner + "." + name + desc;
    }

    /** The opcode-to-operator table, which is where the three widths stop being interchangeable. */
    private static @NotNull Map<Integer, PoseOperator> arithmetic() {
        Map<Integer, PoseOperator> out = new LinkedHashMap<>();
        out.put(Opcodes.FADD, PoseOperator.ADD);
        out.put(Opcodes.FSUB, PoseOperator.SUB);
        out.put(Opcodes.FMUL, PoseOperator.MUL);
        out.put(Opcodes.FDIV, PoseOperator.DIV);
        out.put(Opcodes.FREM, PoseOperator.REM);
        out.put(Opcodes.FNEG, PoseOperator.NEG);
        out.put(Opcodes.DADD, PoseOperator.DADD);
        out.put(Opcodes.DSUB, PoseOperator.DSUB);
        out.put(Opcodes.DMUL, PoseOperator.DMUL);
        out.put(Opcodes.DDIV, PoseOperator.DDIV);
        out.put(Opcodes.DNEG, PoseOperator.DNEG);
        out.put(Opcodes.IADD, PoseOperator.IADD);
        out.put(Opcodes.ISUB, PoseOperator.ISUB);
        out.put(Opcodes.IMUL, PoseOperator.IMUL);
        out.put(Opcodes.IDIV, PoseOperator.IDIV);
        out.put(Opcodes.IREM, PoseOperator.IREM);
        out.put(Opcodes.INEG, PoseOperator.INEG);
        out.put(Opcodes.I2F, PoseOperator.I2F);
        out.put(Opcodes.F2D, PoseOperator.F2D);
        out.put(Opcodes.D2F, PoseOperator.D2F);
        out.put(Opcodes.F2I, PoseOperator.F2I);
        return Map.copyOf(out);
    }

    /**
     * The questions the corpus asks of a reference the render state holds.
     *
     * <p>Two types answer emptiness and they are not one type: a stack and the baked render state of
     * a stack are asked the same word by different models, and there is no shared supertype to key
     * on that would not also admit everything else either of them can be asked.
     *
     * <p>The eight spear figures are the swing a kinetic weapon is used at, and they are named
     * rather than derived because the numbers behind them are server data no offline run carries -
     * measured, not assumed: the item registry's own initialiser mentions that component nowhere.
     * All eight together are exactly a term of zero, and vanilla's own no-such-component arm adds
     * exactly that, so a caller with no components to model renders what vanilla renders. Eight and
     * not the ten the figures are declared as, because two of them no pose body reads: a row nothing
     * asks for would declare an input nothing supplies.
     */
    private static @NotNull Set<String> stateQuestions() {
        String rotations = VanillaSourceClasses.Types.ROTATIONS;
        String spear = VanillaSourceClasses.Types.SPEAR_USE_PARAMS;
        return Set.of(
            key(VanillaSourceClasses.Types.ANIMATION_STATE, VanillaSourceClasses.Methods.IS_STARTED, "()Z"),
            key(VanillaSourceClasses.Types.ITEM_STACK, VanillaSourceClasses.Methods.IS_EMPTY, "()Z"),
            key(VanillaSourceClasses.Types.ITEM_STACK_RENDER_STATE, VanillaSourceClasses.Methods.IS_EMPTY, "()Z"),
            key(VanillaSourceClasses.Types.BLOCK_MODEL_RENDER_STATE, VanillaSourceClasses.Methods.IS_EMPTY, "()Z"),
            key(rotations, PoseChannel.X.token(), "()F"),
            key(rotations, PoseChannel.Y.token(), "()F"),
            key(rotations, PoseChannel.Z.token(), "()F"),
            key(spear, "swayIntensity", "()F"),
            key(spear, "swayScaleSlow", "()F"),
            key(spear, "swayScaleFast", "()F"),
            key(spear, "raiseProgressStart", "()F"),
            key(spear, "raiseProgressMiddle", "()F"),
            key(spear, "raiseProgressEnd", "()F"),
            key(spear, "lowerProgress", "()F"),
            key(spear, "raiseBackProgress", "()F"));
    }

    /** The one call that reads a named component off a reference the render state holds. */
    private static @NotNull Set<String> stateComponents() {
        return Set.of(key(VanillaSourceClasses.Types.ITEM_STACK, "get",
            "(L" + VanillaSourceClasses.Types.DATA_COMPONENT_TYPE + ";)Ljava/lang/Object;"));
    }

    /** The one call that turns a component into the figures a pose reads off it. */
    private static @NotNull Set<String> stateDerivations() {
        String params = "L" + VanillaSourceClasses.Types.SPEAR_USE_PARAMS + ";";
        return Set.of(key(VanillaSourceClasses.Types.SPEAR_USE_PARAMS, "fromKineticWeapon",
            "(L" + VanillaSourceClasses.Types.KINETIC_WEAPON + ";F)" + params));
    }

    /** The box a float rides into a lambda in, and the unbox it arrives through. */
    private static @NotNull Set<String> boxes() {
        String box = VanillaSourceClasses.Types.JAVA_FLOAT;
        return Set.of(
            key(box, "valueOf", "(F)L" + box + ";"),
            key(box, "floatValue", "()F"));
    }

    /** The call-to-operator table, keyed on the whole coordinate so a width cannot be mistaken. */
    private static @NotNull Map<String, PoseOperator> calls() {
        String mth = VanillaSourceClasses.Types.MTH;
        String math = VanillaSourceClasses.Types.JAVA_MATH;
        String ease = VanillaSourceClasses.Types.EASE;

        Map<String, PoseOperator> out = new LinkedHashMap<>();
        out.put(key(mth, "sin", "(D)F"), PoseOperator.MTH_SIN);
        out.put(key(mth, "cos", "(D)F"), PoseOperator.MTH_COS);
        out.put(key(mth, "sqrt", "(F)F"), PoseOperator.SQRT);
        out.put(key(mth, "clamp", "(FFF)F"), PoseOperator.CLAMP);
        out.put(key(mth, "lerp", "(FFF)F"), PoseOperator.LERP);
        out.put(key(mth, "inverseLerp", "(FFF)F"), PoseOperator.INVERSE_LERP);
        out.put(key(mth, "rotLerp", "(FFF)F"), PoseOperator.ROT_LERP);
        out.put(key(mth, "rotLerpRad", "(FFF)F"), PoseOperator.ROT_LERP_RAD);
        out.put(key(mth, "wrapDegrees", "(F)F"), PoseOperator.WRAP_DEGREES);
        out.put(key(mth, "triangleWave", "(FF)F"), PoseOperator.TRIANGLE_WAVE);
        out.put(key(mth, "abs", "(I)I"), PoseOperator.IABS);

        out.put(key(math, "min", "(FF)F"), PoseOperator.MIN);
        out.put(key(math, "max", "(FF)F"), PoseOperator.MAX);
        out.put(key(math, "abs", "(F)F"), PoseOperator.ABS);
        out.put(key(math, "abs", "(I)I"), PoseOperator.IABS);
        out.put(key(math, "clamp", "(FFF)F"), PoseOperator.CLAMP);
        out.put(key(math, "sin", "(D)D"), PoseOperator.LIBM_SIN);
        out.put(key(math, "cos", "(D)D"), PoseOperator.LIBM_COS);
        out.put(key(math, "abs", "(D)D"), PoseOperator.LIBM_ABS);
        out.put(key(math, "signum", "(D)D"), PoseOperator.LIBM_SIGNUM);

        out.put(key(ease, "inCirc", "(F)F"), PoseOperator.EASE_IN_CIRC);
        out.put(key(ease, "inQuad", "(F)F"), PoseOperator.EASE_IN_QUAD);
        out.put(key(ease, "outCirc", "(F)F"), PoseOperator.EASE_OUT_CIRC);
        out.put(key(ease, "outCubic", "(F)F"), PoseOperator.EASE_OUT_CUBIC);
        out.put(key(ease, "outQuart", "(F)F"), PoseOperator.EASE_OUT_QUART);
        out.put(key(ease, "inOutSine", "(F)F"), PoseOperator.EASE_IN_OUT_SINE);
        out.put(key(ease, "inOutExpo", "(F)F"), PoseOperator.EASE_IN_OUT_EXPO);
        out.put(key(ease, "inOutElastic", "(F)F"), PoseOperator.EASE_IN_OUT_ELASTIC);
        return Map.copyOf(out);
    }

}
