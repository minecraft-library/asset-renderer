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

import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evaluates one model's pose at one instant - the arithmetic a {@code setupAnim} body does, read off
 * the shipped table instead of run.
 *
 * <p>Pure, and a function of exactly two things: the pose, and what the caller answers about the
 * subject. Nothing here reads a clock, a world or a render state; a caller that wants an animated
 * subject supplies a {@link Frame} that varies and gets a different answer per instant.
 *
 * <p><b>A pose is a GRAPH, and evaluating it as a tree does not terminate in practice.</b> The
 * generator followed both arms of everything it could not decide, so one sub-expression is reached
 * down enormously many paths - a humanoid's arms are nine hundred distinct nodes standing for
 * twenty-two million - and the table exists to write the nine hundred. The loader preserves that by
 * resolving every reference to ONE record instance, so this memoizes on node IDENTITY and visits
 * each node once. An evaluator keyed on value rather than identity would hash a node by walking
 * everything below it, which is the same walk it is trying to avoid.
 *
 * <p><b>An unanswered question is zero, and zero is not always the value vanilla starts from.</b>
 * Most named figures are a displacement that rests at nothing, so a caller that models none of them
 * gets the pose a model holds before anything has happened to its subject. A field whose own render
 * state gives it a non-zero default is the exception and has to be answered:
 * {@code HumanoidRenderState} constructs {@code speedValue} at one and every humanoid DIVIDES an arm
 * swing by it, so a caller leaving it at zero gets NaN rather than a still arm. {@link #ZERO} is the
 * frame that answers nothing to everything, which is a starting point rather than a rest pose.
 *
 * <p>Channels come back in the units the table carries: a rotation in RADIANS, where
 * {@link EntityModelData.Bone#getRotation()} is in degrees. Whatever applies these owns that
 * conversion; this hands back what the expressions say.
 */
@UtilityClass
public final class PoseEvaluator {

    /**
     * What a caller answers about the subject being posed.
     *
     * <p>Every question has an answer of nothing, so a caller supplies only what it models - which
     * is what makes a subject that neither moves nor carries anything cost no code. What that does
     * not excuse is a figure whose own render state constructs it at something other than zero: it
     * is a real value vanilla always has, and answering nothing substitutes a number vanilla never
     * holds. {@link #input} carries the one the corpus divides by.
     */
    public interface Frame {

        /**
         * A figure the render state carries under its own field name.
         *
         * <p><b>{@code speedValue} is the one the corpus divides by</b>, and
         * {@code HumanoidRenderState} constructs it at one rather than at zero - so every humanoid
         * arm swing is NaN under an answer of nothing. A caller posing a humanoid answers it.
         *
         * @param field the vanilla render-state field
         * @return its value, or zero where the caller models none
         */
        default float input(@NotNull String field) {
            return 0f;
        }

        /**
         * A figure the MODEL carries between poses, accumulated rather than supplied.
         *
         * <p>Answered apart from {@link #input} because the two are a caller's to set in different
         * places and a render-state field of the same name is a different number.
         *
         * @param field the model's own field
         * @return what it holds, or zero where the caller steps none
         */
        default float carried(@NotNull String field) {
            return 0f;
        }

        /**
         * A question asked of a reference the render state names.
         *
         * @param receiver the reference, named the way it was asked for
         * @param question what is asked of it
         * @return the answer, or zero where the caller models none
         */
        default float question(@NotNull String receiver, @NotNull String question) {
            return 0f;
        }

        /**
         * One indexed element of a reference the render state names.
         *
         * @param receiver the reference
         * @param index the element's position
         * @return the element, or zero where the caller models none
         */
        default float element(@NotNull String receiver, int index) {
            return 0f;
        }

        /**
         * Whether a named reference is one particular declared constant of its own type.
         *
         * <p><b>Answering false to every constant is a state no enum is in</b>, so a caller that
         * poses a subject whose pose turns on one of these answers it rather than leaving it. What
         * the default buys is that the rest are not owed.
         *
         * @param member the render-state member the reference was read from
         * @param constant the constant's own name
         * @return whether the member holds that constant
         */
        default boolean is(@NotNull String member, @NotNull String constant) {
            return false;
        }

        /**
         * Whether a reference reached through the render state is there at all.
         *
         * @param member the path the reference is reached by
         * @return whether it is present, or false where the caller models none
         */
        default boolean has(@NotNull String member) {
            return false;
        }

    }

    /** The frame that answers every question with nothing, which is a starting point and not a pose. */
    public static final @NotNull Frame ZERO = new Frame() {};

    /**
     * What one evaluation wrote, per channel.
     *
     * <p>The container is held apart from the bones because it is not one: it is the parent
     * transform above every bone the mesh names at top level, and the mesh names it nowhere.
     *
     * @param container what the flattened container's written channels evaluate to
     * @param bones what each bone's written channels evaluate to, by bone name
     */
    public record ChannelWrites(
        @NotNull Map<PoseChannel, Float> container,
        @NotNull Map<String, Map<PoseChannel, Float>> bones
    ) {

        /** What a model that poses nothing writes, which is a real answer rather than a missing one. */
        public static final @NotNull ChannelWrites NONE = new ChannelWrites(Map.of(), Map.of());

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
     * Evaluates every channel a pose writes.
     *
     * <p>A pose that could not be read writes nothing, the same as one that poses nothing - the two
     * are told apart on {@link EntityPose#isReadable()}, before this, by whatever cares.
     *
     * @param pose the model's pose
     * @param model the mesh being posed, which is what an unwritten channel is read from
     * @param frame what the caller answers about the subject
     * @return the value each written channel evaluates to
     */
    public static @NotNull ChannelWrites evaluate(
        @NotNull EntityPose pose, @NotNull EntityModelData model, @NotNull Frame frame) {

        if (!pose.isReadable()) return ChannelWrites.NONE;

        // One memo across the whole pose rather than one per channel: the sharing spans channels and
        // bones, so a memo per channel would walk the paths the table exists not to write.
        Map<Object, Double> memo = new IdentityHashMap<>();

        Map<PoseChannel, Float> container = channels(pose.container(), model, frame, memo);
        Map<String, Map<PoseChannel, Float>> bones = new LinkedHashMap<>();
        pose.bones().forEach((bone, written) ->
            bones.put(bone, channels(written, model, frame, memo)));
        return new ChannelWrites(container, Collections.unmodifiableMap(bones));
    }

    // ------------------------------------------------------------------------------------

    /** One bone's channels, narrowed to the width a channel is finally stored at. */
    private static @NotNull Map<PoseChannel, Float> channels(
        @NotNull Map<PoseChannel, PoseExpr> written, @NotNull EntityModelData model,
        @NotNull Frame frame, @NotNull Map<Object, Double> memo) {

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
        @NotNull Frame frame, @NotNull Map<Object, Double> memo) {

        Double known = memo.get(expr);
        if (known != null) return known;

        double computed = switch (expr) {
            case PoseExpr.Const literal -> literal.value();
            case PoseExpr.Input input -> frame.input(input.field());
            case PoseExpr.Carried carried -> frame.carried(carried.field());
            case PoseExpr.InputFn question -> frame.question(question.receiver(), question.question());
            case PoseExpr.InputElement element -> frame.element(element.receiver(), element.index());
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
     */
    private static double authored(@NotNull PoseExpr.BoneRead read, @NotNull EntityModelData model) {
        EntityModelData.Bone bone = model.getBones().get(read.bone());
        if (bone == null)
            throw new RendererException("entity pose: reads '%s' of bone '%s', which this mesh does not declare",
                read.channel().token(), read.bone());

        return switch (read.channel()) {
            case X -> bone.getPivot().x();
            case Y -> bone.getPivot().y();
            case Z -> bone.getPivot().z();
            case X_ROT -> bone.getRotation().pitchRadians();
            case Y_ROT -> bone.getRotation().yawRadians();
            case Z_ROT -> bone.getRotation().rollRadians();
            case X_SCALE, Y_SCALE, Z_SCALE -> bone.getScale();
            // A part draws, and skips none of its own cubes, until something writes otherwise; the
            // mesh carries neither, so what a read of one answers is what ModelPart holds at rest.
            case VISIBLE -> 1d;
            case SKIP_DRAW -> 0d;
        };
    }

    /** One condition, memoized the same way an expression is. */
    private static boolean test(
        @NotNull PosePredicate predicate, @NotNull EntityModelData model,
        @NotNull Frame frame, @NotNull Map<Object, Double> memo) {

        Double known = memo.get(predicate);
        if (known != null) return known != 0d;

        boolean answered = switch (predicate) {
            case PosePredicate.Constant decided -> decided.value();
            case PosePredicate.Compare compare -> compare.comparison().test(
                value(compare.left(), model, frame, memo), value(compare.right(), model, frame, memo));
            case PosePredicate.Is check -> frame.is(check.member(), check.constant());
            case PosePredicate.Has present -> frame.has(present.member());
            case PosePredicate.Not not -> !test(not.operand(), model, frame, memo);
        };

        memo.put(predicate, answered ? 1d : 0d);
        return answered;
    }

}
