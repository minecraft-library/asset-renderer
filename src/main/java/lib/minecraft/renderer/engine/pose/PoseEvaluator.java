package lib.minecraft.renderer.engine.pose;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.exception.RendererException;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Evaluates the pose expression language at one instant - the arithmetic a {@code setupAnim} body
 * does, read off the shipped table instead of run.
 *
 * <p>Pure, and a function of exactly three things: the expressions, what the caller answers about the
 * subject, and what a channel already held. Nothing here reads a clock, a world or a render state; a
 * caller that wants an animated subject answers a figure differently per instant and gets a different
 * value per instant.
 *
 * <p><b>A pose is a GRAPH, and evaluating it as a tree does not terminate in practice.</b> The
 * generator followed both arms of everything it could not decide, so one sub-expression is reached
 * down enormously many paths - a humanoid's arms are nine hundred distinct nodes standing for
 * twenty-two million - and the table exists to write the nine hundred. The loader preserves that by
 * resolving every reference to ONE record instance, so this memoizes on node IDENTITY and visits
 * each node once. An evaluator keyed on value rather than identity would hash a node by walking
 * everything below it, which is the same walk it is trying to avoid.
 *
 * <p><b>Answering nothing is a pose rather than a gap.</b> The only figures a shipped pose names are
 * the ones the tick drives, everything a subject standing still answers about itself having been
 * resolved where the table was written - so {@link #AT_REST} is the frame vanilla draws before
 * anything has happened, and there is nothing a caller can leave out and be wrong about.
 *
 * <p>Channels come back in the units the table carries: a rotation in RADIANS, where a bone stores
 * degrees. Whatever applies these owns that conversion; this hands back what the expressions say, and
 * a {@link BoneChannels} answers in the same units for the same reason.
 */
@UtilityClass
public final class PoseEvaluator {

    /**
     * The frame a subject is in before anything has happened to it.
     *
     * <p>A figure the tick does not drive is not in the table at all, so answering nothing to
     * everything is not a caller declining to model a subject - it IS the subject standing still,
     * and the only thing a caller adds on top is elapsed age and the stride a gait carries.
     */
    public static final @NotNull ToDoubleFunction<String> AT_REST = field -> 0d;

    /**
     * What a channel of a named bone held before the pose wrote it, in the units the table's
     * arithmetic is in.
     *
     * <p>The one thing an expression needs a mesh for, resolved before evaluation begins, so the
     * language is evaluated against an answer rather than against the shape that answers it.
     */
    @FunctionalInterface
    public interface BoneChannels {

        /**
         * Answers what one bone's channel held before anything was written to it.
         *
         * @param bone the bone the expression names
         * @param channel the channel the expression reads
         * @return the channel's value before any write, in the units the table speaks
         */
        double held(@NotNull String bone, @NotNull PoseChannel channel);

    }

    /**
     * What one evaluation wrote, per channel.
     *
     * <p>The container is held apart from the bones because it is not one: it is the parent
     * transform above every bone the mesh names at top level, and the mesh names it nowhere.
     *
     * @param container what each of the container's steps evaluates to, outermost first
     * @param bones what each bone's written channels evaluate to, by bone name
     */
    public record ChannelWrites(
        @NotNull List<Map<PoseChannel, Float>> container,
        @NotNull Map<String, Map<PoseChannel, Float>> bones
    ) {

        /** What a model that poses nothing writes, which is a real answer rather than a missing one. */
        public static final @NotNull ChannelWrites NONE = new ChannelWrites(List.of(), Map.of());

        /**
         * Whether this wrote nothing at all.
         *
         * @return {@code true} when no channel of any bone or of the container was written
         */
        public boolean isEmpty() {
            return this.container.isEmpty() && this.bones.isEmpty();
        }

    }

    /**
     * Evaluates every channel a pose writes, over the container's steps and over the bones a caller
     * has already narrowed to the ones that draw.
     *
     * <p>A bone a caller does not hand in is not evaluated, and neither is one the expressions reach
     * through a read - that read goes to {@code authored}, which is where a bone nothing declares
     * fails loudly.
     *
     * @param container the channels each of the container's steps writes, outermost first
     * @param bones the channels each bone writes, by bone name, in the order they are read back in
     * @param authored what a channel held before the pose wrote it
     * @param frame what each render-state figure reads as, {@link #AT_REST} where none is driven
     * @return the value each written channel evaluates to
     */
    public static @NotNull ChannelWrites evaluate(
        @NotNull List<Map<PoseChannel, PoseExpr>> container,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> bones,
        @NotNull BoneChannels authored, @NotNull ToDoubleFunction<String> frame) {

        // One memo across the whole pose rather than one per channel: the sharing spans channels and
        // bones, so a memo per channel would walk the paths the table exists not to write.
        Map<PoseNode, Double> memo = new IdentityHashMap<>();

        // In order, because the container is a sequence rather than one transform: a step is a part
        // pose and what separates two of them is that composing them the other way round is a
        // different placement.
        List<Map<PoseChannel, Float>> steps = container.stream()
            .map(step -> channels(step, authored, frame, memo))
            .toList();
        // Collected in insertion order rather than through Map.copyOf: what comes out is read in
        // order downstream, and copyOf salts its iteration per JVM launch.
        Map<String, Map<PoseChannel, Float>> written = bones
            .entrySet()
            .stream()
            .collect(Concurrent.toUnmodifiableLinkedMap(Map.Entry::getKey,
                writes -> channels(writes.getValue(), authored, frame, memo),
                (first, second) -> first));
        return new ChannelWrites(steps, written);
    }

    /**
     * A list of expressions, each narrowed to the width a channel is finally stored at.
     *
     * <p>What a clip's play site carries: how far through the clip the model is and how hard it is
     * playing it are the model's own arithmetic over the same figures a bone channel reads, so they
     * go through the same evaluation rather than a second one.
     *
     * <p>Memoized within the call and not across it. A play site's terms are a handful of nodes
     * where a bone channel's are nine hundred, and the sharing that makes one memo worth carrying
     * across a whole pose does not reach here.
     *
     * @param expressions the expressions to evaluate, in order
     * @param authored what a channel held before the pose wrote it
     * @param frame what each render-state figure reads as, {@link #AT_REST} where none is driven
     * @return each expression's value, in the order given
     */
    public static @NotNull ConcurrentList<Float> values(
        @NotNull List<PoseExpr> expressions, @NotNull BoneChannels authored,
        @NotNull ToDoubleFunction<String> frame) {

        Map<PoseNode, Double> memo = new IdentityHashMap<>();
        return expressions.stream()
            .map(expression -> (float) value(expression, authored, frame, memo))
            .collect(Concurrent.toUnmodifiableList());
    }

    // ------------------------------------------------------------------------------------

    /** One bone's channels, narrowed to the width a channel is finally stored at. */
    private static @NotNull Map<PoseChannel, Float> channels(
        @NotNull Map<PoseChannel, PoseExpr> written, @NotNull BoneChannels authored,
        @NotNull ToDoubleFunction<String> frame, @NotNull Map<PoseNode, Double> memo) {

        if (written.isEmpty()) return Map.of();
        Map<PoseChannel, Float> out = new EnumMap<>(PoseChannel.class);
        written.forEach((channel, expr) -> out.put(channel, (float) value(expr, authored, frame, memo)));
        return Collections.unmodifiableMap(out);
    }

    /**
     * One expression's value, computed once however many places reach it.
     *
     * <p>Carried as {@code double} between nodes because that is the only width wide enough to hold
     * all three without loss; each operation narrows its own way back out, so a float one rounds
     * exactly once and an integral one truncates rather than rounding.
     */
    private static double value(
        @NotNull PoseExpr expr, @NotNull BoneChannels authored,
        @NotNull ToDoubleFunction<String> frame, @NotNull Map<PoseNode, Double> memo) {

        Double known = memo.get(expr);
        if (known != null) return known;

        double computed = switch (expr) {
            case PoseExpr.Constant literal -> literal.value();
            case PoseExpr.Input input -> frame.applyAsDouble(input.field());
            case PoseExpr.BoneRead read -> authored.held(read.bone(), read.channel());
            case PoseExpr.Op operation -> {
                double[] operands = new double[operation.operands().size()];
                for (int at = 0; at < operands.length; at++)
                    operands[at] = value(operation.operands().get(at), authored, frame, memo);
                yield operation.operator().apply(operands);
            }
            case PoseExpr.Select select -> value(
                test(select.condition(), authored, frame, memo) ? select.whenTrue() : select.whenFalse(),
                authored, frame, memo);
            // A fact about a subject standing still, which a generator settles before it writes a
            // table. No shipped table spells one and the reader has no token for one, so reaching
            // here means a pose was handed in rather than loaded.
            case PoseExpr.Answered answered -> throw new RendererException(
                "entity pose: carries '%s', which a generator settles before a table is written", answered);
        };

        memo.put(expr, computed);
        return computed;
    }

    /** One condition, memoized the same way an expression is. */
    private static boolean test(
        @NotNull PosePredicate predicate, @NotNull BoneChannels authored,
        @NotNull ToDoubleFunction<String> frame, @NotNull Map<PoseNode, Double> memo) {

        Double known = memo.get(predicate);
        if (known != null) return known != 0d;

        boolean answered = predicate.comparison().test(
            value(predicate.left(), authored, frame, memo), value(predicate.right(), authored, frame, memo));
        memo.put(predicate, answered ? 1d : 0d);
        return answered;
    }

}
