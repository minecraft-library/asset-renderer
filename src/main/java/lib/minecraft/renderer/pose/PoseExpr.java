package lib.minecraft.renderer.pose;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pose.EntityPose;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalDouble;
import java.util.StringJoiner;

/**
 * One value a shipped pose expression computes - the arithmetic vanilla runs to decide where a bone
 * goes, read back off the table rather than reproduced by hand.
 *
 * <p>Five arms: a literal, a field read off the render state, a read of a channel the pose has
 * already written, an operation, and the join of a choice. Every operation is {@link Op}, ternaries
 * included, so an arity is a property of the operator rather than of the arm carrying it.
 *
 * <p>There is no arm for a loop and none for a call. A bounded loop was unrolled and a helper was
 * inlined while the table was written, so both are gone by the time an expression exists here, and
 * what survives depends only on the inputs a caller supplies.
 *
 * <p>There is no arm for a figure a model keeps between poses, for a question asked of something the
 * render state holds, or for an element of an array it holds. Each of those is a fact about a subject
 * standing still, which the generator answers where it knows the subject rather than leaving to
 * whoever draws it.
 *
 * <p><b>Every arm prints as {@code <kind>@<identity>(<local data or child references>)} and never
 * recurses.</b> A node here stands for enormously many paths - a humanoid's arms are nine hundred
 * nodes standing for twenty-two million - so a text form that rendered its children would render
 * the tree rather than the graph and exhaust memory on the very shapes the tables exist to
 * compress. That binds anything holding one: {@link EntityPose} and the records under it print
 * their expressions this way because their components do.
 */
public sealed interface PoseExpr extends PoseNode {

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
    record Const(double value, @NotNull PoseOperator.Width width) implements PoseExpr {

        /** {@inheritDoc} */
        @Override
        public @NotNull String toString() {
            return "const" + PoseNode.ref(this) + "(" + this.width.literal(this.value) + ")";
        }

    }

    /**
     * A field read off the render state, named by the vanilla field name alone.
     *
     * @param field the vanilla render-state field name
     */
    record Input(@NotNull String field) implements PoseExpr {

        /** {@inheritDoc} */
        @Override
        public @NotNull String toString() {
            return "input" + PoseNode.ref(this) + "(" + this.field + ")";
        }

    }

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
    record BoneRead(@NotNull String bone, @NotNull PoseChannel channel) implements PoseExpr {

        /** {@inheritDoc} */
        @Override
        public @NotNull String toString() {
            return "read" + PoseNode.ref(this) + "(" + this.bone + "." + this.channel.token() + ")";
        }

    }

    /**
     * An operation applied to operands.
     *
     * @param operator what is applied
     * @param operands the operands, in declaration order
     */
    record Op(@NotNull PoseOperator operator, @NotNull ConcurrentList<PoseExpr> operands) implements PoseExpr {

        /** {@inheritDoc} */
        @Override
        public @NotNull String toString() {
            StringJoiner joined = new StringJoiner(", ", "(", ")");
            for (PoseExpr operand : this.operands)
                joined.add(PoseNode.ref(operand));
            return this.operator.token() + PoseNode.ref(this) + joined;
        }

    }

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
    ) implements PoseExpr {

        /** {@inheritDoc} */
        @Override
        public @NotNull String toString() {
            return "select" + PoseNode.ref(this)
                + "(" + PoseNode.ref(this.condition)
                + " ? " + PoseNode.ref(this.whenTrue)
                + " : " + PoseNode.ref(this.whenFalse) + ")";
        }

    }

    /**
     * This expression's value when it is already a literal.
     *
     * @return the literal value, or empty when the expression depends on anything at all
     */
    default @NotNull OptionalDouble constantValue() {
        return this instanceof Const literal ? OptionalDouble.of(literal.value()) : OptionalDouble.empty();
    }

}
