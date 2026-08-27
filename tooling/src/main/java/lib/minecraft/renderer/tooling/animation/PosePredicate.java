package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * What decides between the two arms of a {@link PoseExpr.Select}, and what guards a write that only
 * one arm makes.
 *
 * <p>One grammar serves both, because a branch that assigns different values and a branch that
 * assigns on one side only are the same bytecode shape read two ways. Nothing here can be decided
 * offline by construction: a condition the walk could resolve was resolved while walking, so every
 * predicate that survives names a render-state input.
 *
 * <p>Five arms, and the corpus builds all five. There is deliberately no arm for a boolean on its
 * own: a boolean the render state declares as a field arrives as a number and a question asked of
 * something it holds answers as one, so both are compared against zero and neither needs a shape of
 * its own. There is none for a conjunction either - a body that tests two things in a row is two
 * branches, and each is kept where it was met rather than gathered up afterwards.
 */
public sealed interface PosePredicate {

    /** How two numbers are compared. */
    @EnumLookup
    @Getter(style = NamingStyle.FLUENT)
    @RequiredArgsConstructor
    enum Comparison {

        /** Equal. */
        EQ("eq"),

        /** Not equal. */
        NE("ne"),

        /** Strictly less. */
        LT("lt"),

        /** Less or equal. */
        LE("le"),

        /** Strictly greater. */
        GT("gt"),

        /** Greater or equal. */
        GE("ge");

        /** The token this comparison is spelled with in the shipped table. */
        @KeyField
        private final @NotNull String token;

        /**
         * Applies this comparison.
         *
         * @param left the left operand
         * @param right the right operand
         * @return whether the comparison holds
         */
        public boolean test(double left, double right) {
            return switch (this) {
                case EQ -> left == right;
                case NE -> left != right;
                case LT -> left < right;
                case LE -> left <= right;
                case GT -> left > right;
                case GE -> left >= right;
            };
        }

    }

    /**
     * A decided predicate, which exists so a folded branch can collapse rather than being special
     * cased at every site that builds one.
     *
     * @param value what the predicate answers
     */
    record Constant(boolean value) implements PosePredicate {}

    /**
     * A numeric comparison between two expressions.
     *
     * @param comparison how the two are compared
     * @param left the left operand
     * @param right the right operand
     */
    record Compare(
        @NotNull Comparison comparison,
        @NotNull PoseExpr left,
        @NotNull PoseExpr right
    ) implements PosePredicate {

        /**
         * Builds a comparison, deciding it when both operands are already literals.
         *
         * @param comparison how the two are compared
         * @param left the left operand
         * @param right the right operand
         * @return the decided constant, or the undecided comparison
         */
        public static @NotNull PosePredicate of(
            @NotNull Comparison comparison, @NotNull PoseExpr left, @NotNull PoseExpr right) {

            if (left instanceof PoseExpr.Const lhs && right instanceof PoseExpr.Const rhs)
                return new Constant(comparison.test(lhs.value(), rhs.value()));
            return new Compare(comparison, left, right);
        }

    }

    /**
     * An equality test against one constant of a render-state enum, which is what a switch over an
     * arm pose or a parrot pose decomposes into.
     *
     * @param field the vanilla render-state field name
     * @param constant the enum constant's own name
     */
    record EnumEq(@NotNull String field, @NotNull String constant) implements PosePredicate {}

    /**
     * A test that a reference the render state is reached through is there at all - the null check
     * a body makes before reading anything off a component an item may not carry.
     *
     * <p>Apart from {@link EnumEq}, which asks WHICH of a closed set of constants a reference is.
     * This asks whether there is one, and the path it names is the whole of the question: the
     * component being tested for is already a hop of that path, so nothing is owed beside it.
     *
     * @param member the path through the render state the reference was reached by
     */
    record Has(@NotNull String member) implements PosePredicate {}

    /**
     * The negation of a predicate.
     *
     * @param operand what is negated
     */
    record Not(@NotNull PosePredicate operand) implements PosePredicate {}

    /**
     * The negation of this predicate, collapsing a double negation and a decided constant rather
     * than wrapping them.
     *
     * @return the negated predicate
     */
    default @NotNull PosePredicate negate() {
        if (this instanceof Constant decided) return new Constant(!decided.value());
        if (this instanceof Not negated) return negated.operand();
        return new Not(this);
    }

}
