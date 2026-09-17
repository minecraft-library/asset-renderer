package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PosePredicate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The resting silhouette each state branch of a walked pose places - where the subject stands
 * when one question the body asks of its render state answers the other way.
 *
 * <p>A pose is folded against the frame its subjects rest in, and the branch a resting subject
 * does not take is dropped whole: a wolf's table carries the standing wolf, and the sitting one
 * - the body lowered onto its haunches, the tail and the hind legs placed by hand where that body
 * carries them - is nowhere in it. That placement is evidence the pose table otherwise loses: it
 * says which parts vanilla moves together, and it is the one silhouette of the subject vanilla
 * itself draws in that state. So each such branch is folded once more, against the same frame
 * with that one answer flipped, and what it places away from the resting row is written beside it.
 *
 * <p><b>A state is one answer flipped, never a product of answers.</b> A body that branches on
 * three flags is three states here, each the resting subject with one flag the other way. That is
 * linear in what the body asks rather than exponential, and it is also what the evidence is for:
 * a placement two flags decide between them has no single branch to be read from.
 *
 * <p><b>What is written is the silhouette at REST, over the mesh's own values.</b> Every figure
 * the tick drives is folded at what it rests at, so a channel that in the state is "the stride
 * plus a tuck" is written as the tuck alone, and a channel placed relative to the authored pivot
 * keeps the read of that pivot rather than a number this side cannot know - the mesh it is
 * evaluated against is the reader's. A channel the state leaves exactly where the resting row
 * leaves it is not written at all, so a state carries only what it moves.
 *
 * <p>Nothing at render reads a silhouette. It is carried for what can be derived from it beside a
 * mesh, which is a question for the side that has the mesh.
 */
final class PoseStates {

    /** What separates a member from the answer it stands at in a state's key. */
    private static final char AT = '=';

    /** The two spellings a boolean member takes where the fold reads it as a number. */
    private static final @NotNull String TRUE = "true";

    private static final @NotNull String FALSE = "false";

    /** The spelling of a unit figure moved to its completed value. */
    private static final @NotNull String ONE = "1";

    private PoseStates() {}

    /**
     * One state's silhouette - the bones and channels it places away from the resting row.
     *
     * @param bones each placed bone's position and rotation channels, at rest, over the mesh's
     *     own values where the branch placed them relative to it
     */
    record Silhouette(@NotNull Map<String, Map<PoseSink, PoseExpr>> bones) {}

    /**
     * Derives every state silhouette of one walked pose against the frame its subjects rest in.
     *
     * <p>The states are the questions the body asks of its render state: each boolean it branches
     * on, flipped from what it rests at; each constant of an enum member it tests other than the
     * one it rests holding; and each unit figure it reads that rests at zero, moved to one - the
     * completed stand of an equine's {@code standAnimation}, a branch no comparison spells but a
     * placement all the same. A figure the tick drives is no state - it stays symbolic in the
     * shipped row already - and a branch on anything else this cannot flip is left alone.
     *
     * @param program the walked pose, as the walk left it
     * @param subjectRest which constant each enum member this subject rests holding - the frame the
     *     row is folded against
     * @param restDefaults the same for the model, read where the subject names no constant
     * @param questionDefaults what a question of a reference the state holds rests answering
     * @param inputDefaults what each figure rests at, one keyspace across every model
     * @param free the render-state figures the tick drives, which no state can settle
     * @param derived which further figures this model's renderer rebuilds from one of those
     * @return each state's silhouette keyed by the answer that reaches it, in key order, states
     *     placing nothing omitted
     */
    static @NotNull Map<String, Silhouette> of(
        @NotNull PoseProgram program, @NotNull Map<String, String> subjectRest,
        @NotNull Map<String, String> restDefaults, @NotNull Map<String, Float> questionDefaults,
        @NotNull Map<String, Float> inputDefaults, @NotNull Set<String> free,
        @NotNull Map<String, String> derived) {

        Toggles toggles = Toggles.of(program);
        if (toggles.isEmpty()) return Map.of();

        Map<String, Map<PoseSink, PoseExpr>> resting =
            resting(program, subjectRest, restDefaults, questionDefaults, inputDefaults);
        Map<String, Silhouette> out = new TreeMap<>();

        // A figure the frame spells as a boolean is one, however the body reads it, and flips as one.
        SortedSet<String> booleans = new TreeSet<>(toggles.booleans());
        for (String figure : toggles.figures())
            if (TRUE.equals(subjectRest.get(figure)) || FALSE.equals(subjectRest.get(figure))) booleans.add(figure);

        for (String member : booleans) {
            if (free.contains(member) || derived.containsKey(member)) continue;
            String flipped = inputAtRest(member, subjectRest, inputDefaults) != 0f ? FALSE : TRUE;
            Map<String, String> frame = new TreeMap<>(subjectRest);
            frame.put(member, flipped);
            place(out, member + AT + flipped, program, frame, resting,
                restDefaults, questionDefaults, inputDefaults);
        }
        for (String figure : toggles.figures()) {
            if (booleans.contains(figure) || free.contains(figure) || derived.containsKey(figure)) continue;
            if (inputAtRest(figure, subjectRest, inputDefaults) != 0f) continue;
            Map<String, Float> moved = new TreeMap<>(inputDefaults);
            moved.put(figure, 1f);
            place(out, figure + AT + ONE, program, subjectRest, resting,
                restDefaults, questionDefaults, moved);
        }
        toggles.enums().forEach((member, constants) -> {
            String held = subjectRest.getOrDefault(member, restDefaults.get(member));
            for (String constant : constants) {
                if (constant.equals(held)) continue;
                Map<String, String> frame = new TreeMap<>(subjectRest);
                frame.put(member, constant);
                place(out, member + AT + constant, program, frame, resting,
                    restDefaults, questionDefaults, inputDefaults);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * Folds the pose against one state's frame and keeps what it places away from the resting row.
     */
    private static void place(
        @NotNull Map<String, Silhouette> out, @NotNull String key, @NotNull PoseProgram program,
        @NotNull Map<String, String> frame, @NotNull Map<String, Map<PoseSink, PoseExpr>> resting,
        @NotNull Map<String, String> restDefaults, @NotNull Map<String, Float> questionDefaults,
        @NotNull Map<String, Float> inputDefaults) {

        Map<String, Map<PoseSink, PoseExpr>> placed =
            resting(program, frame, restDefaults, questionDefaults, inputDefaults);
        Map<String, Map<PoseSink, PoseExpr>> moved = new LinkedHashMap<>();
        placed.forEach((bone, channels) -> {
            Map<PoseSink, PoseExpr> atRest = resting.getOrDefault(bone, Map.of());
            Map<PoseSink, PoseExpr> away = new LinkedHashMap<>();
            for (PoseSink channel : PoseSink.values()) {
                boolean places = channel.channel().map(held -> switch (held.kind()) {
                    case POSITION, ROTATION -> true;
                    case SCALE -> false;
                }).orElse(false);
                if (!places) continue;
                PoseExpr here = channels.get(channel);
                if (here == null || sameShape(here, atRest.get(channel)) || !isPlacement(here)) continue;
                away.put(channel, here);
            }
            if (!away.isEmpty()) moved.put(bone, Collections.unmodifiableMap(away));
        });
        if (!moved.isEmpty()) out.put(key, new Silhouette(Collections.unmodifiableMap(moved)));
    }

    /**
     * Whether two folded channels spell one expression - the same shape node for node, each pair
     * of nodes visited once however many paths reach it.
     *
     * <p>A folded channel is a graph rather than a tree: a term reached down several paths is one
     * instance, and a comparison recursing per path - the record equality an {@code Op} inherits -
     * would visit it per path and not terminate on the shapes the shared table exists to compress.
     * The pairs already answered are memoized by identity on both sides, so a shared term is
     * compared once and answered thereafter.
     */
    static boolean sameShape(@Nullable PoseExpr here, @Nullable PoseExpr there) {
        return sameShape(here, there, new IdentityHashMap<>());
    }

    private static boolean sameShape(
        @Nullable PoseExpr here, @Nullable PoseExpr there,
        @NotNull Map<PoseExpr, Map<PoseExpr, Boolean>> answered) {

        if (here == there) return true;
        if (here == null || there == null) return false;
        Map<PoseExpr, Boolean> against = answered.computeIfAbsent(here, key -> new IdentityHashMap<>());
        Boolean known = against.get(there);
        if (known != null) return known;
        boolean same = switch (here) {
            case PoseExpr.Op op -> there instanceof PoseExpr.Op other
                && op.operator() == other.operator()
                && sameOperands(op.operands(), other.operands(), answered);
            case PoseExpr.Select select -> there instanceof PoseExpr.Select other
                && sameCondition(select.condition(), other.condition(), answered)
                && sameShape(select.whenTrue(), other.whenTrue(), answered)
                && sameShape(select.whenFalse(), other.whenFalse(), answered);
            default -> here.equals(there);
        };
        against.put(there, same);
        return same;
    }

    private static boolean sameOperands(
        @NotNull List<PoseExpr> here, @NotNull List<PoseExpr> there,
        @NotNull Map<PoseExpr, Map<PoseExpr, Boolean>> answered) {

        if (here.size() != there.size()) return false;
        for (int at = 0; at < here.size(); at++)
            if (!sameShape(here.get(at), there.get(at), answered)) return false;
        return true;
    }

    private static boolean sameCondition(
        @NotNull PosePredicate here, @NotNull PosePredicate there,
        @NotNull Map<PoseExpr, Map<PoseExpr, Boolean>> answered) {

        if (here == there) return true;
        return here.comparison() == there.comparison()
            && sameShape(here.left(), there.left(), answered)
            && sameShape(here.right(), there.right(), answered);
    }

    /**
     * Whether a channel folded at rest names a place at all - a literal that is no number is a
     * branch whose arithmetic has no resting value, a charge divided by a duration nothing
     * supplies, and it places nothing.
     */
    private static boolean isPlacement(@NotNull PoseExpr expr) {
        return !(expr instanceof PoseExpr.Constant literal) || Double.isFinite(literal.value());
    }

    /**
     * The pose reduced to what it holds at rest in one frame - every figure at what it rests at,
     * every decided branch taken, every literal operation collapsed, and only a read of the mesh
     * left standing. The same fold the row is emitted through, with nothing left free.
     */
    private static @NotNull Map<String, Map<PoseSink, PoseExpr>> resting(
        @NotNull PoseProgram program, @NotNull Map<String, String> frame,
        @NotNull Map<String, String> restDefaults, @NotNull Map<String, Float> questionDefaults,
        @NotNull Map<String, Float> inputDefaults) {

        return PoseFold.fold(program, frame, restDefaults, questionDefaults, inputDefaults,
            Set.of(), Set.of(), Map.of()).bones();
    }

    /**
     * What a boolean figure reads as before anything happens to the subject - the subject's own
     * spelling first, then the input table, the same rule the fold reads it by.
     */
    private static float inputAtRest(
        @NotNull String member, @NotNull Map<String, String> subjectRest,
        @NotNull Map<String, Float> inputDefaults) {

        String held = subjectRest.get(member);
        if (TRUE.equals(held)) return 1f;
        if (FALSE.equals(held)) return 0f;
        return inputDefaults.getOrDefault(member, 0f);
    }

    /**
     * The questions a walked pose asks of its render state that one answer can flip - the
     * booleans it compares against zero, the enum members it tests against a constant, and the
     * figures it reads at all.
     *
     * @param booleans the boolean render-state fields the body branches on
     * @param enums each enum member the body tests, with every constant it tests it against
     * @param figures every render-state figure the body reads, however it reads it
     */
    private record Toggles(
        @NotNull SortedSet<String> booleans,
        @NotNull SortedMap<String, SortedSet<String>> enums,
        @NotNull SortedSet<String> figures
    ) {

        /** Whether the body asks nothing a state could flip. */
        boolean isEmpty() {
            return this.booleans.isEmpty() && this.enums.isEmpty() && this.figures.isEmpty();
        }

        /** Every toggle one walked pose names, visiting each node once however many paths reach it. */
        static @NotNull Toggles of(@NotNull PoseProgram program) {
            Toggles toggles = new Toggles(new TreeSet<>(), new TreeMap<>(), new TreeSet<>());
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Map<PoseSink, PoseExpr> step : program.container())
                for (PoseExpr expr : step.values()) toggles.collect(expr, seen);
            for (Map<PoseSink, PoseExpr> channels : program.bones().values())
                for (PoseExpr expr : channels.values()) toggles.collect(expr, seen);
            return toggles;
        }

        private void collect(@NotNull PoseExpr expr, @NotNull Set<Object> seen) {
            if (!seen.add(expr)) return;
            switch (expr) {
                case PoseExpr.Input input -> this.figures.add(input.field());
                case PoseExpr.Op operation -> {
                    for (PoseExpr operand : operation.operands()) this.collect(operand, seen);
                }
                case PoseExpr.Select select -> {
                    this.collect(select.condition(), seen);
                    this.collect(select.whenTrue(), seen);
                    this.collect(select.whenFalse(), seen);
                }
                case PoseExpr.Answered.EnumMatch test ->
                    this.enums.computeIfAbsent(test.field(), field -> new TreeSet<>()).add(test.constant());
                default -> { }
            }
        }

        private void collect(@NotNull PosePredicate predicate, @NotNull Set<Object> seen) {
            if (!seen.add(predicate)) return;
            String member = booleanTested(predicate);
            if (member != null) this.booleans.add(member);
            this.collect(predicate.left(), seen);
            this.collect(predicate.right(), seen);
        }

        /**
         * The boolean field a comparison tests, or {@code null} for any other comparison.
         *
         * <p>A boolean the render state declares arrives as a number and is compared against
         * zero for equality, which is the one shape a branch on it can take; a figure compared
         * against a threshold, or against zero by order, is a number and no state.
         */
        private static String booleanTested(@NotNull PosePredicate compare) {
            if (compare.comparison() != PosePredicate.Comparison.EQ
                && compare.comparison() != PosePredicate.Comparison.NE) return null;
            if (compare.left() instanceof PoseExpr.Input input && isZero(compare.right())) return input.field();
            if (compare.right() instanceof PoseExpr.Input input && isZero(compare.left())) return input.field();
            return null;
        }

        private static boolean isZero(@NotNull PoseExpr expr) {
            return expr instanceof PoseExpr.Constant literal && literal.value() == 0d;
        }

    }

    /**
     * A silhouette as the program shape the writer spells a row's bones through - bones alone,
     * no container and no clip.
     *
     * @param model the row's model name
     * @param silhouette the silhouette to spell
     * @return the bones-only program
     */
    static @NotNull PoseProgram asProgram(@NotNull String model, @NotNull Silhouette silhouette) {
        return new PoseProgram(model, List.of(), silhouette.bones(), List.of());
    }

}
