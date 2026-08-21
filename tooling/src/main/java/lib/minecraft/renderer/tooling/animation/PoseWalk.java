package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Interp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** Where the super chain stops being a pose and becomes the reset. */
    private static final @NotNull List<String> RESET_ROOTS =
        List.of(VanillaSourceClasses.Types.ENTITY_MODEL, VanillaSourceClasses.Types.MODEL);

    private static final @NotNull PoseValue.Opaque OPAQUE = new PoseValue.Opaque();

    /** The arithmetic the chassis routes here, keyed by opcode, at the width the opcode names. */
    private static final @NotNull Map<Integer, PoseOperator> ARITHMETIC = arithmetic();

    /** The calls a pose body makes that are arithmetic by another name. */
    private static final @NotNull Map<String, PoseOperator> CALLS = calls();

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
     */
    private static final int MAX_FORKS = 1024;

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
        @NotNull List<PoseClipSite> clipSites,
        int @NotNull [] forks
    ) {}

    /**
     * Extracts one model's pose.
     *
     * @param cache the open client jar
     * @param modelClass the leaf model's internal name
     * @param diagnostics the scope findings are recorded against
     * @return the pose, or empty when the body holds a shape this does not model
     */
    public static @NotNull Optional<PoseProgram> extract(
        @NotNull ClassNodeCache cache, @NotNull String modelClass, @NotNull Diagnostics diagnostics) {

        MethodNode body = findSetupAnim(cache, modelClass);
        // A model whose whole chain declares only the erased override poses nothing: what it
        // inherits is the reset. That is an empty pose rather than a refusal, and the two have to
        // stay distinguishable or a walk that failed reads as a subject that simply holds still.
        if (body == null) return Optional.of(new PoseProgram(ClassKit.simpleName(modelClass), Map.of(), List.of()));

        Context context = new Context(cache, modelClass, PosePartIndex.of(cache, modelClass, diagnostics),
            ClipBindingResolver.fieldToClip(cache, modelClass),
            Interp.of(DOMAIN, Interp.OnUnknown.SILENT, Interp.Width.BY_OPERANDS),
            new LinkedHashMap<>(), new ArrayList<>(), new int[1]);

        try {
            walkBody(body, context, 0);
        } catch (RuntimeException error) {
            diagnostics.info("%s not extracted: %s", ClassKit.simpleName(modelClass), error.getMessage());
            return Optional.empty();
        }
        return Optional.of(new PoseProgram(
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
            AbstractInsnNode target = step(cursor, context, depth);
            cursor = AsmWalker.nextReal(target != null ? target : cursor);
        }
        return false;
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

        Interp<PoseValue> stack = context.stack();
        AbstractInsnNode taken = AsmWalker.nextReal(jump.label);
        AbstractInsnNode fallen = AsmWalker.nextReal(jump);
        AbstractInsnNode join = findJoin(body, taken, fallen, stop);

        Interp.Snapshot<PoseValue> before = stack.snapshot();
        Map<String, Map<PoseChannel, PoseExpr>> posed = copy(context.pose());
        List<PoseClipSite> played = List.copyOf(context.clipSites());

        boolean takenRejoined = walkFrom(body, taken, join, context, depth);
        Interp.Snapshot<PoseValue> afterTaken = stack.snapshot();
        Map<String, Map<PoseChannel, PoseExpr>> poseTaken = copy(context.pose());
        List<PoseClipSite> playedTaken = List.copyOf(context.clipSites());

        stack.restore(before);
        replace(context.pose(), posed);
        replaceSites(context, played);
        boolean fallenRejoined = walkFrom(body, fallen, join, context, depth);

        if (join != null && takenRejoined && fallenRejoined) {
            Interp.Snapshot<PoseValue> afterFallen = stack.snapshot();
            replace(context.pose(), merge(condition, poseTaken, copy(context.pose())));
            replaceSites(context, bothPlayed(playedTaken, context.clipSites()));
            stack.restore(reconcile(condition, afterTaken, afterFallen));
            return join;
        }

        // One arm left the body without meeting the other, so there is no point at which their
        // states line up. Both run to the end instead and only the pose is merged - the same answer,
        // reached the expensive way. Re-running the arm that stopped at the join is what keeps the
        // two maps comparable: merging a partial arm against a finished one would drop every write
        // the partial one had not reached yet.
        stack.restore(before);
        replace(context.pose(), posed);
        replaceSites(context, played);
        walkFrom(body, taken, null, context, depth);
        Map<String, Map<PoseChannel, PoseExpr>> wholeTaken = copy(context.pose());
        List<PoseClipSite> wholePlayedTaken = List.copyOf(context.clipSites());

        stack.restore(before);
        replace(context.pose(), posed);
        replaceSites(context, played);
        walkFrom(body, fallen, null, context, depth);

        replace(context.pose(), merge(condition, wholeTaken, copy(context.pose())));
        replaceSites(context, bothPlayed(wholePlayedTaken, context.clipSites()));
        stack.restore(before);
        return null;
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
            throw new IllegalStateException("arms of a branch reach their join holding different things");

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
        throw new IllegalStateException("arms of a branch leave two different bones in the same place");
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
        if (tested instanceof PoseValue.EnumConstant constant && against instanceof PoseValue.EnumInput input)
            return matching(input, constant);
        if (tested instanceof PoseValue.EnumInput input && against instanceof PoseValue.EnumConstant constant)
            return matching(input, constant);
        throw new IllegalStateException("compares two references that are not an enum and one of its constants");
    }

    private static @NotNull PosePredicate matching(
        @NotNull PoseValue.EnumInput input, @NotNull PoseValue.EnumConstant constant) {

        if (!input.type().equals(constant.type()))
            throw new IllegalStateException("compares " + ClassKit.simpleName(input.type())
                + " against a constant of " + ClassKit.simpleName(constant.type()));
        return new PosePredicate.EnumEq(input.field(), constant.name());
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

    private static void replace(
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> target,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> source) {

        target.clear();
        target.putAll(source);
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
            throw new IllegalStateException("switches on a value, which this walk does not decide");
        if (in instanceof IincInsnNode increment) {
            advance(stack, increment);
            return null;
        }

        switch (in.getOpcode()) {
            case Opcodes.GETFIELD -> readField((FieldInsnNode) in, context);
            case Opcodes.PUTFIELD -> writeField((FieldInsnNode) in, context);
            case Opcodes.GETSTATIC -> {
                // An enum constant is the one static a pose body reads for anything: it is compared
                // against a render-state field to ask which arm, which pose, which way round.
                FieldInsnNode constant = (FieldInsnNode) in;
                stack.push(("L" + constant.owner + ";").equals(constant.desc)
                    ? new PoseValue.EnumConstant(constant.owner, constant.name)
                    : OPAQUE);
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
            case Opcodes.CHECKCAST -> { /* a cast changes the type, never the value */ }
            case Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE ->
                call((MethodInsnNode) in, context, depth);
            case Opcodes.AASTORE, Opcodes.NEW, Opcodes.ANEWARRAY ->
                throw new IllegalStateException("allocates while posing, which this walk does not model");
            default -> stack.step(in);
        }
        return null;
    }

    /**
     * Why a branch could not be turned into a predicate, naming what it was handed.
     *
     * <p>A branch this walk cannot phrase is a shape to go and look at, so the message says which
     * kind of value arrived rather than only that one did - the difference between "there is a
     * branch here" and "a call this walk models as nothing ended up being tested".
     */
    private static @NotNull String undecidable(@NotNull PoseValue tested) {
        String kind = switch (tested) {
            case PoseValue.Opaque ignored -> "a value this walk models as nothing";
            case PoseValue.EnumInput input -> "the enum '" + input.field() + "' against something that is not its constant";
            case PoseValue.EnumConstant constant -> "the constant " + ClassKit.simpleName(constant.type()) + "."
                + constant.name() + " against something that is not its enum";
            case PoseValue.Part part -> "the bone '" + part.bone() + "'";
            case PoseValue.PartArray array -> "the array '" + array.field() + "'";
            case PoseValue.Clip clip -> "the clip '" + clip.coordinate() + "'";
            case PoseValue.Comparison ignored -> "a comparison in a place one cannot be phrased";
            case PoseValue.Num number -> "a number this walk cannot phrase a test of";
        };
        return "branches on " + kind;
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
            if (!(receiver instanceof PoseValue.Part part))
                throw new IllegalStateException("reads a channel off a bone it could not name");
            stack.push(num(current(pose, part.bone(), channel)));
            return;
        }
        if (partDesc().equals(field.desc)) {
            String bone = parts.boneOf(field.name);
            if (bone == null && RESET_ROOTS.contains(field.owner))
                // The mesh root, which is a bone only when the mesh names one: most flatten it into
                // several parented at nothing, and what a transform on the container means then is
                // a question for whoever joins this to a mesh.
                throw new IllegalStateException("poses through the mesh root, which this mesh does not name as a bone");
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
            // A render state holds numbers and it holds enums, and only the numbers are arithmetic
            // on. Reading an enum as though it were a float would put a thing with no numeric value
            // into an expression and only find out at the sink.
            stack.push(isPrimitive(field.desc)
                ? num(new PoseExpr.Input(field.name))
                : new PoseValue.EnumInput(field.name, referenced(field.desc)));
            return;
        }
        stack.push(OPAQUE);
    }

    /** A field write. Only a channel of a bone is one; anything else the walk refuses. */
    private static void writeField(@NotNull FieldInsnNode field, @NotNull Context context) {
        Interp<PoseValue> stack = context.stack();
        Map<String, Map<PoseChannel, PoseExpr>> pose = context.pose();
        PoseValue value = stack.pop();
        PoseValue receiver = stack.pop();

        if (!VanillaSourceClasses.Types.MODEL_PART.equals(field.owner))
            throw new IllegalStateException("writes " + ClassKit.simpleName(field.owner) + "." + field.name
                + ", so its pose is not a function of its inputs alone");

        PoseChannel channel = PoseChannel.ofField(field.name);
        if (channel == null) throw new IllegalStateException("writes ModelPart." + field.name + ", which is not a channel");
        if (!(receiver instanceof PoseValue.Part part))
            throw new IllegalStateException("writes a channel to a bone it could not name");
        if (!(value instanceof PoseValue.Num written))
            throw new IllegalStateException("writes " + part.bone() + "." + channel.token() + " a value it could not model");

        pose.computeIfAbsent(part.bone(), bone -> new EnumMap<>(PoseChannel.class)).put(channel, written.expr());
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

    /** A call: arithmetic by another name, a bone mutated through a method, the reset, or a body to inline. */
    private static void call(@NotNull MethodInsnNode call, @NotNull Context context, int depth) {
        Interp<PoseValue> stack = context.stack();

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

        throw new IllegalStateException("calls " + ClassKit.simpleName(call.owner) + "." + call.name
            + ", which is not a body this walk can enter");
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

    private static @NotNull Map<String, Map<PoseChannel, PoseExpr>> freeze(
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> pose) {

        Map<String, Map<PoseChannel, PoseExpr>> out = new LinkedHashMap<>();
        pose.forEach((bone, channels) -> out.put(bone, Map.copyOf(channels)));
        return Map.copyOf(out);
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
