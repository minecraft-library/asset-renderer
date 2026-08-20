package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.OptionalDouble;

/**
 * One value a pose expression computes - the symbolic operand a {@code setupAnim} walk pushes where
 * a running client would push a number.
 *
 * <p>Six arms cover the corpus: a literal, the two ways a render state is read, a read of a channel
 * the pose has already touched, an operation, and the join of a branch. Every operation is
 * {@link Op}, ternaries included, so an arity is a property of the operator rather than of the arm
 * carrying it and a three-operand call needs no shape of its own.
 *
 * <p>There is no arm for a loop and none for a call. A constant-bound or array-bound loop is
 * unrolled while walking and a helper is inlined into its caller, so both are gone by the time an
 * expression exists. A backward edge that cannot be unrolled is a failed walk, not a node.
 */
public sealed interface PoseExpr {

    /**
     * A literal.
     *
     * <p>The value is carried as a {@code double} because that is the one carrier wide enough for
     * all three widths, and {@code width} says which of them it actually is. A float literal is
     * held as the {@code double} its float value widens to exactly, so narrowing it back is free.
     *
     * @param value the literal value
     * @param width the width the literal was pushed at
     */
    record Const(double value, @NotNull PoseOperator.Width width) implements PoseExpr {

        /**
         * A single-precision literal.
         *
         * @param value the value
         * @return the literal
         */
        public static @NotNull Const of(float value) {
            return new Const(value, PoseOperator.Width.FLOAT);
        }

        /**
         * A double-precision literal.
         *
         * @param value the value
         * @return the literal
         */
        public static @NotNull Const of(double value) {
            return new Const(value, PoseOperator.Width.DOUBLE);
        }

        /**
         * An integral literal.
         *
         * @param value the value
         * @return the literal
         */
        public static @NotNull Const of(int value) {
            return new Const(value, PoseOperator.Width.INT);
        }

    }

    /**
     * A field read off the render state.
     *
     * <p>Named by the vanilla field name alone. Javac writes the static type of the receiver into
     * the constant pool, so one inherited field arrives under as many owners as there are subclasses
     * reading it; keying on the name is what keeps {@code ageInTicks} one input rather than seventy.
     *
     * @param field the vanilla render-state field name
     */
    record Input(@NotNull String field) implements PoseExpr {}

    /**
     * A method read off the render state, pinned at the literal arguments its call site passes.
     *
     * <p>The arguments are part of the identity: a state method called at two different literals is
     * two input channels, because nothing offline can evaluate the method itself.
     *
     * @param method the vanilla render-state method name
     * @param arguments the literal arguments the call site passes, in declaration order
     */
    record InputFn(@NotNull String method, @NotNull List<Double> arguments) implements PoseExpr {}

    /**
     * A read of a bone channel's current value.
     *
     * <p>This is the mirror of a write and it is the corpus's most common read by a wide margin:
     * vanilla's {@code setupAnim} always begins by resetting every bone to its authored pose, so a
     * channel nothing has written yet reads that authored value, and a channel already written this
     * walk reads what was written. Both are this node - the evaluator seeds each channel from the
     * bind pose and the distinction disappears.
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
    record Op(@NotNull PoseOperator operator, @NotNull List<PoseExpr> operands) implements PoseExpr {

        /**
         * Builds an operation, folding it when every operand is already a literal.
         *
         * <p>The fold calls the same method the renderer will, on the same values, so folding here
         * and evaluating there answer the same bits. That is the only reason folding is safe at
         * all - an algebraically equal shortcut would not be.
         *
         * @param operator what is applied
         * @param operands the operands, in declaration order
         * @return the folded literal, or the unfolded operation
         * @throws IllegalArgumentException if the operand count is not the operator's arity
         */
        public static @NotNull PoseExpr of(@NotNull PoseOperator operator, @NotNull List<PoseExpr> operands) {
            if (operands.size() != operator.arity())
                throw new IllegalArgumentException(
                    "'" + operator.token() + "' takes " + operator.arity() + " operand(s), got " + operands.size());

            double[] values = new double[operands.size()];
            for (int index = 0; index < values.length; index++) {
                if (!(operands.get(index) instanceof Const literal))
                    return new Op(operator, List.copyOf(operands));
                values[index] = literal.value();
            }
            return new Const(operator.apply(values), operator.width());
        }

        /**
         * Builds an operation from operands given inline.
         *
         * @param operator what is applied
         * @param operands the operands, in declaration order
         * @return the folded literal, or the unfolded operation
         */
        public static @NotNull PoseExpr of(@NotNull PoseOperator operator, @NotNull PoseExpr @NotNull ... operands) {
            return of(operator, List.of(operands));
        }

    }

    /**
     * The join of a branch whose condition nothing offline can decide.
     *
     * <p>A condition that folds is resolved while walking and never reaches here; what survives is
     * a genuine dependence on the render state, so both arms are kept and the choice is deferred to
     * whoever supplies the inputs.
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
