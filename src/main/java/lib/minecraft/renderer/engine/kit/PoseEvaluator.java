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
import java.util.stream.Collectors;

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
 * gets the pose a model holds before anything has happened to its subject. A figure whose own render
 * state builds it at something else is the exception: {@code HumanoidRenderState} builds
 * {@code speedValue} at one and every humanoid DIVIDES an arm swing by it, so a caller leaving it at
 * zero gets NaN rather than a still arm. {@link #ZERO} answers nothing to everything and
 * {@link #restingIn} answers each figure what it actually rests at - start from the second.
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
     * not excuse is a figure whose own render state builds it at something other than zero, which is
     * why {@link #restingIn} rather than a bare implementation of this is where a caller starts.
     */
    public interface Frame {

        /**
         * A figure the render state carries under its own field name.
         *
         * <p>Answering nothing is right for a figure that rests at nothing and wrong for one its own
         * render state builds at something else - {@link #restingIn} is the frame that knows which,
         * and is what a caller should start from rather than this.
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

    /** The one question whose resting answer is not nothing - see {@link #restingIn}. */
    private static final @NotNull String IS_EMPTY = "isEmpty";

    /**
     * The frame a subject is in before anything has happened to it.
     *
     * <p>What {@link #ZERO} would be if zero were the right answer everywhere. Each figure the pose
     * names reads what its own render state builds it at, which is nothing for most of them and one
     * for a living subject's age scale and a humanoid's speed - the latter being divided by, so a
     * humanoid posed from {@code ZERO} has NaN arms and one posed from this has still ones.
     *
     * <p>The same holds for a question, and the corpus asks one that answers to it: a caller that
     * supplies no item is a subject holding none, and a stack nobody put anything in <em>is</em>
     * empty. Answering {@code isEmpty} nothing says the opposite - that there is something there -
     * which drew a zombie nautilus without the corals it wears when it is not armoured. Every other
     * question the table asks rests at nothing, {@code isStarted} included: an animation nothing
     * started has not started.
     *
     * <p>A caller that models something delegates to this for the rest, rather than answering the
     * whole surface itself.
     *
     * <p>An enum member answers the same way, and cannot be answered from the pose at all: which
     * constant a subject rests holding is the subject's own, where the pose belongs to a model
     * several subjects may share. So the caller supplies it, and a member nothing named still
     * answers false to every constant.
     *
     * @param pose the pose whose named figures are being answered
     * @param restingState which constant each enum render-state member rests at, by member name
     * @return a frame answering each figure its own resting value
     */
    public static @NotNull Frame restingIn(
        @NotNull EntityPose pose, @NotNull Map<String, String> restingState) {

        Map<String, Float> defaults = pose.inputDefaults();
        return new Frame() {
            @Override
            public float input(@NotNull String field) {
                return defaults.getOrDefault(field, 0f);
            }

            @Override
            public float question(@NotNull String receiver, @NotNull String question) {
                return IS_EMPTY.equals(question) ? 1f : 0f;
            }

            @Override
            public boolean is(@NotNull String member, @NotNull String constant) {
                return constant.equals(restingState.get(member));
            }
        };
    }

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
     * Whether a bone draws when nothing has happened to the subject.
     *
     * <p>The one question about a pose that is worth asking before there is a frame to ask it in: it
     * is what a mesh looks like at rest, and therefore which of a model's bones a caller is choosing
     * BETWEEN when it names an appearance. A goat rests with its horns and a donkey rests without
     * its chest, and both of those are read here rather than declared anywhere.
     *
     * <p>A bone the model never writes a visibility for is one it never hides, so it draws.
     *
     * @param pose the model's pose
     * @param model the mesh being posed
     * @param bone the bone to ask about
     * @param restingState which constant each enum render-state member rests at, by member name
     * @return whether it draws before anything has happened
     */
    public static boolean drawsAtRest(
        @NotNull EntityPose pose, @NotNull EntityModelData model, @NotNull String bone,
        @NotNull Map<String, String> restingState) {

        PoseExpr written = pose.bones().getOrDefault(bone, Map.of()).get(PoseChannel.VISIBLE);
        if (written == null) return true;
        return value(written, model, restingIn(pose, restingState), new IdentityHashMap<>()) != 0d;
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
