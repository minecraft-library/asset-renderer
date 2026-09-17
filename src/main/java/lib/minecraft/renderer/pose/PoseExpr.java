package lib.minecraft.renderer.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pose.EntityPose;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.OptionalDouble;
import java.util.StringJoiner;

/**
 * One value a shipped pose expression computes - the arithmetic vanilla runs to decide where a bone
 * goes, read back off the table rather than reproduced by hand.
 *
 * <p>Five arms ship: a literal, a field read off the render state, a read of a channel the pose has
 * already written, an operation, and the join of a choice. Every operation is {@link Op}, ternaries
 * included, so an arity is a property of the operator rather than of the arm carrying it.
 *
 * <p>There is no arm for a loop and none for a call. A bounded loop was unrolled and a helper was
 * inlined while the table was written, so both are gone by the time an expression exists here, and
 * what survives depends only on the inputs a caller supplies.
 *
 * <p>{@link Answered} holds the rest, and nothing at render reads one. Those are the facts about a
 * subject standing still - a figure a model keeps between poses, a question asked of something the
 * render state holds, an element of an array it holds, which constant a member rests at, whether a
 * reference is there at all - and the generator answers every one of them where it knows the subject
 * rather than leaving it to whoever draws it. They are declared here because the generator writes its
 * programs in this vocabulary and narrows them into the shipped one, so the two are one type and the
 * narrowing is a function rather than a translation between two spellings that drift. No shipped
 * table spells any of them and the reader has no token for one, so an arm of that kind reaching a
 * render is a generator that did not finish rather than a value to interpret.
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
    record Constant(double value, @NotNull PoseOperator.Width width) implements PoseExpr {

        /**
         * Constructs a single-precision literal.
         *
         * @param value the value
         */
        public Constant(float value) {
            this(value, PoseOperator.Width.FLOAT);
        }

        /**
         * Constructs a double-precision literal.
         *
         * @param value the value
         */
        public Constant(double value) {
            this(value, PoseOperator.Width.DOUBLE);
        }

        /**
         * Constructs an integral literal.
         *
         * @param value the value
         */
        public Constant(int value) {
            this(value, PoseOperator.Width.INT);
        }

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
     * Builds an operation, folding it to a literal where every operand is already one.
     *
     * <p>The fold applies the very operator a render will, on the same values, so folding here and
     * evaluating there answer the same bits. That is the only reason folding is safe at all - an
     * algebraically equal shortcut would not be.
     *
     * <p><b>Only a generator writing a table may fold, and a reader must not.</b> The shared table's
     * numbering describes the graph that was emitted, so a reader collapsing an operation would
     * resolve a reference to a node the table does not describe. That is why this is a named factory
     * rather than anything {@link Op} does on construction: a reader builds its arms with the record
     * constructors and folds nothing by reaching for one.
     *
     * @param operator what is applied
     * @param operands the operands, in declaration order
     * @return the folded literal, or the unfolded operation
     * @throws IllegalArgumentException if the operand count is not the operator's arity
     */
    static @NotNull PoseExpr operation(@NotNull PoseOperator operator, @NotNull List<PoseExpr> operands) {
        if (operands.size() != operator.arity())
            throw new IllegalArgumentException("'" + operator.token() + "' takes " + operator.arity() + " operand(s), got " + operands.size());

        double[] values = new double[operands.size()];
        for (int index = 0; index < values.length; index++) {
            if (!(operands.get(index) instanceof Constant literal))
                return new Op(operator, Concurrent.newUnmodifiableList(operands));

            values[index] = literal.value();
        }

        return new Constant(operator.apply(values), operator.width());
    }

    /**
     * Builds an operation from operands given inline.
     *
     * @param operator what is applied
     * @param operands the operands, in declaration order
     * @return the folded literal, or the unfolded operation
     */
    static @NotNull PoseExpr operation(@NotNull PoseOperator operator, @NotNull PoseExpr @NotNull ... operands) {
        return operation(operator, List.of(operands));
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
     * A fact about a subject standing still, which the generator settles before it writes a table.
     *
     * <p>Five arms, and they are apart from the rest by who answers them rather than by what they
     * compute. A shipped arm is a function of what a caller supplies at the tick; one of these is a
     * function of the subject, and the subject is known while the table is being written and gone by
     * the time anything draws it. So the generator folds every one of them away and no arm here has a
     * token in any shipped table.
     *
     * <p>They are numbers like any other expression, which is what keeps the grammar one grammar: a
     * question about which constant a member rests at, or about whether a reference is there, answers
     * as a figure and is compared against zero the way a render-state boolean is. Neither needs a
     * shape of its own among the conditions.
     */
    sealed interface Answered extends PoseExpr {

        /**
         * A figure a model keeps between poses rather than reads off the render state.
         *
         * <p>Apart from {@link Input} because the two are looked up in different places: an input is
         * a field of the render state a caller is already building, and this is a field of the MODEL,
         * whose value is how many times that model has been posed. Spelling both as inputs would put
         * a name the render state does not have into the namespace of names it does.
         *
         * @param field the vanilla model field name
         */
        record Carried(@NotNull String field) implements Answered {

            /** {@inheritDoc} */
            @Override
            public @NotNull String toString() {
                return "carried" + PoseNode.ref(this) + "(" + this.field + ")";
            }

        }

        /**
         * A question asked of a reference the render state holds - whether an animation is running,
         * whether a hand is empty.
         *
         * <p>The reference asked is part of the identity as much as the question is. Whether the
         * right hand is empty and whether the left is are two questions, and a model posing on both
         * would otherwise be reading one of them twice - which still draws, and draws the same arm
         * twice.
         *
         * @param receiver the vanilla render-state member the question is asked of
         * @param question the vanilla method name that asks it
         */
        record InputFn(@NotNull String receiver, @NotNull String question) implements Answered {

            /** {@inheritDoc} */
            @Override
            public @NotNull String toString() {
                return "input_fn" + PoseNode.ref(this) + "(" + this.receiver + "." + this.question + ")";
            }

        }

        /**
         * One element of an array the render state holds, pinned at the literal index that picked it.
         *
         * <p>The index is part of the identity, the same way the receiver of a question is: a model
         * posing two heads out of one array of angles is reading two elements, and a walk that lost
         * the index would pose both heads the same way.
         *
         * @param receiver the vanilla render-state member the array was read from
         * @param index the literal index the call site picked
         */
        record InputElement(@NotNull String receiver, int index) implements Answered {

            /** {@inheritDoc} */
            @Override
            public @NotNull String toString() {
                return "input_element" + PoseNode.ref(this) + "(" + this.receiver + "[" + this.index + "])";
            }

        }

        /**
         * Whether one member of the render state rests holding one constant of its enum, which is
         * what a switch over an arm pose or a parrot pose decomposes into.
         *
         * @param field the vanilla render-state field name
         * @param constant the enum constant's own name
         */
        record EnumMatch(@NotNull String field, @NotNull String constant) implements Answered {

            /** {@inheritDoc} */
            @Override
            public @NotNull String toString() {
                return "enum_match" + PoseNode.ref(this) + "(" + this.field + "=" + this.constant + ")";
            }

        }

        /**
         * Whether a reference the render state is reached through is there at all - the null check a
         * body makes before reading anything off a component an item may not carry.
         *
         * <p>Apart from {@link EnumMatch}, which asks WHICH of a closed set of constants a reference
         * is. This asks whether there is one, and the path it names is the whole of the question: the
         * component being tested for is already a hop of that path, so nothing is owed beside it.
         *
         * @param member the path through the render state the reference was reached by
         */
        record Present(@NotNull String member) implements Answered {

            /** {@inheritDoc} */
            @Override
            public @NotNull String toString() {
                return "present" + PoseNode.ref(this) + "(" + this.member + ")";
            }

        }

    }

    /**
     * This expression's value when it is already a literal.
     *
     * @return the literal value, or empty when the expression depends on anything at all
     */
    default @NotNull OptionalDouble constantValue() {
        return this instanceof Constant literal ? OptionalDouble.of(literal.value()) : OptionalDouble.empty();
    }

    /**
     * This figure read as the condition it stands for, which is how a boolean reaches a comparison.
     *
     * <p>A question about which constant a member rests at, or about whether a reference is there,
     * answers as a number. Nothing compares it to anything else, so it is compared against zero the
     * way a render-state boolean is - which is what keeps one grammar rather than two.
     *
     * @return the condition holding where this figure is not zero
     */
    default @NotNull PosePredicate truthy() {
        return PosePredicate.comparing(PosePredicate.Comparison.NE, this, new Constant(0f));
    }

}
