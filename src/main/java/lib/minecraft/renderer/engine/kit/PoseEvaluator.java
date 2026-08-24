package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.asset.pose.PosePredicate;
import lib.minecraft.renderer.exception.RendererException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Evaluates one model's pose at one instant - the arithmetic a {@code setupAnim} body does, read off
 * the shipped table instead of run.
 *
 * <p>Pure, and a function of exactly two things: the pose, and what the caller answers about the
 * subject. Nothing here reads a clock, a world or a render state; a caller that wants an animated
 * subject answers a figure differently per instant and gets a different pose per instant.
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
 * <p>Channels come back in the units the table carries: a rotation in RADIANS, where
 * {@link EntityModelData.Bone#getRotation()} is in degrees. Whatever applies these owns that
 * conversion; this hands back what the expressions say.
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
     * Evaluates every channel a pose writes to a bone this mesh has.
     *
     * <p>A pose that could not be read writes nothing, the same as one that poses nothing - the two
     * are told apart on {@link EntityPose#isReadable()}, before this, by whatever cares.
     *
     * <p><b>A bone the mesh does not declare is not evaluated</b>, because it does not draw and no
     * caller has anywhere to put the answer. A pose belongs to a model class where a mesh belongs to
     * a subject, so the two disagree by construction wherever a bone rests undrawn and its subtree
     * came out with it - an illager's crossed arms leave the pose still naming the pair it hangs
     * instead, and vanilla's own {@code setupAnim} writes those same fields on parts nothing renders.
     * Reading the mesh for a channel of a bone that is gone is what would fail, and it fails loudly
     * where it is genuinely wrong: a bone the mesh <em>does</em> have, reading one it does not.
     *
     * @param pose the model's pose
     * @param model the mesh being posed, which is what an unwritten channel is read from
     * @param frame what each render-state figure reads as, {@link #AT_REST} where none is driven
     * @return the value each written channel evaluates to
     */
    public static @NotNull ChannelWrites evaluate(
        @NotNull EntityPose pose, @NotNull EntityModelData model,
        @NotNull ToDoubleFunction<String> frame) {

        if (!pose.isReadable()) return ChannelWrites.NONE;

        // One memo across the whole pose rather than one per channel: the sharing spans channels and
        // bones, so a memo per channel would walk the paths the table exists not to write.
        Map<Object, Double> memo = new IdentityHashMap<>();

        // In order, because the container is a sequence rather than one transform: a step is a part
        // pose and what separates two of them is that composing them the other way round is a
        // different placement.
        List<Map<PoseChannel, Float>> container = pose.container().stream()
            .map(step -> channels(step, model, frame, memo))
            .toList();
        // Collected into a LinkedHashMap rather than through Map.copyOf: what comes out is read in
        // order downstream, and copyOf salts its iteration per JVM launch.
        Map<String, Map<PoseChannel, Float>> bones = pose.bones()
            .entrySet()
            .stream()
            .filter(written -> model.getBones().containsKey(written.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey,
                written -> channels(written.getValue(), model, frame, memo),
                (first, second) -> first, LinkedHashMap::new));
        return new ChannelWrites(container, Collections.unmodifiableMap(bones));
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
     * @param model the mesh being posed, which is what an unwritten channel is read from
     * @param frame what each render-state figure reads as, {@link #AT_REST} where none is driven
     * @return each expression's value, in the order given
     */
    public static @NotNull List<Float> values(
        @NotNull List<PoseExpr> expressions, @NotNull EntityModelData model,
        @NotNull ToDoubleFunction<String> frame) {

        Map<Object, Double> memo = new IdentityHashMap<>();
        List<Float> out = new ArrayList<>(expressions.size());
        for (PoseExpr expression : expressions)
            out.add((float) value(expression, model, frame, memo));
        return Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------------------------

    /** One bone's channels, narrowed to the width a channel is finally stored at. */
    private static @NotNull Map<PoseChannel, Float> channels(
        @NotNull Map<PoseChannel, PoseExpr> written, @NotNull EntityModelData model,
        @NotNull ToDoubleFunction<String> frame, @NotNull Map<Object, Double> memo) {

        if (written.isEmpty()) return Map.of();
        Map<PoseChannel, Float> out = new EnumMap<>(PoseChannel.class);
        written.forEach((channel, expr) -> out.put(channel, (float) value(expr, model, frame, memo)));
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
        @NotNull PoseExpr expr, @NotNull EntityModelData model,
        @NotNull ToDoubleFunction<String> frame, @NotNull Map<Object, Double> memo) {

        Double known = memo.get(expr);
        if (known != null) return known;

        double computed = switch (expr) {
            case PoseExpr.Const literal -> literal.value();
            case PoseExpr.Input input -> frame.applyAsDouble(input.field());
            case PoseExpr.BoneRead read -> authored(read, model);
            case PoseExpr.Op operation -> {
                double[] operands = new double[operation.operands().size()];
                for (int at = 0; at < operands.length; at++)
                    operands[at] = value(operation.operands().get(at), model, frame, memo);
                yield operation.operator().apply(operands);
            }
            case PoseExpr.Select select -> value(
                test(select.condition(), model, frame, memo) ? select.whenTrue() : select.whenFalse(),
                model, frame, memo);
        };

        memo.put(expr, computed);
        return computed;
    }

    /**
     * What a channel held before the pose wrote it - the mesh's own authored value.
     *
     * <p>A rotation is answered in RADIANS because that is the unit the table's arithmetic is in,
     * where the mesh stores degrees. A bone's scale is uniform, so all three axes read it.
     *
     * <p>A position is answered in the MODEL's own units, which a mesh flattened at
     * {@link EntityModelData#getFlattenedScale() one factor} does not store it in: every pivot below
     * the dissolved root arrived multiplied by that factor, and the number vanilla's own field holds
     * is the one before it. So the read crosses back, and what a pose does with it crosses forward
     * again where it is written.
     */
    private static double authored(@NotNull PoseExpr.BoneRead read, @NotNull EntityModelData model) {
        EntityModelData.Bone bone = model.getBones().get(read.bone());
        if (bone == null)
            throw new RendererException("entity pose: reads '%s' of bone '%s', which this mesh does not declare",
                read.channel().token(), read.bone());

        float flattened = model.getFlattenedScale();
        return switch (read.channel()) {
            case X -> bone.getPivot().x() / flattened;
            case Y -> bone.getPivot().y() / flattened;
            case Z -> bone.getPivot().z() / flattened;
            case X_ROT -> bone.getRotation().pitchRadians();
            case Y_ROT -> bone.getRotation().yawRadians();
            case Z_ROT -> bone.getRotation().rollRadians();
            case X_SCALE, Y_SCALE, Z_SCALE -> bone.getScale();
        };
    }

    /** One condition, memoized the same way an expression is. */
    private static boolean test(
        @NotNull PosePredicate predicate, @NotNull EntityModelData model,
        @NotNull ToDoubleFunction<String> frame, @NotNull Map<Object, Double> memo) {

        Double known = memo.get(predicate);
        if (known != null) return known != 0d;

        boolean answered = predicate.comparison().test(
            value(predicate.left(), model, frame, memo), value(predicate.right(), model, frame, memo));
        memo.put(predicate, answered ? 1d : 0d);
        return answered;
    }

}
