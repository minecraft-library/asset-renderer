package lib.minecraft.renderer.asset.pose;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.OptionalDouble;

/**
 * One value a shipped pose expression computes - the arithmetic vanilla runs to decide where a bone
 * goes, read back off the table rather than reproduced by hand.
 *
 * <p>Seven arms: a literal, the three ways the render state is read, a read of a channel the pose
 * has already written, an operation, and the join of a choice. Every operation is {@link Op},
 * ternaries included, so an arity is a property of the operator rather than of the arm carrying it.
 *
 * <p>There is no arm for a loop and none for a call. A bounded loop was unrolled and a helper was
 * inlined while the table was written, so both are gone by the time an expression exists here, and
 * what survives depends only on the inputs a caller supplies.
 */
public sealed interface PoseExpr {

    /**
     * A literal, at the width it was computed at.
     *
     * <p>Carried as a {@code double} because that is the one carrier wide enough for all three
     * widths; {@code width} says which of them it actually is. A float literal is held as the
     * {@code double} its float value widens to exactly, so narrowing it back is free.
     *
     * @param value the literal value
     * @param width the width the literal was written at
     */
    record Const(double value, @NotNull PoseOperator.Width width) implements PoseExpr {}

    /**
     * A field read off the render state, named by the vanilla field name alone.
     *
     * @param field the vanilla render-state field name
     */
    record Input(@NotNull String field) implements PoseExpr {}

    /**
     * A figure the model keeps between poses rather than reads off the render state.
     *
     * <p>Apart from {@link Input} because the two are answered from different places: an input is a
     * field of the render state a caller is already building, and this is a field of the MODEL, whose
     * value is how many times that model has been posed. A caller that has none to supply supplies
     * nothing and gets the frame vanilla computes the first time it poses the model, because the only
     * write the table can carry is a step added to whatever the figure already held.
     *
     * @param field the vanilla model field name
     */
    record Carried(@NotNull String field) implements PoseExpr {}

    /**
     * A question asked of a reference the render state holds - whether an animation is running,
     * whether a hand is empty.
     *
     * <p>The reference asked is part of the identity as much as the question is: whether the right
     * hand is empty and whether the left is are two inputs, and a caller answering one of them
     * twice would pose both hands the same way.
     *
     * @param receiver the vanilla render-state member the question is asked of
     * @param question the vanilla method name that asks it
     */
    record InputFn(@NotNull String receiver, @NotNull String question) implements PoseExpr {}

    /**
     * One element of an array the render state holds, pinned at the index that picked it.
     *
     * @param receiver the vanilla render-state member the array was read from
     * @param index the index the pose reads
     */
    record InputElement(@NotNull String receiver, int index) implements PoseExpr {}

    /**
     * A read of a bone channel's current value.
     *
     * <p>The corpus's most common read by a wide margin, because vanilla resets every bone to its
     * authored pose before posing any of them: a channel nothing has written reads that authored
     * value, and one already written reads the write. Both are this node, and an evaluator that
     * seeds each channel from the bind pose need not tell them apart.
     *
     * @param bone the geometry bone name
     * @param channel the channel being read
     */
    record BoneRead(@NotNull String bone, @NotNull PoseChannel channel) implements PoseExpr {}

    /**
     * An operation applied to operands.
     *
     * @param operator what is applied
     * @param operands the operands, in declaration order
     */
    record Op(@NotNull PoseOperator operator, @NotNull List<PoseExpr> operands) implements PoseExpr {}

    /**
     * The join of a choice the table could not decide.
     *
     * <p>A condition that folded was folded while the table was written, so what survives is a
     * genuine dependence on what a caller supplies.
     *
     * @param condition what decides between the arms
     * @param whenTrue the value when the condition holds
     * @param whenFalse the value when it does not
     */
    record Select(
        @NotNull PosePredicate condition,
        @NotNull PoseExpr whenTrue,
        @NotNull PoseExpr whenFalse
    ) implements PoseExpr {}

    /**
     * This expression's value when it is already a literal.
     *
     * @return the literal value, or empty when the expression depends on anything at all
     */
    default @NotNull OptionalDouble constantValue() {
        return this instanceof Const literal ? OptionalDouble.of(literal.value()) : OptionalDouble.empty();
    }

}
